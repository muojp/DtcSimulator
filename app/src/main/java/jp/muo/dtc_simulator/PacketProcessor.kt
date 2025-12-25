package jp.muo.dtc_simulator

import android.util.Log
import kotlin.concurrent.Volatile

/**
 * PacketProcessor - Centralized packet processing pipeline
 *
 * 全てのVPN接続タイプ（ServerVPN, LocalVPN）で共通のパケット処理を提供。
 * 以下の機能を統一的に管理:
 * - レイテンシーシミュレーション (delay)
 * - 帯域幅制限 (bandwidth limiting) - 今後実装
 * - パケットロス (packet loss) - 今後実装
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
    private var bandwidthBytesPerSecond: Long = 0 // 0 = unlimited (not yet implemented)

    @Volatile
    private var packetLossRate: Float = 0f // 0.0 - 1.0 (not yet implemented)

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
     * Set packet loss rate
     * @param rate Packet loss probability (0.0 = no loss, 1.0 = drop all)
     * TODO: Phase 2 - Implement packet loss simulation
     */
    fun setPacketLossRate(rate: Float) {
        this.packetLossRate = rate.coerceIn(0f, 1f)
        Log.d(TAG, "Packet loss rate set to: ${this.packetLossRate} (not yet enforced)")
    }

    /**
     * Process an outbound packet (VPN → Network direction)
     * Applies latency simulation and queues the packet for delayed transmission
     *
     * @param data Packet data array
     * @param length Actual length of the packet
     */
    fun processOutboundPacket(data: ByteArray, length: Int) {
        // TODO: Phase 2 - Apply packet loss here
        // if (shouldDropPacket()) return

        outboundDelayManager.addPacket(data, length)
    }

    /**
     * Process an inbound packet (Network → VPN direction)
     * Applies latency simulation and queues the packet for delayed transmission
     *
     * @param data Packet data array
     * @param length Actual length of the packet
     */
    fun processInboundPacket(data: ByteArray, length: Int) {
        // TODO: Phase 2 - Apply packet loss here
        // if (shouldDropPacket()) return

        inboundDelayManager.addPacket(data, length)
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

    // TODO: Phase 2 - Implement packet loss logic
    // private fun shouldDropPacket(): Boolean {
    //     return if (packetLossRate > 0f) {
    //         Random.nextFloat() < packetLossRate
    //     } else {
    //         false
    //     }
    // }

    // TODO: Phase 2 - Implement bandwidth limiting
    // This would require a token bucket or similar rate limiting algorithm
}
