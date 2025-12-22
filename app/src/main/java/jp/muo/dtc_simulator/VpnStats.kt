package jp.muo.dtc_simulator

/**
 * VPN traffic statistics
 */
class VpnStats {
    @get:Synchronized
    var sentBytes: Long = 0
        private set

    @get:Synchronized
    var receivedBytes: Long = 0
        private set

    @get:Synchronized
    var sentPackets: Long = 0
        private set

    @get:Synchronized
    var receivedPackets: Long = 0
        private set

    @get:Synchronized
    var outgoingBufferBytes: Long = 0
        private set

    @get:Synchronized
    var incomingBufferBytes: Long = 0
        private set

    @Synchronized
    fun recordSent(bytes: Int) {
        sentBytes += bytes.toLong()
        sentPackets++
    }

    @Synchronized
    fun recordReceived(bytes: Int) {
        receivedBytes += bytes.toLong()
        receivedPackets++
    }

    @Synchronized
    fun updateBufferBytes(outgoing: Long, incoming: Long) {
        outgoingBufferBytes = outgoing
        incomingBufferBytes = incoming
    }
}
