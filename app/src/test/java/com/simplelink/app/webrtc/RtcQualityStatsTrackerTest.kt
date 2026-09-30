package com.simplelink.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.webrtc.RTCStats
import org.webrtc.RTCStatsReport

class RtcQualityStatsTrackerTest {
    @Test
    fun selectedCandidateAndVideoSenderStatsAreParsed() {
        val tracker = RtcQualityStatsTracker()
        val sample = tracker.sample(
            report(
                sent = 100,
                lost = 2,
                bitrate = 1_800_000.0,
                candidateRttSeconds = 0.15,
                remoteRttSeconds = 0.20,
                limitation = "bandwidth"
            )
        )

        assertEquals(1_800_000L, sample.availableOutgoingBitrateBps)
        assertEquals(150.0, sample.roundTripTimeMs ?: -1.0, 0.01)
        assertNull(sample.packetLossFraction)
        assertTrue(sample.bandwidthLimited)
    }

    @Test
    fun packetLossUsesRecentDeltaInsteadOfLifetimeRatio() {
        val tracker = RtcQualityStatsTracker()
        tracker.sample(report(sent = 100, lost = 2))
        val sample = tracker.sample(report(sent = 200, lost = 7))

        assertEquals(0.05, sample.packetLossFraction ?: -1.0, 0.0001)
    }

    @Test
    fun resetDropsPreviousPacketCounters() {
        val tracker = RtcQualityStatsTracker()
        tracker.sample(report(sent = 100, lost = 2))
        tracker.reset()
        val sample = tracker.sample(report(sent = 200, lost = 20))

        assertNull(sample.packetLossFraction)
    }

    @Test
    fun unselectedCandidatePairDoesNotOverrideSelectedPath() {
        val tracker = RtcQualityStatsTracker()
        val stats = linkedMapOf(
            "selected" to stat(
                "candidate-pair",
                "selected",
                mapOf(
                    "state" to "succeeded",
                    "nominated" to true,
                    "availableOutgoingBitrate" to 1_500_000.0,
                    "currentRoundTripTime" to 0.12
                )
            ),
            "other" to stat(
                "candidate-pair",
                "other",
                mapOf(
                    "state" to "succeeded",
                    "nominated" to false,
                    "selected" to false,
                    "availableOutgoingBitrate" to 9_000_000.0,
                    "currentRoundTripTime" to 0.01
                )
            )
        )

        val sample = tracker.sample(RTCStatsReport(1_000L, stats))
        assertEquals(1_500_000L, sample.availableOutgoingBitrateBps)
        assertEquals(120.0, sample.roundTripTimeMs ?: -1.0, 0.01)
    }

    private fun report(
        sent: Long,
        lost: Long,
        bitrate: Double = 3_000_000.0,
        candidateRttSeconds: Double = 0.10,
        remoteRttSeconds: Double = 0.11,
        limitation: String = "none"
    ): RTCStatsReport {
        val stats = linkedMapOf(
            "pair" to stat(
                "candidate-pair",
                "pair",
                mapOf(
                    "state" to "succeeded",
                    "nominated" to true,
                    "availableOutgoingBitrate" to bitrate,
                    "currentRoundTripTime" to candidateRttSeconds
                )
            ),
            "outbound" to stat(
                "outbound-rtp",
                "outbound",
                mapOf(
                    "kind" to "video",
                    "packetsSent" to sent,
                    "qualityLimitationReason" to limitation
                )
            ),
            "remote" to stat(
                "remote-inbound-rtp",
                "remote",
                mapOf(
                    "kind" to "video",
                    "packetsLost" to lost,
                    "roundTripTime" to remoteRttSeconds
                )
            )
        )
        return RTCStatsReport(1_000L, stats)
    }

    private fun stat(
        type: String,
        id: String,
        members: Map<String, Any>
    ): RTCStats = RTCStats(1_000L, type, id, members)
}
