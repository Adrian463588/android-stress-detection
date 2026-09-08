package com.stressdetector.mobile.data

import com.stressdetector.shared.SessionCommand
import com.stressdetector.shared.SessionIdentity

/** Rejects Watch-originated stop requests unless every session identity field matches. */
internal object SessionStopRequestValidator {
    fun rejectionReason(command: SessionCommand, active: PpgStreamState, sourceNodeId: String): String? = when {
        active.sessionId != command.sessionId -> "stale_session"
        active.sessionNonce != command.sessionNonce -> "stale_session_nonce"
        active.protocolVersion != command.protocolVersion -> "stale_protocol_version"
        command.protocolBuildFingerprint != SessionIdentity.CURRENT_PROTOCOL_BUILD -> "stale_protocol_build"
        active.generation != command.generation -> "stale_generation"
        active.sourceNodeId != sourceNodeId -> "stale_source_node"
        active.peerNodeId.isBlank() || active.peerNodeId != command.peerNodeId -> "stale_peer_node"
        active.sessionMode != command.mode -> "stale_session_mode"
        else -> null
    }
}
