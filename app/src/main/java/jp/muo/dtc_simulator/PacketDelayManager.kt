package jp.muo.dtc_simulator

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * PacketDelayManager - Manages packet delays for network simulation.
 * 
 * Holds packets in a queue and releases them after a specified latency.
 */
class PacketDelayManager {
    companion object {
        private const val TAG = "PacketDelayManager"
    }

    private data class DelayedPacket(
        val data: ByteArray,
        val releaseTime: Long
    ) : Comparable<DelayedPacket> {
        override fun compareTo(other: DelayedPacket): Int {
            return releaseTime.compareTo(other.releaseTime)
        }
    }

    private val queue = PriorityBlockingQueue<DelayedPacket>()
    private val lock = Object()
    
    @Volatile
    private var latencyMs: Int = 0

    /**
     * Set the current latency in milliseconds
     */
    fun setLatency(ms: Int) {
        this.latencyMs = ms
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    /**
     * Add a packet to the delay queue
     */
    fun addPacket(data: ByteArray, length: Int) {
        val now = SystemClock.elapsedRealtime()
        val packetData = data.copyOfRange(0, length)
        
        val releaseTime = if (latencyMs <= 0) now else now + latencyMs
        queue.offer(DelayedPacket(packetData, releaseTime))
        
        Log.v(TAG, "Packet added: size=$length, releaseIn=${releaseTime - now}ms, qSize=${queue.size}")
        
        synchronized(lock) {
            lock.notifyAll()
        }
    }

    /**
     * Get the next packet that is ready to be released.
     * Returns null if no packet is ready.
     */
    fun pollReadyPacket(): ByteArray? {
        val now = SystemClock.elapsedRealtime()
        val head = queue.peek()
        if (head != null && head.releaseTime <= now) {
            val packet = queue.poll()
            if (packet != null) {
                Log.v(TAG, "Packet released: size=${packet.data.size}, delay=${now - packet.releaseTime}ms")
                return packet.data
            }
        }
        return null
    }

    /**
     * Wait and poll for the next ready packet.
     * @param maxWaitMs Maximum time to wait in milliseconds
     * @return The packet data, or null if no packet became ready within the timeout
     */
    fun pollReadyPacketBlocking(maxWaitMs: Long): ByteArray? {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            val head = queue.peek()
            
            if (head != null) {
                if (head.releaseTime <= now) {
                    return queue.poll()?.data
                } else {
                    val waitTime = (head.releaseTime - now).coerceAtMost(maxWaitMs)
                    if (waitTime > 0) {
                        try {
                            lock.wait(waitTime)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            } else {
                try {
                    lock.wait(maxWaitMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        val result = pollReadyPacket()
        if (result == null && queue.isNotEmpty()) {
            val now = SystemClock.elapsedRealtime()
            val head = queue.peek()
            if (head != null) {
                Log.d(TAG, "pollReadyPacketBlocking returned null but queue not empty. Next in ${head.releaseTime - now}ms")
            }
        }
        return result
    }

    /**
     * Check if there are any packets in the queue
     */
    fun hasPackets(): Boolean = !queue.isEmpty()

    /**
     * Get the number of packets currently in the queue
     */
    val queueSize: Int
        get() = queue.size

    /**
     * Get the time until the next packet is ready, in milliseconds.
     * Returns 0 if a packet is ready now, or a large value if the queue is empty.
     */
    fun getTimeToNextReady(): Long {
        val now = SystemClock.elapsedRealtime()
        val head = queue.peek() ?: return 1000L
        val diff = head.releaseTime - now
        return if (diff < 0) 0 else diff
    }
}