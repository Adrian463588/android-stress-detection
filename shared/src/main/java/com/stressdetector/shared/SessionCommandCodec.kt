package com.stressdetector.shared

import java.nio.charset.StandardCharsets

// Optional completionExpected keeps schema 3 peers interoperable during an
// in-place phone/Watch upgrade: older decoders safely ignore unknown fields.
private const val SESSION_COMMAND_CURRENT_SCHEMA_VERSION = 5

data class SessionCommand(
    val sessionId: String,
    val mode: WearSessionMode = WearSessionMode.HOME_STRESS,
    /** Correlates Wear packets and terminal receipt to one phone-owned session. */
    val sessionNonce: String = "",
    /** Shared phone-Wear transport protocol. Legacy commands are rejected before sensor activation. */
    val protocolVersion: Int = SessionIdentity.CURRENT_PROTOCOL_VERSION,
    /** Versioned protocol-build marker; not an APK version or device identifier. */
    val protocolBuildFingerprint: String = SessionIdentity.CURRENT_PROTOCOL_BUILD,
    /** True only when the phone is closing a nominally completed 300-second session. */
    val completionExpected: Boolean = false,
    /** Phone-local node that owns the session. Never a Watch source node. */
    val peerNodeId: String = "",
    /** Rejects callbacks from an older lifecycle of the same logical session. */
    val generation: Long = 0L,
    val schemaVersion: Int = SESSION_COMMAND_CURRENT_SCHEMA_VERSION
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        require(generation >= 0L) { "generation must be non-negative" }
        require(protocolVersion >= SessionIdentity.LEGACY_PROTOCOL_VERSION) {
            "protocolVersion must not be negative"
        }
    }

    val sessionIdentity: SessionIdentity
        get() = SessionIdentity(sessionId, sessionNonce, protocolVersion)
}

object SessionCommandCodec {
    fun encode(command: SessionCommand): ByteArray = buildString {
        append("schemaVersion=")
        append(command.schemaVersion)
        append(";sessionId=")
        append(escape(command.sessionId))
        append(";mode=")
        append(command.mode.name)
        append(";sessionNonce=")
        append(escape(command.sessionNonce))
        append(";protocolVersion=")
        append(command.protocolVersion)
        append(";protocolBuildFingerprint=")
        append(escape(command.protocolBuildFingerprint))
        append(";completionExpected=")
        append(command.completionExpected)
        append(";peerNodeId=")
        append(escape(command.peerNodeId))
        append(";generation=")
        append(command.generation)
    }.toByteArray(StandardCharsets.UTF_8)

    fun decode(bytes: ByteArray): SessionCommand {
        val payload = String(bytes, StandardCharsets.UTF_8)
        val fields = payload.split(';')
            .mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    field.substring(0, separator) to field.substring(separator + 1)
                }
            }.toMap()
        val sessionId = fields["sessionId"]?.let(::unescape)
            ?: throw IllegalArgumentException("SESSION command is missing sessionId")
        val mode = fields["mode"]?.let { value ->
            runCatching { WearSessionMode.valueOf(value) }.getOrElse {
                throw IllegalArgumentException("Unsupported Watch session mode: $value", it)
            }
        } ?: WearSessionMode.HOME_STRESS
        val schemaVersion = fields["schemaVersion"]?.toIntOrNull() ?: LEGACY_SCHEMA_VERSION
        require(schemaVersion in LEGACY_SCHEMA_VERSION..CURRENT_SCHEMA_VERSION) {
            "Unsupported SESSION command schema: $schemaVersion"
        }
        return SessionCommand(
            sessionId = sessionId.trim(),
            mode = mode,
            sessionNonce = fields["sessionNonce"]?.let(::unescape).orEmpty(),
            protocolVersion = fields["protocolVersion"]?.toIntOrNull()
                ?: SessionIdentity.LEGACY_PROTOCOL_VERSION,
            protocolBuildFingerprint = fields["protocolBuildFingerprint"]?.let(::unescape).orEmpty(),
            completionExpected = fields["completionExpected"]?.toBooleanStrictOrNull() ?: false,
            peerNodeId = fields["peerNodeId"]?.let(::unescape).orEmpty(),
            generation = fields["generation"]?.toLongOrNull() ?: 0L,
            schemaVersion = schemaVersion
        )
    }

    private fun escape(value: String): String = value.replace(";", "%3B").replace("=", "%3D")

    private fun unescape(value: String): String = value.replace("%3B", ";").replace("%3D", "=")

    private const val LEGACY_SCHEMA_VERSION = 1
    private const val CURRENT_SCHEMA_VERSION = SESSION_COMMAND_CURRENT_SCHEMA_VERSION
}
