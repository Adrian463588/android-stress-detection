package com.stressdetector.mobile.ml

import java.io.File

/**
 * JVM-only test helper: loads feature name list from a JSON file on disk,
 * bypassing the Android [Context] / [android.content.res.AssetManager] dependency.
 *
 * Mirrors the parsing logic in [FeatureSchema.load] exactly so tests exercise
 * the same name extraction code path.
 */
object FeatureSchemaTestHelper {

    /**
     * Returns the ordered list of selected feature names from [jsonFile].
     *
     * @throws IllegalArgumentException if no feature names are found.
     */
    fun loadNames(jsonFile: File): List<String> {
        val raw = jsonFile.readText()
        val featureBlock = Regex("\"selected_features\"\\s*:\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL)
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?: raw
        val names = Regex("\"([^\"]+)\"").findAll(featureBlock)
            .map { it.groupValues[1] }
            .filterNot { it == "selected_features" || it == "sensor_channel" }
            .toList()
        require(names.isNotEmpty()) { "No selected features found in ${jsonFile.name}" }
        return names
    }
}
