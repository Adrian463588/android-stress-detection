package com.stressdetector.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.stressdetector.mobile.ui.AppSpacing

@Composable
@Suppress("FunctionNaming")
internal fun CounselorMessageText(text: String, modifier: Modifier = Modifier) {
    val blocks = remember(text) { parseCounselorMarkdown(text) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
    ) {
        blocks.forEach { block ->
            when (block) {
                is CounselorTextBlock.Heading -> CounselorInlineText(
                    text = block.text,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        lineBreak = LineBreak.Heading
                    )
                )

                is CounselorTextBlock.Paragraph -> CounselorInlineText(block.text)

                is CounselorTextBlock.ListItem -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
                ) {
                    Text(
                        text = block.marker,
                        modifier = Modifier.widthIn(min = 24.dp),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 1.45.em
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                    CounselorInlineText(
                        text = block.text,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
@Suppress("FunctionNaming")
private fun CounselorInlineText(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge.copy(
        lineHeight = 1.45.em,
        lineBreak = LineBreak.Paragraph
    )
) {
    val tokens = remember(text) { parseCounselorInlineText(text) }
    val annotatedText = buildCounselorAnnotatedString(tokens)
    Text(
        text = annotatedText,
        modifier = modifier,
        style = style,
        color = MaterialTheme.colorScheme.onSurface
    )
}

@Composable
private fun buildCounselorAnnotatedString(tokens: List<CounselorInlineToken>): AnnotatedString {
    val citationColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    return buildAnnotatedString {
        tokens.forEach { token ->
            when (token.style) {
                CounselorInlineStyle.Plain -> append(token.text)
                CounselorInlineStyle.Strong -> withStyle(
                    SpanStyle(fontWeight = FontWeight.SemiBold)
                ) {
                    append(token.text)
                }

                CounselorInlineStyle.Emphasis -> withStyle(
                    SpanStyle(fontStyle = FontStyle.Italic)
                ) {
                    append(token.text)
                }

                CounselorInlineStyle.Code -> withStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        background = codeBackground
                    )
                ) {
                    append(token.text)
                }

                CounselorInlineStyle.Citation -> withStyle(
                    SpanStyle(
                        color = citationColor,
                        fontWeight = FontWeight.SemiBold
                    )
                ) {
                    append(token.text)
                }
            }
        }
    }
}
