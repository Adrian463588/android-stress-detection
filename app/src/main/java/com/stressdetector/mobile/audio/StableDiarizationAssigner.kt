package com.stressdetector.mobile.audio

/** A real diarizer segment before the runtime-specific speaker label is applied. */
internal data class DiarizationSegment(val startOffsetMs: Long, val endOffsetMs: Long, val rawCluster: Int)

internal data class StableDiarizationSegment(
    val startOffsetMs: Long,
    val endOffsetMs: Long,
    val rawCluster: Int,
    val stableSpeakerIndex: Int?
)

/**
 * Keeps speaker identity independent from Sherpa's per-call cluster IDs.
 * Temporal overlap is the only automatic identity evidence; a cluster with
 * no overlap is left unresolved instead of being assigned a fabricated role.
 */
internal class StableDiarizationAssigner(private val maxSpeakers: Int = 2) {
    private var previousAssignments: List<StableDiarizationSegment> = emptyList()

    init {
        require(maxSpeakers > 0)
    }

    fun reset() {
        previousAssignments = emptyList()
    }

    fun assign(current: List<DiarizationSegment>): List<StableDiarizationSegment> {
        if (current.isEmpty()) return emptyList()
        val usedStableIndexes = mutableSetOf<Int>()
        val byRawCluster = current
            .groupBy(DiarizationSegment::rawCluster)
            .toList()
            .sortedWith(compareBy({ it.second.minOf(DiarizationSegment::startOffsetMs) }, { it.first }))
        val assignmentsByRawCluster = linkedMapOf<Int, Int?>()

        byRawCluster.forEach { (rawCluster, segments) ->
            val overlapByStable = previousAssignments
                .filter { it.stableSpeakerIndex != null }
                .groupBy { it.stableSpeakerIndex!! }
                .mapValues { (_, previousSegments) ->
                    previousSegments.sumOf { previous ->
                        segments.sumOf { candidate ->
                            overlapMs(
                                previous.startOffsetMs,
                                previous.endOffsetMs,
                                candidate.startOffsetMs,
                                candidate.endOffsetMs
                            )
                        }
                    }
                }
                .filterKeys { it !in usedStableIndexes }
            val matched = overlapByStable.maxByOrNull { it.value }
                ?.takeIf { it.value > 0L }
                ?.key
            val stableIndex = matched ?: if (previousAssignments.isEmpty()) {
                firstUnseenStableIndex(usedStableIndexes)
            } else {
                null
            }
            stableIndex?.let(usedStableIndexes::add)
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

    private fun firstUnseenStableIndex(used: Set<Int>): Int? {
        val known = previousAssignments.mapNotNull(StableDiarizationSegment::stableSpeakerIndex).toSet()
        return (0 until maxSpeakers).firstOrNull { it !in used && it !in known }
    }

    private fun overlapMs(startA: Long, endA: Long, startB: Long, endB: Long): Long =
        (minOf(endA, endB) - maxOf(startA, startB)).coerceAtLeast(0L)
}
