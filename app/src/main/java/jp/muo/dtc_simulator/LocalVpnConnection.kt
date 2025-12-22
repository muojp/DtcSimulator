package jp.muo.dtc_simulator

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.Volatile

/**
 * LocalVpnConnection - ローカルVPNパケット転送
 *
 * VPNインターフェースからパケットを読み取り、実際のネットワークに転送します。
 * UDP、TCP、ICMPプロトコルをサポートします。
 */
class LocalVpnConnection(
    private val vpnService: VpnService,
    private val vpnInterface: ParcelFileDescriptor
) : Runnable {
    @Volatile
    private var running = true

    private var selector: Selector? = null

    // Latency managers
    private val outboundDelayManager = PacketDelayManager()
    private val inboundDelayManager = PacketDelayManager()

    /**
     * VPN traffic statistics
     */
    val stats: VpnStats = VpnStats()

    /**
     * Update latency settings
     */
    fun updateLatency(outboundMs: Int, inboundMs: Int) {
        outboundDelayManager.setLatency(outboundMs)
        inboundDelayManager.setLatency(inboundMs)
        Log.d(TAG, "Latency updated in LocalVpnConnection: out=$outboundMs, in=$inboundMs")
    }

    // Session management
    private data class UdpSession(
        val srcAddress: InetAddress,
        val srcPort: Int,
        val destAddress: InetAddress,
        val destPort: Int,
        val channel: DatagramChannel,
        var lastActive: Long = System.currentTimeMillis()
    )

    private data class TcpSession(
        val srcAddress: InetAddress,
        val srcPort: Int,
        val destAddress: InetAddress,
        val destPort: Int,
        var channel: SocketChannel? = null,
        var state: TcpState = TcpState.LISTEN,
        var mySequenceNum: Long = 0,
        var theirSequenceNum: Long = 0,
        var lastActive: Long = System.currentTimeMillis(),
        val outBuffer: ConcurrentLinkedQueue<ByteBuffer> = ConcurrentLinkedQueue()
    )

    enum class TcpState {
        LISTEN, SYN_RECEIVED, ESTABLISHED, CLOSE_WAIT, LAST_ACK, CLOSED
    }

    private val udpSessions = ConcurrentHashMap<String, UdpSession>()
    private val tcpSessions = ConcurrentHashMap<String, TcpSession>()
    private val pendingRegistrations = ConcurrentLinkedQueue<Any>() // UdpSession or TcpSession

    override fun run() {
        Log.i(TAG, "LocalVpnConnection starting")

        try {
            selector = Selector.open()
            val inputStream = FileInputStream(vpnInterface.fileDescriptor)
            val outputStream = FileOutputStream(vpnInterface.fileDescriptor)

            // 1. Outgoing loop (VPN -> Network)
            val outgoingReaderThread = Thread({
                try {
                    val packet = ByteBuffer.allocate(MAX_PACKET_SIZE)
                    while (running) {
                        val length = inputStream.read(packet.array())
                        if (length > 0) {
                            outboundDelayManager.addPacket(packet.array(), length)
                        } else if (length < 0) {
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Error in outgoing reader thread", e)
                }
            }, "LocalVpnOutgoingReader")
            outgoingReaderThread.start()

            val outgoingWriterThread = Thread({
                try {
                    while (running) {
                        val data = outboundDelayManager.pollReadyPacket()
                        if (data != null) {
                            forwardToNetwork(ByteBuffer.wrap(data))
                        } else {
                            Thread.sleep(outboundDelayManager.getTimeToNextReady().coerceIn(1, 100))
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Error in outgoing writer thread", e)
                }
            }, "LocalVpnOutgoingWriter")
            outgoingWriterThread.start()

            // 2. Incoming loop (Network -> VPN)
            val incomingWriterThread = Thread({
                try {
                    while (running) {
                        val data = inboundDelayManager.pollReadyPacket()
                        if (data != null) {
                            outputStream.write(data)
                        } else {
                            Thread.sleep(inboundDelayManager.getTimeToNextReady().coerceIn(1, 100))
                        }
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Error in incoming writer thread", e)
                }
            }, "LocalVpnIncomingWriter")
            incomingWriterThread.start()

            var lastCleanupTime = System.currentTimeMillis()
            var lastStatsUpdate = System.currentTimeMillis()
            while (running) {
                // Process pending registrations
                while (pendingRegistrations.isNotEmpty()) {
                    val session = pendingRegistrations.poll()
                    if (session is UdpSession && session.channel.isOpen) {
                        session.channel.register(selector, SelectionKey.OP_READ, session)
                    } else if (session is TcpSession && session.channel?.isOpen == true) {
                        session.channel?.register(selector, SelectionKey.OP_READ or SelectionKey.OP_CONNECT, session)
                    }
                }

                // Wait for data with timeout
                val selected = try {
                    selector!!.select(500)
                } catch (e: Exception) {
                    Log.e(TAG, "Selector error", e)
                    break
                }

                val now = System.currentTimeMillis()

                if (selected > 0) {
                    val keys = selector!!.selectedKeys().iterator()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        keys.remove()

                        if (!key.isValid) continue

                        if (key.isReadable) {
                            processReadableKey(key, outputStream)
                        } else if (key.isConnectable) {
                            processConnectableKey(key, outputStream)
                        }
                    }
                }

                // Update buffer statistics periodically
                if (now - lastStatsUpdate > 500) {
                    stats.updateBufferBytes(
                        outboundDelayManager.getQueuedBytes(),
                        inboundDelayManager.getQueuedBytes()
                    )
                    lastStatsUpdate = now
                }

                // Periodic cleanup
                if (now - lastCleanupTime > 30000) {
                    cleanupIdleSessions(now)
                    lastCleanupTime = now
                }
            }
        } catch (e: Exception) {
            if (running) Log.e(TAG, "Error in forwarding loop", e)
        } finally {
            cleanup()
        }
    }

    private fun processReadableKey(key: SelectionKey, outputStream: FileOutputStream) {
        val attachment = key.attachment()
        if (attachment is UdpSession) {
            val channel = key.channel() as DatagramChannel
            val responseBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE)
            try {
                val remoteAddress = channel.receive(responseBuffer)
                if (remoteAddress != null && responseBuffer.position() > 0) {
                    responseBuffer.flip()
                    stats.recordReceived(responseBuffer.limit())
                    sendResponseToVpn(attachment, responseBuffer)
                }
            } catch (e: IOException) {
                Log.e(TAG, "Error receiving UDP from network", e)
                key.cancel()
                channel.close()
                udpSessions.values.remove(attachment)
            }
        } else if (attachment is TcpSession) {
            handleTcpRead(attachment, key.channel() as SocketChannel, outputStream)
        }
    }

    private fun processConnectableKey(key: SelectionKey, outputStream: FileOutputStream) {
        val session = key.attachment() as? TcpSession ?: return
        val channel = key.channel() as SocketChannel
        try {
            if (channel.finishConnect()) {
                Log.d(TAG, "TCP connected to network: ${session.destAddress}:${session.destPort}")
                session.state = TcpState.ESTABLISHED
                key.interestOps(SelectionKey.OP_READ)
                
                // Flush buffered data
                while (session.outBuffer.isNotEmpty()) {
                    val buffer = session.outBuffer.poll()
                    if (buffer != null) {
                        channel.write(buffer)
                        Log.v(TAG, "Flushed buffered TCP data: ${buffer.remaining()} bytes")
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "TCP connection failed to ${session.destAddress}:${session.destPort}", e)
            key.cancel()
            channel.close()
            tcpSessions.values.remove(session)
        }
    }

    private fun forwardToNetwork(packet: ByteBuffer) {
        if (packet.limit() < 20) return

        val versionAndIHL = packet.get(0).toInt() and 0xFF
        val version = versionAndIHL shr 4
        if (version != 4) return

        val protocol = packet.get(9).toInt() and 0xFF
        when (protocol) {
            17 -> forwardUdpPacket(packet)
            6 -> forwardTcpPacket(packet)
            1 -> forwardIcmpPacket(packet)
            else -> Log.v(TAG, "Unsupported protocol: $protocol")
        }
    }

    private fun forwardIcmpPacket(packet: ByteBuffer) {
        val versionAndIHL = packet.get(0).toInt() and 0xFF
        val ipHeaderLength = (versionAndIHL and 0x0F) * 4
        
        // Dest IP
        val destIpBytes = ByteArray(4)
        packet.position(16)
        packet.get(destIpBytes)
        val destAddress = InetAddress.getByAddress(destIpBytes)

        // ICMP Type
        packet.position(ipHeaderLength)
        val type = packet.get().toInt() and 0xFF
        
        if (type == 8) { // Echo Request
            Log.d(TAG, "ICMP Echo Request to $destAddress")
            // とりあえずローカルでEcho Replyを返す
            sendIcmpEchoReply(packet)
        }
    }

    private fun sendIcmpEchoReply(requestPacket: ByteBuffer) {
        val size = requestPacket.limit()
        val reply = ByteBuffer.allocate(size)
        
        // Copy IP header but swap src/dest
        reply.put(requestPacket.array(), 0, 20)
        System.arraycopy(requestPacket.array(), 16, reply.array(), 12, 4) // Src <- Dest
        System.arraycopy(requestPacket.array(), 12, reply.array(), 16, 4) // Dest <- Src
        
        reply.putShort(10, 0.toShort()) // Clear IP checksum
        reply.putShort(10, calculateChecksum(reply.array(), 0, 20))
        
        // ICMP Header (Type 0 = Echo Reply)
        reply.position(20)
        reply.put(0.toByte()) // Type
        reply.put(requestPacket.get(21)) // Code
        reply.putShort(0.toShort()) // Checksum
        reply.put(requestPacket.array(), 24, size - 24) // Rest of ICMP
        
        // Calculate ICMP Checksum
        reply.putShort(22, calculateChecksum(reply.array(), 20, size - 20))
        
        inboundDelayManager.addPacket(reply.array(), size)
        Log.d(TAG, "Added ICMP Echo Reply to delay manager")
    }

    private fun forwardUdpPacket(packet: ByteBuffer) {
        val versionAndIHL = packet.get(0).toInt() and 0xFF
        val headerLength = (versionAndIHL and 0x0F) * 4
        
        // Source/Dest IP
        val srcIpBytes = ByteArray(4)
        packet.position(12)
        packet.get(srcIpBytes)
        val srcAddress = InetAddress.getByAddress(srcIpBytes)

        val destIpBytes = ByteArray(4)
        packet.position(16)
        packet.get(destIpBytes)
        val destAddress = InetAddress.getByAddress(destIpBytes)

        // UDP Ports
        packet.position(headerLength)
        val srcPort = packet.getShort().toInt() and 0xFFFF
        val destPort = packet.getShort().toInt() and 0xFFFF

        if (destPort == 53 || srcPort == 53) {
            Log.d(TAG, "DNS Query via UDP: $srcAddress:$srcPort -> $destAddress:$destPort")
        }

        // Payload
        val udpDataStart = headerLength + 8
        val payloadLength = packet.limit() - udpDataStart
        if (payloadLength <= 0) return

        val payload = ByteArray(payloadLength)
        packet.position(udpDataStart)
        packet.get(payload)

        val sessionKey = "UDP:$srcAddress:$srcPort -> $destAddress:$destPort"
        var session = udpSessions[sessionKey]

        if (session == null || !session.channel.isOpen) {
            Log.d(TAG, "Creating new UDP session for $sessionKey")
            val channel = DatagramChannel.open()
            if (!vpnService.protect(channel.socket())) {
                channel.close()
                return
            }
            channel.configureBlocking(false)
            channel.connect(InetSocketAddress(destAddress, destPort))
            
            session = UdpSession(srcAddress, srcPort, destAddress, destPort, channel)
            udpSessions[sessionKey] = session
            pendingRegistrations.add(session)
            selector?.wakeup()
        }

        session.lastActive = System.currentTimeMillis()
        try {
            val written = session.channel.write(ByteBuffer.wrap(payload))
            stats.recordSent(written)
        } catch (e: IOException) {
            Log.e(TAG, "Error writing UDP to network", e)
            udpSessions.remove(sessionKey)
            session.channel.close()
        }
    }

    private fun forwardTcpPacket(packet: ByteBuffer) {
        val versionAndIHL = packet.get(0).toInt() and 0xFF
        val ipHeaderLength = (versionAndIHL and 0x0F) * 4
        
        // IP Addresses
        val srcIpBytes = ByteArray(4)
        packet.position(12)
        packet.get(srcIpBytes)
        val srcAddress = InetAddress.getByAddress(srcIpBytes)
        val destIpBytes = ByteArray(4)
        packet.position(16)
        packet.get(destIpBytes)
        val destAddress = InetAddress.getByAddress(destIpBytes)

        // TCP Ports
        packet.position(ipHeaderLength)
        val srcPort = packet.getShort().toInt() and 0xFFFF
        val destPort = packet.getShort().toInt() and 0xFFFF
        
        // TCP Fields
        val seqNum = packet.getInt().toLong() and 0xFFFFFFFFL
        val ackNum = packet.getInt().toLong() and 0xFFFFFFFFL
        val dataOffsetAndFlags = packet.getShort().toInt() and 0xFFFF
        val tcpHeaderLength = ((dataOffsetAndFlags shr 12) and 0xF) * 4
        val flags = dataOffsetAndFlags and 0x3F
        
        val sessionKey = "TCP:$srcAddress:$srcPort -> $destAddress:$destPort"
        var session = tcpSessions[sessionKey]

        val isSyn = (flags and 0x02) != 0
        val isAck = (flags and 0x10) != 0
        val isFin = (flags and 0x01) != 0
        val isRst = (flags and 0x04) != 0

        if (session == null) {
            if (isSyn) {
                // Reject port 853 if we want to skip Private DNS in Local mode
                if (destPort == 853) {
                    Log.d(TAG, "TCP SYN to 853 (Private DNS) received. Skipping to force fallback.")
                    return
                }

                session = TcpSession(srcAddress, srcPort, destAddress, destPort)
                session.theirSequenceNum = seqNum
                session.mySequenceNum = System.currentTimeMillis() and 0xFFFFFFFFL
                session.state = TcpState.SYN_RECEIVED
                tcpSessions[sessionKey] = session
                
                try {
                    val channel = SocketChannel.open()
                    if (!vpnService.protect(channel.socket())) {
                        channel.close()
                        tcpSessions.remove(sessionKey)
                        return
                    }
                    channel.configureBlocking(false)
                    channel.connect(InetSocketAddress(destAddress, destPort))
                    session.channel = channel
                    pendingRegistrations.add(session)
                    selector?.wakeup()
                } catch (e: Exception) {
                    tcpSessions.remove(sessionKey)
                    return
                }

                sendTcpControlPacket(session, 0x12, session.mySequenceNum, session.theirSequenceNum + 1, null)
                session.mySequenceNum++
            }
            return
        }

        session.lastActive = System.currentTimeMillis()

        if (isRst) {
            closeTcpSession(session, sessionKey)
            return
        }

        if (session.state == TcpState.SYN_RECEIVED && isAck) {
            session.state = TcpState.ESTABLISHED
            return
        }

        if (session.state == TcpState.ESTABLISHED || session.state == TcpState.SYN_RECEIVED) {
            val payloadStart = ipHeaderLength + tcpHeaderLength
            val payloadSize = packet.limit() - payloadStart
            if (payloadSize > 0) {
                val payload = ByteArray(payloadSize)
                packet.position(payloadStart)
                packet.get(payload)
                
                try {
                    val channel = session.channel
                    if (channel != null && channel.isConnected) {
                        channel.write(ByteBuffer.wrap(payload))
                    } else {
                        session.outBuffer.add(ByteBuffer.wrap(payload))
                    }
                    session.theirSequenceNum += payloadSize
                    sendTcpControlPacket(session, 0x10, session.mySequenceNum, session.theirSequenceNum, null)
                } catch (e: IOException) {
                    closeTcpSession(session, sessionKey)
                }
            }
        }
        
        if (isFin) {
            sendTcpControlPacket(session, 0x11, session.mySequenceNum, seqNum + 1, null)
            closeTcpSession(session, sessionKey)
        }
    }

    private fun handleTcpRead(session: TcpSession, channel: SocketChannel, out: FileOutputStream) {
        val buffer = ByteBuffer.allocate(MAX_PACKET_SIZE - 40)
        try {
            val read = channel.read(buffer)
            if (read > 0) {
                buffer.flip()
                val totalDataSize = buffer.remaining()
                stats.recordReceived(totalDataSize)
                
                while (buffer.hasRemaining()) {
                    val chunkSize = minOf(buffer.remaining(), MAX_SEGMENT_SIZE)
                    val chunk = buffer.slice()
                    chunk.limit(chunkSize)
                    
                    sendTcpControlPacket(session, 0x10, session.mySequenceNum, session.theirSequenceNum, chunk)
                    
                    session.mySequenceNum += chunkSize
                    buffer.position(buffer.position() + chunkSize)
                }
            } else if (read < 0) {
                sendTcpControlPacket(session, 0x11, session.mySequenceNum, session.theirSequenceNum, null)
                closeTcpSession(session, "TCP:${session.srcAddress}:${session.srcPort} -> ${session.destAddress}:${session.destPort}")
            }
        } catch (e: IOException) {
            closeTcpSession(session, "TCP:${session.srcAddress}:${session.srcPort} -> ${session.destAddress}:${session.destPort}")
        }
    }

    private fun sendTcpControlPacket(session: TcpSession, flags: Int, seq: Long, ack: Long, payload: ByteBuffer?) {
        val payloadSize = payload?.remaining() ?: 0
        val totalSize = 20 + 20 + payloadSize
        val packet = ByteBuffer.allocate(totalSize)

        // IP Header
        packet.put(0x45.toByte())
        packet.put(0x00.toByte())
        packet.putShort(totalSize.toShort())
        packet.putShort(0.toShort())
        packet.putShort(0.toShort())
        packet.put(64.toByte())
        packet.put(6.toByte())
        packet.putShort(0.toShort())
        packet.put(session.destAddress.address)
        packet.put(session.srcAddress.address)
        packet.putShort(10, calculateChecksum(packet.array(), 0, 20))

        // TCP Header
        packet.position(20)
        packet.putShort(session.destPort.toShort())
        packet.putShort(session.srcPort.toShort())
        packet.putInt(seq.toInt())
        packet.putInt(ack.toInt())
        packet.putShort(((5 shl 12) or flags).toShort())
        packet.putShort(16384.toShort())
        packet.putShort(0.toShort())
        packet.putShort(0.toShort())
        
        if (payload != null) {
            packet.put(payload)
            payload.position(0)
        }

        packet.putShort(36, calculateTcpChecksum(packet.array(), session.destAddress, session.srcAddress, 20 + payloadSize))

        inboundDelayManager.addPacket(packet.array(), totalSize)
    }

    private fun calculateTcpChecksum(packet: ByteArray, srcAddr: InetAddress, destAddr: InetAddress, tcpLength: Int): Short {
        val pseudoHeader = ByteBuffer.allocate(12 + tcpLength)
        pseudoHeader.put(srcAddr.address)
        pseudoHeader.put(destAddr.address)
        pseudoHeader.put(0.toByte())
        pseudoHeader.put(6.toByte())
        pseudoHeader.putShort(tcpLength.toShort())
        System.arraycopy(packet, 20, pseudoHeader.array(), 12, tcpLength)
        return calculateChecksum(pseudoHeader.array(), 0, 12 + tcpLength)
    }

    private fun calculateChecksum(data: ByteArray, offset: Int, length: Int): Short {
        var sum = 0
        var i = 0
        while (i < length - 1) {
            val b1 = data[offset + i].toInt() and 0xFF
            val b2 = data[offset + i + 1].toInt() and 0xFF
            sum += (b1 shl 8) or b2
            i += 2
        }
        if (i < length) {
            sum += (data[offset + i].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toShort()
    }

    private fun sendResponseToVpn(session: UdpSession, payload: ByteBuffer) {
        val payloadSize = payload.limit()
        val totalSize = 20 + 8 + payloadSize
        val packet = ByteBuffer.allocate(totalSize)

        packet.put(0x45.toByte())
        packet.put(0x00.toByte())
        packet.putShort(totalSize.toShort())
        packet.putShort(0.toShort())
        packet.putShort(0.toShort())
        packet.put(64.toByte())
        packet.put(17.toByte())
        packet.putShort(0.toShort())
        packet.put(session.destAddress.address)
        packet.put(session.srcAddress.address)
        packet.putShort(10, calculateChecksum(packet.array(), 0, 20))

        packet.position(20)
        packet.putShort(session.destPort.toShort())
        packet.putShort(session.srcPort.toShort())
        packet.putShort((8 + payloadSize).toShort())
        packet.putShort(0.toShort()) // UDP Checksum (Optional)
        packet.put(payload)

        inboundDelayManager.addPacket(packet.array(), totalSize)
    }

    private fun closeTcpSession(session: TcpSession, key: String) {
        try { session.channel?.close() } catch (e: Exception) {}
        tcpSessions.remove(key)
    }

    private fun cleanupIdleSessions(now: Long) {
        val udpIterator = udpSessions.entries.iterator()
        while (udpIterator.hasNext()) {
            val entry = udpIterator.next()
            if (now - entry.value.lastActive > SESSION_TIMEOUT_MS) {
                try { entry.value.channel.close() } catch (e: IOException) {}
                udpIterator.remove()
            }
        }
        val tcpIterator = tcpSessions.entries.iterator()
        while (tcpIterator.hasNext()) {
            val entry = tcpIterator.next()
            if (now - entry.value.lastActive > SESSION_TIMEOUT_MS) {
                try { entry.value.channel?.close() } catch (e: IOException) {}
                tcpIterator.remove()
            }
        }
    }

    fun stop() {
        running = false
        selector?.wakeup()
    }

    private fun cleanup() {
        for (session in udpSessions.values) { try { session.channel.close() } catch (e: IOException) {} }
        for (session in tcpSessions.values) { try { session.channel?.close() } catch (e: IOException) {} }
        udpSessions.clear()
        tcpSessions.clear()
        pendingRegistrations.clear()
        try { selector?.close() } catch (e: IOException) {}
    }

    companion object {
        private const val TAG = "LocalVpnConnection"
        private const val MAX_PACKET_SIZE = 16384
        private const val MAX_SEGMENT_SIZE = 1400
        private const val SESSION_TIMEOUT_MS: Long = 60000
    }
}
