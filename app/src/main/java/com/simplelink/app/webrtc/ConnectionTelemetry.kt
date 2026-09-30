package com.simplelink.app.webrtc

enum class ConnectionRoute {
    DIRECT,
    RELAY,
    UNKNOWN
}

data class ConnectionTelemetry(
    val controlRttMs: Long? = null,
    val networkRttMs: Double? = null,
    val packetLossFraction: Double? = null,
    val availableOutgoingBitrateBps: Long? = null,
    val quality: StreamQuality? = null,
    val route: ConnectionRoute = ConnectionRoute.UNKNOWN
)

class ControlLatencyTracker {
    private var nextPingId = 1L
    private var pendingPingId: Long? = null
    private var pendingSentAtMs = 0L
    private var smoothedRttMs: Long? = null

    fun reset() {
        nextPingId = 1L
        pendingPingId = null
        pendingSentAtMs = 0L
        smoothedRttMs = null
    }

    fun beginPing(nowMs: Long): Long {
        val id = nextPingId++
        pendingPingId = id
        pendingSentAtMs = nowMs
        return id
    }

    fun completePong(id: Long, nowMs: Long): Long? {
        if (id <= 0L || pendingPingId != id || nowMs < pendingSentAtMs) return null

        val rawRtt = (nowMs - pendingSentAtMs).coerceAtMost(MAX_REASONABLE_RTT_MS)
        pendingPingId = null
        pendingSentAtMs = 0L

        val previous = smoothedRttMs
        val smoothed = if (previous == null) {
            rawRtt
        } else {
            ((previous * 3L) + rawRtt) / 4L
        }
        smoothedRttMs = smoothed
        return smoothed
    }

    companion object {
        private const val MAX_REASONABLE_RTT_MS = 60_000L
    }
}
