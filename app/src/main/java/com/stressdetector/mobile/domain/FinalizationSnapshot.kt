package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.PpgType
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

data class FinalizationSnapshotInput(
    val sessionId: String,
    val streamState: PpgStreamState,
    val windows: FinalizationSnapshotWindows,
    val capturedAtMonotonicMillis: Long
)

data class FinalizationSnapshotWindows(val green: WindowSnapshot, val ir: WindowSnapshot, val red: WindowSnapshot)

/**
 * Immutable phone-side evidence captured after Watch drain. It contains only
 * samples that were accepted by the phone repository; it never creates data.
 */
class FinalizationSnapshot private constructor(
    val sessionId: String,
    val streamState: PpgStreamState,
    private val windows: FrozenWindows,
    val capturedAtMonotonicMillis: Long,
    val sourceFingerprint: String
) {
    /** Returns a defensive copy so late repository writes cannot mutate the frozen input. */
    val greenWindow: WindowSnapshot get() = windows.green.copyForSnapshot()
    val irWindow: WindowSnapshot get() = windows.ir.copyForSnapshot()
    val redWindow: WindowSnapshot get() = windows.red.copyForSnapshot()

    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(windows.green.type == PpgType.GREEN) { "greenWindow must be Green" }
        require(windows.ir.type == PpgType.IR) { "irWindow must be IR" }
        require(windows.red.type == PpgType.RED) { "redWindow must be Red" }
    }

    companion object {
        fun from(input: FinalizationSnapshotInput): FinalizationSnapshot {
            val frozenWindows = FrozenWindows(
                green = input.windows.green.copyForSnapshot(),
                ir = input.windows.ir.copyForSnapshot(),
                red = input.windows.red.copyForSnapshot()
            )
            val fingerprint = sha256(
                buildString {
                    append(input.sessionId)
                    append('|').append(input.streamState.totalGreenSamples)
                    append('|').append(input.streamState.totalIrSamples)
                    append('|').append(input.streamState.totalRedSamples)
                    append('|')
                    appendWindow(frozenWindows.green)
                    append('|')
                    appendWindow(frozenWindows.ir)
                    append('|')
                    appendWindow(frozenWindows.red)
                }
            )
            return FinalizationSnapshot(
                sessionId = input.sessionId,
                streamState = input.streamState,
                windows = frozenWindows,
                capturedAtMonotonicMillis = input.capturedAtMonotonicMillis,
                sourceFingerprint = fingerprint
            )
        }

        private fun StringBuilder.appendWindow(window: WindowSnapshot) {
            append(window.type.name)
            append(':').append(window.size)
            append(':').append(window.sessionElapsedStartMillis)
            append(':').append(window.sessionElapsedEndMillis)
            window.timestampsMillis.forEach { append(':').append(it) }
            window.rawValues.forEach { append(':').append(it) }
            window.timestampBatchIds.forEach { append(':').append(it) }
        }

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private data class FrozenWindows(val green: WindowSnapshot, val ir: WindowSnapshot, val red: WindowSnapshot)
}

private fun WindowSnapshot.copyForSnapshot(): WindowSnapshot = copy(
    timestampsMillis = timestampsMillis.copyOf(),
    rawValues = rawValues.copyOf(),
    elapsedTimestampsMillis = elapsedTimestampsMillis.copyOf(),
    timestampBatchIds = timestampBatchIds.copyOf()
)
