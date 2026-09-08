package com.stressdetector.wear.data.samsung

import com.stressdetector.shared.PpgType
import java.util.Locale

/** Why a callback point was not eligible for live PPG ingestion. */
enum class SamsungPpgSampleRejectionReason {
    STATUS_UNAVAILABLE,
    NON_NORMAL_STATUS,
    VALUE_UNAVAILABLE
}

/** Bounded, channel-specific diagnostic emitted only at a reporting threshold. */
data class SamsungPpgSampleDiagnostic(
    val type: PpgType,
    val reason: SamsungPpgSampleRejectionReason,
    val status: Int?,
    val channelRejectedCount: Int
) {
    val shouldReport: Boolean
        get() = channelRejectedCount == 1 || channelRejectedCount % REPORT_INTERVAL == 0

    fun message(): String = buildString {
        append(type.name.lowercase(Locale.ROOT))
        append(" PPG callback rejected: ")
        append(reason.name.lowercase())
        status?.let { append(" (status=").append(it).append(')') }
        append(" [count=").append(channelRejectedCount).append(']')
    }

    private companion object {
        const val REPORT_INTERVAL = 100
    }
}

/** Thread-safe counters retained across a Samsung tracker reconnect within one session. */
class SamsungPpgCallbackDiagnostics {
    private val byType = PpgType.acquisitionChannels.associateWith { ChannelCounts() }.toMutableMap()

    @Synchronized
    fun reset() {
        byType.values.forEach(ChannelCounts::reset)
    }

    @Synchronized
    fun record(type: PpgType, reason: SamsungPpgSampleRejectionReason, status: Int? = null): SamsungPpgSampleDiagnostic {
        require(type.isAcquisitionChannel) { "Only acquired PPG channels have callback diagnostics" }
        val counts = requireNotNull(byType[type])
        counts.rejected += 1
        when (reason) {
            SamsungPpgSampleRejectionReason.STATUS_UNAVAILABLE -> counts.statusUnavailable += 1
            SamsungPpgSampleRejectionReason.NON_NORMAL_STATUS -> {
                counts.nonNormalStatus += 1
                counts.lastNonNormalStatus = status
            }
            SamsungPpgSampleRejectionReason.VALUE_UNAVAILABLE -> counts.valueUnavailable += 1
        }
        return SamsungPpgSampleDiagnostic(type, reason, status, counts.rejected)
    }

    /** Compact root-cause text for channels that have not reached usable readiness. */
    @Synchronized
    fun readinessSummary(waitingChannels: Set<PpgType>): String? = waitingChannels
        .filter { it.isProductionChannel }
        .sortedBy(PpgType::ordinal)
        .mapNotNull { type ->
            val counts = requireNotNull(byType[type])
            counts.takeIf { it.rejected > 0 }?.let {
                buildString {
                    append(type.name.lowercase(Locale.ROOT))
                    append("{non_normal=").append(it.nonNormalStatus)
                    append(",status_unavailable=").append(it.statusUnavailable)
                    append(",value_unavailable=").append(it.valueUnavailable)
                    it.lastNonNormalStatus?.let { status -> append(",last_status=").append(status) }
                    append('}')
                }
            }
        }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("; ")

    private class ChannelCounts {
        var rejected: Int = 0
        var nonNormalStatus: Int = 0
        var statusUnavailable: Int = 0
        var valueUnavailable: Int = 0
        var lastNonNormalStatus: Int? = null

        fun reset() {
            rejected = 0
            nonNormalStatus = 0
            statusUnavailable = 0
            valueUnavailable = 0
            lastNonNormalStatus = null
        }
    }
}
