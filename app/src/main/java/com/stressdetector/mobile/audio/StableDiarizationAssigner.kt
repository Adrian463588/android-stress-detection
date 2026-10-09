package com.stressdetector.mobile.audio

/** A real diarizer segment before the runtime-specific speaker label is applied. */
internal data class DiarizationSegment(val startOffsetMs: Long, val endOffsetMs: Long, val rawCluster: Int)

internal data class StableDiarizationSegment(
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val rawCluster: Int,
    val stableSpeakerIndex: Int?
)

internal data class TemporalSpeakerEvidencePolicy(
    val minimumOverlapMs: Long = 300L,
    val minimumCoverage: Float = 0.6f,
    val ambiguityMargin: Float = 0.2f
) {
    init {
        require(minimumOverlapMs > 0L)
        require(minimumCoverage > 0f && minimumCoverage <= 1f)
        require(ambiguityMargin > 0f && ambiguityMargin <= 1f)
    }
}

/**
 * Keeps speaker identity independent from Sherpa's per-call cluster IDs.
 * Temporal overlap and native speaker embeddings are identity evidence. Raw
 * cluster numbers and the order of interview turns never determine roles.
 */
internal class StableDiarizationAssigner(
    private val maxSpeakers: Int = 2,
    private val matchThreshold: Float = 0.65f,
    private val ambiguityMargin: Float = 0.08f,
    private val distinctThreshold: Float = 0.45f,
    private val temporalPolicy: TemporalSpeakerEvidencePolicy = TemporalSpeakerEvidencePolicy()
) {
    private var previousAssignments: List<StableDiarizationSegment> = emptyList()
    private val anchors = mutableMapOf<Int, FloatArray>()

    init {
        require(maxSpeakers > 0)
        require(distinctThreshold < matchThreshold && matchThreshold <= 1f)
        require(ambiguityMargin > 0f)
    }

    fun reset() {
        previousAssignments = emptyList()
        anchors.clear()
    }

    fun assign(current: List<DiarizationSegment>, embeddings: Map<Int, FloatArray> = emptyMap()): List<StableDiarizationSegment> {
        if (current.isEmpty()) return emptyList()
        val assignedSegmentsByStable = mutableMapOf<Int, MutableList<DiarizationSegment>>()
        val byRawCluster = current
            .groupBy(DiarizationSegment::rawCluster)
            .toList()
            .sortedWith(compareBy({ it.second.minOf(DiarizationSegment::startOffsetMs) }, { it.first }))
        val assignmentsByRawCluster = linkedMapOf<Int, Int?>()

        byRawCluster.forEach { (rawCluster, segments) ->
            val usedStableIndexes = assignedSegmentsByStable.keys
            val blockedStableIndexes = assignedSegmentsByStable.filterValues { assigned ->
                assigned.any { previous ->
                    segments.any { candidate ->
                        overlapMs(previous.startOffsetMs, previous.endOffsetMs, candidate.startOffsetMs, candidate.endOffsetMs) > 0L
                    }
                }
            }.keys
            val matched = temporalIdentity(segments)
            val embedding = embeddings[rawCluster]?.let(::normalize)
            val stableIndex = if (embedding != null) {
                resolveEmbedding(embedding, matched, usedStableIndexes, blockedStableIndexes)
            } else if (embeddings.isNotEmpty()) {
                matched?.takeIf { it !in usedStableIndexes } // Never bootstrap from insufficient native audio.
            } else if (matched != null) {
                matched.takeIf { it !in usedStableIndexes }
            } else if (previousAssignments.isEmpty()) {
                firstUnseenStableIndex(usedStableIndexes)
            } else {
                null
            }
            stableIndex?.let { assignedSegmentsByStable.getOrPut(it) { mutableListOf() }.addAll(segments) }
            assignmentsByRawCluster[rawCluster] = stableIndex
        }

        return current.map { segment ->
            StableDiarizationSegment(
                startOffsetMs = segment.startOffsetMs,
                endOffsetMs = segment.endOffsetMs,
                rawCluster = segment.rawCluster,
                stableSpeakerIndex = assignmentsByRawCluster[segment.rawCluster]
            )
        }.also { previousAssignments = it }
    }

    private fun temporalIdentity(segments: List<DiarizationSegment>): Int? {
        val durationMs = unionDurationMs(segments.map { it.startOffsetMs to it.endOffsetMs })
        // Previous overlap/Unknown spans cannot become identity evidence.
        val previous = DiarizedUtterancePlanner.plan(
            previousAssignments,
            segments.minOf(DiarizationSegment::startOffsetMs),
            segments.maxOf(DiarizationSegment::endOffsetMs)
        ).filter { it.stableSpeakerIndex != null }
        val scores = previous.groupBy { requireNotNull(it.stableSpeakerIndex) }.mapValues { (_, turns) ->
            unionDurationMs(
                turns.flatMap { turn ->
                    segments.map { candidate ->
                        maxOf(turn.startOffsetMs, candidate.startOffsetMs) to minOf(turn.endOffsetMs, candidate.endOffsetMs)
                    }
                }
            )
        }.entries.sortedByDescending { it.value }
        return scores.firstOrNull()?.takeIf { best ->
            durationMs > 0L &&
                best.value >= temporalPolicy.minimumOverlapMs &&
                best.value.toFloat() / durationMs >= temporalPolicy.minimumCoverage &&
                (best.value - (scores.getOrNull(1)?.value ?: 0L)).toFloat() / durationMs >= temporalPolicy.ambiguityMargin
        }?.key
    }

    private fun unionDurationMs(intervals: List<Pair<Long, Long>>): Long {
        var durationMs = 0L
        var coveredEnd = Long.MIN_VALUE
        intervals.filter { (start, end) -> end > start }.sortedBy { it.first }.forEach { (start, end) ->
            durationMs += (end - maxOf(start, coveredEnd)).coerceAtLeast(0L)
            coveredEnd = maxOf(coveredEnd, end)
        }
        return durationMs
    }

    private fun firstUnseenStableIndex(used: Set<Int>): Int? {
        val known = previousAssignments.mapNotNull(StableDiarizationSegment::stableSpeakerIndex).toSet() + anchors.keys
        return (0 until maxSpeakers).firstOrNull { it !in used && it !in known }
    }

    private fun resolveEmbedding(embedding: FloatArray, temporal: Int?, used: Set<Int>, blocked: Set<Int>): Int? {
        val scores = anchors.mapNotNull { (identity, anchor) ->
            if (anchor.size == embedding.size) identity to cosine(anchor, embedding) else null
        }.sortedByDescending { it.second }
        val identity = if (anchors.isEmpty()) firstUnseenStableIndex(used) else selectIdentity(scores, temporal, used, blocked)
        // Immutable session anchors avoid gradual identity drift. A separate
        // validated enrollment workflow can improve anchors in the future.
        if (identity != null && identity !in anchors) anchors[identity] = embedding.copyOf()
        return identity
    }

    private fun selectIdentity(scores: List<Pair<Int, Float>>, temporal: Int?, used: Set<Int>, blocked: Set<Int>): Int? {
        val best = scores.firstOrNull() ?: return null
        val second = scores.getOrNull(1)
        val unambiguous = second == null || best.second - second.second >= ambiguityMargin
        val temporalSupported = scores.any { it.first == temporal && it.second >= distinctThreshold }
        return when {
            !unambiguous -> null
            best.second >= matchThreshold -> best.first.takeIf { it !in blocked }
            best.second < distinctThreshold -> firstUnseenStableIndex(used)
            temporalSupported -> temporal?.takeIf { it !in blocked }
            else -> null
        }
    }

    private fun normalize(vector: FloatArray): FloatArray? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it })
        return if (norm > 0.0) FloatArray(vector.size) { (vector[it] / norm).toFloat() } else null
    }

    private fun cosine(first: FloatArray, second: FloatArray): Float = first.indices.sumOf { first[it].toDouble() * second[it] }.toFloat()

    private fun overlapMs(startA: Long, endA: Long, startB: Long, endB: Long): Long =
        (minOf(endA, endB) - maxOf(startA, startB)).coerceAtLeast(0L)
}
