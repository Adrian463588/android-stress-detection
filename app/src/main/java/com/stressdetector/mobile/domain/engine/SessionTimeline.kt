package com.stressdetector.mobile.domain.engine

import java.util.concurrent.atomic.AtomicLong

/** One session-owned clock used to align audio offsets with Watch PPG elapsed time. */
data class TimelinePauseInterval(val startElapsedMillis: Long, val endElapsedMillis: Long? = null)

@Suppress("TooManyFunctions")
class SessionTimeline private constructor(
    val sessionId: String,
    val generation: Long,
    val startedAtUtcMillis: Long,
    private val startedAtMonotonicMillis: Long,
    private val monotonicNowMillis: () -> Long
) {
    private val lock = Any()
    private var audioStartElapsedMillis: Long? = null
    private var ppgToSessionOffsetMillis: Long? = null
    private var clockBindingSourceValue: String = "UNBOUND"
    private var clockUncertaintyValue: Long? = null
    private val pauseIntervalList = mutableListOf<TimelinePauseInterval>()

    fun currentElapsedMillis(): Long = (monotonicNowMillis() - startedAtMonotonicMillis).coerceAtLeast(0L)

    fun audioStartElapsedMillisOrNull(): Long? = synchronized(lock) { audioStartElapsedMillis }

    fun clockBindingSource(): String = synchronized(lock) { clockBindingSourceValue }

    fun clockUncertaintyMillis(): Long? = synchronized(lock) { clockUncertaintyValue }

    fun pauseIntervals(): List<TimelinePauseInterval> = synchronized(lock) { pauseIntervalList.toList() }

    fun recordPauseStart(elapsedMillis: Long = currentElapsedMillis()) {
        synchronized(lock) {
            val current = pauseIntervalList.lastOrNull()
            if (current == null || current.endElapsedMillis != null) {
                pauseIntervalList.add(TimelinePauseInterval(startElapsedMillis = elapsedMillis.coerceAtLeast(0L)))
            }
        }
    }

    fun recordPauseEnd(elapsedMillis: Long = currentElapsedMillis()) {
        synchronized(lock) {
            val last = pauseIntervalList.lastOrNull() ?: return
            if (last.endElapsedMillis == null) {
                val safeEnd = elapsedMillis.coerceAtLeast(last.startElapsedMillis)
                pauseIntervalList[pauseIntervalList.lastIndex] = last.copy(endElapsedMillis = safeEnd)
            }
        }
    }

    fun activeRecordingMillis(upToElapsedMillis: Long = currentElapsedMillis()): Long = synchronized(lock) {
        val total = upToElapsedMillis.coerceAtLeast(0L)
        var paused = 0L
        pauseIntervalList.forEach { interval ->
            val start = interval.startElapsedMillis.coerceIn(0L, total)
            val end = (interval.endElapsedMillis ?: total).coerceIn(start, total)
            paused += (end - start)
        }
        (total - paused).coerceAtLeast(0L)
    }

    fun markAudioStarted(): Long = synchronized(lock) {
        markAudioStartedAtLocked(monotonicNowMillis())
    }

    fun markAudioStartedAt(monotonicMillis: Long): Long = synchronized(lock) {
        markAudioStartedAtLocked(monotonicMillis)
    }

    fun bindPpgClock(
        ppgElapsedMillis: Long,
        receivedAtMonotonicMillis: Long = monotonicNowMillis(),
        bindingSource: String = "SESSION_READY",
        uncertaintyMillis: Long? = null
    ): Long = synchronized(lock) {
        val receivedAtSessionElapsed = sessionElapsedAt(receivedAtMonotonicMillis)
        val offset = receivedAtSessionElapsed - ppgElapsedMillis.coerceAtLeast(0L)
        ppgToSessionOffsetMillis = offset
        clockBindingSourceValue = bindingSource
        clockUncertaintyValue = uncertaintyMillis
        offset
    }

    fun audioOffsetToSessionElapsed(offsetMillis: Long): Long = synchronized(lock) {
        ((audioStartElapsedMillis ?: 0L) + offsetMillis).coerceAtLeast(0L)
    }

    fun ppgElapsedToSessionElapsed(elapsedMillis: Long): Long = synchronized(lock) {
        (elapsedMillis + (ppgToSessionOffsetMillis ?: 0L)).coerceAtLeast(0L)
    }

    /** Canonical persisted inference excludes the 30-second lead-in/lead-out. */
    fun isCanonicalCoreElapsed(elapsedMillis: Long): Boolean = elapsedMillis in CORE_START_MILLIS until CORE_END_MILLIS

    private fun markAudioStartedAtLocked(monotonicMillis: Long): Long =
        audioStartElapsedMillis ?: sessionElapsedAt(monotonicMillis).also { audioStartElapsedMillis = it }

    private fun sessionElapsedAt(monotonicMillis: Long): Long = (monotonicMillis - startedAtMonotonicMillis).coerceAtLeast(0L)

    companion object {
        private const val CORE_START_MILLIS = 30_000L
        private const val CORE_END_MILLIS = 270_000L
        private val nextGeneration = AtomicLong(0L)

        fun start(
            sessionId: String,
            utcNowMillis: () -> Long = System::currentTimeMillis,
            monotonicNowMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() }
        ): SessionTimeline {
            require(sessionId.isNotBlank()) { "sessionId must not be blank" }
            return SessionTimeline(
                sessionId = sessionId,
                generation = nextGeneration.incrementAndGet(),
                startedAtUtcMillis = utcNowMillis(),
                startedAtMonotonicMillis = monotonicNowMillis(),
                monotonicNowMillis = monotonicNowMillis
            )
        }
    }
}
