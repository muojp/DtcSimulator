package jp.muo.dtc_simulator

import android.app.PendingIntent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.*
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.concurrent.Volatile

/**
 * ServerVpnConnection - ToyVpnのアプローチを使った外部サーバー経由のVPN接続
 */
class ServerVpnConnection(
    private val mService: VpnService,
    private val mServerName: String, private val mServerPort: Int,
    private val mSharedSecret: ByteArray,
    private val mAllowedPackages: MutableSet<String>,
    private val packetProcessor: PacketProcessor
) : Runnable {
    val instanceId = System.currentTimeMillis() % 10000

    interface OnEstablishListener {
        fun onEstablish(tunInterface: ParcelFileDescriptor?)
    }

    interface OnDisconnectListener {
        fun onDisconnect(isFatal: Boolean, message: String?)
    }

    private var mConfigureIntent: PendingIntent? = null
    private var mOnEstablishListener: OnEstablishListener? = null
    private var mOnDisconnectListener: OnDisconnectListener? = null

    @Volatile
    private var mRunning = true
    private var mTunnel: DatagramChannel? = null
    private var mSelector: Selector? = null

    fun setConfigureIntent(intent: PendingIntent) {
        mConfigureIntent = intent
    }

    fun setOnEstablishListener(listener: OnEstablishListener?) {
        mOnEstablishListener = listener
    }

    fun setOnDisconnectListener(listener: OnDisconnectListener?) {
        mOnDisconnectListener = listener
    }

    fun updateLatency(outboundMs: Int, inboundMs: Int) {
        packetProcessor.setLatency(outboundMs, inboundMs)
        Log.d(TAG, "[$instanceId] Latency updated via PacketProcessor: out=$outboundMs, in=$inboundMs")
    }

    fun stop() {
        Log.i(TAG, "[$instanceId] stop() called")
        mRunning = false
        mSelector?.wakeup()

        val tunnel = mTunnel
        if (tunnel != null) {
            mTunnel = null
            Thread {
                try {
                    if (tunnel.isOpen && tunnel.isConnected) {
                        val disconnect = ByteBuffer.allocate(2)
                        disconnect.put(0.toByte()).put(0xFF.toByte()).flip()
                        tunnel.write(disconnect)
                        Log.i(TAG, "[$instanceId] Sent disconnect message to server")
                    }
                    tunnel.close()
                    Log.i(TAG, "[$instanceId] Tunnel closed")
                } catch (e: Exception) {
                    Log.w(TAG, "[$instanceId] Failed to close tunnel safely", e)
                }
            }.start()
        }
    }

    override fun run() {
        Log.i(TAG, "[$instanceId] Starting connection to $mServerName:$mServerPort")
        val serverAddress: SocketAddress = InetSocketAddress(mServerName, mServerPort)

        var isFatal = false
        var errorMessage: String? = null

        try {
            if (!run(serverAddress)) {
                Log.w(TAG, "[$instanceId] Connection failed or was closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$instanceId] Connection error: ${e.message}", e)
            errorMessage = e.message
            
            isFatal = when {
                // Handshake failures are fatal
                e.message?.contains("handshake", ignoreCase = true) == true -> true
                // Fundamental network unreachable errors are fatal
                e is java.net.UnknownHostException || 
                e is java.net.NoRouteToHostException || 
                e is java.net.PortUnreachableException -> true
                e.message?.contains("unreachable", ignoreCase = true) == true -> true
                // "No inbound traffic" is a retryable idle timeout
                e is IOException && e.message == "No inbound traffic for 60s" -> false
                // Other IOExceptions are generally retryable, but non-IOExceptions are fatal
                else -> e !is IOException
            }
        } finally {
            mRunning = false
            Log.i(TAG, "[$instanceId] Calling onDisconnect (isFatal=$isFatal, error=$errorMessage)")
            mOnDisconnectListener?.onDisconnect(isFatal, errorMessage)
            Log.i(TAG, "[$instanceId] ServerVpnConnection thread exiting")
        }
    }

    @Throws(IOException::class, InterruptedException::class, IllegalArgumentException::class)
    private fun run(server: SocketAddress?): Boolean {
        var iface: ParcelFileDescriptor? = null
        var connected = false

        try {
            DatagramChannel.open().use { tunnel ->
                mTunnel = tunnel
                check(mService.protect(tunnel.socket())) { "Cannot protect the tunnel" }
                
                tunnel.connect(server)
                tunnel.configureBlocking(false)

                iface = handshake(tunnel)
                connected = true
                Log.i(TAG, "[$instanceId] Connection established")

                val vpnFd = iface!!.fileDescriptor
                FileInputStream(vpnFd).use { `in` ->
                    FileOutputStream(vpnFd).use { out ->
                        val outgoingHandlerThread = HandlerThread("VpnOutgoingReader-$instanceId").apply { start() }
                        val outgoingMessageQueue = outgoingHandlerThread.looper.queue

                        val readerPacket = ByteBuffer.allocate(MAX_PACKET_SIZE)
                        val fdListener = object : MessageQueue.OnFileDescriptorEventListener {
                            override fun onFileDescriptorEvents(fd: java.io.FileDescriptor, events: Int): Int {
                                if (!mRunning) return 0
                                if (events and MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT != 0) {
                                    try {
                                        while (true) {
                                            val length = `in`.read(readerPacket.array())
                                            if (length > 0) {
                                                packetProcessor.processOutboundPacket(readerPacket.array(), length)
                                            } else if (length < 0) {
                                                return 0
                                            } else break
                                        }
                                        return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                                    } catch (e: Exception) {
                                        return 0
                                    }
                                }
                                return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                            }
                        }

                        outgoingMessageQueue.addOnFileDescriptorEventListener(
                            vpnFd, MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT, fdListener
                        )

                        val outgoingWriterThread = Thread({
                            try {
                                while (mRunning) {
                                    val data = packetProcessor.pollReadyOutboundPacket(100)
                                    if (data != null) {
                                        val tunnelRef = mTunnel
                                        if (tunnelRef != null && tunnelRef.isConnected) {
                                            val buffer = ByteBuffer.wrap(data)
                                            val sent = tunnelRef.write(buffer)
                                            if (sent > 0) packetProcessor.recordSent(sent)
                                        }
                                    }
                                }
                            } catch (e: Exception) {}
                        }, "VpnOutgoingWriter-$instanceId")
                        outgoingWriterThread.start()

                        val incomingWriterThread = Thread({
                            try {
                                while (mRunning) {
                                    val data = packetProcessor.pollReadyInboundPacket(100)
                                    if (data != null) {
                                        out.write(data)
                                        out.flush()
                                    }
                                }
                            } catch (e: Exception) {}
                        }, "VpnIncomingWriter-$instanceId")
                        incomingWriterThread.start()

                        val selector = Selector.open()
                        mSelector = selector
                        tunnel.register(selector, SelectionKey.OP_READ)
                        val packet = ByteBuffer.allocate(MAX_PACKET_SIZE)

                        var lastSendTime = System.currentTimeMillis()
                        var lastReceiveTime = System.currentTimeMillis()
                        var lastStatsUpdate = System.currentTimeMillis()
                        
                        while (mRunning && !Thread.currentThread().isInterrupted) {
                            val selected = try {
                                selector.select(5000)
                            } catch (e: Exception) { 0 }
                            
                            val timeNow = System.currentTimeMillis()

                            if (selected > 0) {
                                val keys = selector.selectedKeys().iterator()
                                while (keys.hasNext()) {
                                    val key = keys.next()
                                    keys.remove()
                                    if (!key.isValid || !key.isReadable) continue

                                    packet.clear()
                                    val length = tunnel.read(packet)
                                    if (length > 0) {
                                        lastReceiveTime = timeNow
                                        if (packet.get(0).toInt() != 0) {
                                            packetProcessor.recordReceived(length)
                                            packetProcessor.processInboundPacket(packet.array(), length)
                                        }
                                    }
                                }
                            }

                            if (timeNow - lastReceiveTime > 60000) {
                                throw IOException("No inbound traffic for 60s")
                            }

                            if (timeNow - lastStatsUpdate > 500) {
                                packetProcessor.updateBufferStats()
                                lastStatsUpdate = timeNow
                            }

                            if (lastSendTime + KEEPALIVE_INTERVAL_MS <= timeNow) {
                                val tunnelForKA = mTunnel
                                if (tunnelForKA != null && tunnelForKA.isConnected) {
                                    packet.clear()
                                    packet.put(0.toByte()).limit(1)
                                    for (i in 0..2) {
                                        packet.position(0)
                                        tunnelForKA.write(packet)
                                    }
                                }
                                lastSendTime = timeNow
                            }
                        }

                        outgoingMessageQueue.removeOnFileDescriptorEventListener(vpnFd)
                        outgoingHandlerThread.quitSafely()
                    }
                }
            }
        } finally {
            mTunnel = null
            iface?.close()
        }
        return connected
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun handshake(tunnel: DatagramChannel): ParcelFileDescriptor? {
        Log.i(TAG, "[$instanceId] Starting handshake with server...")
        val packet = ByteBuffer.allocate(1024)
        packet.put(0.toByte()).put(mSharedSecret).put(0.toByte()).flip()

        for (i in 0..2) {
            packet.position(0)
            tunnel.write(packet)
        }
        packet.clear()

        var i = 0
        while (i < MAX_HANDSHAKE_ATTEMPTS && mRunning) {
            Thread.sleep(IDLE_INTERVAL_MS)
            packet.clear()
            val length = tunnel.read(packet)
            if (length > 0 && packet.get(0).toInt() == 0) {
                Log.i(TAG, "[$instanceId] Handshake successful")
                return configure(String(packet.array(), 1, length - 1, StandardCharsets.US_ASCII).trim())
            }
            ++i
        }

        if (!mRunning) throw IOException("Handshake interrupted")
        throw IOException("Handshake timed out")
    }

    private fun configure(parameters: String): ParcelFileDescriptor? {
        val builder = mService.Builder()
        for (parameter in parameters.split(" ").filter { it.isNotEmpty() }) {
            val fields = parameter.split(",")
            try {
                when (fields[0][0]) {
                    'm' -> builder.setMtu(fields[1].toShort().toInt())
                    'a' -> builder.addAddress(fields[1], fields[2].toInt())
                    'r' -> builder.addRoute(fields[1], fields[2].toInt())
                    'd' -> builder.addDnsServer(fields[1])
                    's' -> builder.addSearchDomain(fields[1])
                }
            } catch (e: Exception) {}
        }

        for (packageName in mAllowedPackages) {
            try { builder.addAllowedApplication(packageName) } catch (e: Exception) {}
        }

        builder.setSession(mServerName).setConfigureIntent(mConfigureIntent!!)
        synchronized(mService) {
            val vpnInterface = builder.establish()
            mOnEstablishListener?.onEstablish(vpnInterface)
            return vpnInterface
        }
    }

    companion object {
        private const val TAG = "ServerVpnConnection"
        private const val MAX_PACKET_SIZE = Short.MAX_VALUE.toInt()
        private val KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10)
        private val IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100)
        private const val MAX_HANDSHAKE_ATTEMPTS = 50
    }
}