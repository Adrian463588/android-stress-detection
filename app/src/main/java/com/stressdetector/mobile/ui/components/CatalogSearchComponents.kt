package com.stressdetector.mobile.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.domain.search.SearchHistoryEntry
import com.stressdetector.mobile.domain.search.SearchMatchType
import com.stressdetector.mobile.domain.search.SearchSuggestion
import com.stressdetector.mobile.ui.AppSpacing
import com.stressdetector.mobile.ui.LocalAppMotion

@Composable
@Suppress("LongParameterList", "LongMethod")
internal fun CatalogSearchSection(
    query: String,
    placeholder: String,
    categoryDisplayName: String,
    isFocused: Boolean,
    recentSearches: List<SearchHistoryEntry>,
    suggestions: List<SearchSuggestion>,
    showSuggestions: Boolean,
    showClearHistoryConfirmation: Boolean,
    onQueryChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onCommitSearch: () -> Unit,
    onClearQuery: () -> Unit,
    onSelectSuggestion: (SearchSuggestion) -> Unit,
    onSelectHistoryChip: (SearchHistoryEntry) -> Unit,
    onRemoveHistoryChip: (SearchHistoryEntry) -> Unit,
    onRequestClearHistory: () -> Unit,
    onDismissClearHistory: () -> Unit,
    onConfirmClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val motion = LocalAppMotion.current
    val density = LocalDensity.current
    val isImeVisible = WindowInsets.ime.getBottom(density) > 0
    var wasImeVisible by remember { mutableStateOf(false) }

    LaunchedEffect(isImeVisible) {
        if (wasImeVisible && !isImeVisible && isFocused) {
            focusManager.clearFocus()
            onFocusChange(false)
        }
        wasImeVisible = isImeVisible
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = AppSpacing.Micro),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Compact)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .onFocusChanged { onFocusChange(it.isFocused) },
            placeholder = {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(
                        onClick = onClearQuery,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Hapus pencarian",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Search,
                keyboardType = KeyboardType.Text
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onCommitSearch()
                }
            ),
            textStyle = MaterialTheme.typography.bodyMedium
        )

        val showHistoryPanel = query.isBlank() && isFocused
        AnimatedVisibility(
            visible = showHistoryPanel,
            enter = fadeIn(tween(motion.duration(160))) + expandVertically(tween(motion.duration(160))),
            exit = fadeOut(tween(motion.duration(120))) + shrinkVertically(tween(motion.duration(120)))
        ) {
            SearchHistoryChips(
                recentSearches = recentSearches,
                categoryDisplayName = categoryDisplayName,
                onSelectChip = { entry ->
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onSelectHistoryChip(entry)
                },
                onRemoveChip = onRemoveHistoryChip,
                onRequestClearAll = onRequestClearHistory
            )
        }

        AnimatedVisibility(
            visible = showSuggestions && suggestions.isNotEmpty() && query.isNotBlank(),
            enter = fadeIn(tween(motion.duration(160))) + expandVertically(tween(motion.duration(160))),
            exit = fadeOut(tween(motion.duration(120))) + shrinkVertically(tween(motion.duration(120)))
        ) {
            SearchSuggestionDropdown(
                suggestions = suggestions,
                onSelectSuggestion = { suggestion ->
                    focusManager.clearFocus()
                    keyboardController?.hide()
                    onSelectSuggestion(suggestion)
                }
            )
        }
    }

    if (showClearHistoryConfirmation) {
        ClearSearchHistoryDialog(
            categoryDisplayName = categoryDisplayName,
            onDismiss = onDismissClearHistory,
            onConfirm = onConfirmClearHistory
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun SearchHistoryChips(
    recentSearches: List<SearchHistoryEntry>,
    categoryDisplayName: String,
    onSelectChip: (SearchHistoryEntry) -> Unit,
    onRemoveChip: (SearchHistoryEntry) -> Unit,
    onRequestClearAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                Icon(
                    imageVector = Icons.Default.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Pencarian Terakhir",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.semantics { heading() }
                )
            }
            if (recentSearches.isNotEmpty()) {
                TextButton(
                    onClick = onRequestClearAll,
                    contentPadding = PaddingValues(horizontal = AppSpacing.Compact, vertical = 0.dp),
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    Text(
                        text = "Hapus Riwayat",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (recentSearches.isEmpty()) {
            Text(
                text = "Belum ada riwayat pencarian untuk $categoryDisplayName.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = AppSpacing.Micro)
            )
        } else {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.Compact),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.Micro)
            ) {
                recentSearches.take(MAX_VISIBLE_HISTORY_CHIPS).forEach { entry ->
                    InputChip(
                        selected = false,
                        onClick = { onSelectChip(entry) },
                        label = {
                            Text(
                                text = entry.canonicalTitle,
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        trailingIcon = {
                            IconButton(
                                onClick = { onRemoveChip(entry) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Hapus riwayat ${entry.canonicalTitle}",
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        colors = InputChipDefaults.inputChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            labelColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier.heightIn(min = 48.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun SearchSuggestionDropdown(
    suggestions: List<SearchSuggestion>,
    onSelectSuggestion: (SearchSuggestion) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 264.dp)
        ) {
            items(suggestions, key = { it.canonicalItemId }) { suggestion ->
                val badge = suggestionMatchLabel(suggestion)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelectSuggestion(suggestion) }
                        .semantics(mergeDescendants = true) {
                            contentDescription =
                                "Saran ${suggestion.canonicalTitle}, ${suggestion.categoryLabel}. Ketuk untuk mengisi."
                        }
                        .padding(horizontal = AppSpacing.Medium, vertical = AppSpacing.Compact),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.Small)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = suggestion.canonicalTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.NorthWest,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (suggestion != suggestions.lastOrNull()) {
                    Divider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                }
            }
        }
    }
}

@Composable
internal fun ClearSearchHistoryDialog(categoryDisplayName: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hapus Riwayat Pencarian?") },
        text = {
            Text(
                "Semua riwayat pencarian pada $categoryDisplayName akan dihapus. " +
                    "Data asesmen dan materi konseling tetap aman."
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Hapus Riwayat", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("Batal")
            }
        }
    )
}

private fun suggestionMatchLabel(suggestion: SearchSuggestion): String = when (suggestion.matchType) {
    SearchMatchType.FUZZY -> "${suggestion.categoryLabel} · Kemungkinan maksud Anda"
    else -> suggestion.categoryLabel
}

private const val MAX_VISIBLE_HISTORY_CHIPS = 5
