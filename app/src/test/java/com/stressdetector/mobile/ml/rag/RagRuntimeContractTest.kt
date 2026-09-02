package com.stressdetector.mobile.ml.rag

import com.stressdetector.mobile.domain.counselor.CounselorPersonaRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagRuntimeContractTest {
    @Test
    fun `generated contract pins E5 hybrid retrieval and fixture-only release state`() {
        val contract = AssetRagRuntimeContract.parseJson(generatedContractAsset().readText())

        assertEquals("rag_e5_v1", contract.configVersion)
        assertEquals(384, contract.embedding.dimensions)
        assertEquals(RagRuntimeContract.E5_REVISION, contract.embedding.revision)
        assertEquals("query: ", contract.embedding.queryPrefix)
        assertEquals("passage: ", contract.embedding.passagePrefix)
        assertEquals(60, contract.retrieval.rrfK)
        assertEquals(0.75f, contract.retrieval.mmrLambda)
        assertEquals("abstain", contract.retrieval.uncalibratedBehavior)
        assertTrue(contract.retrievalRanker().lexicalBackendStatus.productionReady)
        contract.requirePersonaWordLimit(CounselorPersonaRepository.MAX_FOCUS_NOTE_WORDS)
    }

    @Test
    fun `contract checksum rejects a changed runtime asset`() {
        val tampered = generatedContractAsset().readText().replaceFirst("\"rrf_k\": 60", "\"rrf_k\": 61")

        val error = runCatching { AssetRagRuntimeContract.parseJson(tampered) }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("rag_contract_checksum_mismatch"))
    }

    private fun generatedContractAsset(): File = listOf(
        File("src/main/canonicalAssets/ai/rag_runtime_contract.json"),
        File("app/src/main/canonicalAssets/ai/rag_runtime_contract.json"),
        File("android/app/src/main/canonicalAssets/ai/rag_runtime_contract.json")
    ).firstOrNull(File::isFile) ?: error("Generated RAG runtime contract is missing.")
}
