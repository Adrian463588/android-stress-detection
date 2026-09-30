package com.stressdetector.mobile.domain

import com.stressdetector.mobile.data.PpgStreamState
import com.stressdetector.mobile.data.WindowSnapshot
import com.stressdetector.shared.PpgType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotSame
import org.junit.Test

class FinalizationSnapshotTest {
    @Test
    fun frozenWindowsDoNotExposeMutableRepositoryArrays() {
        val green = window(PpgType.GREEN, 1)
        val snapshot = FinalizationSnapshot.from(
            FinalizationSnapshotInput(
                sessionId = "session-snapshot",
                streamState = PpgStreamState(sessionId = "session-snapshot"),
                windows = FinalizationSnapshotWindows(
                    green = green,
                    ir = window(PpgType.IR, 2),
                    red = window(PpgType.RED, 3)
                ),
                capturedAtMonotonicMillis = 10L
            )
        )

        green.rawValues[0] = 99
        val firstRead = snapshot.greenWindow
        firstRead.rawValues[0] = 77
        val secondRead = snapshot.greenWindow

        assertNotSame(firstRead, secondRead)
        assertArrayEquals(intArrayOf(1, 2), secondRead.rawValues)
    }

    private fun window(type: PpgType, offset: Int): WindowSnapshot = WindowSnapshot(
        type = type,
        timestampsMillis = longArrayOf(0L, 40L),
        rawValues = intArrayOf(offset, offset + 1),
        timestampBatchIds = arrayOf("batch-$offset", "batch-$offset")
    )
}
