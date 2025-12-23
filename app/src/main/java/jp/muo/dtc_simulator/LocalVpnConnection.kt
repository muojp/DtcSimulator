package jp.muo.dtc_simulator

import android.net.VpnService
import android.os.*
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
import java.util.concurrent.atomic.AtomicInteger
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
        @Volatile var lastActive: Long = System.currentTimeMillis()
    )

    private class TcpSession(
        val srcAddress: InetAddress,
        val srcPort: Int,
        val destAddress: InetAddress,
        val destPort: Int
    ) {
        var channel: SocketChannel? = null
        @Volatile var state: TcpState = TcpState.LISTEN
        @Volatile var mySequenceNum: Long = 0
        @Volatile var theirSequenceNum: Long = 0
        @Volatile var lastActive: Long = System.currentTimeMillis()
        val outBuffer: ConcurrentLinkedQueue<ByteBuffer> = ConcurrentLinkedQueue()
        val outOfOrderBuffer: java.util.TreeMap<Long, ByteArray> = java.util.TreeMap { a, b ->
            val diff = (a.toInt() - b.toInt())
            if (diff == 0) 0 else if (diff < 0) -1 else 1
        }
    }

    private fun isBefore(a: Long, b: Long): Boolean = (a.toInt() - b.toInt()) < 0
    private fun isAfter(a: Long, b: Long): Boolean = (a.toInt() - b.toInt()) > 0

    private val nextIpId = AtomicInteger(0)

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
            val vpnFd = vpnInterface.fileDescriptor
            val inputStream = FileInputStream(vpnFd)
            val outputStream = FileOutputStream(vpnFd)

            // 1. Event-driven outgoing reader (VPN -> Network)
            val outgoingHandlerThread = HandlerThread("LocalVpnOutgoingReader").apply { start() }
            val outgoingMessageQueue = outgoingHandlerThread.looper.queue

            val readerPacket = ByteBuffer.allocate(MAX_PACKET_SIZE)
            val fdListener = object : MessageQueue.OnFileDescriptorEventListener {
                override fun onFileDescriptorEvents(fd: java.io.FileDescriptor, events: Int): Int {
                    if (!running) return 0

                    if (events and MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT != 0) {
                        try {
                            // Read all available packets
                            while (true) {
                                val length = inputStream.read(readerPacket.array())
                                if (length > 0) {
                                    outboundDelayManager.addPacket(readerPacket.array(), length)
                                } else if (length < 0) {
                                    Log.i(TAG, "VPN interface closed")
                                    return 0
                                } else {
                                    // No more data available
                                    break
                                }
                            }
                            // Continue monitoring
                            return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                        } catch (e: Exception) {
                            if (running) Log.e(TAG, "Error in outgoing reader", e)
                            return 0
                        }
                    }
                    return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                }
            }

            outgoingMessageQueue.addOnFileDescriptorEventListener(
                vpnFd,
                MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT,
                fdListener
            )
            Log.d(TAG, "Event-driven VPN reader registered")

            val outgoingWriterThread = Thread({
                try {
                    while (running) {
                        val data = outboundDelayManager.pollReadyPacketBlocking(100)
                        if (data != null) {
                            forwardToNetwork(ByteBuffer.wrap(data))
                        }
                        stats.updateBufferStats(outboundDelayManager.queueSize, inboundDelayManager.queueSize)
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
                        val data = inboundDelayManager.pollReadyPacketBlocking(100)
                        if (data != null) {
                            outputStream.write(data)
                        }
                        stats.updateBufferStats(outboundDelayManager.queueSize, inboundDelayManager.queueSize)
                    }
                } catch (e: Exception) {
                    if (running) Log.e(TAG, "Error in incoming writer thread", e)
                }
            }, "LocalVpnIncomingWriter")
            incomingWriterThread.start()

            var lastCleanupTime = System.currentTimeMillis()
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

                // Periodic cleanup
                if (now - lastCleanupTime > 30000) {
                    cleanupIdleSessions(now)
                    lastCleanupTime = now
                }
            }

            // Cleanup: remove file descriptor listener and stop handler thread
            try {
                outgoingMessageQueue.removeOnFileDescriptorEventListener(vpnFd)
                outgoingHandlerThread.quitSafely()
                outgoingHandlerThread.join(1000)
                Log.d(TAG, "Event-driven VPN reader cleaned up")
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up event listener", e)
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
                        val written = channel.write(buffer)
                        if (written > 0) stats.recordSent(written)
                        Log.v(TAG, "Flushed buffered TCP data: $written bytes")
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
        if (packet.limit() < 20) {
            Log.w(TAG, "Discarding too short packet: ${packet.limit()} bytes")
            return
        }

        val versionAndIHL = packet.get(0).toInt() and 0xFF
        val version = versionAndIHL shr 4
        if (version != 4) {
            Log.w(TAG, "Discarding non-IPv4 packet: version=$version")
            return
        }

        // Verify source IP is our VPN address
        val srcIpBytes = ByteArray(4)
        packet.position(12)
        packet.get(srcIpBytes)
        val srcAddress = InetAddress.getByAddress(srcIpBytes)
        if (srcAddress.hostAddress != "10.0.0.2") {
            Log.v(TAG, "Discarding packet with unexpected source IP: ${srcAddress.hostAddress}")
            return
        }

        val protocol = packet.get(9).toInt() and 0xFF
        when (protocol) {
            17 -> forwardUdpPacket(packet)
            6 -> forwardTcpPacket(packet)
            1 -> forwardIcmpPacket(packet)
            else -> Log.d(TAG, "Unsupported protocol: $protocol (discarding)")
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
        } else {
            Log.d(TAG, "Unsupported ICMP type: $type (discarding)")
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
        if (payloadLength <= 0) {
            Log.v(TAG, "Empty UDP payload from $srcAddress:$srcPort to $destAddress:$destPort")
            return
        }

        val payload = ByteArray(payloadLength)
        packet.position(udpDataStart)
        packet.get(payload)

        val sessionKey = "UDP:$srcAddress:$srcPort -> $destAddress:$destPort"
        var session = udpSessions[sessionKey]

        if (session == null || !session.channel.isOpen) {
            Log.d(TAG, "Creating new UDP session for $sessionKey")
            val channel = DatagramChannel.open()
            if (!vpnService.protect(channel.socket())) {
                Log.e(TAG, "Failed to protect UDP socket for $sessionKey")
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

                val newSession = TcpSession(srcAddress, srcPort, destAddress, destPort)
                newSession.theirSequenceNum = (seqNum + 1) and 0xFFFFFFFFL
                newSession.mySequenceNum = java.util.Random().nextInt().toLong() and 0xFFFFFFFFL
                newSession.state = TcpState.SYN_RECEIVED
                tcpSessions[sessionKey] = newSession
                
                try {
                    val channel = SocketChannel.open()
                    if (!vpnService.protect(channel.socket())) {
                        Log.e(TAG, "Failed to protect TCP socket for $sessionKey")
                        channel.close()
                        tcpSessions.remove(sessionKey)
                        return
                    }
                    channel.configureBlocking(false)
                    channel.connect(InetSocketAddress(destAddress, destPort))
                    newSession.channel = channel
                    pendingRegistrations.add(newSession)
                    selector?.wakeup()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to open TCP channel for $sessionKey", e)
                    tcpSessions.remove(sessionKey)
                    return
                }

                // Send SYN+ACK: our SEQ is mySequenceNum, we ACK their initial SEQ + 1
                sendTcpControlPacket(newSession, 0x12, newSession.mySequenceNum, newSession.theirSequenceNum, null)
                newSession.mySequenceNum = (newSession.mySequenceNum + 1) and 0xFFFFFFFFL
            } else {
                Log.v(TAG, "Discarding non-SYN TCP packet for new session: $sessionKey (Sending RST)")
                sendTcpReset(srcAddress, srcPort, destAddress, destPort, ackNum, (seqNum + 1) and 0xFFFFFFFFL)
            }
            return
        }

        synchronized(session) {
            session.lastActive = System.currentTimeMillis()

            if (isRst) {
                Log.d(TAG, "TCP RST received for $sessionKey")
                closeTcpSession(session, sessionKey)
                return
            }

            if (isSyn) {
                // Retransmitted SYN, resend SYN+ACK
                Log.v(TAG, "TCP SYN retransmitted for $sessionKey")
                sendTcpControlPacket(session, 0x12, (session.mySequenceNum - 1) and 0xFFFFFFFFL, session.theirSequenceNum, null)
                return
            }

            if (session.state == TcpState.SYN_RECEIVED && isAck) {
                session.state = TcpState.ESTABLISHED
                Log.v(TAG, "TCP state established for $sessionKey")
            }

            if (session.state == TcpState.ESTABLISHED || session.state == TcpState.SYN_RECEIVED) {
                val payloadStart = ipHeaderLength + tcpHeaderLength
                val payloadSize = packet.limit() - payloadStart
                
                // Validate sequence number for established sessions
                if (payloadSize > 0) {
                    if (isBefore(seqNum, session.theirSequenceNum)) {
                        Log.v(TAG, "TCP duplicate/old packet for $sessionKey (seqNum $seqNum < ${session.theirSequenceNum})")
                        // Resend ACK for their current position
                        sendTcpControlPacket(session, 0x10, session.mySequenceNum, session.theirSequenceNum, null)
                        return
                    }
                    
                    if (isAfter(seqNum, session.theirSequenceNum)) {
                        val diff = (seqNum.toInt() - session.theirSequenceNum.toInt())
                        if (diff > 65535) {
                            Log.w(TAG, "TCP packet too far ahead for $sessionKey (seqNum $seqNum, expected ${session.theirSequenceNum}) - discarding")
                            return
                        }
                        
                        Log.v(TAG, "TCP out-of-order packet for $sessionKey (seqNum $seqNum > ${session.theirSequenceNum}) - buffering")
                        val payload = ByteArray(payloadSize)
                        packet.position(payloadStart)
                        packet.get(payload)
                        session.outOfOrderBuffer[seqNum] = payload
                        
                        // Send ACK for current position to trigger retransmission of missing data
                        sendTcpControlPacket(session, 0x10, session.mySequenceNum, session.theirSequenceNum, null)
                        return
                    }

                    val payload = ByteArray(payloadSize)
                    packet.position(payloadStart)
                    packet.get(payload)
                    
                    try {
                        val channel = session.channel
                        if (channel != null && channel.isConnected) {
                            val written = channel.write(ByteBuffer.wrap(payload))
                            if (written > 0) stats.recordSent(written)
                            session.theirSequenceNum = (seqNum + payloadSize) and 0xFFFFFFFFL
                            
                            // Process reassembled packets from buffer
                            while (session.outOfOrderBuffer.isNotEmpty()) {
                                val nextSeq = session.outOfOrderBuffer.firstKey()
                                if (!isAfter(nextSeq, session.theirSequenceNum)) {
                                    val bufferedPayload = session.outOfOrderBuffer.remove(nextSeq)
                                    val nextSeqEnd = (nextSeq + (bufferedPayload?.size ?: 0)) and 0xFFFFFFFFL
                                    if (isAfter(nextSeqEnd, session.theirSequenceNum)) {
                                        val offset = (session.theirSequenceNum.toInt() - nextSeq.toInt())
                                        val actualPayload = if (offset > 0) bufferedPayload!!.sliceArray(offset until bufferedPayload.size) else bufferedPayload!!
                                        val reassembledWritten = channel.write(ByteBuffer.wrap(actualPayload))
                                        if (reassembledWritten > 0) stats.recordSent(reassembledWritten)
                                        session.theirSequenceNum = (session.theirSequenceNum + actualPayload.size) and 0xFFFFFFFFL
                                        Log.v(TAG, "Reassembled TCP packet for $sessionKey (seqNum $nextSeq)")
                                    }
                                } else {
                                    break
                                }
                            }
                            
                            // Send ACK
                            sendTcpControlPacket(session, 0x10, session.mySequenceNum, session.theirSequenceNum, null)
                        } else {
                            // Buffer data while connecting
                            session.outBuffer.add(ByteBuffer.wrap(payload))
                            session.theirSequenceNum = (seqNum + payloadSize) and 0xFFFFFFFFL
                            // Acknowledge receipt even if not forwarded yet
                            sendTcpControlPacket(session, 0x10, session.mySequenceNum, session.theirSequenceNum, null)
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "TCP write failed for $sessionKey", e)
                        closeTcpSession(session, sessionKey)
                        return
                    }
                } else if (isAck) {
                    // Update their sequence number even for pure ACKs if it's newer
                    if (isAfter(seqNum, session.theirSequenceNum)) {
                        session.theirSequenceNum = seqNum
                    }
                }
            }
            
            if (isFin) {
                Log.d(TAG, "TCP FIN received for $sessionKey")
                val finSeq = (seqNum + (if (ipHeaderLength + tcpHeaderLength < packet.limit()) packet.limit() - (ipHeaderLength + tcpHeaderLength) else 0)) and 0xFFFFFFFFL
                session.theirSequenceNum = (finSeq + 1) and 0xFFFFFFFFL
                sendTcpControlPacket(session, 0x11, session.mySequenceNum, session.theirSequenceNum, null)
                session.mySequenceNum = (session.mySequenceNum + 1) and 0xFFFFFFFFL
                closeTcpSession(session, sessionKey)
            }
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
                
                synchronized(session) {
                    while (buffer.hasRemaining()) {
                        val chunkSize = minOf(buffer.remaining(), MAX_SEGMENT_SIZE)
                        val chunk = buffer.slice()
                        chunk.limit(chunkSize)
                        
                        sendTcpControlPacket(session, 0x10, session.mySequenceNum, session.theirSequenceNum, chunk)
                        
                        session.mySequenceNum = (session.mySequenceNum + chunkSize) and 0xFFFFFFFFL
                        buffer.position(buffer.position() + chunkSize)
                    }
                }
            } else if (read < 0) {
                Log.d(TAG, "TCP remote closed connection for TCP:${session.srcAddress}:${session.srcPort} -> ${session.destAddress}:${session.destPort}")
                synchronized(session) {
                    sendTcpControlPacket(session, 0x11, session.mySequenceNum, session.theirSequenceNum, null)
                    session.mySequenceNum = (session.mySequenceNum + 1) and 0xFFFFFFFFL
                    closeTcpSession(session, "TCP:${session.srcAddress}:${session.srcPort} -> ${session.destAddress}:${session.destPort}")
                }
            }
        } catch (e: IOException) {
            closeTcpSession(session, "TCP:${session.srcAddress}:${session.srcPort} -> ${session.destAddress}:${session.destPort}")
        }
    }

    private fun sendTcpReset(srcAddr: InetAddress, srcPort: Int, destAddr: InetAddress, destPort: Int, seq: Long, ack: Long) {
        val totalSize = 40
        val packet = ByteBuffer.allocate(totalSize)

        // IP Header
        packet.put(0x45.toByte())
        packet.put(0x00.toByte())
        packet.putShort(totalSize.toShort())
        packet.putShort(nextIpId.getAndIncrement().toShort())
        packet.putShort(0.toShort())
        packet.put(64.toByte())
        packet.put(6.toByte())
        packet.putShort(0.toShort())
        packet.put(destAddr.address)
        packet.put(srcAddr.address)
        packet.putShort(10, calculateChecksum(packet.array(), 0, 20))

        // TCP Header (RST + ACK)
        packet.position(20)
        packet.putShort(destPort.toShort())
        packet.putShort(srcPort.toShort())
        packet.putInt(seq.toInt())
        packet.putInt(ack.toInt())
        packet.putShort(((5 shl 12) or 0x14).toShort()) // RST=1, ACK=1
        packet.putShort(16384.toShort())
        packet.putShort(0.toShort())
        packet.putShort(0.toShort())

        packet.putShort(36, calculateTcpChecksum(packet.array(), destAddr, srcAddr, 20))

        inboundDelayManager.addPacket(packet.array(), totalSize)
    }

    private fun sendTcpControlPacket(session: TcpSession, flags: Int, seq: Long, ack: Long, payload: ByteBuffer?) {
        val payloadSize = payload?.remaining() ?: 0
        val totalSize = 20 + 20 + payloadSize
        val packet = ByteBuffer.allocate(totalSize)

        // IP Header
        packet.put(0x45.toByte())
        packet.put(0x00.toByte())
        packet.putShort(totalSize.toShort())
        packet.putShort(nextIpId.getAndIncrement().toShort())
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

    private fun calculateUdpChecksum(packet: ByteArray, srcAddr: InetAddress, destAddr: InetAddress, udpLength: Int): Short {
        val pseudoHeader = ByteBuffer.allocate(12 + udpLength)
        pseudoHeader.put(srcAddr.address)
        pseudoHeader.put(destAddr.address)
        pseudoHeader.put(0.toByte())
        pseudoHeader.put(17.toByte())
        pseudoHeader.putShort(udpLength.toShort())
        System.arraycopy(packet, 20, pseudoHeader.array(), 12, udpLength)
        val checksum = calculateChecksum(pseudoHeader.array(), 0, 12 + udpLength)
        return if (checksum == 0.toShort()) 0xFFFF.toShort() else checksum
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
        packet.putShort(nextIpId.getAndIncrement().toShort())
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
        packet.putShort(0.toShort())
        packet.put(payload)

        packet.putShort(26, calculateUdpChecksum(packet.array(), session.destAddress, session.srcAddress, 8 + payloadSize))

        inboundDelayManager.addPacket(packet.array(), totalSize)
    }

    private fun closeTcpSession(session: TcpSession, key: String) {
        try { session.channel?.close() } catch (e: Exception) {}
        session.outBuffer.clear()
        session.outOfOrderBuffer.clear()
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
        private const val SESSION_TIMEOUT_MS: Long = 300000
    }
}