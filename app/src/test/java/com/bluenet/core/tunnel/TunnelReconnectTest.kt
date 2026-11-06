package com.bluenet.core.tunnel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelReconnectTest {

    @Test
    fun firstConnectionGivesUpAfterAFewFailures() {
        assertFalse(TunnelReconnect.shouldGiveUp(everConnected = false, consecutiveFailures = 4, nowMs = 0, deadlineMs = 0))
        assertTrue(TunnelReconnect.shouldGiveUp(everConnected = false, consecutiveFailures = 5, nowMs = 0, deadlineMs = 0))
    }

    @Test
    fun establishedSessionKeepsDialingUntilTheBudget() {
        val now = 1_000_000L
        val deadline = now + TunnelReconnect.RECONNECT_BUDGET_MS
        assertFalse(TunnelReconnect.shouldGiveUp(everConnected = true, consecutiveFailures = 20, nowMs = now, deadlineMs = 0))
        assertFalse(TunnelReconnect.shouldGiveUp(everConnected = true, consecutiveFailures = 20, nowMs = deadline - 1, deadlineMs = deadline))
        assertTrue(TunnelReconnect.shouldGiveUp(everConnected = true, consecutiveFailures = 1, nowMs = deadline, deadlineMs = deadline))
    }
}
