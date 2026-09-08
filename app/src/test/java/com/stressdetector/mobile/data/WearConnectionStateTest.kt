package com.stressdetector.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearConnectionStateTest {
    @Test
    fun freshPongWithinWindowIsReachableEvidence() {
        val now = 100_000L
        val state = WearConnectionState(lastPongMillis = now - 5_000L)

        assertTrue(state.isPongFresh(now))
    }

    @Test
    fun stalePongOutsideWindowIsNotReachableEvidence() {
        val now = 100_000L
        val state = WearConnectionState(lastPongMillis = now - 20_000L)

        assertFalse(state.isPongFresh(now))
    }

    @Test
    fun missingPongIsNotFresh() {
        assertFalse(WearConnectionState().isPongFresh(100_000L))
    }

    @Test
    fun discoveredNodeRemainsACommandCandidateWhenPongIsStale() {
        val state = WearConnectionState(
            status = WatchConnectionStatus.NODE_ONLY,
            reachabilityPhase = WearReachabilityPhase.NODE_DISCOVERED,
            lastPongMillis = 1L,
            lastPongNodeId = "watch-node",
            lastNodeIds = listOf("watch-node")
        )

        assertFalse(state.isPongFresh(100_000L))
        assertEquals("watch-node", state.commandNodeId)
        assertTrue(state.canAttemptCommands)
        assertFalse(state.canStartSession(100_000L))
        assertFalse(state.isAppReachable)
        assertEquals(WearReachabilityPhase.NODE_DISCOVERED, state.reachabilityPhase)
    }

    @Test
    fun freshApplicationPongAuthorizesOnlyAppReachableSessionStart() {
        val now = 100_000L
        val state = WearConnectionState(
            status = WatchConnectionStatus.APP_REACHABLE,
            reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
            lastPongMillis = now - 1_000L,
            lastPongNodeId = "watch-node",
            lastNodeIds = listOf("watch-node")
        )

        assertTrue(state.canStartSession(now))
    }

    @Test
    fun streamingPhaseCannotAuthorizeAnotherSessionStart() {
        val now = 100_000L
        val state = WearConnectionState(
            status = WatchConnectionStatus.RECORDING,
            reachabilityPhase = WearReachabilityPhase.STREAMING,
            lastPongMillis = now - 1_000L,
            lastPongNodeId = "watch-node",
            lastNodeIds = listOf("watch-node")
        )

        assertFalse(state.canStartSession(now))
    }

    @Test
    fun uncorrelatedPongDoesNotCreateReachabilityEvidence() {
        val repository = WearConnectionRepositoryImpl()

        repository.markPong("watch")

        assertEquals(WatchConnectionStatus.DISCONNECTED, repository.state.value.status)
        assertEquals(0L, repository.state.value.lastPongMillis)
    }

    @Test
    fun refreshDoesNotReplaceAnInFlightPingNonce() {
        var nextNonce = 0L
        val registry = WearConnectionRepositoryImpl.PendingPingRegistry(1_000L) { ++nextNonce }

        val first = registry.prepare("watch", nowMillis = 10_000L)
        val second = registry.prepare("watch", nowMillis = 10_500L)

        assertNotNull(first)
        assertNull(second)
        assertTrue(registry.consumeIfMatches("watch", first!!))
    }

    @Test
    fun stalePongCannotConsumeTheNewLease() {
        var nextNonce = 0L
        val registry = WearConnectionRepositoryImpl.PendingPingRegistry(1_000L) { ++nextNonce }

        val first = registry.prepare("watch", nowMillis = 10_000L)!!
        val second = registry.prepare("watch", nowMillis = 11_001L)!!

        assertNotEquals(first, second)
        assertFalse(registry.consumeIfMatches("watch", first))
        assertTrue(registry.consumeIfMatches("watch", second))
    }

    @Test
    fun discardedSendFailureDoesNotRemoveAReplacementLease() {
        var nextNonce = 0L
        val registry = WearConnectionRepositoryImpl.PendingPingRegistry(1_000L) { ++nextNonce }

        val first = registry.prepare("watch", nowMillis = 10_000L)!!
        val second = registry.prepare("watch", nowMillis = 11_001L)!!

        registry.discardIfMatches("watch", first)

        assertTrue(registry.consumeIfMatches("watch", second))
    }

    @Test
    fun acceptedBatchIsStreamingEvidenceEvenWithoutFreshPong() {
        val repository = WearConnectionRepositoryImpl()
        val state = repository.state

        repository.markStreamingEvidence("watch")

        assertEquals(WearReachabilityPhase.STREAMING, state.value.reachabilityPhase)
        assertEquals(WatchConnectionStatus.RECORDING, state.value.status)
        assertEquals("watch", state.value.lastStreamingNodeId)
        assertTrue(state.value.lastStreamingEvidenceMillis > 0L)
    }
}
