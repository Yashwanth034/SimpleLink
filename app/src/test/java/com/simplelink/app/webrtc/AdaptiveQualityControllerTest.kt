package com.simplelink.app.webrtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveQualityControllerTest {
    @Test
    fun severeNetworkDropsHighToLowAfterTwoBadSamples() {
        val controller = AdaptiveQualityController()
        controller.reset(0)

        val severe = NetworkQualitySample(
            availableOutgoingBitrateBps = 400_000,
            roundTripTimeMs = 800.0,
            packetLossFraction = 0.18
        )

        assertNull(controller.observe(severe, 2_000))
        assertEquals(StreamQuality.LOW, controller.observe(severe, 4_000))
        assertEquals(StreamQuality.LOW, controller.currentQuality)
    }

    @Test
    fun moderateCongestionDropsOneStepOnly() {
        val controller = AdaptiveQualityController()
        controller.reset(0)

        val constrained = NetworkQualitySample(
            availableOutgoingBitrateBps = 1_800_000,
            roundTripTimeMs = 320.0,
            packetLossFraction = 0.02
        )

        assertNull(controller.observe(constrained, 2_000))
        assertEquals(StreamQuality.BALANCED, controller.observe(constrained, 4_000))
    }

    @Test
    fun recoveryRequiresSustainedGoodSamplesAndMovesOneStep() {
        val controller = AdaptiveQualityController()
        controller.reset(0)

        val poor = NetworkQualitySample(availableOutgoingBitrateBps = 500_000)
        controller.observe(poor, 2_000)
        assertEquals(StreamQuality.LOW, controller.observe(poor, 4_000))

        val good = NetworkQualitySample(
            availableOutgoingBitrateBps = 5_000_000,
            roundTripTimeMs = 80.0,
            packetLossFraction = 0.005
        )

        assertNull(controller.observe(good, 6_000))
        assertNull(controller.observe(good, 8_000))
        assertNull(controller.observe(good, 10_000))
        assertNull(controller.observe(good, 12_000))
        assertEquals(StreamQuality.BALANCED, controller.observe(good, 14_000))
        assertEquals(StreamQuality.BALANCED, controller.currentQuality)
    }

    @Test
    fun missingStatsDoNotChangeQuality() {
        val controller = AdaptiveQualityController()
        controller.reset(0)

        repeat(10) { index ->
            assertNull(controller.observe(NetworkQualitySample(), (index + 1) * 2_000L))
        }
        assertEquals(StreamQuality.HIGH, controller.currentQuality)
    }

    @Test
    fun bandwidthLimitedSignalRequestsBalancedProfile() {
        val controller = AdaptiveQualityController()
        controller.reset(0)

        val limited = NetworkQualitySample(
            availableOutgoingBitrateBps = 4_000_000,
            roundTripTimeMs = 100.0,
            packetLossFraction = 0.0,
            bandwidthLimited = true
        )

        assertNull(controller.observe(limited, 2_000))
        assertEquals(StreamQuality.BALANCED, controller.observe(limited, 4_000))
    }
}
