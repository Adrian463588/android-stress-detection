package com.stressdetector.mobile.ui.components

internal sealed interface CounselorTextBlock {
    data class Paragraph(val text: String) : CounselorTextBlock

    data class Heading(val text: String) : CounselorTextBlock

    data class ListItem(val marker: String, val text: String) : CounselorTextBlock
}

internal enum class CounselorInlineStyle {
    Plain,
    Strong,
    Emphasis,
    Code,
    Citation
}

internal data class CounselorInlineToken(val text: String, val style: CounselorInlineStyle)

private data class InlineMatch(val consumed: Int, val token: CounselorInlineToken)

private val HeadingPattern = Regex("""^(#{1,3})\s+(.+)$""")
private val OrderedListPattern = Regex("""^(\d{1,3}[.)])(?:\s+|(?=(?:\*\*|__)?\p{L}))(.+)$""")
private val UnorderedListPattern = Regex("""^([-*+•])\s+(.+)$""")
private val CitationPattern = Regex("""\[((?:S|W)\d+)\|([^\]\s|]+)]""")
private val LinkPattern = Regex("""\[([^\]\n]+)]\(https?://[^)\s]+\)""")
private val HorizontalWhitespacePattern = Regex("[\\t ]+")

/**
 * Turns the small Markdown subset emitted by the counselor into readable blocks.
 * The stored model output is intentionally left untouched; this is presentation-only.
 */
internal fun parseCounselorMarkdown(raw: String): List<CounselorTextBlock> {
    val lines = normalizeCounselorLayout(raw)
        .split('\n')
        .map { it.replace(HorizontalWhitespacePattern, " ").trim() }

    val blocks = mutableListOf<CounselorTextBlock>()
    val paragraphLines = mutableListOf<String>()
    var pendingListItem: CounselorTextBlock.ListItem? = null

    fun flushParagraph() {
        if (paragraphLines.isNotEmpty()) {
            blocks += CounselorTextBlock.Paragraph(paragraphLines.joinToString(" "))
            paragraphLines.clear()
        }
    }

    fun flushListItem() {
        pendingListItem?.let(blocks::add)
        pendingListItem = null
    }

    lines.forEach { line ->
        if (line.isBlank()) {
            flushParagraph()
            flushListItem()
            return@forEach
        }

        val heading = HeadingPattern.matchEntire(line)
        if (heading != null) {
            flushParagraph()
            flushListItem()
            blocks += CounselorTextBlock.Heading(heading.groupValues[2])
            return@forEach
        }

        val listItem = parseListItem(line)
        if (listItem != null) {
            flushParagraph()
            flushListItem()
            pendingListItem = CounselorTextBlock.ListItem(listItem.first, listItem.second)
            return@forEach
        }

        pendingListItem = pendingListItem?.let { item ->
            item.copy(text = "${item.text} $line")
        }
        if (pendingListItem == null) paragraphLines += line
    }

    flushParagraph()
    flushListItem()
    return blocks
}

/**
 * Tokenizes only inline formatting that can be rendered safely without adding a Markdown
 * dependency. Citation identity remains in the separate citation UI; only its compact marker
 * is shown in the answer body.
 */
internal fun parseCounselorInlineText(text: String): List<CounselorInlineToken> {
    if (text.isEmpty()) return emptyList()

    val tokens = mutableListOf<CounselorInlineToken>()
    val plain = StringBuilder()
    var index = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            appendToken(tokens, CounselorInlineToken(plain.toString(), CounselorInlineStyle.Plain))
            plain.clear()
        }
    }

    while (index < text.length) {
        val match = parseInlineMatch(text, index)
        if (match == null) {
            plain.append(text[index])
            index++
        } else {
            flushPlain()
            appendToken(tokens, match.token)
            index += match.consumed
        }
    }
    flushPlain()
    return tokens
}

private fun normalizeCounselorLayout(raw: String): String {
    var normalized = raw.replace("\r\n", "\n").replace('\r', '\n')
    normalized = normalized.replace(Regex("""(?<=[.!?])(?=\p{L})"""), " ")
    normalized = normalized.replace(
        Regex("""(?<=[.!?:])[\t ]*(?=\d{1,3}[.)][\t ]*(?:\*\*|__)?\p{L})"""),
        "\n"
    )
    normalized = normalized.replace(
        Regex("""(${CitationPattern.pattern})[\t ]*(?=\d{1,3}[.)][\t ]*(?:\*\*|__)?\p{L})"""),
        "$1\n"
    )
    normalized = normalized.replace(
        Regex("""(?<=[\p{L}])(?=\d+(?:[-–]\d+)?[\t ]*(?:menit|jam|detik|hari|minggu|bulan|tahun|%))"""),
        " "
    )
    normalized = normalized.replace(
        Regex("""(?<=\d)(?=(?:menit|jam|detik|hari|minggu|bulan|tahun|%))"""),
        " "
    )
    return normalized
}

private fun parseListItem(line: String): Pair<String, String>? = OrderedListPattern.matchEntire(line)?.let { match ->
    match.groupValues[1] to match.groupValues[2]
} ?: UnorderedListPattern.matchEntire(line)?.let { match ->
    "•" to match.groupValues[2]
}

private fun parseInlineMatch(text: String, index: Int): InlineMatch? = parseBracketMatch(text, index)
    ?: parseCodeMatch(text, index)
    ?: when {
        text.startsWith("**", index) -> delimitedMatch(text, index, "**", CounselorInlineStyle.Strong)
        text.startsWith("__", index) -> delimitedMatch(text, index, "__", CounselorInlineStyle.Strong)
        text[index] == '*' -> delimitedMatch(text, index, "*", CounselorInlineStyle.Emphasis)
        text[index] == '_' && !isWordCharacter(text.getOrNull(index - 1)) ->
            delimitedMatch(text, index, "_", CounselorInlineStyle.Emphasis)
        else -> null
    }

private fun parseBracketMatch(text: String, index: Int): InlineMatch? {
    if (text[index] != '[') return null
    return CitationPattern.find(text, index)?.takeIf { it.range.first == index }?.let { match ->
        InlineMatch(
            consumed = match.value.length,
            token = CounselorInlineToken(
                text = "[${match.groupValues[1]}]",
                style = CounselorInlineStyle.Citation
            )
        )
    } ?: LinkPattern.find(text, index)?.takeIf { it.range.first == index }?.let { match ->
        InlineMatch(
            consumed = match.value.length,
            token = CounselorInlineToken(
                text = match.groupValues[1],
                style = CounselorInlineStyle.Plain
            )
        )
    }
}

private fun parseCodeMatch(text: String, index: Int): InlineMatch? {
    if (text[index] != '`') return null
    val end = text.indexOf('`', index + 1)
    return if (end > index + 1 && !text.substring(index + 1, end).contains('\n')) {
        InlineMatch(
            consumed = end - index + 1,
            token = CounselorInlineToken(
                text = text.substring(index + 1, end),
                style = CounselorInlineStyle.Code
            )
        )
    } else {
        null
    }
}

private fun delimitedMatch(text: String, index: Int, delimiter: String, style: CounselorInlineStyle): InlineMatch? {
    val contentStart = index + delimiter.length
    val end = text.indexOf(delimiter, contentStart)
    val isValid = end > contentStart &&
        !(delimiter == "_" && isWordCharacter(text.getOrNull(end + delimiter.length)))
    return if (isValid) {
        InlineMatch(
            consumed = end + delimiter.length - index,
            token = CounselorInlineToken(
                text = text.substring(contentStart, end),
                style = style
            )
        )
    } else {
        null
    }
}

private fun isWordCharacter(character: Char?): Boolean = character?.let { it.isLetterOrDigit() || it == '_' } == true

private fun appendToken(tokens: MutableList<CounselorInlineToken>, token: CounselorInlineToken) {
    if (token.text.isEmpty()) return
    val previous = tokens.lastOrNull()
    if (previous?.style == token.style) {
        tokens[tokens.lastIndex] = previous.copy(text = previous.text + token.text)
    } else {
        tokens += token
    }
}
