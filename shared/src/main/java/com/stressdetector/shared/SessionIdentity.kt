package com.stressdetector.shared

import java.nio.charset.StandardCharsets

/**
 * Canonical identity for one phone-owned Wear PPG session.
 *
 * [generation] remains wire metadata for rolling upgrades, but it is not part
 * of this identity and must not decide whether a batch belongs to a session.
 */
data class SessionIdentity(val sessionId: String, val nonce: String, val protocolVersion: Int) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(protocolVersion >= LEGACY_PROTOCOL_VERSION) {
            "protocolVersion must not be negative"
        }
    }

    val isCanonical: Boolean
        get() = nonce.isNotBlank() && protocolVersion == CURRENT_PROTOCOL_VERSION

    fun matches(other: SessionIdentity): Boolean = sessionId == other.sessionId &&
        nonce == other.nonce &&
        protocolVersion == other.protocolVersion

    fun storageKey(): String = "$sessionId|$nonce|$protocolVersion"

    companion object {
        const val LEGACY_PROTOCOL_VERSION = 0
        const val CURRENT_PROTOCOL_VERSION = 1

        /** Versioned protocol-build marker, intentionally independent of APK version. */
        const val CURRENT_PROTOCOL_BUILD = "ppg-continuous-v1"
    }
}

/** Explicit early rejection sent before Wear creates a Samsung tracker. */
data class SessionProtocolRejection(
    val sessionId: String,
    val sessionNonce: String,
    val expectedProtocolVersion: Int,
    val receivedProtocolVersion: Int,
    val expectedBuildFingerprint: String,
    val receivedBuildFingerprint: String,
    val reason: String = "PROTOCOL_MISMATCH"
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
    }
}

/** Shared handshake rule so phone and Wear cannot silently run incompatible wire contracts. */
object SessionProtocolHandshake {
    fun rejectionFor(identity: SessionIdentity, buildFingerprint: String): SessionProtocolRejection? {
        val normalizedBuild = buildFingerprint.trim()
        val mismatch = !identity.isCanonical || normalizedBuild != SessionIdentity.CURRENT_PROTOCOL_BUILD
        if (!mismatch) return null
        return SessionProtocolRejection(
            sessionId = identity.sessionId,
            sessionNonce = identity.nonce,
            expectedProtocolVersion = SessionIdentity.CURRENT_PROTOCOL_VERSION,
            receivedProtocolVersion = identity.protocolVersion,
            expectedBuildFingerprint = SessionIdentity.CURRENT_PROTOCOL_BUILD,
            receivedBuildFingerprint = normalizedBuild
        )
    }
}

object SessionProtocolCodec {
    fun encode(rejection: SessionProtocolRejection): ByteArray = buildString {
        appendLine("sessionId=${rejection.sessionId}")
        appendLine("sessionNonce=${rejection.sessionNonce}")
        appendLine("expectedProtocolVersion=${rejection.expectedProtocolVersion}")
        appendLine("receivedProtocolVersion=${rejection.receivedProtocolVersion}")
        appendLine("expectedBuildFingerprint=${rejection.expectedBuildFingerprint}")
        appendLine("receivedBuildFingerprint=${rejection.receivedBuildFingerprint}")
        appendLine("reason=${rejection.reason}")
    }.toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): SessionProtocolRejection {
        val fields = String(bytes, StandardCharsets.UTF_8)
            .lineSequence()
            .filter { it.contains('=') }
            .associate { line ->
                val separator = line.indexOf('=')
                line.substring(0, separator) to line.substring(separator + 1)
            }
        return SessionProtocolRejection(
            sessionId = fields["sessionId"].orEmpty().ifBlank { error("Missing sessionId") },
            sessionNonce = fields["sessionNonce"].orEmpty(),
            expectedProtocolVersion = fields["expectedProtocolVersion"]?.toIntOrNull()
                ?: error("Missing expectedProtocolVersion"),
            receivedProtocolVersion = fields["receivedProtocolVersion"]?.toIntOrNull()
                ?: error("Missing receivedProtocolVersion"),
            expectedBuildFingerprint = fields["expectedBuildFingerprint"].orEmpty(),
            receivedBuildFingerprint = fields["receivedBuildFingerprint"].orEmpty(),
            reason = fields["reason"].orEmpty().ifBlank { "PROTOCOL_MISMATCH" }
        )
    }
}
