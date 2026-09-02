package com.stressdetector.mobile.ml.llm

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

fun interface GemmaCandidatePreflight {
    suspend fun verify(candidate: File): Result<Unit>
}

internal class LiteRtGemmaCandidatePreflight(context: Context) : GemmaCandidatePreflight {
    private val cacheDirectory = GemmaRuntimeContract.cacheDirectory(context)

    override suspend fun verify(candidate: File): Result<Unit> = withContext(Dispatchers.IO) {
        var engine: Engine? = null
        var conversation: Conversation? = null
        try {
            require(candidate.isFile && candidate.length() > 0L) { "Candidate Gemma tidak tersedia." }
            cacheDirectory.mkdirs()
            val createdEngine = Engine(
                EngineConfig(
                    modelPath = candidate.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = null,
                    audioBackend = null,
                    maxNumTokens = GemmaRuntimeContract.MAX_NUM_TOKENS,
                    cacheDir = cacheDirectory.absolutePath
                )
            )
            createdEngine.initialize()
            engine = createdEngine
            val createdConversation = createdEngine.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of("Local runtime health check. Reply briefly."),
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0)
                )
            )
            conversation = createdConversation
            withTimeout(PREFLIGHT_TIMEOUT_MILLIS) {
                var receivedContent = false
                createdConversation.sendMessageAsync("ping").collect { message ->
                    val text = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString(separator = "") { it.text }
                    if (text.isNotBlank()) receivedContent = true
                }
                check(receivedContent) { "LiteRT-LM preflight menghasilkan respons kosong." }
            }
            Result.success(Unit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Result.failure(error)
        } finally {
            runCatching { conversation?.cancelProcess() }
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
        }
    }

    private companion object {
        const val PREFLIGHT_TIMEOUT_MILLIS = 120_000L
    }
}
