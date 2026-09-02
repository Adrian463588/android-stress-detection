package com.stressdetector.mobile.domain.engine

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.stressdetector.mobile.ml.llm.GemmaRuntimeContract
import com.stressdetector.mobile.ml.llm.ModelManager
import com.stressdetector.mobile.ml.llm.ModelReadiness
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Production on-device Gemma 4 E2B runtime backed by LiteRT-LM. */
class LiteRTLlmEngine(private val context: Context, private val modelManager: ModelManager = ModelManager(context.applicationContext)) :
    OnDeviceLlmEngine {

    companion object {
        private const val TAG = "LiteRTLlmEngine"
        private const val RUNTIME_VERSION = GemmaRuntimeContract.RUNTIME_VERSION
        private const val MAX_CONTEXT_TOKENS = GemmaRuntimeContract.MAX_NUM_TOKENS
        private const val GENERATION_TIMEOUT_MS = 120_000L
        private const val PREF_SUCCESS_FINGERPRINT = "success_fingerprint"
        private const val PREFS_NAME = "ai_runtime_preflight"
        const val MODEL_FILE_NAME = ModelManager.MODEL_FILE
    }

    private val applicationContext = context.applicationContext
    private val lifecycleLock = Any()
    private val runtimeMutex = Mutex()
    private val generationMutex = Mutex()
    private val generationCoordinator = LiteRtGenerationCoordinator()
    private val promptBuilder = CounselorPromptBuilder()
    private val preflightPreferences = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutableRuntimeReadiness = MutableStateFlow<LlmRuntimeReadiness>(LlmRuntimeReadiness.Unknown)

    @Volatile
    private var successfulPreflightFingerprint: String? = null

    @Volatile
    private var engine: Engine? = null

    @Volatile
    private var loadedModelFingerprint: String? = null

    @Volatile
    private var activeConversation: Conversation? = null

    override fun runtimeReadiness(): StateFlow<LlmRuntimeReadiness> = mutableRuntimeReadiness.asStateFlow()

    fun getModelDir(): File = modelManager.getModelDir()

    override suspend fun load(): LlmReadiness = runtimeMutex.withLock {
        loadInternal()
    }

    private suspend fun loadInternal(): LlmReadiness = withContext(Dispatchers.IO) {
        val packageStatus = modelManager.currentReadiness()
        if (packageStatus !is ModelReadiness.Ready) {
            return@withContext packageStatus.toLlmReadiness()
        }

        val modelFingerprint = modelFingerprint(packageStatus)
        engine?.let {
            if (loadedModelFingerprint == modelFingerprint) {
                return@withContext LlmReadiness.Ready(packageStatus.metadata.version)
            }
            closeEngine()
        }

        runCatching {
            val config = EngineConfig(
                modelPath = modelManager.getModelFile().absolutePath,
                backend = Backend.CPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = GemmaRuntimeContract.cacheDirectory(applicationContext)
                    .also { it.mkdirs() }
                    .absolutePath
            )
            val candidate = Engine(config)
            candidate.initialize()
            synchronized(lifecycleLock) {
                engine?.close()
                engine = candidate
                loadedModelFingerprint = modelFingerprint
            }
            LlmReadiness.Ready(packageStatus.metadata.version)
        }.getOrElse { error ->
            Log.e(TAG, "LiteRT-LM initialization failed", error)
            synchronized(lifecycleLock) {
                engine?.close()
                engine = null
            }
            mutableRuntimeReadiness.value = LlmRuntimeReadiness.Unavailable(
                userMessage = "AI lokal belum dapat memuat model pada perangkat ini.",
                technicalMessage = error.message
            )
            LlmReadiness.InitializationFailed(error.message ?: "LiteRT-LM initialization failed")
        }
    }

    override suspend fun preflight(): LlmRuntimeReadiness = runtimeMutex.withLock {
        preflightInternal()
    }

    private suspend fun preflightInternal(): LlmRuntimeReadiness = withContext(Dispatchers.IO) {
        val modelStatus = modelManager.currentReadiness()
        val readyModel = modelStatus as? ModelReadiness.Ready
        if (readyModel == null) {
            val result = LlmRuntimeReadiness.Unavailable(
                userMessage = "Model AI belum tersedia. Unduh model melalui Profile.",
                technicalMessage = modelStatus.toString()
            )
            mutableRuntimeReadiness.value = result
            return@withContext result
        }

        val fingerprint = listOf(
            readyModel.metadata.sha256,
            readyModel.metadata.sizeBytes,
            modelManager.getModelFile().lastModified(),
            RUNTIME_VERSION,
            android.os.Build.VERSION.SDK_INT,
            android.os.Build.DEVICE
        ).joinToString("|")
        if (engine != null &&
            loadedModelFingerprint == modelFingerprint(readyModel) &&
            (
                successfulPreflightFingerprint == fingerprint ||
                    preflightPreferences.getString(PREF_SUCCESS_FINGERPRINT, null) == fingerprint
                )
        ) {
            successfulPreflightFingerprint = fingerprint
            val cached = LlmRuntimeReadiness.Ready(RUNTIME_VERSION)
            mutableRuntimeReadiness.value = cached
            return@withContext cached
        }

        mutableRuntimeReadiness.value = LlmRuntimeReadiness.Checking
        val loaded = loadInternal()
        if (loaded !is LlmReadiness.Ready) {
            val result = LlmRuntimeReadiness.Unavailable(
                userMessage = "AI lokal belum dapat memuat model pada perangkat ini.",
                technicalMessage = loaded.toString()
            )
            mutableRuntimeReadiness.value = result
            return@withContext result
        }

        val runtime = engine
        if (runtime == null) {
            val result = LlmRuntimeReadiness.Unavailable(
                userMessage = "AI lokal belum siap digunakan.",
                technicalMessage = "LiteRT-LM engine is unavailable after initialization"
            )
            mutableRuntimeReadiness.value = result
            return@withContext result
        }

        val result = try {
            val conversation = runtime.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("You are a local runtime health check. Reply briefly."),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                )
            )
            try {
                withTimeout(GENERATION_TIMEOUT_MS) {
                    var receivedContent = false
                    conversation.sendMessageAsync("ping").collect { message ->
                        if (message.textContent().isNotBlank()) receivedContent = true
                    }
                    check(receivedContent) { "LiteRT-LM preflight returned an empty response." }
                }
            } finally {
                conversation.cancelProcess()
                conversation.close()
            }
            LlmRuntimeReadiness.Ready(RUNTIME_VERSION)
        } catch (timeout: TimeoutCancellationException) {
            Log.e(TAG, "LiteRT-LM preflight timed out", timeout)
            closeEngine()
            LlmRuntimeReadiness.Unavailable(
                userMessage = "AI lokal terlalu lama menyiapkan model pada perangkat ini.",
                technicalMessage = timeout.message
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.e(TAG, "LiteRT-LM preflight failed", error)
            closeEngine()
            LlmRuntimeReadiness.Unavailable(
                userMessage = "AI lokal belum dapat menjalankan model pada perangkat ini.",
                technicalMessage = error.message
            )
        }
        mutableRuntimeReadiness.value = result
        if (result is LlmRuntimeReadiness.Ready) {
            successfulPreflightFingerprint = fingerprint
            preflightPreferences.edit().putString(PREF_SUCCESS_FINGERPRINT, fingerprint).apply()
        }
        result
    }

    override fun readiness(): LlmReadiness {
        val packageStatus = modelManager.currentReadiness()
        return when (packageStatus) {
            is ModelReadiness.Ready -> if (engine != null && loadedModelFingerprint == modelFingerprint(packageStatus)) {
                LlmReadiness.Ready(packageStatus.metadata.version)
            } else {
                LlmReadiness.InitializationFailed("Gemma 4 E2B is validated but LiteRT-LM is not loaded")
            }
            else -> packageStatus.toLlmReadiness()
        }
    }

    override suspend fun generate(request: CounselorGenerationRequest): Flow<LlmGenerationState> = flow {
        generationMutex.withLock {
            val loaded = load()
            if (loaded !is LlmReadiness.Ready) {
                emit(LlmGenerationState.Unavailable(loaded))
                return@withLock
            }

            val runtime = engine ?: run {
                emit(LlmGenerationState.Unavailable(LlmReadiness.InitializationFailed("LiteRT-LM engine is unavailable")))
                return@withLock
            }

            var conversation: Conversation? = null
            try {
                val createdConversation = runtime.createConversation(
                    ConversationConfig(
                        systemInstruction = Contents.of(promptBuilder.systemInstruction(request)),
                        samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.7)
                    )
                )
                conversation = createdConversation
                generationCoordinator.begin(createdConversation)
                synchronized(lifecycleLock) {
                    activeConversation = createdConversation
                }
                withTimeout(GENERATION_TIMEOUT_MS) {
                    createdConversation.sendMessageAsync(promptBuilder.userPrompt(request)).collect { message ->
                        val text = message.textContent()
                        if (text.isNotBlank()) emit(LlmGenerationState.Content(text))
                    }
                }
            } catch (timeout: TimeoutCancellationException) {
                generationCoordinator.requestCancellation()?.cancelProcess()
                generationCoordinator.requestReset()
                emit(LlmGenerationState.Failed("LiteRT-LM generation timed out after ${GENERATION_TIMEOUT_MS / 1_000}s"))
            } catch (cancelled: CancellationException) {
                generationCoordinator.requestCancellation()?.cancelProcess()
                emit(LlmGenerationState.Cancelled)
                throw cancelled
            } catch (error: Throwable) {
                Log.e(TAG, "Gemma 4 E2B generation failed", error)
                mutableRuntimeReadiness.value = LlmRuntimeReadiness.Unavailable(
                    userMessage = "AI lokal belum dapat menjalankan model pada perangkat ini.",
                    technicalMessage = error.message
                )
                generationCoordinator.requestReset()
                emit(LlmGenerationState.Failed(error.message ?: "Gemma 4 E2B generation failed"))
            } finally {
                synchronized(lifecycleLock) {
                    if (activeConversation === conversation) activeConversation = null
                }
                conversation?.close()
                if (generationCoordinator.finish(conversation)) closeEngineNow()
            }
        }
    }.flowOn(Dispatchers.IO)

    override fun cancelGeneration() {
        generationCoordinator.requestCancellation()?.cancelProcess()
    }

    override suspend fun unload() = withContext(Dispatchers.IO) {
        shutdown()
    }

    override fun shutdown() {
        val active = generationCoordinator.requestReset()
        active?.cancelProcess()
        if (active != null || generationCoordinator.hasActiveGeneration()) return
        closeEngineNow()
    }

    private fun closeEngine() {
        val active = generationCoordinator.requestReset()
        active?.cancelProcess()
        if (active != null || generationCoordinator.hasActiveGeneration()) return
        closeEngineNow()
    }

    private fun closeEngineNow() {
        synchronized(lifecycleLock) {
            activeConversation?.close()
            activeConversation = null
            engine?.close()
            engine = null
            loadedModelFingerprint = null
        }
        successfulPreflightFingerprint = null
        preflightPreferences.edit().remove(PREF_SUCCESS_FINGERPRINT).apply()
        mutableRuntimeReadiness.value = LlmRuntimeReadiness.Unknown
    }

    private fun detachActiveConversation(conversation: Conversation?) {
        synchronized(lifecycleLock) {
            if (activeConversation === conversation) activeConversation = null
        }
    }

    private fun modelFingerprint(status: ModelReadiness.Ready): String = listOf(
        status.metadata.sha256,
        status.metadata.sizeBytes,
        modelManager.getModelFile().lastModified()
    ).joinToString("|")

    private fun Message.textContent(): String = contents.contents
        .filterIsInstance<Content.Text>()
        .joinToString(separator = "") { it.text }

    private fun ModelReadiness.toLlmReadiness(): LlmReadiness = when (this) {
        ModelReadiness.Missing -> LlmReadiness.ModelMissing
        is ModelReadiness.Corrupted -> LlmReadiness.ModelCorrupted(reason)
        is ModelReadiness.Untrusted -> LlmReadiness.ModelCorrupted(reason)
        is ModelReadiness.UnsupportedDevice -> LlmReadiness.InitializationFailed(reason)
        is ModelReadiness.Ready -> LlmReadiness.Ready(metadata.version)
    }
}
