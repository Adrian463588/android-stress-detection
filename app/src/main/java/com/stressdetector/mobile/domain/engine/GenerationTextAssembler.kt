package com.stressdetector.mobile.domain.engine

/**
 * Pure response buffer for streamed LLM output.
 *
 * The limit is applied to the complete response, never independently to each
 * token. This prevents the final token from replacing the visible answer.
 */
class GenerationTextAssembler(private val maxChars: Int = DEFAULT_MAX_OUTPUT_CHARS) {
    private val text = StringBuilder()

    init {
        require(maxChars > 0) { "maxChars must be positive" }
    }

    fun append(content: LlmGenerationState.Content): String {
        when (content.mode) {
            GenerationContentMode.DELTA -> appendDelta(content.text)
            GenerationContentMode.SNAPSHOT -> replaceSnapshot(content.text)
        }
        return text.toString()
    }

    fun currentText(): String = text.toString()

    private fun appendDelta(delta: String) {
        if (delta.isEmpty() || text.length >= maxChars) return
        val remaining = maxChars - text.length
        text.append(delta.take(remaining))
    }

    private fun replaceSnapshot(snapshot: String) {
        text.setLength(0)
        text.append(snapshot.take(maxChars))
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_CHARS = 1_400
    }
}
