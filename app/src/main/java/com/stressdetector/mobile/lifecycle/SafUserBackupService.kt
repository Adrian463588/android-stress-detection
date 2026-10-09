package com.stressdetector.mobile.lifecycle

import android.content.Context
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.ml.storage.ModelStorageSlot
import com.stressdetector.mobile.ml.storage.SafGenerationPayload
import com.stressdetector.mobile.security.KeyManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Keeps user data outside app-private storage only as an authenticated SAF generation. */
class SafUserBackupService(
    context: Context,
    private val database: StressDatabase,
    private val storage: ModelStorageManager,
    private val archive: PortableUserDataArchive,
    keyManager: KeyManager
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val secretStore = LocalBackupSecretStore(appContext, keyManager)
    private val preferences = appContext.getSharedPreferences("saf_user_backup_state", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(
        SafUserBackupState(
            enabled = secretStore.exists,
            lastSuccessfulAtMillis = preferences.getLong("last_success", 0L).takeIf { it > 0L },
            lastGeneration = preferences.getString("last_generation", null)
        )
    )
    val state: StateFlow<SafUserBackupState> = mutableState.asStateFlow()
    private var started = false
    private val mediaChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val changeRevision = AtomicLong()

    fun requestBackup() {
        markPending()
        mediaChanges.tryEmit(Unit)
    }

    init {
        // A process death can leave a local plaintext staging file; never reuse it.
        File(appContext.cacheDir, ".user-data-restore.zip").delete()
    }

    @Synchronized
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    @Suppress("SpreadOperator") // Room's vararg API; one small scope list, not a hot-path allocation.
    fun startAutoBackup() {
        if (started) return
        started = true
        scope.launch {
            runCatching { archive.recoverInterruptedRestore() }.onFailure(mutableState::reportOperationFailure)
            if (secretStore.exists) runCatching { storage.reconcile() }.onFailure(mutableState::reportOperationFailure)
            merge(
                database.invalidationTracker.createFlow(*PortableArchiveFormat.TABLES.toTypedArray()).map { Unit },
                storage.state.map { it.connected }.distinctUntilChanged().filter { it }.map { Unit },
                mediaChanges
            )
                .onEach { markPending() }
                .debounce(AUTO_BACKUP_QUIET_MILLIS)
                .collect {
                    if (secretStore.exists && storage.state.value.connected) {
                        runCatching { backupWithStoredSecret() }
                            .onFailure(mutableState::reportOperationFailure)
                    }
                }
        }
    }

    suspend fun createBackup(passphrase: CharArray): String = withContext(Dispatchers.IO) {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Kata sandi pemulihan minimal 12 karakter." }
        mutex.withLock {
            archive.recoverInterruptedRestore()
            require(storage.state.value.connected) { "Folder SAF belum terhubung." }
            clearAutomationSecret()
            val generation = publish(passphrase)
            rememberAutomationSecret(passphrase)
            generation
        }
    }

    suspend fun forgetLocalSecret() = mutex.withLock {
        clearAutomationSecret()
    }

    @Suppress("TooGenericExceptionCaught") // Operation boundary reports and rethrows all failures.
    suspend fun restoreBackup(passphrase: CharArray): String = withContext(Dispatchers.IO) {
        require(passphrase.isNotEmpty()) { "Masukkan kata sandi pemulihan." }
        mutex.withLock {
            require(storage.state.value.connected) { "Hubungkan ulang folder SAF yang berisi cadangan." }
            val ciphertext = File(appContext.cacheDir, ".user-data-restore.enc")
            val plaintext = File(appContext.cacheDir, ".user-data-restore.zip")
            mutableState.value = mutableState.value.copy(running = true, error = null)
            try {
                val payload = storage.copyLatestGenerationFile(
                    ModelStorageSlot.USER_DATA_BACKUP,
                    BACKUP_FILE_NAME,
                    ciphertext
                ).getOrThrow() ?: error("Belum ada cadangan data yang lengkap di folder SAF ini.")
                FileInputStream(ciphertext).use { input ->
                    FileOutputStream(plaintext).use { output ->
                        PortableBackupCipher.decrypt(input, output, passphrase)
                        output.fd.sync()
                    }
                }
                clearAutomationSecret()
                FileInputStream(plaintext).use { archive.restoreFrom(it) }
                mutableState.update {
                    it.copy(running = false, lastGeneration = payload.generation, error = null)
                }
                rememberAutomationSecret(passphrase)
                payload.generation
            } catch (error: Exception) {
                mutableState.reportOperationFailure(error)
                throw error
            } finally {
                ciphertext.delete()
                plaintext.delete()
            }
        }
    }

    private suspend fun backupWithStoredSecret() = mutex.withLock {
        if (!secretStore.exists) return@withLock
        val passphrase = secretStore.read()
        try {
            publish(passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    @Suppress("TooGenericExceptionCaught") // Incomplete generation must not be reported as a backup.
    private suspend fun publish(passphrase: CharArray): String {
        require(storage.state.value.connected) { "Folder SAF belum terhubung." }
        val snapshotRevision = changeRevision.get()
        mutableState.value = mutableState.value.copy(running = true, error = null)
        val candidate = File(appContext.cacheDir, ".user-data-backup.enc.part")
        try {
            FileOutputStream(candidate).use { output ->
                PortableBackupCipher.encryptTo(output, passphrase) { plain -> archive.writeTo(plain) }
                output.fd.sync()
            }
            val sha = sha256(candidate)
            val generation = "data-${System.currentTimeMillis()}-${SecureRandom().nextInt(GENERATION_NONCE_BOUND)}"
            storage.publishGeneration(
                slot = ModelStorageSlot.USER_DATA_BACKUP,
                generation = generation,
                version = "1",
                revision = sha,
                payloads = listOf(
                    SafGenerationPayload(
                        source = candidate,
                        fileName = BACKUP_FILE_NAME,
                        expectedSizeBytes = candidate.length(),
                        expectedSha256 = sha
                    )
                )
            ).getOrThrow()
            val now = System.currentTimeMillis()
            preferences.edit().putLong("last_success", now).putString("last_generation", generation).apply()
            mutableState.update {
                SafUserBackupState(
                    enabled = secretStore.exists,
                    pendingChanges = changeRevision.get() != snapshotRevision,
                    lastSuccessfulAtMillis = now,
                    lastGeneration = generation
                )
            }
            return generation
        } catch (error: Exception) {
            mutableState.reportOperationFailure(error)
            throw error
        } finally {
            candidate.delete()
        }
    }

    private fun clearAutomationSecret() {
        // Do this before publication/import commits so a failed cache write cannot reuse an old password.
        secretStore.forget()
        mutableState.update { it.copy(enabled = false) }
    }

    @Suppress("TooGenericExceptionCaught") // Optional cache errors must not invalidate a committed archive.
    private fun rememberAutomationSecret(passphrase: CharArray) {
        val cacheError = try {
            secretStore.save(passphrase)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            error
        }
        mutableState.update { it.withAutomationCache(cacheError) }
    }

    private fun markPending() {
        changeRevision.incrementAndGet()
        if (secretStore.exists) mutableState.update { it.copy(pendingChanges = true) }
    }

    private companion object {
        const val BACKUP_FILE_NAME = "user-data.sdb"
        const val MIN_PASSPHRASE_LENGTH = 12
        const val AUTO_BACKUP_QUIET_MILLIS = 15_000L
        const val GENERATION_NONCE_BOUND = 1_000_000
    }
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
