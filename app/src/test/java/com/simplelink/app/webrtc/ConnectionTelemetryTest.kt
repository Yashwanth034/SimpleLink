package com.simplelink.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionTelemetryTest {
    @Test
    fun matchingPongReturnsSmoothedRoundTripTime() {
        val tracker = ControlLatencyTracker()
        val first = tracker.beginPing(1_000)
        assertEquals(80L, tracker.completePong(first, 1_080))

        val second = tracker.beginPing(2_000)
        assertEquals(85L, tracker.completePong(second, 2_100))
    }

    @Test
    fun staleOrUnknownPongIsIgnored() {
        val tracker = ControlLatencyTracker()
        val active = tracker.beginPing(1_000)

        assertNull(tracker.completePong(active + 1, 1_050))
        assertEquals(60L, tracker.completePong(active, 1_060))
    }

    @Test
    fun resetForgetsOutstandingPing() {
        val tracker = ControlLatencyTracker()
        val id = tracker.beginPing(1_000)
        tracker.reset()

        assertNull(tracker.completePong(id, 1_040))
    }
}
