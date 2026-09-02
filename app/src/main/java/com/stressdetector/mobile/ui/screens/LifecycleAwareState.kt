package com.stressdetector.mobile.ui.screens

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.StateFlow

/** Lifecycle-aware equivalent used when lifecycle-runtime-compose is unavailable offline. */
@Composable
@SuppressLint("StateFlowValueCalledInComposition")
fun <T> StateFlow<T>.collectAsStateWithLifecycleCompat(): State<T> {
    val lifecycleOwner = LocalContext.current as? LifecycleOwner
    return produceState(initialValue = value, this, lifecycleOwner) {
        if (lifecycleOwner == null) {
            collect { value = it }
        } else {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                collect { value = it }
            }
        }
    }
}
