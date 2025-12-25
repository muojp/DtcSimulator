package jp.muo.dtc_simulator

/**
 * NetworkProfile - Configuration for network simulation parameters
 *
 * Supports YAML-like text configuration for:
 * - Delay (latency) with percentile distribution
 * - Packet loss rate
 * - Bandwidth limiting
 *
 * Example YAML configurations:
 * ```
 * delay:
 *   p25: 90
 *   p50: 145
 *   p90: 475
 *   p95: 590
 * loss: 9.0
 * bandwidth: 3072
 * ```
 */
data class NetworkProfile(
    val delay: DelayConfig? = null,
    val loss: LossConfig? = null,
    val bandwidth: BandwidthConfig? = null
) {
    /**
     * Delay configuration
     * Can be:
     * - Single value: delay: 120 -> distributed as up=60%, down=40%
     * - Up/Down split: delay: { up: 70, down: 50 }
     * - Percentile distribution: delay: { p25: 90, p50: 145, p90: 475, p95: 590 }
     */
    data class DelayConfig(
        val value: Int? = null,  // Single value (ms)
        val up: Int? = null,     // Uplink delay (ms)
        val down: Int? = null,   // Downlink delay (ms)
        val p25: PercentileValue? = null,  // 25th percentile
        val p50: PercentileValue? = null,  // 50th percentile (median)
        val p90: PercentileValue? = null,  // 90th percentile
        val p95: PercentileValue? = null   // 95th percentile
    ) {
        /**
         * Get effective uplink and downlink delays
         * Returns Pair(upMs, downMs)
         */
        fun getEffectiveValues(): Pair<Int, Int> {
            return when {
                // Explicit up/down values take priority
                up != null && down != null -> Pair(up, down)

                // Single value: distribute as 60% up, 40% down
                value != null -> {
                    val upMs = (value * 0.6).toInt()
                    val downMs = (value * 0.4).toInt()
                    Pair(upMs, downMs)
                }

                // Use percentile median (p50) if available, otherwise p25
                p50 != null -> p50.getEffectiveValues()
                p25 != null -> p25.getEffectiveValues()

                else -> Pair(0, 0)
            }
        }

        /**
         * Check if percentile distribution is configured
         */
        fun hasPercentiles(): Boolean {
            return p25 != null || p50 != null || p90 != null || p95 != null
        }
    }

    /**
     * Percentile value for delay
     * Can be single value or up/down split
     */
    data class PercentileValue(
        val value: Int? = null,
        val up: Int? = null,
        val down: Int? = null
    ) {
        fun getEffectiveValues(): Pair<Int, Int> {
            return when {
                up != null && down != null -> Pair(up, down)
                value != null -> {
                    val upMs = (value * 0.6).toInt()
                    val downMs = (value * 0.4).toInt()
                    Pair(upMs, downMs)
                }
                else -> Pair(0, 0)
            }
        }
    }

    /**
     * Packet loss configuration
     * Can be:
     * - Single value: loss: 9.0 (split 50/50 between up and down to avoid double-counting)
     * - Up/Down split: loss: { up: 5.0, down: 4.0 }
     */
    data class LossConfig(
        val value: Float? = null,  // Single loss rate (%)
        val up: Float? = null,     // Uplink loss (%)
        val down: Float? = null    // Downlink loss (%)
    ) {
        /**
         * Get effective uplink and downlink loss rates
         * Returns Pair(upPercent, downPercent)
         * Note: For total value, splits 50/50 to prevent effective 2x loss
         */
        fun getEffectiveValues(): Pair<Float, Float> {
            return when {
                up != null && down != null -> Pair(up, down)
                value != null -> {
                    // Split total loss rate evenly between up and down
                    val halfValue = value / 2f
                    Pair(halfValue, halfValue)
                }
                else -> Pair(0f, 0f)
            }
        }
    }

    /**
     * Bandwidth configuration (kbps)
     * Can be:
     * - Single value: bandwidth: 3072 (applies equally to both directions)
     * - Up/Down split: bandwidth: { up: 3072, down: 5120 }
     */
    data class BandwidthConfig(
        val value: Int? = null,  // Single bandwidth (kbps)
        val up: Int? = null,     // Uplink bandwidth (kbps)
        val down: Int? = null    // Downlink bandwidth (kbps)
    ) {
        /**
         * Get effective uplink and downlink bandwidth
         * Returns Pair(upKbps, downKbps)
         */
        fun getEffectiveValues(): Pair<Int, Int> {
            return when {
                up != null && down != null -> Pair(up, down)
                value != null -> Pair(value, value)
                else -> Pair(0, 0)  // 0 = unlimited
            }
        }
    }

    /**
     * Convert to simple parameters for PacketProcessor
     * Returns: Triple(outboundLatency, inboundLatency, lossRate)
     */
    fun toSimpleParams(): Triple<Int, Int, Float> {
        val (outDelay, inDelay) = delay?.getEffectiveValues() ?: Pair(0, 0)
        val (lossUp, lossDown) = loss?.getEffectiveValues() ?: Pair(0f, 0f)
        val averageLoss = (lossUp + lossDown) / 2f

        return Triple(outDelay, inDelay, averageLoss)
    }

    companion object {
        /**
         * Create a default profile with no simulation
         */
        fun default(): NetworkProfile {
            return NetworkProfile()
        }
    }
}
