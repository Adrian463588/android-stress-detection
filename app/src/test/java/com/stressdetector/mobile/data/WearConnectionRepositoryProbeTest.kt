package com.stressdetector.mobile.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class WearConnectionRepositoryProbeTest {
    private val context = Mockito.mock(Context::class.java)

    @Test
    fun timeoutExpiresNonceSoImmediateRetrySendsANewPing() = runTest {
        val gateway = RecordingWearDataLayerGateway()
        val repository = WearConnectionRepositoryImpl(gateway)

        val first = repository.waitForReachable(context, timeoutMillis = 250L)
        val firstPayload = gateway.sent.single().payload

        gateway.sent.clear()
        val second = repository.waitForReachable(context, timeoutMillis = 250L)

        assertEquals(WearReachabilityPhase.NODE_DISCOVERED, first.reachabilityPhase)
        assertEquals(WearReachabilityPhase.NODE_DISCOVERED, second.reachabilityPhase)
        assertEquals(1, gateway.sent.size)
        assertNotEquals(firstPayload.toList(), gateway.sent.single().payload.toList())
        assertEquals(ReachabilityProbeStatus.IDLE, repository.state.value.probeStatus)
    }

    @Test
    fun onlyFreshCorrelatedPongAuthorizesReachability() = runTest {
        lateinit var repository: WearConnectionRepositoryImpl
        val gateway = RecordingWearDataLayerGateway(
            onSend = { nodeId, _, payload ->
                assertEquals("watch", nodeId)
                assertEquals(PongDisposition.ACCEPTED, repository.markPong(nodeId, payload))
            }
        )
        repository = WearConnectionRepositoryImpl(gateway)

        val result = repository.waitForReachable(context, timeoutMillis = 250L)

        assertEquals(WearReachabilityPhase.APP_REACHABLE, result.reachabilityPhase)
        assertTrue(result.canStartSession())
        assertEquals(ReachabilityProbeStatus.IDLE, result.probeStatus)
    }

    @Test
    fun latePongFromTimedOutAttemptCannotConsumeReplacementNonce() = runTest {
        val gateway = RecordingWearDataLayerGateway()
        val repository = WearConnectionRepositoryImpl(gateway)

        repository.waitForReachable(context, timeoutMillis = 250L)
        val firstPayload = gateway.sent.single().payload

        gateway.sent.clear()
        val secondProbe = async {
            repository.waitForReachable(context, timeoutMillis = 250L)
        }
        while (gateway.sent.isEmpty()) yield()

        assertEquals(PongDisposition.UNKNOWN_NONCE, repository.markPong("watch", firstPayload))
        val secondPayload = gateway.sent.single().payload
        assertNotEquals(firstPayload.toList(), secondPayload.toList())
        assertEquals(PongDisposition.ACCEPTED, repository.markPong("watch", secondPayload))
        assertEquals(WearReachabilityPhase.APP_REACHABLE, secondProbe.await().reachabilityPhase)
    }

    @Test
    fun reachableCapabilityIsPreferredButStillNeedsPong() = runTest {
        val gateway = RecordingWearDataLayerGateway(
            connectedNodeIds = listOf("other", "watch"),
            reachableAppNodeIds = setOf("watch")
        )
        val repository = WearConnectionRepositoryImpl(gateway)

        val result = repository.waitForReachable(context, timeoutMillis = 250L)

        assertEquals("watch", gateway.sent.single().nodeId)
        assertEquals(WearReachabilityPhase.NODE_DISCOVERED, result.reachabilityPhase)
        assertEquals(ReachabilityProbeStatus.IDLE, result.probeStatus)
    }

    @Test
    fun malformedAndWrongNodePongsCannotAuthorizeReachability() = runTest {
        val gateway = RecordingWearDataLayerGateway()
        val repository = WearConnectionRepositoryImpl(gateway)
        val probe = async { repository.waitForReachable(context, timeoutMillis = 250L) }
        while (gateway.sent.isEmpty()) yield()

        assertEquals(
            PongDisposition.MALFORMED_PAYLOAD,
            repository.markPong("watch", ByteArray(Long.SIZE_BYTES - 1))
        )
        assertEquals(
            PongDisposition.UNKNOWN_NONCE,
            repository.markPong("different-watch", gateway.sent.single().payload)
        )
        assertEquals(WearReachabilityPhase.NODE_DISCOVERED, probe.await().reachabilityPhase)
    }

    @Test
    fun concurrentProbesAreSerializedAndSendOnePingPerProbe() = runTest {
        val gateway = RecordingWearDataLayerGateway()
        val repository = WearConnectionRepositoryImpl(gateway)

        val results = listOf(
            async { repository.waitForReachable(context, timeoutMillis = 250L) },
            async { repository.waitForReachable(context, timeoutMillis = 250L) }
        ).awaitAll()

        assertEquals(2, gateway.sent.size)
        assertTrue(results.all { it.reachabilityPhase == WearReachabilityPhase.NODE_DISCOVERED })
        assertNotEquals(gateway.sent[0].payload.toList(), gateway.sent[1].payload.toList())
    }

    private class RecordingWearDataLayerGateway(
        private val connectedNodeIds: List<String> = listOf("watch"),
        private val reachableAppNodeIds: Set<String> = emptySet(),
        private val onSend: ((String, String, ByteArray) -> Unit)? = null
    ) : WearDataLayerGateway {
        val sent = mutableListOf<SentMessage>()

        override suspend fun connectedNodeIds(context: Context): List<String> = connectedNodeIds

        override suspend fun reachableAppNodeIds(context: Context): Set<String> = reachableAppNodeIds

        override suspend fun sendMessage(context: Context, nodeId: String, path: String, payload: ByteArray) {
            sent += SentMessage(nodeId, path, payload.copyOf())
            onSend?.invoke(nodeId, path, payload.copyOf())
        }
    }

    private data class SentMessage(val nodeId: String, val path: String, val payload: ByteArray)
}
