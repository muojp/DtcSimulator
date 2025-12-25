package jp.muo.dtc_simulator

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * DtcVpnService - DTC Simulator VPN Service
 *
 * VPN Serviceを使用して、特定のmeta-dataタグを持つアプリケーションのみに
 * データ通信を許可するフィルタリング機能を提供します。
 */
class DtcVpnService : VpnService() {
    // Connection management (similar to ToyVpnService)
    private class Connection(
        var thread: Thread?,
        var parcelFileDescriptor: ParcelFileDescriptor,
        var serverVpnConnection: ServerVpnConnection?,
        var localVpnConnection: LocalVpnConnection? = null,
        var packetProcessor: PacketProcessor
    )

    private val mConnectingThread = AtomicReference<Thread?>()
    private val mConnection = AtomicReference<Connection?>()
    private val mNextConnectionId = AtomicInteger(1)

    private val mHandler = Handler(Looper.getMainLooper())

    private var allowlistManager: AllowlistManager? = null
    private var mConfigureIntent: PendingIntent? = null

    @Volatile
    private var isDestroyed = false

    // Connection settings for restarts
    private var currentVpnMode: String? = null
    private var currentServerAddress: String? = null
    private var currentServerSecret: String? = null

    // Network simulation settings
    private var outboundLatencyMs = 0
    private var inboundLatencyMs = 0
    private var currentPacketLoss: Float = 0f
    private var currentBandwidthKbps: Int = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        isDestroyed = false
        Log.i(TAG, "onCreate: DtcVpnService created")

        // AllowlistManagerの初期化
        allowlistManager = AllowlistManager(this)

        // Configure intentの初期化
        mConfigureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand: startId=$startId, action=${intent?.action}")

        // Handle explicit stop action
        if (intent?.action == ACTION_STOP_VPN) {
            Log.i(TAG, "onStartCommand: Received STOP action")
            disconnect()
            stopSelf()
            return START_NOT_STICKY
        }

        if (isDestroyed) {
            Log.w(TAG, "onStartCommand: service is already marked as destroyed, ignoring")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // フォアグラウンドサービスとして通知表示
        startForegroundWithNotification()

        // Get VPN mode from intent (default to LOCAL mode)
        val vpnMode = intent?.getStringExtra(MainActivity.EXTRA_VPN_MODE) ?: MainActivity.VPN_MODE_LOCAL

        // Get server configuration from intent
        val serverAddress = intent?.getStringExtra(MainActivity.EXTRA_SERVER_ADDRESS) ?: BuildConfig.DEFAULT_SERVER_ADDRESS
        val serverSecret = intent?.getStringExtra(MainActivity.EXTRA_SERVER_SECRET) ?: BuildConfig.DEFAULT_SERVER_SECRET

        // Get initial latency values
        outboundLatencyMs = intent?.getIntExtra(MainActivity.EXTRA_OUTBOUND_LATENCY, 0) ?: 0
        inboundLatencyMs = intent?.getIntExtra(MainActivity.EXTRA_INBOUND_LATENCY, 0) ?: 0

        // Get packet loss and bandwidth values
        val packetLoss = intent?.getFloatExtra(MainActivity.EXTRA_PACKET_LOSS, 0f) ?: 0f
        val bandwidthKbps = intent?.getIntExtra(MainActivity.EXTRA_BANDWIDTH, 0) ?: 0

        Log.i(TAG, "onStartCommand: mode=$vpnMode")
        Log.i(TAG, "  Latency: Outbound=$outboundLatencyMs ms, Inbound=$inboundLatencyMs ms")
        Log.i(TAG, "  Packet Loss: $packetLoss%")
        Log.i(TAG, "  Bandwidth: $bandwidthKbps kbps")

        // 重い処理をバックグラウンドスレッドで実行
        Thread({
            try {
                if (isDestroyed) return@Thread

                // 許可アプリリストの初期化
                Log.i(TAG, "onStartCommand: Building allowlist...")
                allowlistManager!!.scanAndBuildAllowlist()
                Log.i(TAG, "onStartCommand: Allowlist contains " + allowlistManager!!.allowlistSize + " packages")

                // サーバー接続の開始をHandler経由でシリアル化
                mHandler.post {
                    if (isDestroyed) return@post
                    Log.d(TAG, "onStartCommand: calling connect() via Handler")
                    connect(vpnMode, serverAddress, serverSecret, packetLoss, bandwidthKbps)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during service start", e)
            }
        }, "DtcVpnServiceStartThread").start()

        return START_STICKY
    }

    override fun onRevoke() {
        Log.i(TAG, "onRevoke: VPN permission revoked")
        disconnect()
        super.onRevoke()
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: DtcVpnService stopping...")
        isDestroyed = true

        // 接続の停止
        disconnect()

        // 通知を送信してUIを更新させる
        Log.d(TAG, "onDestroy: Sending ACTION_VPN_STOPPED broadcast")
        val intent = Intent(ACTION_VPN_STOPPED)
        intent.`package` = packageName
        sendBroadcast(intent)

        instance = null
        Log.i(TAG, "onDestroy: DtcVpnService stopped and instance cleared")
        super.onDestroy()
    }

    /**
     * Update latency settings for the current connection
     */
    fun updateLatency(outboundMs: Int, inboundMs: Int) {
        this.outboundLatencyMs = outboundMs
        this.inboundLatencyMs = inboundMs

        Log.i(TAG, "Updating latency: outbound=$outboundMs ms, inbound=$inboundMs ms")

        val conn = mConnection.get()
        if (conn != null) {
            // Update via centralized PacketProcessor
            conn.packetProcessor.setLatency(outboundMs, inboundMs)
        }
    }

    /**
     * Update network profile for advanced configuration
     * @param profile NetworkProfile configuration or null to clear
     */
    fun updateNetworkProfile(profile: NetworkProfile?) {
        Log.i(TAG, "Updating network profile: ${if (profile != null) "set" else "cleared"}")

        val conn = mConnection.get()
        if (conn != null) {
            if (profile != null) {
                conn.packetProcessor.setNetworkProfile(profile)
            } else {
                // Clear profile by resetting to simple values
                conn.packetProcessor.setLatency(outboundLatencyMs, inboundLatencyMs)
                conn.packetProcessor.setPacketLossRate(0f)
                conn.packetProcessor.setBandwidth(0)
            }
        }
    }

    /**
     * Update packet loss rate
     * @param lossPercent Packet loss rate in percentage (0-100)
     */
    fun updatePacketLoss(lossPercent: Float) {
        Log.i(TAG, "Updating packet loss: $lossPercent%")

        val conn = mConnection.get()
        if (conn != null) {
            // Convert percentage to rate (0-1)
            conn.packetProcessor.setPacketLossRate(lossPercent / 100f)
        }
    }

    /**
     * Update bandwidth limit
     * @param bandwidthKbps Bandwidth limit in kbps (0 = unlimited)
     */
    fun updateBandwidth(bandwidthKbps: Int) {
        Log.i(TAG, "Updating bandwidth: $bandwidthKbps kbps")

        val conn = mConnection.get()
        if (conn != null) {
            // Convert kbps to bytes/sec
            val bytesPerSec = if (bandwidthKbps > 0) (bandwidthKbps * 1024L / 8) else 0L
            conn.packetProcessor.setBandwidth(bytesPerSec)
        }
    }

    /**
     * サーバーへの接続を開始
     */
    private fun connect(vpnMode: String, serverAddressPort: String, sharedSecret: String, packetLoss: Float, bandwidthKbps: Int) {
        if (isDestroyed) {
            Log.w(TAG, "connect() ignored: service is destroyed")
            return
        }

        // Ensure this runs on the serialized handler
        if (Looper.myLooper() != mHandler.looper) {
            mHandler.post { connect(vpnMode, serverAddressPort, sharedSecret, packetLoss, bandwidthKbps) }
            return
        }

        Log.i(TAG, "connect: mode=$vpnMode, addr=$serverAddressPort")

        this.currentVpnMode = vpnMode
        this.currentServerAddress = serverAddressPort
        this.currentServerSecret = sharedSecret
        this.currentPacketLoss = packetLoss
        this.currentBandwidthKbps = bandwidthKbps

        // Stop any existing connection attempt first
        setConnectingThread(null)

        // Get allowed packages from AllowlistManager (merges meta-data apps with user overrides)
        val allowedPackages = allowlistManager!!.getFinalAllowedPackages().toMutableSet()
        Log.i(TAG, "Allowed packages: " + allowedPackages.size + " apps")

        when (vpnMode) {
            MainActivity.VPN_MODE_SERVER -> {
                val parts = serverAddressPort.split(":")
                val serverAddress = if (parts.isNotEmpty()) parts[0] else DEFAULT_SERVER_ADDRESS
                val serverPort = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_SERVER_PORT else DEFAULT_SERVER_PORT

                Log.i(TAG, "Connecting to server: $serverAddress:$serverPort")

                // Create centralized PacketProcessor
                val packetProcessor = PacketProcessor()
                packetProcessor.setLatency(outboundLatencyMs, inboundLatencyMs)
                packetProcessor.setPacketLossRate(packetLoss / 100f)
                val bytesPerSec = if (bandwidthKbps > 0) (bandwidthKbps * 1024L / 8) else 0L
                packetProcessor.setBandwidth(bytesPerSec)

                val connection = ServerVpnConnection(
                    this,
                    serverAddress,
                    serverPort,
                    sharedSecret.toByteArray(),
                    allowedPackages,
                    packetProcessor
                )

                startServerConnection(connection, packetProcessor)
            }
            MainActivity.VPN_MODE_LOCAL -> {
                Log.i(TAG, "Starting local VPN connection")
                startLocalConnection(allowedPackages, packetLoss, bandwidthKbps)
            }
            else -> {
                Log.e(TAG, "Unknown VPN mode: $vpnMode, defaulting to LOCAL")
                connect(MainActivity.VPN_MODE_LOCAL, serverAddressPort, sharedSecret, packetLoss, bandwidthKbps)
            }
        }
    }

    private fun startServerConnection(connection: ServerVpnConnection, packetProcessor: PacketProcessor) {
        val thread = Thread(connection, "ServerVpnConnectionThread")
        setConnectingThread(thread)

        connection.setConfigureIntent(mConfigureIntent!!)
        connection.setOnEstablishListener(object : ServerVpnConnection.OnEstablishListener {
            override fun onEstablish(tunInterface: ParcelFileDescriptor?) {
                mHandler.post {
                    Log.i(TAG, "VPN connection established: thread=${thread.id}")
                    if (mConnectingThread.get() === thread) {
                        mConnectingThread.set(null)
                        setConnection(Connection(thread, tunInterface!!, connection, null, packetProcessor))
                    } else {
                        Log.w(TAG, "Outdated thread established connection, closing")
                        try {
                            tunInterface?.close()
                            connection.stop()
                        } catch (e: Exception) {
                            Log.e(TAG, "Error closing outdated interface", e)
                        }
                    }
                }
            }
        })

        connection.setOnDisconnectListener(object : ServerVpnConnection.OnDisconnectListener {
            override fun onDisconnect(isFatal: Boolean, message: String?) {
                mHandler.post {
                    if (isFatal && instance != null) {
                        Log.e(TAG, "Fatal connection error: $message")
                        android.widget.Toast.makeText(
                            applicationContext,
                            "VPN Connection Failed: ${message ?: "Server unreachable"}",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                        disconnect()
                        stopSelf()
                        return@post
                    }

                    if (instance != null && currentVpnMode == MainActivity.VPN_MODE_SERVER) {
                        val isCurrentConnection = mConnection.get()?.serverVpnConnection === connection
                        val isLatestAttempt = mConnectingThread.get() === thread
                        
                        if (isCurrentConnection || isLatestAttempt) {
                            Log.i(TAG, "Active connection disconnected, scheduling reconnection...")
                            mHandler.postDelayed({
                                val mode = currentVpnMode
                                val addr = currentServerAddress
                                val secret = currentServerSecret
                                val loss = currentPacketLoss
                                val bw = currentBandwidthKbps
                                if (instance != null && mode != null && addr != null && secret != null) {
                                    connect(mode, addr, secret, loss, bw)
                                }
                            }, 3000)
                        }
                    }
                }
            }
        })

        thread.start()
    }

    private fun startLocalConnection(allowedPackages: MutableSet<String>, packetLoss: Float, bandwidthKbps: Int) {
        val vpnInterface = establishVpnInterface(allowedPackages)
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface for local mode")
            return
        }

        // Create centralized PacketProcessor
        val packetProcessor = PacketProcessor()
        packetProcessor.setLatency(outboundLatencyMs, inboundLatencyMs)
        packetProcessor.setPacketLossRate(packetLoss / 100f)
        val bytesPerSec = if (bandwidthKbps > 0) (bandwidthKbps * 1024L / 8) else 0L
        packetProcessor.setBandwidth(bytesPerSec)

        val connection = LocalVpnConnection(this, vpnInterface, packetProcessor)
        val thread = Thread(connection, "LocalVpnConnectionThread")

        setConnectingThread(null)
        setConnection(Connection(thread, vpnInterface, null, connection, packetProcessor))

        thread.start()
        Log.i(TAG, "Local VPN connection started")
    }

    private fun establishVpnInterface(allowedPackages: MutableSet<String>): ParcelFileDescriptor? {
        try {
            val builder = Builder()
            builder.setSession("DtcSimulator-Local")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)

            for (packageName in allowedPackages) {
                try {
                    builder.addAllowedApplication(packageName)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "Package not found: $packageName", e)
                }
            }

            return builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "Error establishing VPN interface", e)
            return null
        }
    }

    private fun setConnectingThread(thread: Thread?) {
        val oldThread = mConnectingThread.getAndSet(thread)
        if (oldThread != null && oldThread !== thread) {
            Log.i(TAG, "Interrupting old connecting thread: ${oldThread.id}")
            oldThread.interrupt()
        }
    }

    private fun setConnection(connection: Connection?) {
        val oldConnection = mConnection.getAndSet(connection)
        if (oldConnection != null && oldConnection !== connection) {
            val currentThread = Thread.currentThread()
            Log.i(TAG, "setConnection: Closing old connection (oldThread=${oldConnection.thread?.id})")
            
            try {
                if (oldConnection.serverVpnConnection != null) {
                    Log.d(TAG, "setConnection: Stopping serverVpnConnection")
                    oldConnection.serverVpnConnection!!.stop()
                }
                if (oldConnection.localVpnConnection != null) {
                    Log.d(TAG, "setConnection: Stopping localVpnConnection")
                    oldConnection.localVpnConnection!!.stop()
                }
                
                if (oldConnection.thread != null && oldConnection.thread !== currentThread) {
                    Log.i(TAG, "setConnection: Interrupting thread ${oldConnection.thread!!.id}")
                    oldConnection.thread!!.interrupt()
                }
                
                oldConnection.parcelFileDescriptor.close()
                Log.d(TAG, "setConnection: parcelFileDescriptor closed")
            } catch (e: IOException) {
                Log.e(TAG, "Error closing VPN interface", e)
            }
        }
    }

    private fun disconnect() {
        Log.i(TAG, "disconnect() called")
        setConnectingThread(null)
        setConnection(null)
        stopForeground(true)
        Log.i(TAG, "disconnect() finished")
    }

    private fun startForegroundWithNotification() {
        createNotificationChannel()

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DTC Network Simulator")
            .setContentText("Simulating satellite DTC network latency")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        )
        Log.i(TAG, "Foreground notification displayed")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "DTC Simulator VPN Service notifications"
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }

    val isRunning: Boolean
        get() {
            val conn = mConnection.get()
            return conn != null && conn.thread != null && conn.thread!!.isAlive
        }

    val stats: VpnStats?
        get() {
            val conn = mConnection.get()
            if (conn != null) {
                return conn.packetProcessor.stats
            }
            return null
        }

    companion object {
        private const val TAG = "DtcVpnService"
        const val ACTION_STOP_VPN = "jp.muo.dtc_simulator.ACTION_STOP_VPN"
        const val ACTION_VPN_STOPPED = "jp.muo.dtc_simulator.ACTION_VPN_STOPPED"

        var instance: DtcVpnService? = null
            private set

        private const val CHANNEL_ID = "DtcVpnServiceChannel"
        private const val CHANNEL_NAME = "DTC VPN Service"
        private const val NOTIFICATION_ID = 1

        private const val DEFAULT_SERVER_ADDRESS = "192.168.0.157"
        private const val DEFAULT_SERVER_PORT = 8000
    }
}