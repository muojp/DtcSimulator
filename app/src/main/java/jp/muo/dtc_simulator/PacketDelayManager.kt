package jp.muo.dtc_simulator

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue

/**
 * PacketDelayManager - Manages packet delays for network simulation.
 * 
 * Holds packets in a queue and releases them after a specified latency.
 */
class PacketDelayManager {
    private data class DelayedPacket(
        val data: ByteArray,
        val arrivalTime: Long,
        val sequenceNumber: Long
    ) : Comparable<DelayedPacket> {
        override fun compareTo(other: DelayedPacket): Int {
            val timeCompare = arrivalTime.compareTo(other.arrivalTime)
            if (timeCompare != 0) return timeCompare
            return sequenceNumber.compareTo(other.sequenceNumber)
        }
    }

    private val queue = PriorityBlockingQueue<DelayedPacket>()
    private var nextSequenceNumber = 0L
    @Volatile
    private var latencyMs: Int = 0

    /**
     * Set the current latency in milliseconds
     */
    fun setLatency(ms: Int) {
        this.latencyMs = ms
    }

    /**
     * Add a packet to the delay queue
     */
    @Synchronized
    fun addPacket(data: ByteArray, length: Int) {
        val now = System.currentTimeMillis()
        val packetData = data.copyOfRange(0, length)
        queue.offer(DelayedPacket(packetData, now, nextSequenceNumber++))
    }

    /**
     * Get the next packet that is ready to be released.
     * Returns null if no packet is ready.
     */
    fun pollReadyPacket(): ByteArray? {
        val now = System.currentTimeMillis()
        val head = queue.peek()
        if (head != null && (now - head.arrivalTime) >= latencyMs) {
            return queue.poll()?.data
        }
        return null
    }

    /**
     * Check if there are any packets in the queue
     */
    fun hasPackets(): Boolean = !queue.isEmpty()

    /**
     * Get the time until the next packet is ready, in milliseconds.
     * Returns 0 if a packet is ready now, or a large value if the queue is empty.
     */
    fun getTimeToNextReady(): Long {
        val now = System.currentTimeMillis()
        val head = queue.peek() ?: return 1000L
        val readyTime = head.arrivalTime + latencyMs
        val diff = readyTime - now
        return if (diff < 0) 0 else diff
    }

    /**
     * Get the total number of bytes currently queued.
     */
    fun getQueuedBytes(): Long {
        return queue.sumOf { it.data.size.toLong() }
    }
}
