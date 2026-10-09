package com.stressdetector.mobile.ml.speech

import java.security.MessageDigest

/** Usable verified legacy bundles must not block an explicit current-bundle upgrade. */
internal fun isCurrentSpeechBundleComplete(readiness: SpeechBundleReadiness, descriptor: SpeechBundleDescriptor): Boolean {
    if (readiness !is SpeechBundleReadiness.Ready) return false
    val manifest = readiness.manifest
    return manifest.bundleVersion == descriptor.bundleVersion &&
        manifest.indonesianStt != null &&
        (descriptor.whisperStt == null || manifest.whisperStt != null) &&
        descriptor.languages.all { language ->
            manifest.ttsProfiles.values.any { it.language.equals(language, ignoreCase = true) }
        }
}

/** Cached Ready already passed integrity validation; upgrade progress takes precedence. */
internal fun shouldRevalidateIdleSpeechBundle(readiness: SpeechBundleReadiness, hasActiveWork: Boolean): Boolean =
    !hasActiveWork && readiness !is SpeechBundleReadiness.Ready

internal fun speechBundleComponentsForVersion(descriptor: SpeechBundleDescriptor, bundleVersion: String): List<SpeechBundleComponentSpec> =
    if (bundleVersion == SpeechBundleCatalog.LEGACY_BUNDLE_VERSION) {
        descriptor.components.filter { it.id in SpeechBundleCatalog.LEGACY_COMPONENT_IDS }.also { known ->
            require(
                known.map(SpeechBundleComponentSpec::id).toSet() == SpeechBundleCatalog.LEGACY_COMPONENT_IDS &&
                    known.size == SpeechBundleCatalog.LEGACY_COMPONENT_IDS.size
            ) { "The canonical catalog does not contain the exact legacy speech components." }
        }
    } else {
        require(bundleVersion == descriptor.bundleVersion) { "Speech bundle version does not match the canonical catalog." }
        descriptor.components
    }

/** Keep the original version + sorted component/download-SHA256 generation format. */
internal fun speechBundleGeneration(descriptor: SpeechBundleDescriptor, bundleVersion: String = descriptor.bundleVersion): String {
    val fingerprint = speechBundleComponentsForVersion(descriptor, bundleVersion)
        .sortedBy(SpeechBundleComponentSpec::id)
        .joinToString("\n") { component -> "${component.id}:${component.expectedDownloadSha256}" }
    val hash = MessageDigest.getInstance("SHA-256").digest(fingerprint.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return "$bundleVersion-${hash.take(GENERATION_HASH_LENGTH)}"
}

private const val GENERATION_HASH_LENGTH = 12
