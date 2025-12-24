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
 *
 * VPNインターフェースから読み取った生のIPパケットを、UDP経由でサーバーに送信し、
 * サーバー側でルーティングすることで、通信を素通しさせます。
 *
 * Based on ToyVpnConnection from Android ToyVpn sample.
 */
class ServerVpnConnection(
    private val mService: VpnService,
    private val mServerName: String, private val mServerPort: Int,
    private val mSharedSecret: ByteArray,
// Allowlist: 許可されたパッケージ
    private val mAllowedPackages: MutableSet<String>
) : Runnable {
    val instanceId = System.currentTimeMillis() % 10000

    /**
     * Callback interface to let the VPN service know about connection establishment
     */
    interface OnEstablishListener {
        fun onEstablish(tunInterface: ParcelFileDescriptor?)
    }

    interface OnDisconnectListener {
        fun onDisconnect()
    }

    private var mConfigureIntent: PendingIntent? = null
    private var mOnEstablishListener: OnEstablishListener? = null
    private var mOnDisconnectListener: OnDisconnectListener? = null

    @Volatile
    private var mRunning = true
    private var mTunnel: DatagramChannel? = null

    // Latency managers
    private val outboundDelayManager = PacketDelayManager()
    private val inboundDelayManager = PacketDelayManager()

    /**
     * Get current VPN traffic statistics
     */
    val stats: VpnStats = VpnStats()

    fun setConfigureIntent(intent: PendingIntent) {
        mConfigureIntent = intent
    }

    fun setOnEstablishListener(listener: OnEstablishListener?) {
        mOnEstablishListener = listener
    }

    fun setOnDisconnectListener(listener: OnDisconnectListener?) {
        mOnDisconnectListener = listener
    }

    /**
     * Update latency settings
     */
    fun updateLatency(outboundMs: Int, inboundMs: Int) {
        outboundDelayManager.setLatency(outboundMs)
        inboundDelayManager.setLatency(inboundMs)
        Log.d(TAG, "Latency updated in connection: out=$outboundMs, in=$inboundMs")
    }

    /**
     * Stop the connection and send disconnect message to server
     */
    fun stop() {
        Log.i(TAG, "[$instanceId] stop() called")
        mRunning = false

        val tunnel = mTunnel
        if (tunnel != null) {
            mTunnel = null
            Thread {
                try {
                    // Send disconnect message: 0x00 0xFF
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

        try {
            if (!run(serverAddress)) {
                Log.w(TAG, "[$instanceId] Connection failed or was closed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$instanceId] Connection error: ${e.message}", e)
        } finally {
            mRunning = false
            Log.i(TAG, "[$instanceId] Calling onDisconnect")
            mOnDisconnectListener?.onDisconnect()
            Log.i(TAG, "[$instanceId] ServerVpnConnection thread exiting")
        }
    }

    @Throws(IOException::class, InterruptedException::class, IllegalArgumentException::class)
    private fun run(server: SocketAddress?): Boolean {
        var iface: ParcelFileDescriptor? = null
        var connected = false

        // Create a DatagramChannel as the VPN tunnel
        try {
            DatagramChannel.open().use { tunnel ->
                mTunnel = tunnel
                // Protect the tunnel before connecting to avoid loopback
                check(mService.protect(tunnel.socket())) { "Cannot protect the tunnel" }
                Log.i(TAG, "[$instanceId] Socket protected successfully")

                // Connect to the server
                Log.i(TAG, "[$instanceId] Connecting to server: $server")
                tunnel.connect(server)
                Log.i(TAG, "[$instanceId] Connected to server: " + tunnel.remoteAddress)

                // Put the tunnel into non-blocking mode for handshake and Selector
                tunnel.configureBlocking(false)
                Log.d(TAG, "[$instanceId] Tunnel set to non-blocking mode")

                // Authenticate and configure the virtual network interface
                iface = handshake(tunnel)

                // Now we are connected
                connected = true
                Log.i(TAG, "[$instanceId] Connection established")

                val vpnFd = iface!!.fileDescriptor
                FileInputStream(vpnFd).use { `in` ->
                    FileOutputStream(vpnFd).use { out ->

                        // 1. Event-driven outgoing reader (TUN -> DelayManager -> Server)
                        val outgoingHandlerThread = HandlerThread("VpnOutgoingReader-$instanceId").apply { start() }
                        val outgoingMessageQueue = outgoingHandlerThread.looper.queue

                        val readerPacket = ByteBuffer.allocate(MAX_PACKET_SIZE)
                        val fdListener = object : MessageQueue.OnFileDescriptorEventListener {
                            override fun onFileDescriptorEvents(fd: java.io.FileDescriptor, events: Int): Int {
                                if (!mRunning) return 0

                                if (events and MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT != 0) {
                                    try {
                                        // Read all available packets
                                        while (true) {
                                            val length = `in`.read(readerPacket.array())
                                            if (length > 0) {
                                                outboundDelayManager.addPacket(readerPacket.array(), length)
                                            } else if (length < 0) {
                                                Log.i(TAG, "[$instanceId] VPN interface closed")
                                                return 0
                                            } else {
                                                // No more data available
                                                break
                                            }
                                        }
                                        // Continue monitoring
                                        return MessageQueue.OnFileDescriptorEventListener.EVENT_INPUT
                                    } catch (e: Exception) {
                                        if (mRunning) Log.e(TAG, "[$instanceId] Outgoing reader error", e)
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
                        Log.d(TAG, "[$instanceId] Event-driven VPN reader registered")

                        val outgoingWriterThread = Thread({
                            try {
                                Log.i(TAG, "[$instanceId] Outgoing writer thread started")
                                while (mRunning) {
                                    val data = outboundDelayManager.pollReadyPacketBlocking(100)
                                    if (data != null) {
                                        val tunnel = mTunnel
                                        if (tunnel != null && tunnel.isConnected) {
                                            val buffer = ByteBuffer.wrap(data)
                                            val sent = tunnel.write(buffer)
                                            if (sent > 0) {
                                                stats.recordSent(sent)
                                            }
                                        } else {
                                            Log.w(TAG, "[$instanceId] Tunnel not ready for writing: tunnel=$tunnel, isConnected=${tunnel?.isConnected}")
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                if (mRunning) Log.e(TAG, "[$instanceId] Outgoing writer error", e)
                            } finally {
                                Log.i(TAG, "[$instanceId] Outgoing writer thread exiting")
                            }
                        }, "VpnOutgoingWriter-$instanceId")
                        outgoingWriterThread.start()

                        // 2. Incoming loop (Server -> DelayManager -> TUN)
                        val incomingWriterThread = Thread({
                            try {
                                Log.i(TAG, "[$instanceId] Incoming writer thread started")
                                while (mRunning) {
                                    val data = inboundDelayManager.pollReadyPacketBlocking(100)
                                    if (data != null) {
                                        out.write(data)
                                        out.flush()
                                    }
                                }
                            } catch (e: Exception) {
                                if (mRunning) Log.e(TAG, "[$instanceId] Incoming writer error", e)
                            } finally {
                                Log.i(TAG, "[$instanceId] Incoming writer thread exiting")
                            }
                        }, "VpnIncomingWriter-$instanceId")
                        incomingWriterThread.start()

                        val selector = Selector.open()
                        tunnel.register(selector, SelectionKey.OP_READ)
                        val packet = ByteBuffer.allocate(MAX_PACKET_SIZE)

                        var lastSendTime = System.currentTimeMillis()
                        var lastStatsUpdate = System.currentTimeMillis()
                        var lastAliveLog = System.currentTimeMillis()
                        var lastReceiveTime = System.currentTimeMillis()
                        
                        while (mRunning && !Thread.currentThread().isInterrupted) {
                            val selected = try {
                                selector.select(5000)
                            } catch (e: Exception) {
                                if (e is InterruptedException || e.cause is InterruptedException) {
                                    Log.i(TAG, "[$instanceId] Selector interrupted")
                                    break
                                }
                                Log.e(TAG, "[$instanceId] Selector select error", e)
                                0
                            }
                            val timeNow = System.currentTimeMillis()

                            if (timeNow - lastAliveLog > 30000) {
                                Log.i(TAG, "[$instanceId] Selector loop alive, selected=$selected, inQ=${inboundDelayManager.queueSize}, outQ=${outboundDelayManager.queueSize}, tunnelOpen=${mTunnel?.isOpen}, tunnelConnected=${mTunnel?.isConnected}")
                                lastAliveLog = timeNow
                            }

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
                                            stats.recordReceived(length)
                                            inboundDelayManager.addPacket(packet.array(), length)
                                        } else {
                                            Log.d(TAG, "[$instanceId] [Control] Received control message ($length bytes)")
                                        }
                                    } else if (length < 0) {
                                        Log.w(TAG, "[$instanceId] Tunnel read returned -1, connection might be closed")
                                    }
                                }
                            }

                            // Check for silence timeout (Recovery logic)
                            if (timeNow - lastReceiveTime > 60000) {
                                throw IOException("No inbound traffic for 60s. Potential OS-level UDP block.")
                            }

                            // Update buffer statistics periodically
                            if (timeNow - lastStatsUpdate > 500) {
                                stats.updateBufferStats(
                                    outboundDelayManager.queueSize,
                                    inboundDelayManager.queueSize
                                )
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

                        // Cleanup: remove file descriptor listener and stop handler thread
                        try {
                            outgoingMessageQueue.removeOnFileDescriptorEventListener(vpnFd)
                            outgoingHandlerThread.quitSafely()
                            outgoingHandlerThread.join(1000)
                            Log.d(TAG, "[$instanceId] Event-driven VPN reader cleaned up")
                        } catch (e: Exception) {
                            Log.w(TAG, "[$instanceId] Error cleaning up event listener", e)
                        }
                    }
                }
            }
        } catch (e: SocketException) {
            Log.e(TAG, "Cannot use socket", e)
        } finally {
            mTunnel = null
            if (iface != null) {
                try {
                    iface.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Unable to close interface", e)
                }
            }
        }
        return connected
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun handshake(tunnel: DatagramChannel): ParcelFileDescriptor? {
        Log.i(TAG, "[$instanceId] Starting handshake with server...")

        // Send the shared secret (must be null-terminated for the server's strcmp)
        val packet = ByteBuffer.allocate(1024)

        // Control messages always start with zero
        packet.put(0.toByte()).put(mSharedSecret).put(0.toByte()).flip()

        // Send the secret several times in case of packet loss
        for (i in 0..2) {
            packet.position(0)
            val sent = tunnel.write(packet)
            Log.d(TAG, "[$instanceId] Handshake attempt " + (i + 1) + ": sent " + sent + " bytes")
        }
        packet.clear()
        Log.i(TAG, "[$instanceId] Sent handshake message (secret) to server, waiting for response...")

        // Wait for the parameters within a limited time
        var i = 0
        while (i < MAX_HANDSHAKE_ATTEMPTS && mRunning) {
            Thread.sleep(IDLE_INTERVAL_MS)

            // Clear the buffer before reading
            packet.clear()

            // Check that the first byte is 0 as expected
            val length = tunnel.read(packet)
            if (length > 0) {
                Log.d(
                    TAG,
                    "[$instanceId] Received packet: $length bytes, first byte: 0x" + String.format(
                        "%02X",
                        packet.get(0)
                    )
                )
                if (packet.get(0).toInt() == 0) {
                    Log.i(TAG, "[$instanceId] Handshake successful, received VPN parameters from server")
                    return configure(
                        String(
                            packet.array(),
                            1,
                            length - 1,
                            StandardCharsets.US_ASCII
                        ).trim { it <= ' ' })
                }
            } else if (i % 10 == 0 && i > 0) {
                Log.d(
                    TAG,
                    "[$instanceId] Waiting for handshake response... attempt $i/$MAX_HANDSHAKE_ATTEMPTS"
                )
            }
            ++i
        }

        if (!mRunning) {
            throw IOException("Handshake interrupted by stop()")
        }
        throw IOException("Handshake timed out")
    }

    @Throws(IllegalArgumentException::class)
    private fun configure(parameters: String): ParcelFileDescriptor? {
        Log.i(TAG, "[$instanceId] Configuring VPN with parameters: $parameters")

        // Configure a builder while parsing the parameters
        val builder = mService.Builder()

        for (parameter in parameters.split(" ".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()) {
            if (parameter.isEmpty()) {
                continue
            }
            val fields =
                parameter.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            try {
                when (fields[0][0]) {
                    'm' -> {
                        builder.setMtu(fields[1].toShort().toInt())
                        Log.d(TAG, "[$instanceId] Set MTU: " + fields[1])
                    }

                    'a' -> {
                        builder.addAddress(fields[1], fields[2].toInt())
                        Log.d(TAG, "[$instanceId] Add address: " + fields[1] + "/" + fields[2])
                    }

                    'r' -> {
                        builder.addRoute(fields[1], fields[2].toInt())
                        Log.d(TAG, "[$instanceId] Add route: " + fields[1] + "/" + fields[2])
                    }

                    'd' -> {
                        builder.addDnsServer(fields[1])
                        Log.d(TAG, "[$instanceId] Add DNS: " + fields[1])
                    }

                    's' -> {
                        builder.addSearchDomain(fields[1])
                        Log.d(TAG, "[$instanceId] Add search domain: " + fields[1])
                    }
                }
            } catch (_: NumberFormatException) {
                throw IllegalArgumentException("Bad parameter: $parameter")
            }
        }

        // Add allowed applications (allowlist filtering)
        var allowedCount = 0
        for (packageName in mAllowedPackages) {
            try {
                builder.addAllowedApplication(packageName)
                allowedCount++
                Log.d(TAG, "[$instanceId] Added allowed app to VPN: $packageName")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "[$instanceId] Package not found, skipping: $packageName", e)
            }
        }
        Log.i(TAG, "[$instanceId] Added $allowedCount allowed applications to VPN")

        // Set session and configure intent
        builder.setSession(mServerName).setConfigureIntent(mConfigureIntent!!)

        // Establish the VPN interface
        synchronized(mService) {
            val vpnInterface = builder.establish()
            if (mOnEstablishListener != null) {
                mOnEstablishListener!!.onEstablish(vpnInterface)
            }
            Log.i(TAG, "[$instanceId] VPN interface established: $vpnInterface")
            return vpnInterface
        }
    }

    companion object {
        private const val TAG = "ServerVpnConnection"

        /** Maximum packet size is constrained by the MTU  */
        private const val MAX_PACKET_SIZE = Short.MAX_VALUE.toInt()

        /** Time to wait between losing the connection and retrying  */
        private val RECONNECT_WAIT_MS = TimeUnit.SECONDS.toMillis(3)

        /** Time between keepalives if there is no traffic  */
        private val KEEPALIVE_INTERVAL_MS = TimeUnit.SECONDS.toMillis(10)

        /** Time between polling the VPN interface for new traffic  */
        private val IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100)

        /** Number of idle periods before declaring handshake failed  */
        private const val MAX_HANDSHAKE_ATTEMPTS = 50
    }
}
