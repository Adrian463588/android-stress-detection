package com.stressdetector.mobile.lifecycle

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.stressdetector.mobile.data.db.ChatMessageEntity
import com.stressdetector.mobile.data.db.ChatSessionEntity
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.data.db.StressDatabase
import com.stressdetector.mobile.data.db.StressSessionEntity
import com.stressdetector.mobile.data.db.StressSessionReportEntity
import com.stressdetector.mobile.data.db.StressWindowEvaluationEntity
import com.stressdetector.mobile.data.db.TranscriptSegmentEntity
import com.stressdetector.mobile.data.db.VerbatimSessionEntity
import com.stressdetector.mobile.domain.history.HistoryChartArtifactStore
import com.stressdetector.mobile.domain.history.HistoryChartSeries
import com.stressdetector.mobile.ml.storage.ModelStorageManager
import com.stressdetector.mobile.security.AndroidEncryptedStorageService
import com.stressdetector.mobile.security.AndroidKeystoreKeyManager
import com.stressdetector.mobile.security.KeyManager
import com.stressdetector.mobile.security.PrivacyIdentity
import com.stressdetector.shared.PpgType
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.util.UUID
import javax.crypto.SecretKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable fixtures exercise real Room/file/Keystore APIs without accessing user records. */
@RunWith(AndroidJUnit4::class)
class PortableUserDataArchiveInstrumentedTest {
    private lateinit var root: File
    private lateinit var sourceContext: Context
    private lateinit var targetContext: Context
    private lateinit var source: StressDatabase
    private lateinit var target: StressDatabase
    private lateinit var sourceKeys: IsolatedKeys
    private lateinit var targetKeys: IsolatedKeys
    private lateinit var sourceStorage: AndroidEncryptedStorageService
    private lateinit var targetStorage: AndroidEncryptedStorageService

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val id = UUID.randomUUID().toString()
        root = File(context.cacheDir, "portable-archive-test-$id").apply { mkdirs() }
        sourceContext = IsolatedContext(context, File(root, "source"))
        targetContext = IsolatedContext(context, File(root, "target"))
        source = Room.inMemoryDatabaseBuilder(sourceContext, StressDatabase::class.java).build()
        target = Room.inMemoryDatabaseBuilder(targetContext, StressDatabase::class.java).build()
        sourceKeys = IsolatedKeys("portable-test-source-$id")
        targetKeys = IsolatedKeys("portable-test-target-$id")
        sourceStorage = AndroidEncryptedStorageService(sourceContext, sourceKeys)
        targetStorage = AndroidEncryptedStorageService(targetContext, targetKeys)
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
        sourceKeys.deleteKeys(PrivacyIdentity.LOCAL_PROFILE_ID)
        targetKeys.deleteKeys(PrivacyIdentity.LOCAL_PROFILE_ID)
        check(root.canonicalPath.startsWith(ApplicationProvider.getApplicationContext<Context>().cacheDir.canonicalPath + File.separator))
        root.deleteRecursively()
    }

    @Test
    fun restoresAllThreeDomainsUsingANewKeystoreKeyAndRejectsOverwrite() = runBlocking(Dispatchers.IO) {
        populateSource()
        val secret = "test-only-recovery-password".toCharArray()
        val ciphertext = ByteArrayOutputStream()
        PortableBackupCipher.encryptTo(ciphertext, secret) {
            PortableUserDataArchive(sourceContext, source, sourceStorage).writeTo(it)
        }
        val decoded = ByteArrayOutputStream()
        PortableBackupCipher.decrypt(ByteArrayInputStream(ciphertext.toByteArray()), decoded, secret)
        val archive = PortableUserDataArchive(targetContext, target, targetStorage)
        val receipt = archive.restoreFrom(ByteArrayInputStream(decoded.toByteArray()))

        assertRestoredDomains(receipt)
        assertThrows(IllegalArgumentException::class.java) { archive.restoreFrom(ByteArrayInputStream(decoded.toByteArray())) }
        assertEquals(2, target.chatDao().getMessagesForSession("chat-fixture").first().size)
        secret.fill('\u0000')
    }

    @Test
    fun selectedSafProviderRestoresAllDomainsWithoutTheOldInstallationKey() = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val resolver = context.contentResolver
        // Construction reads the selection only: no reconcile, selection writes, generation or auto-backup.
        val tree = ModelStorageManager(context).state.value.treeUri
        assumeTrue("A local SAF tree must already be selected.", tree?.authority == SafRecoveryTestFolder.LOCAL_AUTHORITY)
        val selected = requireNotNull(tree)
        assumeTrue(
            "The existing tree must have a persisted read/write grant.",
            resolver.persistedUriPermissions.any { it.uri == selected && it.isReadPermission && it.isWritePermission }
        )
        populateSource()
        val secret = "test-only-provider-recovery-password".toCharArray()
        try {
            SafRecoveryTestFolder(resolver, selected).use { folder ->
                folder.output().use { output ->
                    PortableBackupCipher.encryptTo(output, secret) {
                        PortableUserDataArchive(sourceContext, source, sourceStorage).writeTo(it)
                    }
                }
                sourceKeys.deleteKeys(PrivacyIdentity.LOCAL_PROFILE_ID)
                assertFalse(sourceKeys.isKeyValid(PrivacyIdentity.LOCAL_PROFILE_ID))
                assertThrows(Exception::class.java) {
                    folder.input().use { PortableBackupCipher.decrypt(it, ByteArrayOutputStream(), "test-only-wrong-secret".toCharArray()) }
                }
                assertTrue(target.verbatimDao().getAllSessionsOnce().isEmpty())
                val decoded = ByteArrayOutputStream()
                folder.input().use { PortableBackupCipher.decrypt(it, decoded, secret) }
                val receipt = PortableUserDataArchive(targetContext, target, targetStorage)
                    .restoreFrom(ByteArrayInputStream(decoded.toByteArray()))
                assertRestoredDomains(receipt)
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    @Test
    fun optionalAutomationCacheIoFailureDoesNotInvalidateRestoredDataOrReuseOldPassword() = runBlocking(Dispatchers.IO) {
        populateSource()
        val archive = ByteArrayOutputStream()
        PortableUserDataArchive(sourceContext, source, sourceStorage).writeTo(archive)
        val store = LocalBackupSecretStore(targetContext, targetKeys)
        val oldSecret = "test-only-previous-password".toCharArray()
        try {
            store.save(oldSecret)
        } finally {
            oldSecret.fill('\u0000')
        }
        store.forget()
        val receipt = PortableUserDataArchive(targetContext, target, targetStorage)
            .restoreFrom(ByteArrayInputStream(archive.toByteArray()))
        val blocked = File(targetContext.noBackupFilesDir, ".saf-user-recovery-secret.enc.part").apply { mkdirs() }
        File(blocked, "test-only-blocker").writeText("fixture")
        val newSecret = "test-only-current-password".toCharArray()
        val failure = try {
            assertThrows(Exception::class.java) { store.save(newSecret) }
        } finally {
            newSecret.fill('\u0000')
        }
        val state = SafUserBackupState(lastGeneration = "test-only-completed-import").withAutomationCache(failure)

        assertRestoredDomains(receipt)
        assertFalse(state.enabled)
        assertEquals("test-only-completed-import", state.lastGeneration)
        assertTrue(requireNotNull(state.error).contains("cadangan otomatis belum aktif"))
        assertFalse(LocalBackupSecretStore(targetContext, targetKeys).exists)
    }

    private suspend fun assertRestoredDomains(receipt: RestoreSummary) {
        assertEquals(1L, receipt.rowsByTable["stress_sessions"])
        assertEquals(1L, receipt.rowsByTable["stress_session_reports"])
        assertEquals(1L, receipt.rowsByTable["stress_window_evaluations"])
        assertEquals(2L, receipt.rowsByTable["chat_messages"])
        assertEquals(1L, receipt.rowsByTable["transcript_segments"])
        assertEquals(1L, receipt.rowsByTable["divergence_events"])
        assertEquals(1, receipt.chartFiles)
        assertEquals(1, receipt.encryptedFiles)
        assertEquals(source.stressSessionDao().getById("home-fixture"), target.stressSessionDao().getById("home-fixture"))
        assertEquals(
            source.chatDao().getMessagesForSession("chat-fixture").first(),
            target.chatDao().getMessagesForSession("chat-fixture").first()
        )
        assertEquals(
            source.verbatimDao().getSegmentsForSession("verbatim-fixture").first(),
            target.verbatimDao().getSegmentsForSession("verbatim-fixture").first()
        )
        assertEquals(
            source.liveMonitorDao().getDivergenceEventsForSession("verbatim-fixture").first(),
            target.liveMonitorDao().getDivergenceEventsForSession("verbatim-fixture").first()
        )
        val restoredChart = HistoryChartArtifactStore(targetContext).load("home-fixture")!!
        assertEquals("fixture-fingerprint", restoredChart.series.single().processingFingerprint)
        assertArrayEquals(longArrayOf(30_000L, 30_020L), restoredChart.series.single().elapsedMillis)
        val restoredAudio = ByteArrayOutputStream()
        targetStorage.copyEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, AUDIO_PATH, restoredAudio)
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 4), restoredAudio.toByteArray())
        assertTrue(targetKeys.isKeyValid(PrivacyIdentity.LOCAL_PROFILE_ID))
    }

    @Test
    fun wrongRecoverySecretCannotImportRows() = runBlocking(Dispatchers.IO) {
        populateSource()
        val ciphertext = ByteArrayOutputStream()
        PortableBackupCipher.encryptTo(ciphertext, "test-only-correct-secret".toCharArray()) {
            PortableUserDataArchive(sourceContext, source, sourceStorage).writeTo(it)
        }
        assertThrows(Exception::class.java) {
            val decoded = ByteArrayOutputStream()
            PortableBackupCipher.decrypt(ByteArrayInputStream(ciphertext.toByteArray()), decoded, "test-only-wrong-secret".toCharArray())
            PortableUserDataArchive(targetContext, target, targetStorage).restoreFrom(ByteArrayInputStream(decoded.toByteArray()))
        }
        assertEquals(0, target.chatDao().getAllChatSessions().first().size)
        assertEquals(0, target.verbatimDao().getAllSessionsOnce().size)
        assertTrue(targetStorage.listEncryptedFiles(PrivacyIdentity.LOCAL_PROFILE_ID).isEmpty())
    }

    @Test
    fun interruptedRestoreRemovesOnlyItsOwnedMedia() {
        val prefix = "portable_archive_staging/${UUID.randomUUID()}"
        val staged = "$prefix/1.enc"
        val unrelated = "verbatim/unrelated-test/audio.wav.enc"
        targetStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, ByteArrayInputStream(byteArrayOf(1, 2)))
        targetStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, unrelated, ByteArrayInputStream(byteArrayOf(3, 4)))
        PortableRestoreJournal(targetContext, targetStorage, PrivacyIdentity.LOCAL_PROFILE_ID)
            .begin(mapOf(AUDIO_PATH to staged), emptyMap(), prefix)
        targetStorage.moveNewEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, AUDIO_PATH)

        PortableUserDataArchive(targetContext, target, targetStorage).recoverInterruptedRestore()
        assertEquals(listOf(unrelated), targetStorage.listEncryptedFiles(PrivacyIdentity.LOCAL_PROFILE_ID).map { it.relativePath })
    }

    @Test
    fun committedRowsKeepMediaWhileJournalStagingIsCleaned() = runBlocking(Dispatchers.IO) {
        val prefix = "portable_archive_staging/${UUID.randomUUID()}"
        val staged = "$prefix/1.enc"
        targetStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, ByteArrayInputStream(byteArrayOf(1, 2)))
        PortableRestoreJournal(targetContext, targetStorage, PrivacyIdentity.LOCAL_PROFILE_ID)
            .begin(mapOf(AUDIO_PATH to staged), emptyMap(), prefix)
        targetStorage.moveNewEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, AUDIO_PATH)
        target.chatDao().insertChatSession(ChatSessionEntity("committed-test", "Test only", 1L, 2L))
        target.runInTransaction {
            PortableRestoreCommits().markCommitted(target.openHelper.writableDatabase, prefix)
        }

        PortableUserDataArchive(targetContext, target, targetStorage).recoverInterruptedRestore()
        assertEquals(listOf(AUDIO_PATH), targetStorage.listEncryptedFiles(PrivacyIdentity.LOCAL_PROFILE_ID).map { it.relativePath })
        assertEquals(1, target.chatDao().getAllChatSessions().first().size)
    }

    @Test
    fun createOnlyPublicationAndRecoveryCannotDeleteAnUnrelatedRaceWinner() {
        val prefix = "portable_archive_staging/${UUID.randomUUID()}"
        val staged = "$prefix/1.enc"
        targetStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, ByteArrayInputStream(byteArrayOf(1, 2)))
        val journal = PortableRestoreJournal(targetContext, targetStorage, PrivacyIdentity.LOCAL_PROFILE_ID)
        journal.begin(mapOf(AUDIO_PATH to staged), emptyMap(), prefix)
        targetStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, AUDIO_PATH, ByteArrayInputStream(byteArrayOf(3, 4)))
        assertThrows(FileAlreadyExistsException::class.java) {
            targetStorage.moveNewEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, AUDIO_PATH)
        }
        assertThrows(IllegalStateException::class.java) { journal.recover { false } }
        val preserved = ByteArrayOutputStream()
        targetStorage.copyEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, AUDIO_PATH, preserved)
        assertArrayEquals(byteArrayOf(3, 4), preserved.toByteArray())
        assertTrue(targetStorage.listEncryptedFiles(PrivacyIdentity.LOCAL_PROFILE_ID).any { it.relativePath == staged })
    }

    @Test
    fun unrelatedRowsDoNotTurnRolledBackMediaIntoCommittedRestore() = runBlocking(Dispatchers.IO) {
        val prefix = "portable_archive_staging/${UUID.randomUUID()}"
        val staged = "$prefix/1.enc"
        targetStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, ByteArrayInputStream(byteArrayOf(1, 2)))
        PortableRestoreJournal(targetContext, targetStorage, PrivacyIdentity.LOCAL_PROFILE_ID)
            .begin(mapOf(AUDIO_PATH to staged), emptyMap(), prefix)
        targetStorage.moveNewEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, AUDIO_PATH)
        target.chatDao().insertChatSession(ChatSessionEntity("unrelated-test", "Test only", 1L, 2L))

        PortableUserDataArchive(targetContext, target, targetStorage).recoverInterruptedRestore()
        assertTrue(targetStorage.listEncryptedFiles(PrivacyIdentity.LOCAL_PROFILE_ID).isEmpty())
        assertEquals("unrelated-test", target.chatDao().getAllChatSessions().first().single().chatSessionId)
    }

    @Test
    fun abandonedChartPreparationIsCleanedWithoutTouchingExistingCharts() {
        val stagingId = UUID.randomUUID().toString()
        val staged = "portable_archive_staging/$stagingId/1.enc"
        targetStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, staged, ByteArrayInputStream("{}".toByteArray()))
        val charts = File(targetContext.filesDir, "history_chart_artifacts")
        val media = PortableArchiveMedia(targetStorage, PrivacyIdentity.LOCAL_PROFILE_ID, charts)
        val temporary = media.prepareChart(staged, "abandoned-test", stagingId)
        val existing = File(charts, "existing-test.json").apply { writeText("{}") }

        PortableUserDataArchive(targetContext, target, targetStorage).recoverInterruptedRestore()
        assertTrue(!temporary.exists())
        assertEquals("{}", existing.readText())
        assertTrue(!File(charts, ".portable-restore").exists())
    }

    private suspend fun populateSource() {
        source.stressSessionDao().insertWithReport(
            StressSessionEntity("home-fixture", 1L, 300_001L, 300_000L, "GREEN", "INVALID", 0f, "invalid", null, 0, 0, "test-only-fixture"),
            StressSessionReportEntity(
                sessionId = "home-fixture", sessionDurationSeconds = 300, coreStartSeconds = 30,
                coreEndSeconds = 270, analysisDurationSeconds = 240, expectedWindowCount = 7,
                greenAttemptedWindows = 1, greenValidWindows = 0, irAttemptedWindows = 0, irValidWindows = 0,
                selectedChannel = null, modelVersion = null, preprocessingFingerprint = "fixture-fingerprint",
                reportStatus = "INVALID", rejectionReason = "test-only-fixture", createdAtMillis = 300_001L
            ),
            listOf(
                StressWindowEvaluationEntity(
                    "home-fixture", "GREEN", 0, "window-fixture", 30_000L, 210_000L,
                    "REJECTED", null, null, null, "invalid", null, 0, 0, "test-only-fixture", null, "fixture-fingerprint"
                )
            )
        )
        HistoryChartArtifactStore(sourceContext).save(
            "home-fixture",
            listOf(
                HistoryChartSeries(
                    "home-fixture",
                    PpgType.GREEN,
                    longArrayOf(30_000L, 30_020L),
                    floatArrayOf(1f, 2f),
                    booleanArrayOf(false, false),
                    emptyList(),
                    "fixture-fingerprint"
                )
            )
        ).getOrThrow()
        source.chatDao().insertChatSession(ChatSessionEntity("chat-fixture", "Test only", 1L, 3L))
        source.chatDao().insertMessage(ChatMessageEntity("message-user", "chat-fixture", "user", "Fixture Indonesia", 2L, null))
        source.chatDao().insertMessage(ChatMessageEntity("message-ai", "chat-fixture", "counselor", "Fixture English", 3L, "[]"))
        sourceStorage.writeEncryptedFile(PrivacyIdentity.LOCAL_PROFILE_ID, AUDIO_PATH, ByteArrayInputStream(byteArrayOf(0, 1, 2, 3, 4)))
        source.verbatimDao().insertSession(
            VerbatimSessionEntity("verbatim-fixture", 1L, 1001L, AUDIO_PATH, true, 1L, "COMPLETED", finalizationStatus = "COMPLETED")
        )
        source.verbatimDao().insertSegments(
            listOf(
                TranscriptSegmentEntity(
                    "segment-fixture", "verbatim-fixture", "Speaker 1", 0L, 1000L,
                    "mungkin test", "mungkin test edited", 0.8f, "id"
                )
            )
        )
        source.liveMonitorDao().insertDivergenceEvents(
            listOf(
                DivergenceEventEntity(
                    "marker-fixture", "verbatim-fixture", 501L,
                    null, null, null, "Fixture cue", channel = "AUDIO", markerType = "ACOUSTIC_DIVERGENCE", markerElapsedMillis = 500L
                )
            )
        )
    }

    private class IsolatedContext(base: Context, private val root: File) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir(): File = File(root, "no_backup").apply { mkdirs() }
    }

    private class IsolatedKeys(private val id: String) : KeyManager {
        private val delegate = AndroidKeystoreKeyManager()
        override fun getOrCreateFileEncryptionKey(userId: String): SecretKey = delegate.getOrCreateFileEncryptionKey(id)
        override fun getExistingFileEncryptionKey(userId: String): SecretKey = delegate.getExistingFileEncryptionKey(id)
        override fun deleteKeys(userId: String) = delegate.deleteKeys(id)
        override fun isKeyValid(userId: String): Boolean = delegate.isKeyValid(id)
    }

    private companion object {
        const val AUDIO_PATH = "verbatim/verbatim-fixture/audio.wav.enc"
    }
}
