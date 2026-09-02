package com.stressdetector.mobile.ml.rag

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** Parses the generated contract and rejects any incomplete or non-canonical asset. */
internal object RagRuntimeContractJson {
    private val supportedFormats = listOf("pdf", "docx", "txt_utf8", "markdown")
    private val releaseGateNames = listOf(
        "sqlite_fts5_integrated",
        "retrieval_benchmark_passed",
        "signed_e5_artifact_parity_passed",
        "encrypted_source_storage_passed",
        "encrypted_database_passed",
        "physical_end_to_end_validation_passed"
    )

    fun parse(raw: String): RagRuntimeContract {
        val root = ContractJsonReader(JsonParser.parseString(raw).asJsonObject)
        val contractChecksum = root.string("contract_sha256")
        require(contractChecksum == root.copyWithout("contract_sha256").canonicalContractSha256()) {
            "rag_contract_checksum_mismatch"
        }
        require(root.string("schema_version") == "android_rag_contract_v1") { "rag_contract_schema_unsupported" }
        verifyFormats(root.objectValue("formats"))
        val embedding = root.objectValue("embedding")
        verifyEmbedding(embedding)
        verifySafetyAndReleaseGates(root)
        val chunking = root.objectValue("chunking")
        verifyChunking(chunking)
        val persona = root.objectValue("persona")
        verifyPersona(persona)
        verifyStorage(root.objectValue("storage"))
        val retrieval = root.objectValue("retrieval")
        verifyRetrieval(retrieval)
        return RagRuntimeContract(
            configVersion = root.string("config_version"),
            sourceConfigSha256 = root.string("source_config_sha256"),
            contractSha256 = contractChecksum,
            embedding = embedding.toEmbedding(),
            chunking = RagRuntimeContract.Chunking(
                policyVersion = chunking.string("policy_version"),
                targetTokens = chunking.int("target_tokens"),
                hardMaximumTokens = chunking.int("hard_max_tokens"),
                overlapTokens = chunking.int("overlap_tokens")
            ),
            retrieval = retrieval.toRetrieval(),
            persona = RagRuntimeContract.Persona(persona.int("focus_note_max_words"))
        )
    }

    private fun verifyFormats(formats: ContractJsonReader) {
        require(formats.stringList("supported") == supportedFormats) { "rag_supported_formats_mismatch" }
        require(formats.stringList("rejected") == listOf("doc")) { "rag_rejected_formats_mismatch" }
        require(!formats.boolean("partial_extraction_default_retrieval")) { "rag_partial_document_default_mismatch" }
    }

    private fun verifyEmbedding(embedding: ContractJsonReader) {
        require(embedding.string("model") == RagRuntimeContract.E5_MODEL) { "rag_embedding_model_mismatch" }
        require(embedding.string("revision") == RagRuntimeContract.E5_REVISION) { "rag_embedding_revision_mismatch" }
        require(embedding.int("dimension") == LocalEmbeddingModel.EMBEDDING_DIMENSIONS) { "rag_embedding_dimension_mismatch" }
        require(embedding.int("max_tokens") == LocalEmbeddingModel.MAX_TOKENS) { "rag_embedding_max_tokens_mismatch" }
        require(embedding.string("query_prefix") == "query: ") { "rag_embedding_query_prefix_mismatch" }
        require(embedding.string("passage_prefix") == "passage: ") { "rag_embedding_passage_prefix_mismatch" }
        require(embedding.string("pooling") == "attention_mask_mean") { "rag_embedding_pooling_mismatch" }
        require(embedding.string("normalization") == "l2") { "rag_embedding_normalization_mismatch" }
        require(embedding.boolean("require_model_and_tokenizer_checksum")) {
            "rag_embedding_checksum_requirement_missing"
        }
    }

    private fun verifySafetyAndReleaseGates(root: ContractJsonReader) {
        val safety = root.objectValue("safety")
        require(safety.boolean("buffer_generation_until_final_guard")) { "rag_final_guard_disabled" }
        require(safety.boolean("crisis_interception_before_retrieval")) { "rag_crisis_order_mismatch" }
        require(safety.boolean("exact_prompt_visible_citation_binding")) { "rag_citation_binding_mismatch" }
        require(safety.boolean("web_search_requires_explicit_consent")) { "rag_web_consent_mismatch" }
        val releaseGates = root.objectValue("release_gates")
        releaseGateNames.forEach { name ->
            require(!releaseGates.boolean(name)) { "rag_release_gate_must_be_blocked_$name" }
        }
    }

    private fun verifyChunking(chunking: ContractJsonReader) {
        require(chunking.string("policy_version") == TextChunker.POLICY_VERSION) { "rag_chunk_policy_mismatch" }
        require(chunking.int("target_tokens") == RagRuntimeContract.DEFAULT_CHUNK_TARGET_TOKENS) {
            "rag_chunk_target_mismatch"
        }
        require(chunking.int("hard_max_tokens") == RagRuntimeContract.DEFAULT_CHUNK_HARD_MAXIMUM_TOKENS) {
            "rag_chunk_max_mismatch"
        }
        require(chunking.int("overlap_tokens") == RagRuntimeContract.DEFAULT_CHUNK_OVERLAP_TOKENS) {
            "rag_chunk_overlap_mismatch"
        }
        require(!chunking.boolean("cross_pdf_page")) { "rag_chunk_pdf_boundary_mismatch" }
        require(!chunking.boolean("cross_docx_top_level_section")) { "rag_chunk_docx_boundary_mismatch" }
        require(!chunking.boolean("silent_embedding_truncation")) { "rag_chunk_truncation_mismatch" }
    }

    private fun verifyPersona(persona: ContractJsonReader) {
        require(persona.string("profile_type") == "structured") { "rag_persona_type_mismatch" }
        require(
            persona.stringList("allowed_fields") ==
                listOf("language", "tone", "response_length", "reflective_questions", "focus_note")
        ) { "rag_persona_fields_mismatch" }
        require(persona.boolean("reject_role_markers")) { "rag_persona_role_marker_guard_missing" }
        require(persona.boolean("reject_safety_bypass")) { "rag_persona_safety_guard_missing" }
        require(persona.boolean("reject_diagnosis_or_medication_instruction")) {
            "rag_persona_scope_guard_missing"
        }
        require(persona.boolean("reject_crisis_bypass")) { "rag_persona_crisis_guard_missing" }
    }

    private fun verifyStorage(storage: ContractJsonReader) {
        require(storage.string("source_encryption_required") == "aes_gcm_android_keystore") {
            "rag_source_encryption_contract_mismatch"
        }
        require(storage.string("knowledge_database_encryption_required") == "sqlcipher_keystore_wrapped_passphrase") {
            "rag_database_encryption_contract_mismatch"
        }
        require(storage.boolean("atomic_generation_required")) { "rag_atomic_generation_contract_missing" }
    }

    private fun verifyRetrieval(retrieval: ContractJsonReader) {
        require(retrieval.int("semantic_candidates") == RagRuntimeContract.DEFAULT_CANDIDATE_LIMIT) {
            "rag_semantic_candidate_mismatch"
        }
        require(retrieval.int("lexical_candidates") == RagRuntimeContract.DEFAULT_CANDIDATE_LIMIT) {
            "rag_lexical_candidate_mismatch"
        }
        require(retrieval.int("rrf_k") == RagRuntimeContract.DEFAULT_RRF_K) { "rag_rrf_mismatch" }
        require(retrieval.float("mmr_lambda") == RagRuntimeContract.DEFAULT_MMR_LAMBDA) { "rag_mmr_mismatch" }
        require(retrieval.int("max_evidence_chunks") == RagRuntimeContract.DEFAULT_MAX_EVIDENCE_CHUNKS) {
            "rag_evidence_limit_mismatch"
        }
        require(retrieval.int("max_chunks_per_document") == RagRuntimeContract.DEFAULT_MAX_CHUNKS_PER_DOCUMENT) {
            "rag_document_limit_mismatch"
        }
        require(retrieval.int("max_chunks_per_page") == RagRuntimeContract.DEFAULT_MAX_CHUNKS_PER_PAGE) {
            "rag_page_limit_mismatch"
        }
        require(retrieval.string("runtime_lexical_backend") == "sqlite_fts5_bm25") {
            "rag_lexical_backend_mismatch"
        }
        require(retrieval.string("release_required_lexical_backend") == "sqlite_fts5_bm25") {
            "rag_release_lexical_backend_mismatch"
        }
        require(retrieval.string("production_status") == "blocked_until_benchmark_and_physical_validation") {
            "rag_production_status_mismatch"
        }
        require(retrieval.string("threshold_policy") == "benchmark_calibrated") {
            "rag_threshold_policy_mismatch"
        }
        require(retrieval.string("uncalibrated_behavior") == "abstain") { "rag_uncalibrated_behavior_mismatch" }
        require(retrieval.string("in_memory_backend_scope") == "development_only") {
            "rag_in_memory_backend_scope_mismatch"
        }
    }

    private fun ContractJsonReader.toEmbedding() = RagRuntimeContract.Embedding(
        model = string("model"),
        revision = string("revision"),
        dimensions = int("dimension"),
        maximumTokens = int("max_tokens"),
        queryPrefix = string("query_prefix"),
        passagePrefix = string("passage_prefix"),
        pooling = string("pooling"),
        normalization = string("normalization")
    )

    private fun ContractJsonReader.toRetrieval() = RagRuntimeContract.Retrieval(
        semanticCandidates = int("semantic_candidates"),
        lexicalCandidates = int("lexical_candidates"),
        rrfK = int("rrf_k"),
        mmrLambda = float("mmr_lambda"),
        maxEvidenceChunks = int("max_evidence_chunks"),
        maxChunksPerDocument = int("max_chunks_per_document"),
        maxChunksPerPage = int("max_chunks_per_page"),
        lexicalBackend = string("runtime_lexical_backend"),
        productionStatus = string("production_status"),
        uncalibratedBehavior = string("uncalibrated_behavior")
    )
}

internal class ContractJsonReader(private val element: JsonObject) {
    fun objectValue(name: String): ContractJsonReader = ContractJsonReader(
        element.get(name)
            ?.takeIf(JsonElement::isJsonObject)
            ?.asJsonObject
            ?: error("rag_contract_missing_object_$name")
    )

    fun string(name: String): String = primitive(name, "string").asString
        .takeIf(String::isNotBlank)
        ?: error("rag_contract_missing_string_$name")

    fun int(name: String): Int = primitive(name, "int").asInt

    fun float(name: String): Float = primitive(name, "float").asFloat

    fun boolean(name: String): Boolean = primitive(name, "boolean").asBoolean

    fun stringList(name: String): List<String> = element.get(name)
        ?.takeIf(JsonElement::isJsonArray)
        ?.asJsonArray
        ?.map { value -> value.asString }
        ?: error("rag_contract_missing_list_$name")

    fun copyWithout(name: String): JsonObject = JsonObject().also { copy ->
        element.entrySet().filterNot { entry -> entry.key == name }.forEach { entry -> copy.add(entry.key, entry.value) }
    }

    private fun primitive(name: String, type: String) = element.get(name)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asJsonPrimitive
        ?: error("rag_contract_missing_${type}_$name")
}
