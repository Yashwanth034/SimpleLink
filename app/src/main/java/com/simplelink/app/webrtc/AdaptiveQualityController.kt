package com.simplelink.app.webrtc

enum class StreamQuality(
    val longEdge: Int,
    val fps: Int,
    val maxBitrateBps: Int
) {
    HIGH(longEdge = 1600, fps = 30, maxBitrateBps = 3_200_000),
    BALANCED(longEdge = 1280, fps = 24, maxBitrateBps = 2_000_000),
    LOW(longEdge = 960, fps = 18, maxBitrateBps = 1_100_000),
    RECOVERY(longEdge = 720, fps = 12, maxBitrateBps = 650_000)
}

data class NetworkQualitySample(
    val availableOutgoingBitrateBps: Long? = null,
    val roundTripTimeMs: Double? = null,
    val packetLossFraction: Double? = null,
    val bandwidthLimited: Boolean = false
)

class AdaptiveQualityController(
    initialQuality: StreamQuality = StreamQuality.HIGH
) {
    var currentQuality: StreamQuality = initialQuality
        private set

    private var badSamples = 0
    private var goodSamples = 0
    private var lastChangeAtMs = 0L

    fun reset(nowMs: Long = 0L) {
        currentQuality = StreamQuality.HIGH
        badSamples = 0
        goodSamples = 0
        lastChangeAtMs = nowMs
    }

    fun observe(sample: NetworkQualitySample, nowMs: Long): StreamQuality? {
        val recommended = recommend(sample) ?: return null

        if (recommended.ordinal > currentQuality.ordinal) {
            goodSamples = 0
            badSamples += 1
            if (badSamples < BAD_SAMPLES_TO_DOWNGRADE) return null
            if (nowMs - lastChangeAtMs < MIN_DOWNGRADE_DWELL_MS) return null

            badSamples = 0
            val step = if (
                recommended == StreamQuality.RECOVERY &&
                currentQuality == StreamQuality.HIGH
            ) {
                2
            } else {
                1
            }
            val targetOrdinal = minOf(
                currentQuality.ordinal + step,
                recommended.ordinal,
                StreamQuality.RECOVERY.ordinal
            )
            return changeTo(StreamQuality.entries[targetOrdinal], nowMs)
        }

        if (recommended.ordinal < currentQuality.ordinal) {
            badSamples = 0
            goodSamples += 1
            if (goodSamples < GOOD_SAMPLES_TO_UPGRADE) return null
            if (nowMs - lastChangeAtMs < MIN_UPGRADE_DWELL_MS) return null

            goodSamples = 0
            val targetOrdinal = maxOf(
                currentQuality.ordinal - 1,
                recommended.ordinal,
                StreamQuality.HIGH.ordinal
            )
            return changeTo(StreamQuality.entries[targetOrdinal], nowMs)
        }

        badSamples = 0
        goodSamples = 0
        return null
    }

    internal fun recommend(sample: NetworkQualitySample): StreamQuality? {
        val bitrate = sample.availableOutgoingBitrateBps
        val rtt = sample.roundTripTimeMs
        val loss = sample.packetLossFraction?.coerceIn(0.0, 1.0)
        val hasMetric = bitrate != null || rtt != null || loss != null || sample.bandwidthLimited
        if (!hasMetric) return null

        if (
            (bitrate != null && bitrate < 700_000L) ||
            (rtt != null && rtt > 650.0) ||
            (loss != null && loss > 0.12)
        ) {
            return StreamQuality.RECOVERY
        }

        if (
            (bitrate != null && bitrate < 1_200_000L) ||
            (rtt != null && rtt > 450.0) ||
            (loss != null && loss > 0.07)
        ) {
            return StreamQuality.LOW
        }

        if (
            sample.bandwidthLimited ||
            (bitrate != null && bitrate < 2_200_000L) ||
            (rtt != null && rtt > 280.0) ||
            (loss != null && loss > 0.03)
        ) {
            return StreamQuality.BALANCED
        }

        return StreamQuality.HIGH
    }

    private fun changeTo(target: StreamQuality, nowMs: Long): StreamQuality? {
        if (target == currentQuality) return null
        currentQuality = target
        lastChangeAtMs = nowMs
        return target
    }

    companion object {
        private const val BAD_SAMPLES_TO_DOWNGRADE = 2
        private const val GOOD_SAMPLES_TO_UPGRADE = 5
        private const val MIN_DOWNGRADE_DWELL_MS = 3_000L
        private const val MIN_UPGRADE_DWELL_MS = 10_000L
    }
}
