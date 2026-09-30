package com.simplelink.app.webrtc

import org.webrtc.RTCStatsReport

class RtcQualityStatsTracker {
    private var previousPacketsSent: Long? = null
    private var previousPacketsLost: Long? = null

    fun reset() {
        previousPacketsSent = null
        previousPacketsLost = null
    }

    fun sample(report: RTCStatsReport): NetworkQualitySample {
        var availableOutgoingBitrate: Long? = null
        var roundTripTimeMs: Double? = null
        var packetsSent: Long? = null
        var packetsLost: Long? = null
        var bandwidthLimited = false

        report.statsMap.values.forEach { stat ->
            val members = stat.members
            when (stat.type) {
                "candidate-pair" -> {
                    val state = members["state"]?.toString()
                    val nominated = members["nominated"] as? Boolean ?: false
                    val selected = members["selected"] as? Boolean ?: false
                    if (state == "succeeded" && (nominated || selected)) {
                        (members["availableOutgoingBitrate"] as? Number)?.toLong()?.let { value ->
                            availableOutgoingBitrate = maxOf(availableOutgoingBitrate ?: 0L, value)
                        }
                        (members["currentRoundTripTime"] as? Number)?.toDouble()?.let { seconds ->
                            val ms = seconds * 1_000.0
                            roundTripTimeMs = minOf(roundTripTimeMs ?: ms, ms)
                        }
                    }
                }

                "outbound-rtp" -> {
                    val mediaType = members["kind"]?.toString()
                        ?: members["mediaType"]?.toString()
                    if (mediaType == "video") {
                        (members["packetsSent"] as? Number)?.toLong()?.let { value ->
                            packetsSent = maxOf(packetsSent ?: 0L, value)
                        }
                        if (members["qualityLimitationReason"]?.toString() == "bandwidth") {
                            bandwidthLimited = true
                        }
                    }
                }

                "remote-inbound-rtp" -> {
                    val mediaType = members["kind"]?.toString()
                        ?: members["mediaType"]?.toString()
                    if (mediaType == "video") {
                        (members["packetsLost"] as? Number)?.toLong()?.let { value ->
                            packetsLost = maxOf(packetsLost ?: 0L, value)
                        }
                        (members["roundTripTime"] as? Number)?.toDouble()?.let { seconds ->
                            val ms = seconds * 1_000.0
                            roundTripTimeMs = minOf(roundTripTimeMs ?: ms, ms)
                        }
                    }
                }
            }
        }

        val sentNow = packetsSent
        val lostNow = packetsLost
        val sentBefore = previousPacketsSent
        val lostBefore = previousPacketsLost
        val lossFraction = if (
            sentNow != null &&
            lostNow != null &&
            sentBefore != null &&
            lostBefore != null
        ) {
            val sentDelta = sentNow - sentBefore
            val lostDelta = lostNow - lostBefore
            if (sentDelta > 0 && lostDelta >= 0) {
                (lostDelta.toDouble() / sentDelta.toDouble()).coerceIn(0.0, 1.0)
            } else {
                null
            }
        } else {
            null
        }

        if (sentNow != null) previousPacketsSent = sentNow
        if (lostNow != null) previousPacketsLost = lostNow

        return NetworkQualitySample(
            availableOutgoingBitrateBps = availableOutgoingBitrate,
            roundTripTimeMs = roundTripTimeMs,
            packetLossFraction = lossFraction,
            bandwidthLimited = bandwidthLimited
        )
    }
}
