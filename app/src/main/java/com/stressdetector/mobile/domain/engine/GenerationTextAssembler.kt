package com.stressdetector.mobile.domain.engine

/**
 * Pure response buffer for streamed LLM output.
 *
 * The limit is applied to the complete response, never independently to each
 * token. This prevents the final token from replacing the visible answer.
 */
class GenerationTextAssembler(private val maxChars: Int = DEFAULT_MAX_OUTPUT_CHARS) {
    private val text = StringBuilder()
    private var exceededLimit = false

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

    internal fun finish(): AssembledAnswer {
        val answer = currentText().trim()
        val lastSentence = sentenceEnd.findAll(answer).lastOrNull()?.range?.last?.plus(1)
        val tail = answer.substring(lastSentence ?: 0).trim()
        val openBold = boldMarker.findAll(tail).count() % 2 != 0
        val danglingProse = lastSentence != null && tail.length >= MIN_UNFINISHED_TAIL_CHARS && '\n' !in tail
        val unfinished = exceededLimit || openBold || tail.endsWith(':') || danglingProse
        val completeText = if (unfinished) lastSentence?.let(answer::take).orEmpty().trimEnd() else answer
        return AssembledAnswer(completeText, incomplete = unfinished)
    }

    /** Preserves all generated text for graceful display and continuation rather than dropping trailing content. */
    internal fun finishPreservingPartial(interrupted: Boolean = false): PreservedAnswer {
        val answer = sanitizeTrailingMarkdown(currentText().trim())
        val lastSentence = sentenceEnd.findAll(answer).lastOrNull()?.range?.last?.plus(1)
        val tail = answer.substring(lastSentence ?: 0).trim()
        val openBold = boldMarker.findAll(tail).count() % 2 != 0
        val danglingProse = lastSentence != null && tail.length >= MIN_UNFINISHED_TAIL_CHARS && '\n' !in tail
        val noSentenceAtAll = answer.isNotBlank() && lastSentence == null && answer.length >= MIN_UNFINISHED_TAIL_CHARS
        val reason = when {
            interrupted && answer.isNotBlank() -> CompletionReason.INTERRUPTED
            exceededLimit -> CompletionReason.OUTPUT_LIMIT_REACHED
            openBold || tail.endsWith(':') || danglingProse || noSentenceAtAll -> CompletionReason.UNFINISHED_SENTENCE
            else -> CompletionReason.COMPLETE
        }
        return PreservedAnswer(text = answer, reason = reason, incomplete = reason != CompletionReason.COMPLETE)
    }

    private fun sanitizeTrailingMarkdown(raw: String): String {
        var result = raw.trimEnd()
        if (boldMarker.findAll(result).count() % 2 != 0 && result.endsWith("**")) {
            result = result.removeSuffix("**").trimEnd()
        }
        return result
    }

    private fun appendDelta(delta: String) {
        val remaining = maxChars - text.length
        exceededLimit = exceededLimit || delta.length > remaining
        text.append(delta.take(remaining))
    }

    private fun replaceSnapshot(snapshot: String) {
        text.setLength(0)
        exceededLimit = snapshot.length > maxChars
        text.append(snapshot.take(maxChars))
    }

    companion object {
        const val DEFAULT_MAX_OUTPUT_CHARS = 2_400
        private const val MIN_UNFINISHED_TAIL_CHARS = 18
        private val boldMarker = Regex("""\*\*""")
        private val sentenceEnd = Regex("""(?<!\.)[.!?]["”’')*]*(?:[ \t]*\[[SW]\d+\|[^\]\r\n]+])*+(?=\s|$)""")

        fun mergeContinuation(existing: String, continuation: String): String {
            val base = existing.trimEnd()
            val next = continuation.trimStart()
            if (base.isBlank()) return next
            if (next.isBlank()) return base
            val maxOverlap = minOf(base.length, next.length, 120)
            for (len in maxOverlap downTo 8) {
                if (base.endsWith(next.take(len), ignoreCase = true)) {
                    val remainder = next.drop(len).trimStart()
                    return if (remainder.isBlank()) base else "$base $remainder"
                }
            }
            val noSpaceBefore = listOf('.', ',', '!', '?', ';', ':')
            val needsSpace =
                !base.last().isWhitespace() &&
                    !next.first().isWhitespace() &&
                    next.first() !in noSpaceBefore
            return if (needsSpace) "$base $next" else "$base\n\n$next"
        }
    }
}

internal data class AssembledAnswer(val text: String, val incomplete: Boolean)
internal data class PreservedAnswer(val text: String, val reason: CompletionReason, val incomplete: Boolean)
