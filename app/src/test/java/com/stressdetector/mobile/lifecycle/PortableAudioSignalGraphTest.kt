package com.stressdetector.mobile.lifecycle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PortableAudioSignalGraphTest {
    @Test
    fun allowsOnlyOwnedSignalChunksInFinalizedAndFailedWithEvidenceSessions() {
        val graph = graph("verbatim_sessions/Interview")
        val core = listOf("audio.wav.enc", "live_transcript.jsonl.enc", "divergence_events.jsonl.enc", "ai_summary.txt.enc")
            .map { "verbatim_sessions/Interview/$it" }.toSet()
        val trace = setOf(
            "verbatim_sessions/Interview/artifact_warnings.json.enc",
            "verbatim_sessions/Interview/audio_signal/manifest.json.enc",
            "verbatim_sessions/Interview/audio_signal/chunk_000000.json.enc",
            "verbatim/session/audio_signal/manifest.json.enc"
        )
        val unowned = "verbatim/other/audio_signal/manifest.json.enc"
        assertEquals(core + trace, graph.referencedArtifactPaths(core + trace + unowned))
        assertThrows(IllegalArgumentException::class.java) { graph.validateFiles((core + trace + unowned).map { "files/$it" }) }
        assertThrows(IllegalArgumentException::class.java) {
            graph.referencedArtifactPaths(core + "verbatim_sessions/Interview/audio_signal/unexpected.json.enc")
        }
    }

    @Test
    fun originalCaptureTraceIsIncludedWhenFinalizationFailedBeforeArchiveFolderExists() {
        val graph = graph("")
        val path = "verbatim/session/audio_signal/chunk_000000.json.enc"
        assertEquals(setOf(path), graph.referencedArtifactPaths(setOf(path)))
    }

    private fun graph(folder: String): PortableArchiveGraph {
        val columns = listOf("sessionId", "status", "finalizationStatus", "artifactFolderPath", "audioFilePath")
            .mapIndexed { index, name -> ArchiveColumn(name, "TEXT", true, null, if (index == 0) 1 else 0) }
        return PortableArchiveGraph(mapOf("verbatim_sessions" to columns)).also {
            it.record("verbatim_sessions", listOf("session", "COMPLETED", "FAILED_WITH_EVIDENCE", folder, ""))
        }
    }
}
