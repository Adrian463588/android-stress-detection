package com.stressdetector.mobile.ml.llm

import android.content.Context
import java.io.File

/** Shared runtime settings for Gemma preflight and production inference. */
internal object GemmaRuntimeContract {
    const val RUNTIME_VERSION = "litertlm-android-0.13.1"
    const val MAX_NUM_TOKENS = 2_048
    const val RESPONSE_RESERVE_TOKENS = 384
    const val PROMPT_OVERHEAD_TOKENS = 64

    fun cacheDirectory(context: Context): File = File(
        context.applicationContext.cacheDir,
        "litertlm/gemma-4-e2b/$RUNTIME_VERSION"
    )
}
