package com.stressdetector.mobile.ml.assets

enum class AiAssetKind { LLM, EMBEDDING, CLINICAL_DOCUMENT }

data class AiAssetEntry(
    val kind: AiAssetKind,
    val fileName: String,
    val source: String,
    val license: String,
    val version: String,
    val sizeBytes: Long,
    val sha256: String
)
