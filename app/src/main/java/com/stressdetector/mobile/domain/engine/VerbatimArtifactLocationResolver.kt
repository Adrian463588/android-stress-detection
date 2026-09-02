package com.stressdetector.mobile.domain.engine

/** Resolves the canonical encrypted audio artifact for a persisted session. */
interface VerbatimArtifactLocationResolver {
    fun resolveAudio(session: VerbatimSessionSummary): String
}

/** Finalized artifact folder is authoritative; staging paths are legacy fallback only. */
class CanonicalVerbatimArtifactLocationResolver : VerbatimArtifactLocationResolver {
    override fun resolveAudio(session: VerbatimSessionSummary): String {
        val folder = session.artifactFolderPath.trim()
        if (folder.startsWith("verbatim_sessions/") &&
            !folder.contains("..") &&
            !folder.endsWith("/") &&
            folder.substringAfter("verbatim_sessions/").isNotBlank()
        ) {
            return "$folder/audio.wav.enc"
        }

        return session.audioFilePath.trim().takeIf { it.isNotBlank() }
            ?: error("Sesi ini tidak memiliki artifact audio.")
    }
}
