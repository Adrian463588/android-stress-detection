@file:Suppress("MagicNumber")

package com.stressdetector.shared

import java.security.MessageDigest

/**
 * Versioned, manifest-owned filter contract shared by Python export and the
 * Android preprocessing boundary.  The source rate is deliberately not part
 * of this value: the contract describes the waveform after target-rate
 * construction, while [modelRateHz] is carried by [ModelSessionContract].
 */
data class PpgFilterContract(
    val algorithm: String = ALGORITHM,
    val order: Int = 4,
    val lowCutHz: Double,
    val highCutHz: Double,
    val zeroPhase: Boolean = true,
    val padPolicy: String = PAD_POLICY,
    val designVersion: String = DESIGN_VERSION,
    val fingerprint: String = ""
) {
    init {
        require(algorithm == ALGORITHM) { "unsupported_ppg_filter_algorithm" }
        require(order == 4) { "unsupported_ppg_filter_order" }
        require(lowCutHz.isFinite() && lowCutHz > 0.0) { "invalid_ppg_filter_low_cut" }
        require(highCutHz.isFinite() && highCutHz > lowCutHz) {
            "invalid_ppg_filter_high_cut"
        }
        require(zeroPhase) { "ppg_filter_must_be_zero_phase" }
        require(padPolicy == PAD_POLICY) { "unsupported_ppg_filter_pad_policy" }
        require(designVersion == DESIGN_VERSION) { "unsupported_ppg_filter_design" }
        if (fingerprint.isNotBlank()) {
            require(fingerprint.matches(SHA256)) { "invalid_ppg_filter_fingerprint" }
            require(fingerprint == computedFingerprint()) { "ppg_filter_fingerprint_mismatch" }
        }
    }

    fun computedFingerprint(): String = sha256(
        listOf(
            algorithm,
            order.toString(),
            canonicalDouble(lowCutHz),
            canonicalDouble(highCutHz),
            zeroPhase.toString(),
            padPolicy,
            designVersion
        ).joinToString("\n")
    )

    fun withComputedFingerprint(): PpgFilterContract = copy(fingerprint = computedFingerprint())

    fun requireCompatibleWith(modelRateHz: Double): PpgFilterContract {
        require(modelRateHz.isFinite() && modelRateHz > 0.0) {
            "invalid_ppg_model_rate"
        }
        require(highCutHz < modelRateHz / 2.0) {
            "ppg_filter_cutoff_exceeds_nyquist"
        }
        return this
    }

    companion object {
        const val ALGORITHM = "butterworth_sosfiltfilt"
        const val PAD_POLICY = "scipy_odd_sosfiltfilt_default"
        const val DESIGN_VERSION = "sos_zpk_bilinear_v1"
        private val SHA256 = Regex("[0-9a-f]{64}")

        fun forChannel(channel: String): PpgFilterContract = when (channel) {
            "ppg_green" -> PpgFilterContract(lowCutHz = 0.5, highCutHz = 5.0)
            "ppg_ir", "ppg_red" -> PpgFilterContract(lowCutHz = 0.5, highCutHz = 8.0)
            else -> error("unsupported_ppg_filter_channel:$channel")
        }.withComputedFingerprint()

        private fun canonicalDouble(value: Double): String = java.math.BigDecimal.valueOf(value)
            .stripTrailingZeros().toPlainString()

        private fun sha256(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
    }
}
