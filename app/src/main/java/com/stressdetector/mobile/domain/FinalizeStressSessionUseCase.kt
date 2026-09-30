package com.stressdetector.mobile.domain

import com.stressdetector.mobile.domain.engine.WatchSessionCoordinator
import com.stressdetector.shared.PpgType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** Explicit, bounded states for the final phase of a Home stress session. */
sealed interface StressSessionFinalizationState {
    data object Idle : StressSessionFinalizationState
    data object Stopping : StressSessionFinalizationState
    data object Draining : StressSessionFinalizationState
    data class Evaluating(
        val channel: PpgType?,
        val completedWindows: Int,
        val totalWindows: Int,
        val channels: Map<PpgType, FinalizationChannelProgress> = emptyMap()
    ) : StressSessionFinalizationState

    data object Persisting : StressSessionFinalizationState
    data class ResultReady(val persisted: Boolean, val warning: String? = null) : StressSessionFinalizationState
    data object Completed : StressSessionFinalizationState
    data class InsufficientSignal(val reason: String) : StressSessionFinalizationState
    data class Failed(val reason: String) : StressSessionFinalizationState
    data class TimedOut(val stage: FinalizationStage) : StressSessionFinalizationState
}

enum class FinalizationStage {
    STOP_WATCH,
    DRAIN_WATCH,
    EVALUATE,
    PERSIST,
    OVERALL
}

data class FinalizationChannelProgress(val completedWindows: Int, val totalWindows: Int)

data class FinalizationEvaluationProgress(
    val channel: PpgType?,
    val completedWindows: Int,
    val totalWindows: Int,
    val channels: Map<PpgType, FinalizationChannelProgress> = emptyMap()
)

data class FinalizationStageDuration(val stage: FinalizationStage, val elapsedMillis: Long)

/** Metadata-only finalization trace. It intentionally contains no waveform values. */
data class StressSessionFinalizationTrace(
    val sessionId: String,
    val stageDurations: List<FinalizationStageDuration>,
    val totalElapsedMillis: Long,
    val transportWarning: String? = null,
    val snapshotFingerprint: String? = null
)

data class FinalizationRequest(val sessionId: String, val completionExpected: Boolean) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }
}

/** Deadlines are policy, not hidden coroutine timing constants. */
data class FinalizationTimeoutPolicy(
    val stopWatchMillis: Long = 5_000L,
    val drainWatchMillis: Long = 8_000L,
    val evaluateMillis: Long = 20_000L,
    val persistMillis: Long = 4_000L,
    /** Includes coroutine scheduling and Room overhead beyond stage budgets. */
    val overallMillis: Long = 40_000L
) {
    init {
        require(stopWatchMillis > 0L) { "stopWatchMillis must be positive" }
        require(drainWatchMillis > 0L) { "drainWatchMillis must be positive" }
        require(evaluateMillis > 0L) { "evaluateMillis must be positive" }
        require(persistMillis > 0L) { "persistMillis must be positive" }
        require(overallMillis > 0L) { "overallMillis must be positive" }
    }
}

sealed interface StressSessionFinalizationOutcome<out Evaluation> {
    val trace: StressSessionFinalizationTrace

    data class Completed<Evaluation>(
        val evaluation: Evaluation,
        val stored: HomeFinalizationResult.Stored,
        override val trace: StressSessionFinalizationTrace
    ) : StressSessionFinalizationOutcome<Evaluation>

    data class InsufficientSignal<Evaluation>(
        val evaluation: Evaluation,
        val stored: HomeFinalizationResult.Stored,
        val reason: String,
        override val trace: StressSessionFinalizationTrace
    ) : StressSessionFinalizationOutcome<Evaluation>

    /** Evaluation is real and complete, but persistence must be retried. */
    data class PersistencePending<Evaluation>(
        val evaluation: Evaluation,
        val reason: String,
        val insufficientSignalReason: String?,
        override val trace: StressSessionFinalizationTrace
    ) : StressSessionFinalizationOutcome<Evaluation>

    data class Failed(val reason: String, override val trace: StressSessionFinalizationTrace) :
        StressSessionFinalizationOutcome<Nothing>

    data class TimedOut(val stage: FinalizationStage, override val trace: StressSessionFinalizationTrace) :
        StressSessionFinalizationOutcome<Nothing>
}

/**
 * Owns the absolute deadline and terminal-state contract for Home-session finalization.
 * Optional presentation work is outside this use case.
 */
class FinalizeStressSessionUseCase(
    private val watchSessionCoordinator: WatchSessionCoordinator,
    private val timeoutPolicy: FinalizationTimeoutPolicy = FinalizationTimeoutPolicy(),
    private val monotonicMillis: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }
) {
    @Suppress("LongParameterList")
    suspend fun <Evaluation> execute(
        request: FinalizationRequest,
        onState: (StressSessionFinalizationState) -> Unit,
        evaluate: suspend ((FinalizationEvaluationProgress) -> Unit) -> Evaluation,
        insufficientSignalReason: (Evaluation) -> String?,
        persist: suspend (Evaluation, String?) -> HomeFinalizationResult,
        evaluationFingerprint: (Evaluation) -> String? = { null },
        onEvaluationReady: (Evaluation) -> Unit = {}
    ): StressSessionFinalizationOutcome<Evaluation> {
        val run = FinalizationRun(request, timeoutPolicy, monotonicMillis)
        var evaluationReady: Evaluation? = null
        val evaluationCallbacks = EvaluationCallbacks(
            onState = onState,
            evaluate = evaluate
        )
        val persistenceCallbacks = PersistenceCallbacks(
            onState = onState,
            insufficientSignalReason = insufficientSignalReason,
            persist = persist,
            evaluationFingerprint = evaluationFingerprint,
            onEvaluationReady = { evaluation ->
                evaluationReady = evaluation
                onEvaluationReady(evaluation)
            }
        )
        val envelopeResult = runCatching {
            withTimeoutOrNull(timeoutPolicy.overallMillis) {
                executeStages(run, evaluationCallbacks, persistenceCallbacks)
            }
        }
        val envelope = envelopeResult.fold(
            onSuccess = { result ->
                result ?: evaluationReady?.let { evaluation ->
                    PersistencePendingEnvelope(
                        evaluation = evaluation,
                        reason = "overall_timeout_after_evaluation",
                        insufficientSignalReason = insufficientSignalReason(evaluation)
                    )
                } ?: TimeoutEnvelope(FinalizationStage.OVERALL)
            },
            onFailure = { error ->
                if (error is CancellationException) {
                    onState(StressSessionFinalizationState.Failed("finalization_cancelled"))
                    throw error
                }
                FailureEnvelope(failureReason(error, "finalization_failed"))
            }
        )
        return terminalOutcome(envelope, run.trace(), onState)
    }

    private suspend fun <Evaluation> executeStages(
        run: FinalizationRun,
        evaluationCallbacks: EvaluationCallbacks<Evaluation>,
        persistenceCallbacks: PersistenceCallbacks<Evaluation>
    ): ExecutionEnvelope<Evaluation> {
        run.transportWarning = stopAndDrain(run, evaluationCallbacks.onState)
        return when (val evaluation = evaluateSnapshot(run, evaluationCallbacks)) {
            is EvaluationEnvelope -> persistEvaluation(
                run = run,
                evaluation = evaluation.value,
                callbacks = persistenceCallbacks
            )
            is FailureEnvelope -> evaluation
            is TimeoutEnvelope -> evaluation
            is SuccessEnvelope, is InsufficientEnvelope, is PersistencePendingEnvelope ->
                error("evaluation_envelope_invalid")
        }
    }

    private suspend fun stopAndDrain(run: FinalizationRun, onState: (StressSessionFinalizationState) -> Unit): String? {
        onState(StressSessionFinalizationState.Stopping)
        val stopWarning = transportStage(
            stage = FinalizationStage.STOP_WATCH,
            timeoutMillis = timeoutPolicy.stopWatchMillis,
            timeoutReason = "stop_watch_timeout",
            run = run
        ) {
            watchSessionCoordinator.stop(run.sessionId, run.completionExpected)
        }
        onState(StressSessionFinalizationState.Draining)
        val drainWarning = transportStage(
            stage = FinalizationStage.DRAIN_WATCH,
            timeoutMillis = timeoutPolicy.drainWatchMillis,
            timeoutReason = "drain_watch_timeout",
            run = run
        ) {
            watchSessionCoordinator.awaitDrain(
                run.sessionId,
                run.remainingMillis().coerceAtMost(timeoutPolicy.drainWatchMillis).coerceAtLeast(1L)
            )
        }
        return listOfNotNull(stopWarning, drainWarning).joinToString("; ").ifBlank { null }
    }

    private suspend fun transportStage(
        stage: FinalizationStage,
        timeoutMillis: Long,
        timeoutReason: String,
        run: FinalizationRun,
        action: suspend () -> Result<Unit>
    ): String? = when (val result = runStage(stage, timeoutMillis, run, action)) {
        is StageResult.Value -> result.value.exceptionOrNull()?.message
        is StageResult.Error -> failureReason(result.error, timeoutReason)
        StageResult.Timeout -> timeoutReason
    }

    private suspend fun <Evaluation> evaluateSnapshot(
        run: FinalizationRun,
        callbacks: EvaluationCallbacks<Evaluation>
    ): ExecutionEnvelope<Evaluation> {
        callbacks.onState(
            StressSessionFinalizationState.Evaluating(
                channel = null,
                completedWindows = 0,
                totalWindows = 0
            )
        )
        return when (
            val step = runStage(FinalizationStage.EVALUATE, timeoutPolicy.evaluateMillis, run) {
                callbacks.evaluate { progress ->
                    callbacks.onState(
                        StressSessionFinalizationState.Evaluating(
                            channel = progress.channel,
                            completedWindows = progress.completedWindows,
                            totalWindows = progress.totalWindows,
                            channels = progress.channels
                        )
                    )
                }
            }
        ) {
            is StageResult.Value -> EvaluationEnvelope(step.value)
            is StageResult.Error -> FailureEnvelope(failureReason(step.error, "evaluation_failed"))
            StageResult.Timeout -> TimeoutEnvelope(FinalizationStage.EVALUATE)
        }
    }

    private suspend fun <Evaluation> persistEvaluation(
        run: FinalizationRun,
        evaluation: Evaluation,
        callbacks: PersistenceCallbacks<Evaluation>
    ): ExecutionEnvelope<Evaluation> {
        callbacks.onEvaluationReady(evaluation)
        run.snapshotFingerprint = callbacks.evaluationFingerprint(evaluation)
        callbacks.onState(StressSessionFinalizationState.Persisting)
        val insufficientReason = callbacks.insufficientSignalReason(evaluation)
        return when (
            val step = runStage(FinalizationStage.PERSIST, timeoutPolicy.persistMillis, run) {
                callbacks.persist(evaluation, run.transportWarning)
            }
        ) {
            is StageResult.Value -> persistedEnvelope(step.value, evaluation, insufficientReason)
            is StageResult.Error -> PersistencePendingEnvelope(
                evaluation,
                failureReason(step.error, "persistence_failed"),
                insufficientReason
            )
            StageResult.Timeout -> PersistencePendingEnvelope(
                evaluation,
                "persistence_timeout",
                insufficientReason
            )
        }
    }

    private fun <Evaluation> persistedEnvelope(
        persisted: HomeFinalizationResult,
        evaluation: Evaluation,
        insufficientReason: String?
    ): ExecutionEnvelope<Evaluation> = when (persisted) {
        is HomeFinalizationResult.Stored -> if (insufficientReason == null) {
            SuccessEnvelope(evaluation, persisted)
        } else {
            InsufficientEnvelope(evaluation, persisted, insufficientReason)
        }
        is HomeFinalizationResult.Failed -> PersistencePendingEnvelope(
            evaluation,
            persisted.reason,
            insufficientReason
        )
    }

    private fun <Evaluation> terminalOutcome(
        envelope: ExecutionEnvelope<Evaluation>,
        trace: StressSessionFinalizationTrace,
        onState: (StressSessionFinalizationState) -> Unit
    ): StressSessionFinalizationOutcome<Evaluation> = when (envelope) {
        is SuccessEnvelope -> StressSessionFinalizationOutcome.Completed(
            envelope.evaluation,
            envelope.stored,
            trace
        ).also { onState(StressSessionFinalizationState.Completed) }
        is InsufficientEnvelope -> StressSessionFinalizationOutcome.InsufficientSignal(
            envelope.evaluation,
            envelope.stored,
            envelope.reason,
            trace
        ).also { onState(StressSessionFinalizationState.InsufficientSignal(envelope.reason)) }
        is PersistencePendingEnvelope -> StressSessionFinalizationOutcome.PersistencePending(
            evaluation = envelope.evaluation,
            reason = envelope.reason,
            insufficientSignalReason = envelope.insufficientSignalReason,
            trace = trace
        ).also {
            onState(
                envelope.insufficientSignalReason?.let(StressSessionFinalizationState::InsufficientSignal)
                    ?: StressSessionFinalizationState.ResultReady(persisted = false, warning = envelope.reason)
            )
        }
        is FailureEnvelope -> StressSessionFinalizationOutcome.Failed(envelope.reason, trace)
            .also { onState(StressSessionFinalizationState.Failed(envelope.reason)) }
        is TimeoutEnvelope -> StressSessionFinalizationOutcome.TimedOut(envelope.stage, trace)
            .also { onState(StressSessionFinalizationState.TimedOut(envelope.stage)) }
        is EvaluationEnvelope -> error("unpersisted_evaluation_envelope")
    }

    private suspend fun <Value> runStage(
        stage: FinalizationStage,
        timeoutMillis: Long,
        run: FinalizationRun,
        block: suspend () -> Value
    ): StageResult<Value> {
        val startedAt = run.monotonicMillis()
        val budget = timeoutMillis.coerceAtMost(run.remainingMillis())
        if (budget <= 0L) {
            run.timings += FinalizationStageDuration(stage, 0L)
            return StageResult.Timeout
        }
        val result = runCatching {
            withTimeoutOrNull(budget) { StageAttempt(block()) }
        }.fold(
            onSuccess = { attempt ->
                if (attempt == null) StageResult.Timeout else StageResult.Value(attempt.value)
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                StageResult.Error(error)
            }
        )
        run.timings += FinalizationStageDuration(stage, (run.monotonicMillis() - startedAt).coerceAtLeast(0L))
        return result
    }

    private fun failureReason(error: Throwable, fallback: String): String = error.message ?: error::class.simpleName ?: fallback

    private sealed interface StageResult<out Value> {
        data class Value<T>(val value: T) : StageResult<T>
        data class Error(val error: Throwable) : StageResult<Nothing>
        data object Timeout : StageResult<Nothing>
    }

    private data class StageAttempt<Value>(val value: Value)

    private data class EvaluationCallbacks<Evaluation>(
        val onState: (StressSessionFinalizationState) -> Unit,
        val evaluate: suspend ((FinalizationEvaluationProgress) -> Unit) -> Evaluation
    )

    private data class PersistenceCallbacks<Evaluation>(
        val onState: (StressSessionFinalizationState) -> Unit,
        val insufficientSignalReason: (Evaluation) -> String?,
        val persist: suspend (Evaluation, String?) -> HomeFinalizationResult,
        val evaluationFingerprint: (Evaluation) -> String?,
        val onEvaluationReady: (Evaluation) -> Unit
    )

    private sealed interface ExecutionEnvelope<out Evaluation>

    private data class EvaluationEnvelope<Evaluation>(val value: Evaluation) : ExecutionEnvelope<Evaluation>

    private data class SuccessEnvelope<Evaluation>(val evaluation: Evaluation, val stored: HomeFinalizationResult.Stored) :
        ExecutionEnvelope<Evaluation>

    private data class InsufficientEnvelope<Evaluation>(
        val evaluation: Evaluation,
        val stored: HomeFinalizationResult.Stored,
        val reason: String
    ) : ExecutionEnvelope<Evaluation>

    private data class PersistencePendingEnvelope<Evaluation>(
        val evaluation: Evaluation,
        val reason: String,
        val insufficientSignalReason: String?
    ) : ExecutionEnvelope<Evaluation>

    private data class FailureEnvelope(val reason: String) : ExecutionEnvelope<Nothing>

    private data class TimeoutEnvelope(val stage: FinalizationStage) : ExecutionEnvelope<Nothing>

    private class FinalizationRun(
        private val request: FinalizationRequest,
        private val timeoutPolicy: FinalizationTimeoutPolicy,
        val monotonicMillis: () -> Long
    ) {
        val sessionId: String = request.sessionId
        val completionExpected: Boolean = request.completionExpected
        val timings = mutableListOf<FinalizationStageDuration>()
        val startedAtMillis: Long = monotonicMillis()
        private val deadlineMillis = startedAtMillis + timeoutPolicy.overallMillis
        var transportWarning: String? = null
        var snapshotFingerprint: String? = null

        fun remainingMillis(): Long = (deadlineMillis - monotonicMillis()).coerceAtLeast(0L)

        fun trace(): StressSessionFinalizationTrace = StressSessionFinalizationTrace(
            sessionId = sessionId,
            stageDurations = timings.toList(),
            totalElapsedMillis = (monotonicMillis() - startedAtMillis).coerceAtLeast(0L),
            transportWarning = transportWarning,
            snapshotFingerprint = snapshotFingerprint
        )
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
