package com.bluenet.core.tunnel

/**
 * How long a VPN session keeps dialing a sharer.
 *
 * The first connection gives up after a handful of failures. A session that already had a link
 * keeps trying for [RECONNECT_BUDGET_MS]: a phone can leave BLE scan results for longer than a
 * few quick retries and still be the sharer the user asked for.
 */
object TunnelReconnect {
    const val MAX_INITIAL_FAILURES = 5
    const val RECONNECT_BUDGET_MS = 3 * 60 * 1000L

    fun shouldGiveUp(everConnected: Boolean, consecutiveFailures: Int, nowMs: Long, deadlineMs: Long): Boolean =
        if (!everConnected) consecutiveFailures >= MAX_INITIAL_FAILURES
        else deadlineMs != 0L && nowMs >= deadlineMs
}
