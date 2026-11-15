package dev.aidos.bridge

/**
 * The channel adapter that actually puts a message on the wire (Matrix, Signal, SMS, email).
 *
 * It is reached only through [EgressGateCoordinator.transmit], after a single-use declassification
 * grant has been checked against the exact payload and recipient (RFC-0113 §4.3 step 5). The
 * messenger processes (Agentic Messenger and friends) implement it; the daemon holds none of its
 * own, so with no transport configured nothing is ever sent.
 */
fun interface OutboundMessageTransport {
    /** Returns normally once the channel accepted the message; throws if it did not. */
    suspend fun deliver(channel: String, recipient: String, payload: String)
}
