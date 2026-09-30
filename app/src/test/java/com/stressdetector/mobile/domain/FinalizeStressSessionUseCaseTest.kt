package com.stressdetector.mobile.domain

import com.stressdetector.mobile.domain.engine.WatchSessionCoordinator
import com.stressdetector.mobile.domain.engine.WatchSessionState
import com.stressdetector.shared.PpgType
import com.stressdetector.shared.WearSessionMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FinalizeStressSessionUseCaseTest {
    @Test
    fun stopTimeoutStillDrainsAndEndsInTerminalState() = runTest {
        val coordinator = TestWatchSessionCoordinator(
            stopAction = {
                delay(20L)
                Result.success(Unit)
            }
        )
        val states = mutableListOf<StressSessionFinalizationState>()
        val useCase = FinalizeStressSessionUseCase(
            watchSessionCoordinator = coordinator,
            timeoutPolicy = testTimeouts(stopWatchMillis = 1L),
            monotonicMillis = { testScheduler.currentTime }
        )

        val result = useCase.execute(
            request = FinalizationRequest("session-timeout", completionExpected = false),
            onState = states::add,
            evaluate = { _ -> Unit },
            insufficientSignalReason = { null },
            persist = { _, _ -> HomeFinalizationResult.Failed("must_not_persist") }
        )

        assertTrue(result is StressSessionFinalizationOutcome.PersistencePending)
        assertEquals(1, coordinator.drainCalls)
        assertTrue(states.any { it is StressSessionFinalizationState.Draining })
        assertEquals(
            StressSessionFinalizationState.ResultReady(persisted = false, warning = "must_not_persist"),
            states.last()
        )
    }

    @Test
    fun failedStopStillDrainsAndEveryStageEndsTerminally() = runTest {
        val coordinator = TestWatchSessionCoordinator(
            stopAction = { Result.failure(IllegalStateException("watch_not_reachable")) }
        )
        val states = mutableListOf<StressSessionFinalizationState>()
        val useCase = FinalizeStressSessionUseCase(
            watchSessionCoordinator = coordinator,
            timeoutPolicy = testTimeouts(),
            monotonicMillis = { testScheduler.currentTime }
        )

        val result = useCase.execute(
            request = FinalizationRequest("session-drain", completionExpected = true),
            onState = states::add,
            evaluate = { progress ->
                progress(FinalizationEvaluationProgress(PpgType.GREEN, completedWindows = 1, totalWindows = 7))
                "frozen-snapshot"
            },
            insufficientSignalReason = { null },
            persist = { _, _ -> HomeFinalizationResult.Failed("database_unavailable") },
            evaluationFingerprint = { "frozen-snapshot-fingerprint" }
        )

        assertEquals(1, coordinator.drainCalls)
        assertTrue(states.any { it is StressSessionFinalizationState.Draining })
        assertTrue(
            states.any {
                it == StressSessionFinalizationState.Evaluating(
                    PpgType.GREEN,
                    completedWindows = 1,
                    totalWindows = 7
                )
            }
        )
        assertTrue(states.any { it is StressSessionFinalizationState.Persisting })
        assertEquals(
            StressSessionFinalizationState.ResultReady(persisted = false, warning = "database_unavailable"),
            states.last()
        )
        assertTrue(result is StressSessionFinalizationOutcome.PersistencePending)
        assertEquals("frozen-snapshot-fingerprint", result.trace.snapshotFingerprint)
    }

    @Test
    fun evaluationTimeoutIsTerminalAndDoesNotPersist() = runTest {
        val states = mutableListOf<StressSessionFinalizationState>()
        var persisted = false
        val useCase = FinalizeStressSessionUseCase(
            watchSessionCoordinator = TestWatchSessionCoordinator(),
            timeoutPolicy = testTimeouts().copy(evaluateMillis = 10L),
            monotonicMillis = { testScheduler.currentTime }
        )

        val result = useCase.execute(
            request = FinalizationRequest("session-evaluate-timeout", completionExpected = false),
            onState = states::add,
            evaluate = { _ ->
                delay(50L)
                Unit
            },
            insufficientSignalReason = { null },
            persist = { _, _ ->
                persisted = true
                HomeFinalizationResult.Failed("must_not_persist")
            }
        )

        assertTrue(result is StressSessionFinalizationOutcome.TimedOut)
        assertFalse(persisted)
        assertEquals(StressSessionFinalizationState.TimedOut(FinalizationStage.EVALUATE), states.last())
    }

    @Test
    fun drainReceivesOnlyTheRemainingAbsoluteBudget() = runTest {
        var drainTimeoutMillis = Long.MAX_VALUE
        val coordinator = object : TestWatchSessionCoordinator(
            stopAction = {
                delay(15L)
                Result.success(Unit)
            }
        ) {
            override suspend fun awaitDrain(sessionId: String, timeoutMillis: Long): Result<Unit> {
                drainTimeoutMillis = timeoutMillis
                return Result.success(Unit)
            }
        }
        val useCase = FinalizeStressSessionUseCase(
            watchSessionCoordinator = coordinator,
            timeoutPolicy = FinalizationTimeoutPolicy(
                stopWatchMillis = 100L,
                drainWatchMillis = 100L,
                evaluateMillis = 100L,
                persistMillis = 100L,
                overallMillis = 20L
            ),
            monotonicMillis = { testScheduler.currentTime }
        )

        useCase.execute(
            request = FinalizationRequest("session-absolute-deadline", completionExpected = false),
            onState = {},
            evaluate = { _ -> Unit },
            insufficientSignalReason = { null },
            persist = { _, _ -> HomeFinalizationResult.Failed("database_unavailable") }
        )

        assertTrue(drainTimeoutMillis in 1L..5L)
    }

    private fun testTimeouts(stopWatchMillis: Long = 100L): FinalizationTimeoutPolicy = FinalizationTimeoutPolicy(
        stopWatchMillis = stopWatchMillis,
        drainWatchMillis = 100L,
        evaluateMillis = 100L,
        persistMillis = 100L,
        overallMillis = 1_000L
    )

    private open class TestWatchSessionCoordinator(
        private val stopAction: suspend () -> Result<Unit> = { Result.success(Unit) },
        private val drainAction: suspend () -> Result<Unit> = { Result.success(Unit) }
    ) : WatchSessionCoordinator {
        override val state: StateFlow<WatchSessionState> = MutableStateFlow(WatchSessionState.Ready)
        var drainCalls: Int = 0
            private set

        override suspend fun start(sessionId: String, mode: WearSessionMode): Result<Unit> = Result.success(Unit)

        override suspend fun stop(sessionId: String, completionExpected: Boolean): Result<Unit> = stopAction()

        open override suspend fun awaitDrain(sessionId: String, timeoutMillis: Long): Result<Unit> {
            drainCalls += 1
            return drainAction()
        }
    }
}
