package jp.muo.dtc_simulator

import android.util.Log
import kotlin.concurrent.Volatile
import kotlin.random.Random

/**
 * PacketProcessor - Centralized packet processing pipeline
 *
 * 全てのVPN接続タイプ（ServerVPN, LocalVPN）で共通のパケット処理を提供。
 * 以下の機能を統一的に管理:
 * - レイテンシーシミュレーション (delay) with percentile distribution
 * - パケットロス (packet loss)
 * - 帯域幅制限 (bandwidth limiting) - 今後実装
 */
class PacketProcessor {
    companion object {
        private const val TAG = "PacketProcessor"
    }

    // Latency managers for outbound (VPN → Network) and inbound (Network → VPN)
    private val outboundDelayManager = PacketDelayManager()
    private val inboundDelayManager = PacketDelayManager()

    /**
     * VPN traffic statistics
     */
    val stats: VpnStats = VpnStats()

    // Configuration parameters
    @Volatile
    private var outboundLatencyMs: Int = 0

    @Volatile
    private var inboundLatencyMs: Int = 0

    @Volatile
    private var outboundLossRate: Float = 0f // 0.0 - 1.0

    @Volatile
    private var inboundLossRate: Float = 0f // 0.0 - 1.0

    @Volatile
    private var bandwidthBytesPerSecond: Long = 0 // 0 = unlimited (not yet implemented)

    @Volatile
    private var networkProfile: NetworkProfile? = null

    // Random instance for packet loss simulation
    private val random = Random.Default

    // Packet loss statistics
    @Volatile
    private var outboundPacketsTotal = 0L
    @Volatile
    private var outboundPacketsDropped = 0L
    @Volatile
    private var inboundPacketsTotal = 0L
    @Volatile
    private var inboundPacketsDropped = 0L

    private var lastStatsLogTime = 0L
    private val STATS_LOG_INTERVAL_MS = 10000L // Log stats every 10 seconds

    /**
     * Update latency settings for outbound and inbound directions
     * @param outboundMs Latency in milliseconds for outbound packets (VPN → Network)
     * @param inboundMs Latency in milliseconds for inbound packets (Network → VPN)
     */
    fun setLatency(outboundMs: Int, inboundMs: Int) {
        this.outboundLatencyMs = outboundMs
        this.inboundLatencyMs = inboundMs
        outboundDelayManager.setLatency(outboundMs)
        inboundDelayManager.setLatency(inboundMs)
        Log.d(TAG, "Latency updated: outbound=${outboundMs}ms, inbound=${inboundMs}ms")
    }

    /**
     * Set bandwidth limit (bytes per second)
     * @param bytesPerSecond Maximum throughput in bytes/sec. 0 = unlimited
     * TODO: Phase 2 - Implement bandwidth throttling
     */
    fun setBandwidth(bytesPerSecond: Long) {
        this.bandwidthBytesPerSecond = bytesPerSecond
        Log.d(TAG, "Bandwidth limit set to: $bytesPerSecond bytes/sec (not yet enforced)")
    }

    /**
     * Set packet loss rate for both directions (splits evenly between up/down)
     * @param rate Packet loss probability (0.0 = no loss, 1.0 = drop all)
     * Note: Total loss rate is split 50/50 between outbound and inbound to avoid double-counting
     */
    fun setPacketLossRate(rate: Float) {
        // Split the total loss rate evenly between outbound and inbound
        // This prevents effective loss from being ~2x the configured value
        val halfRate = (rate.coerceIn(0f, 1f)) / 2f
        this.outboundLossRate = halfRate
        this.inboundLossRate = halfRate
        Log.i(TAG, "Packet loss rate set to: ${rate * 100}% total (outbound=${this.outboundLossRate * 100}%, inbound=${this.inboundLossRate * 100}%)")

        // Reset statistics when loss rate changes
        resetLossStatistics()
    }

    /**
     * Set packet loss rate separately for outbound and inbound (for advanced profiles)
     * @param outboundRate Outbound packet loss rate in percentage (0.0 - 100.0)
     * @param inboundRate Inbound packet loss rate in percentage (0.0 - 100.0)
     */
    fun setPacketLossRate(outboundRate: Float, inboundRate: Float) {
        this.outboundLossRate = outboundRate.coerceIn(0f, 100f) / 100f
        this.inboundLossRate = inboundRate.coerceIn(0f, 100f) / 100f
        val totalRate = (this.outboundLossRate + this.inboundLossRate) * 50f
        Log.i(TAG, "Packet loss rate set: ~${totalRate}% total (outbound=${this.outboundLossRate * 100}%, inbound=${this.inboundLossRate * 100}%)")

        // Reset statistics when loss rate changes
        resetLossStatistics()
    }

    /**
     * Set network profile for advanced configuration
     * @param profile NetworkProfile with percentile delays, loss rates, bandwidth limits
     */
    fun setNetworkProfile(profile: NetworkProfile) {
        this.networkProfile = profile

        // Apply basic latency from profile
        val (outMs, inMs) = profile.delay?.getEffectiveValues() ?: Pair(0, 0)
        setLatency(outMs, inMs)

        // Apply loss rates
        val (lossUp, lossDown) = profile.loss?.getEffectiveValues() ?: Pair(0f, 0f)
        setPacketLossRate(lossUp, lossDown)

        // Apply bandwidth
        val (bwUp, bwDown) = profile.bandwidth?.getEffectiveValues() ?: Pair(0, 0)
        if (bwUp > 0 || bwDown > 0) {
            // Convert kbps to bytes/sec (average of up and down for now)
            val avgBandwidthKbps = (bwUp + bwDown) / 2
            setBandwidth((avgBandwidthKbps * 1024L) / 8)
        }

        Log.d(TAG, "Network profile applied")
    }

    /**
     * Process an outbound packet (VPN → Network direction)
     * Applies packet loss and latency simulation
     *
     * @param data Packet data array
     * @param length Actual length of the packet
     */
    fun processOutboundPacket(data: ByteArray, length: Int) {
        outboundPacketsTotal++

        // Apply packet loss
        if (shouldDropPacket(outboundLossRate)) {
            outboundPacketsDropped++
            logLossStatistics()
            return
        }

        // Apply latency with percentile distribution if configured
        val delayMs = calculateDelayMs(isOutbound = true)
        if (delayMs != outboundLatencyMs) {
            // Temporarily adjust delay manager for this packet
            outboundDelayManager.addPacketWithCustomDelay(data, length, delayMs)
        } else {
            outboundDelayManager.addPacket(data, length)
        }

        logLossStatistics()
    }

    /**
     * Process an inbound packet (Network → VPN direction)
     * Applies packet loss and latency simulation
     *
     * @param data Packet data array
     * @param length Actual length of the packet
     */
    fun processInboundPacket(data: ByteArray, length: Int) {
        inboundPacketsTotal++

        // Apply packet loss
        if (shouldDropPacket(inboundLossRate)) {
            inboundPacketsDropped++
            logLossStatistics()
            return
        }

        // Apply latency with percentile distribution if configured
        val delayMs = calculateDelayMs(isOutbound = false)
        if (delayMs != inboundLatencyMs) {
            // Temporarily adjust delay manager for this packet
            inboundDelayManager.addPacketWithCustomDelay(data, length, delayMs)
        } else {
            inboundDelayManager.addPacket(data, length)
        }

        logLossStatistics()
    }

    /**
     * Poll for the next ready outbound packet
     * Blocks for up to timeoutMs waiting for a packet to become ready
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return Packet data, or null if no packet is ready within timeout
     */
    fun pollReadyOutboundPacket(timeoutMs: Long): ByteArray? {
        return outboundDelayManager.pollReadyPacketBlocking(timeoutMs)
    }

    /**
     * Poll for the next ready inbound packet
     * Blocks for up to timeoutMs waiting for a packet to become ready
     *
     * @param timeoutMs Maximum time to wait in milliseconds
     * @return Packet data, or null if no packet is ready within timeout
     */
    fun pollReadyInboundPacket(timeoutMs: Long): ByteArray? {
        return inboundDelayManager.pollReadyPacketBlocking(timeoutMs)
    }

    /**
     * Update buffer statistics
     */
    fun updateBufferStats() {
        stats.updateBufferStats(outboundDelayManager.queueSize, inboundDelayManager.queueSize)
    }

    /**
     * Get current outbound queue size
     */
    val outboundQueueSize: Int
        get() = outboundDelayManager.queueSize

    /**
     * Get current inbound queue size
     */
    val inboundQueueSize: Int
        get() = inboundDelayManager.queueSize

    /**
     * Record sent bytes (outbound traffic)
     */
    fun recordSent(bytes: Int) {
        stats.recordSent(bytes)
    }

    /**
     * Record received bytes (inbound traffic)
     */
    fun recordReceived(bytes: Int) {
        stats.recordReceived(bytes)
    }

    /**
     * Determine if a packet should be dropped based on loss rate
     */
    private fun shouldDropPacket(lossRate: Float): Boolean {
        return if (lossRate > 0f) {
            random.nextFloat() < lossRate
        } else {
            false
        }
    }

    /**
     * Calculate delay for a packet based on percentile distribution
     * @param isOutbound True for outbound, false for inbound
     * @return Delay in milliseconds
     */
    private fun calculateDelayMs(isOutbound: Boolean): Int {
        val profile = networkProfile ?: return if (isOutbound) outboundLatencyMs else inboundLatencyMs
        val delayConfig = profile.delay ?: return if (isOutbound) outboundLatencyMs else inboundLatencyMs

        // If percentiles are not configured, use simple values
        if (!delayConfig.hasPercentiles()) {
            return if (isOutbound) outboundLatencyMs else inboundLatencyMs
        }

        // Generate random percentile (0.0 - 1.0)
        val percentile = random.nextFloat()

        // Map percentile to delay value
        return when {
            percentile <= 0.25f -> {
                val (up, down) = delayConfig.p25?.getEffectiveValues() ?: Pair(0, 0)
                if (isOutbound) up else down
            }
            percentile <= 0.50f -> {
                val (up, down) = delayConfig.p50?.getEffectiveValues() ?: Pair(0, 0)
                if (isOutbound) up else down
            }
            percentile <= 0.90f -> {
                val (up, down) = delayConfig.p90?.getEffectiveValues() ?: Pair(0, 0)
                if (isOutbound) up else down
            }
            else -> {
                val (up, down) = delayConfig.p95?.getEffectiveValues() ?: Pair(0, 0)
                if (isOutbound) up else down
            }
        }
    }

    /**
     * Reset packet loss statistics
     */
    private fun resetLossStatistics() {
        outboundPacketsTotal = 0
        outboundPacketsDropped = 0
        inboundPacketsTotal = 0
        inboundPacketsDropped = 0
        lastStatsLogTime = System.currentTimeMillis()
    }

    /**
     * Log packet loss statistics periodically
     */
    private fun logLossStatistics() {
        val now = System.currentTimeMillis()
        if (now - lastStatsLogTime >= STATS_LOG_INTERVAL_MS) {
            val outboundActualRate = if (outboundPacketsTotal > 0) {
                (outboundPacketsDropped * 100.0 / outboundPacketsTotal)
            } else 0.0

            val inboundActualRate = if (inboundPacketsTotal > 0) {
                (inboundPacketsDropped * 100.0 / inboundPacketsTotal)
            } else 0.0

            val totalPackets = outboundPacketsTotal + inboundPacketsTotal
            val totalDropped = outboundPacketsDropped + inboundPacketsDropped
            val totalActualRate = if (totalPackets > 0) {
                (totalDropped * 100.0 / totalPackets)
            } else 0.0

            val totalTargetRate = (this.outboundLossRate + this.inboundLossRate) * 100f

            Log.i(TAG, "=== PACKET LOSS STATISTICS ===")
            Log.i(TAG, "Total: ${totalDropped}/${totalPackets} dropped (${String.format("%.2f", totalActualRate)}% actual, ${String.format("%.1f", totalTargetRate)}% target)")
            Log.i(TAG, "  Outbound: ${outboundPacketsDropped}/${outboundPacketsTotal} (${String.format("%.2f", outboundActualRate)}% actual, ${String.format("%.1f", this.outboundLossRate * 100)}% target)")
            Log.i(TAG, "  Inbound: ${inboundPacketsDropped}/${inboundPacketsTotal} (${String.format("%.2f", inboundActualRate)}% actual, ${String.format("%.1f", this.inboundLossRate * 100)}% target)")
            Log.i(TAG, "==============================")

            lastStatsLogTime = now
        }
    }

    // TODO: Phase 2 - Implement bandwidth limiting
    // This would require a token bucket or similar rate limiting algorithm
}
