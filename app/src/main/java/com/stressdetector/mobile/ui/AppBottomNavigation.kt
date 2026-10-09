@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Keep the user's font size; move labels out of crowded navigation slots. */
@Composable
internal fun AppBottomNavigation(selectedDestination: Screen?, onSelect: (Screen) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val labelWidth = with(density) {
            (maxWidth / APP_DESTINATIONS.size - AppSpacing.Small * 2).coerceAtLeast(0.dp).toPx()
        }
        val labelsFit = APP_DESTINATIONS.all { destination ->
            measurer.measure(AnnotatedString(destination.label), labelStyle, softWrap = false).size.width <= labelWidth
        }
        Column {
            if (!labelsFit && selectedDestination != null) {
                TextNavigationDestination(selectedDestination)
            }
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                APP_DESTINATIONS.forEach { destination ->
                    val active = selectedDestination == destination
                    NavigationBarItem(
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                            contentDescription = destination.label
                            stateDescription = if (active) "Tab aktif" else "Tab tidak aktif"
                        },
                        selected = active,
                        onClick = { onSelect(destination) },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = if (labelsFit) {
                            { androidx.compose.material3.Text(destination.label, style = labelStyle, maxLines = 1) }
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TextNavigationDestination(destination: Screen) {
    androidx.compose.material3.Text(
        destination.label,
        modifier = Modifier.fillMaxWidth().padding(horizontal = AppSpacing.Screen, vertical = AppSpacing.Micro),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center
    )
}
