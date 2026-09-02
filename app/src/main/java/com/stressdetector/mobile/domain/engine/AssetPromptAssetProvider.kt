package com.stressdetector.mobile.domain.engine

import android.content.Context
import com.google.gson.Gson

/** Loads versioned counselor prompt assets from the application package. */
class AssetPromptAssetProvider(context: Context, private val assetPath: String = "ai/prompts/counselor_v1.json") : PromptAssetProvider {
    private val applicationContext = context.applicationContext
    private val gson = Gson()

    override fun getCounselorPrompt(): PromptAsset = applicationContext.assets.open(assetPath).bufferedReader().use { reader ->
        gson.fromJson(reader, PromptAsset::class.java)
            ?: error("Prompt asset is empty: $assetPath")
    }
}
