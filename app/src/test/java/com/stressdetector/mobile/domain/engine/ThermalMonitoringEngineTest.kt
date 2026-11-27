@file:Suppress("MagicNumber", "LongParameterList")

package com.stressdetector.mobile.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThermalMonitoringEngineTest {

    private fun obs(
        seq: Long,
        elapsedMs: Long,
        skinC: Float?,
        ambientC: Float? = 25.0f,
        status: Int = 0,
        isValid: Boolean = skinC != null && status == 0,
        wearerRole: String = "CLIENT"
    ) = SkinTemperatureObservation(
        sampleId = "s1:temp:1:$seq",
        sessionId = "s1",
        generation = 1L,
        sequenceNumber = seq,
        sensorTimestampMs = 1_760_000_000_000L + elapsedMs,
        receivedTimestampMs = 1_760_000_000_050L + elapsedMs,
        elapsedMillis = elapsedMs,
        clockUncertaintyMs = 250L,
        skinTemperatureC = skinC,
        ambientTemperatureC = ambientC,
        measurementStatus = status,
        isValid = isValid,
        wearerRole = wearerRole,
        source = "WATCH_SDK_SKIN_TEMP"
    )

    @Test
    fun observesWithoutAutoMarkersUntilValidatedPolicyEnabled() {
        val engine = ThermalMonitoringEngine(ThermalPolicyConfig.CANDIDATE_OBSERVE_ONLY)
        engine.beginSession("s1", "CLIENT", watchEnabled = true)
        listOf(0L, 45_000L, 90_000L, 135_000L, 180_000L).forEachIndexed { idx, t ->
            assertTrue(engine.ingest(obs(idx + 1L, t, 33.4f)).isEmpty())
        }
        assertTrue(engine.ingest(obs(6L, 210_000L, 32.6f)).isEmpty())
        assertTrue(engine.ingest(obs(7L, 250_000L, 32.5f)).isEmpty())
        assertTrue(engine.markerEntities().isEmpty())

        val snap = engine.snapshot(250_000L, watchConnected = true)
        assertEquals(ThermalSignalStatus.ACTIVE, snap.status)
        assertEquals(33.4f, snap.baselineSkinTempC!!, 0.05f)
        assertEquals(32.5f, snap.currentSkinTempC!!, 0.05f)
        assertEquals(-0.9f, snap.deltaC!!, 0.05f)
    }

    @Test
    fun emitsSingleDeduplicatedEpisodeFreezesBaselineAndUpdatesOnRecovery() {
        val engine = ThermalMonitoringEngine(
            ThermalPolicyConfig.VALIDATED_AUTO_MARKER.copy(
                warmupDurationMs = 60_000L,
                persistenceMs = 20_000L,
                cooldownMs = 40_000L,
                deltaThresholdC = 0.4f,
                hysteresisC = 0.15f
            )
        )
        engine.beginSession("s1", "CLIENT", watchEnabled = true)

        // Warm-up stable at 33.4 C
        val warmup = listOf(0L, 15_000L, 30_000L, 45_000L, 60_000L)
        warmup.forEachIndexed { i, t -> engine.ingest(obs(i + 1L, t, 33.4f)) }

        // Out-of-order & duplicate should remain deterministic
        engine.ingest(obs(5L, 60_000L, 33.4f))
        assertEquals(5, engine.allObservations().size)

        // Sustained decrease beyond -0.4 C over 20s
        assertTrue(engine.ingest(obs(6L, 70_000L, 32.8f)).isEmpty())
        val opened = engine.ingest(obs(7L, 95_000L, 32.7f))
        assertEquals(1, opened.size)
        assertEquals(ThermalDirection.DECREASE.name, opened.first().thermalDirection)
        assertEquals(33.4f, opened.first().baselineValue!!, 0.01f)

        // While active, worsening point updates same single episode (no duplicate IDs)
        val continued = engine.ingest(obs(8L, 110_000L, 32.6f))
        assertEquals(1, continued.size)
        assertEquals(opened.first().eventId, continued.first().eventId)
        assertEquals(1, engine.markerEntities().size)
        assertEquals(33.4f, engine.snapshot(110_000L, true).baselineSkinTempC!!, 0.01f)

        // Hysteresis zone (delta -0.30 is not yet within 0.25) -> stays open
        val stayOpen = engine.ingest(obs(9L, 120_000L, 33.1f))
        assertEquals(ThermalDirection.DECREASE.name, stayOpen.first().thermalDirection)

        // Recovery within 0.25 -> closes and updates same episode to RECOVERY
        val recovered = engine.ingest(obs(10L, 130_000L, 33.25f))
        assertEquals(1, recovered.size)
        assertEquals(ThermalDirection.RECOVERY.name, recovered.first().thermalDirection)
        assertEquals(opened.first().eventId, recovered.first().eventId)
        assertEquals(1, engine.markerEntities().size)
    }

    @Test
    fun handlesInvalidStatusPauseStopCutoffWearerResetAndAmbientConfound() {
        val engine = ThermalMonitoringEngine(
            ThermalPolicyConfig.VALIDATED_AUTO_MARKER.copy(
                warmupDurationMs = 30_000L,
                persistenceMs = 10_000L,
                maxAmbientSwingC = 1.0f
            )
        )
        engine.beginSession("s1", "CLIENT", watchEnabled = true)
        listOf(0L, 10_000L, 20_000L, 30_000L, 35_000L).forEachIndexed { i, t ->
            engine.ingest(obs(i + 1L, t, 33.5f, ambientC = 25.0f))
        }

        // Invalid SDK status sets status INVALID_STATUS cleanly without fake values
        engine.ingest(obs(6L, 40_000L, skinC = null, status = -2, isValid = false))
        assertEquals(ThermalSignalStatus.INVALID_STATUS, engine.snapshot(41_000L, true).status)
        assertNull(engine.snapshot(41_000L, true).currentSkinTempC)

        // Prolonged ambient swing abstains from markers
        engine.ingest(obs(7L, 50_000L, 32.8f, ambientC = 26.8f))
        assertEquals(ThermalSignalStatus.AMBIENT_CONFOUND, engine.snapshot(52_000L, true).status)
        assertTrue(engine.markerEntities().isEmpty())

        // Stale timeout detection
        assertEquals(ThermalSignalStatus.STALE, engine.snapshot(200_000L, true).status)

        // Wearer role change resets baseline
        engine.onWearerRoleChanged("PSYCHOLOGIST", elapsedMillis = 205_000L)
        assertNull(engine.snapshot(206_000L, true).baselineSkinTempC)

        // Pause & stop cutoff exclude late samples
        engine.setPaused(true)
        engine.ingest(obs(8L, 210_000L, 34.0f, wearerRole = "PSYCHOLOGIST"))
        assertEquals(ThermalSignalStatus.PAUSED, engine.snapshot(210_000L, true).status)
        engine.setPaused(false)
        engine.applyStopCutoff(215_000L)
        engine.ingest(obs(9L, 220_000L, 34.1f, wearerRole = "PSYCHOLOGIST"))
        assertEquals(8, engine.allObservations().size)
    }
}
