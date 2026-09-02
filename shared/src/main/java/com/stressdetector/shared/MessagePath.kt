package com.stressdetector.shared

object MessagePath {
    const val PPG_GREEN = "/ppg/green"
    const val PPG_IR = "/ppg/ir"
    const val PPG_RED = "/ppg/red"

    /** Phone -> Watch acknowledgement. Kept below /session for the existing Wear manifest filter. */
    const val PPG_ACK = "/session/ppg/ack"

    /** Watch -> phone terminal receipt, emitted after both production outboxes drain. */
    const val PPG_TERMINAL_RECEIPT = "/ppg/terminal-receipt"

    /** Phone -> Watch terminal acknowledgement. */
    const val PPG_TERMINAL_ACK = "/session/ppg/terminal-ack"

    /** @deprecated Use [PPG_TERMINAL_RECEIPT]. */
    const val PPG_FLUSH_ACK = PPG_TERMINAL_RECEIPT
    const val SESSION_START = "/session/start"

    /** Watch -> phone acknowledgement that Health Tracker listeners are ready. */
    const val SESSION_READY = "/session/ready"

    /** Watch -> phone early rejection before any Samsung tracker is started. */
    const val SESSION_PROTOCOL_REJECTED = "/session/protocol-rejected"

    /** Watch -> phone correlated stop request initiated from the Watch UI. */
    const val SESSION_STOP_REQUEST = "/session/stop-request"
    const val SESSION_STOP = "/session/stop"
    const val PREDICTION_STRESS = "/prediction/stress"
    const val CONNECTION_PING = "/connection/ping"
    const val CONNECTION_PONG = "/connection/pong"
}
