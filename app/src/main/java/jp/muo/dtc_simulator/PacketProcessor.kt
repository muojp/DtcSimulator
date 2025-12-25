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
     * Set packet loss rate for both directions
     * @param rate Packet loss probability (0.0 = no loss, 1.0 = drop all)
     */
    fun setPacketLossRate(rate: Float) {
        this.outboundLossRate = rate.coerceIn(0f, 1f)
        this.inboundLossRate = rate.coerceIn(0f, 1f)
        Log.d(TAG, "Packet loss rate set to: ${this.outboundLossRate}")
    }

    /**
     * Set packet loss rate separately for outbound and inbound
     * @param outboundRate Outbound packet loss rate (0.0 - 1.0)
     * @param inboundRate Inbound packet loss rate (0.0 - 1.0)
     */
    fun setPacketLossRate(outboundRate: Float, inboundRate: Float) {
        this.outboundLossRate = outboundRate.coerceIn(0f, 100f) / 100f
        this.inboundLossRate = inboundRate.coerceIn(0f, 100f) / 100f
        Log.d(TAG, "Packet loss rate set: outbound=${this.outboundLossRate}, inbound=${this.inboundLossRate}")
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
        // Apply packet loss
        if (shouldDropPacket(outboundLossRate)) {
            Log.v(TAG, "Dropped outbound packet (loss simulation)")
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
    }

    /**
     * Process an inbound packet (Network → VPN direction)
     * Applies packet loss and latency simulation
     *
     * @param data Packet data array
     * @param length Actual length of the packet
     */
    fun processInboundPacket(data: ByteArray, length: Int) {
        // Apply packet loss
        if (shouldDropPacket(inboundLossRate)) {
            Log.v(TAG, "Dropped inbound packet (loss simulation)")
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

    // TODO: Phase 2 - Implement bandwidth limiting
    // This would require a token bucket or similar rate limiting algorithm
}
