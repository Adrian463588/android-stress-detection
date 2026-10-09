@file:Suppress("FunctionNaming")

package com.stressdetector.mobile.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.stressdetector.mobile.ui.LocalAppMotion

private const val DISCLOSURE_DURATION_MILLIS = 180

/** A single accessible disclosure primitive; expansion has no inference or I/O side effect. */
@Composable
internal fun VerbatimDisclosure(
    label: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val duration = LocalAppMotion.current.duration(DISCLOSURE_DURATION_MILLIS)
    Column(modifier) {
        TextButton(
            onClick = { onExpandedChange(!expanded) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics {
                stateDescription = if (expanded) "Terbuka" else "Tertutup"
            }
        ) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(duration)),
            exit = shrinkVertically(tween(duration))
        ) { Column(Modifier.fillMaxWidth()) { content() } }
    }
}
