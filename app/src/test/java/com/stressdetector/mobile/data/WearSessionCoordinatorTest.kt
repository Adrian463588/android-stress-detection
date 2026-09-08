package com.stressdetector.mobile.data

import android.content.Context
import com.stressdetector.mobile.domain.engine.WatchSessionState
import com.stressdetector.shared.PpgBatch
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.SessionIdentity
import com.stressdetector.shared.WearSessionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito

class WearSessionCoordinatorTest {
    @Test
    fun discoveredNodeWithStalePongIsRejectedBeforeLeaseOrCommand() = runTest {
        val connection = FakeConnectionRepository(
            WearConnectionState(
                status = WatchConnectionStatus.NODE_ONLY,
                reachabilityPhase = WearReachabilityPhase.NODE_DISCOVERED,
                lastPongMillis = 1L,
                lastPongNodeId = "watch-node",
                lastNodeIds = listOf("watch-node")
            )
        )
        val commands = FakeCommandGateway()
        val coordinator = coordinator(connection, commands)

        val result = coordinator.start("session-1", WearSessionMode.HOME_STRESS)

        assertTrue(result.isFailure)
        assertEquals(null, commands.startedNodeId)
        assertEquals(0, commands.startCalls)
        assertEquals(0, connection.recordingStartedCalls)
        assertTrue(coordinator.state.value is WatchSessionState.Error)
    }

    @Test
    fun freshReachableWatchStartsSession() = runTest {
        val connection = FakeConnectionRepository(reachableState())
        val commands = FakeCommandGateway()
        val coordinator = coordinator(connection, commands)

        val result = coordinator.start("session-1", WearSessionMode.HOME_STRESS)

        assertTrue(result.isSuccess)
        assertEquals("watch-node", commands.startedNodeId)
        assertEquals(1, commands.startCalls)
        assertEquals(1, connection.recordingStartedCalls)
        assertEquals(WatchSessionState.Streaming, coordinator.state.value)
    }

    @Test
    fun noDiscoveredNodeFailsWithoutSendingCommand() = runTest {
        val connection = FakeConnectionRepository(WearConnectionState())
        val commands = FakeCommandGateway()
        val coordinator = coordinator(connection, commands)

        val result = coordinator.start("session-no-node", WearSessionMode.HOME_STRESS)

        assertTrue(result.isFailure)
        assertEquals(0, commands.startCalls)
        assertTrue(coordinator.state.value is WatchSessionState.Error)
    }

    @Test
    fun repeatedStopSendsOnlyOneCommand() = runTest {
        val connection = FakeConnectionRepository(
            WearConnectionState(
                status = WatchConnectionStatus.APP_REACHABLE,
                reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
                lastPongMillis = System.currentTimeMillis(),
                lastPongNodeId = "watch-node",
                lastNodeIds = listOf("watch-node")
            )
        )
        val commands = FakeCommandGateway()
        val coordinator = coordinator(connection, commands)
        assertTrue(coordinator.start("session-stop", WearSessionMode.HOME_STRESS).isSuccess)

        assertTrue(coordinator.stop("session-stop").isSuccess)
        assertTrue(coordinator.stop("session-stop").isSuccess)

        assertEquals(1, commands.stopCalls)
        assertEquals(WatchSessionState.Stopping, coordinator.state.value)
    }

    @Test
    fun secondModeReturnsBusyWithoutClearingTheStreamingSessionBuffers() = runTest {
        val connection = FakeConnectionRepository(
            WearConnectionState(
                status = WatchConnectionStatus.APP_REACHABLE,
                reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
                lastPongMillis = System.currentTimeMillis(),
                lastPongNodeId = "watch-node",
                lastNodeIds = listOf("watch-node")
            )
        )
        val commands = FakeCommandGateway()
        val repository = PpgRepositoryImpl(monotonicNowMillis = { 0L })
        val coordinator = coordinator(connection, commands, repository)

        assertTrue(coordinator.start("home-session", WearSessionMode.HOME_STRESS).isSuccess)
        val state = repository.state.value
        repository.append(
            PpgBatch(
                type = PpgType.GREEN,
                timestampsMillis = longArrayOf(1L, 2L),
                rawValues = intArrayOf(100, 101),
                sessionId = "home-session",
                elapsedMillis = 1L,
                batchId = PpgBatch.batchIdFor(
                    SessionIdentity("home-session", state.sessionNonce, state.protocolVersion),
                    PpgType.GREEN,
                    1L
                ),
                sequenceNumber = 1L,
                elapsedStartMillis = 0L,
                elapsedEndMillis = 1L,
                sessionNonce = state.sessionNonce,
                protocolVersion = state.protocolVersion,
                peerNodeId = state.peerNodeId,
                generation = state.generation
            ),
            sourceNodeId = "watch-node"
        )

        val secondStart = coordinator.start("verbatim-session", WearSessionMode.VERBATIM_AROUSAL)

        assertTrue(secondStart.isFailure)
        assertTrue(secondStart.exceptionOrNull() is PpgSessionBusyException)
        assertEquals(2, repository.fullGreenWindow().size)
        assertEquals(WatchSessionState.Streaming, coordinator.state.value)
    }

    private fun coordinator(
        connection: IWearConnectionRepository,
        commands: WearCommandGateway,
        repository: IPpgRepository = PpgRepositoryImpl(monotonicNowMillis = { 0L })
    ): WearSessionCoordinator = WearSessionCoordinator(
        context = Mockito.mock(Context::class.java),
        ppgRepository = repository,
        connectionRepository = connection,
        commandGateway = commands
    )

    private fun reachableState(): WearConnectionState = WearConnectionState(
        status = WatchConnectionStatus.APP_REACHABLE,
        reachabilityPhase = WearReachabilityPhase.APP_REACHABLE,
        lastPongMillis = System.currentTimeMillis(),
        lastPongNodeId = "watch-node",
        lastNodeIds = listOf("watch-node")
    )

    private class FakeConnectionRepository(initial: WearConnectionState) : IWearConnectionRepository {
        private val mutableState = MutableStateFlow(initial)
        override val state: StateFlow<WearConnectionState> = mutableState
        var recordingStartedCalls = 0

        override suspend fun refreshAndPing(context: Context): WearConnectionState = mutableState.value

        override suspend fun waitForReachable(context: Context, timeoutMillis: Long): WearConnectionState = mutableState.value

        override fun markPong(nodeId: String) = Unit

        override fun markRecording(recording: Boolean) {
            if (recording) recordingStartedCalls += 1
        }
    }

    private class FakeCommandGateway : WearCommandGateway {
        var startCalls = 0
        var stopCalls = 0
        var startedNodeId: String? = null

        override suspend fun localNodeId(): String = "phone-node"

        override suspend fun startTracking(
            watchNodeId: String,
            sessionId: String,
            mode: WearSessionMode,
            sessionNonce: String,
            phonePeerNodeId: String,
            generation: Long
        ): CommandSendResult {
            startCalls += 1
            startedNodeId = watchNodeId
            return CommandSendResult(nodeCount = 1, sentCount = 1, failedCount = 0)
        }

        override suspend fun stopTracking(
            watchNodeId: String,
            sessionId: String?,
            sessionNonce: String,
            completionExpected: Boolean,
            phonePeerNodeId: String,
            generation: Long
        ): CommandSendResult {
            stopCalls += 1
            return CommandSendResult(nodeCount = 1, sentCount = 1, failedCount = 0)
        }
    }
}
