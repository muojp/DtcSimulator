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
 *
 * ToyVpnのアプローチを使用し、外部サーバー経由で通信を転送します。
 *
 * Phase 1: 基本的な通信フィルタリング
 * - AllowlistManagerによるアプリケーションフィルタリング
 * - 許可されたアプリケーションのみVPNを使用可能
 * - 外部サーバー経由でパケット転送
 *
 * Phase 2: 衛星通信シミュレーション（将来実装）
 * - レイテンシーシミュレーション
 * - スループット制限
 * - パケットロス再現
 */
class DtcVpnService : VpnService() {
    // Connection management (similar to ToyVpnService)
    private class Connection(
        var thread: Thread?,
        var parcelFileDescriptor: ParcelFileDescriptor,
        var serverVpnConnection: ServerVpnConnection?,
        var localVpnConnection: LocalVpnConnection? = null
    )

    private val mConnectingThread = AtomicReference<Thread?>()
    private val mConnection = AtomicReference<Connection?>()
    private val mNextConnectionId = AtomicInteger(1)

    private var allowlistManager: AllowlistManager? = null
    private var mConfigureIntent: PendingIntent? = null

    private var wakeLock: PowerManager.WakeLock? = null

    // Latency settings
    private var outboundLatencyMs = 0
    private var inboundLatencyMs = 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "DtcVpnService created")

        // AllowlistManagerの初期化
        allowlistManager = AllowlistManager(this)

        // Configure intentの初期化
        mConfigureIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        // WakeLockの初期化
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DtcSimulator:VpnWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "DtcVpnService starting...")

        // フォアグラウンドサービスとして通知表示
        startForegroundWithNotification()

        // WakeLockの取得 (10分間。再送出時に必要。トラフィックがあれば維持される)
        wakeLock?.acquire(10 * 60 * 1000L)

        // Get VPN mode from intent (default to LOCAL mode)
        val vpnMode = intent?.getStringExtra(MainActivity.EXTRA_VPN_MODE) ?: MainActivity.VPN_MODE_LOCAL

        // Get server configuration from intent
        val serverAddress = intent?.getStringExtra(MainActivity.EXTRA_SERVER_ADDRESS) ?: BuildConfig.DEFAULT_SERVER_ADDRESS
        val serverSecret = intent?.getStringExtra(MainActivity.EXTRA_SERVER_SECRET) ?: BuildConfig.DEFAULT_SERVER_SECRET

        // Get initial latency values
        outboundLatencyMs = intent?.getIntExtra(MainActivity.EXTRA_OUTBOUND_LATENCY, 0) ?: 0
        inboundLatencyMs = intent?.getIntExtra(MainActivity.EXTRA_INBOUND_LATENCY, 0) ?: 0
        Log.i(TAG, "VPN Mode: $vpnMode, Latency: Outbound=$outboundLatencyMs ms, Inbound=$inboundLatencyMs ms")

        // 重い処理をバックグラウンドスレッドで実行
        Thread({
            try {
                // 許可アプリリストの初期化
                Log.i(TAG, "Building allowlist...")
                allowlistManager!!.scanAndBuildAllowlist()
                Log.i(TAG, "Allowlist contains " + allowlistManager!!.allowlistSize + " packages")

                // サーバー接続の開始
                connect(vpnMode, serverAddress, serverSecret)
                Log.i(TAG, "DtcVpnService connection initiated")
            } catch (e: Exception) {
                Log.e(TAG, "Error during service start", e)
            }
        }, "DtcVpnServiceStartThread").start()

        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "DtcVpnService stopping...")

        // 接続の停止
        disconnect()

        // WakeLockの解放
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }

        instance = null
        Log.i(TAG, "DtcVpnService stopped")
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
            conn.serverVpnConnection?.updateLatency(outboundMs, inboundMs)
            conn.localVpnConnection?.updateLatency(outboundMs, inboundMs)
        }
    }

    /**
     * サーバーへの接続を開始
     */
    private fun connect(vpnMode: String, serverAddressPort: String, sharedSecret: String) {
        // Get allowed packages from AllowlistManager
        val allowedPackages = allowlistManager!!.getAllowedPackages().toMutableSet()
        Log.i(TAG, "Allowed packages: " + allowedPackages.size + " apps")

        when (vpnMode) {
            MainActivity.VPN_MODE_SERVER -> {
                // Parse server address and port from "address:port" format
                val parts = serverAddressPort.split(":")
                val serverAddress = if (parts.isNotEmpty()) parts[0] else DEFAULT_SERVER_ADDRESS
                val serverPort = if (parts.size > 1) parts[1].toIntOrNull() ?: DEFAULT_SERVER_PORT else DEFAULT_SERVER_PORT

                Log.i(TAG, "Connecting to server: $serverAddress:$serverPort")

                // ServerVpnConnectionを作成して接続
                val connection = ServerVpnConnection(
                    this,
                    serverAddress,
                    serverPort,
                    sharedSecret.toByteArray(),
                    allowedPackages
                )
                connection.updateLatency(outboundLatencyMs, inboundLatencyMs)

                startServerConnection(connection)
            }
            MainActivity.VPN_MODE_LOCAL -> {
                Log.i(TAG, "Starting local VPN connection")
                startLocalConnection(allowedPackages)
            }
            else -> {
                Log.e(TAG, "Unknown VPN mode: $vpnMode, defaulting to LOCAL mode")
                connect(MainActivity.VPN_MODE_LOCAL, serverAddressPort, sharedSecret)
            }
        }
    }

    /**
     * ServerVpnConnectionを起動
     */
    private fun startServerConnection(connection: ServerVpnConnection) {
        // 新しい接続スレッドを作成
        val thread = Thread(connection, "ServerVpnConnectionThread")
        setConnectingThread(thread)

        // VPNインターフェース確立時のリスナーを設定
        connection.setConfigureIntent(mConfigureIntent!!)
        connection.setOnEstablishListener(object : ServerVpnConnection.OnEstablishListener {
            override fun onEstablish(tunInterface: ParcelFileDescriptor?) {
                Log.i(TAG, "VPN connection established")
                mConnectingThread.compareAndSet(thread, null)
                setConnection(Connection(thread, tunInterface!!, connection, null))
            }
        })

        thread.start()
    }

    /**
     * LocalVpnConnectionを起動
     */
    private fun startLocalConnection(allowedPackages: MutableSet<String>) {
        // VPNインターフェースを確立
        val vpnInterface = establishVpnInterface(allowedPackages)
        if (vpnInterface == null) {
            Log.e(TAG, "Failed to establish VPN interface for local mode")
            return
        }

        // LocalVpnConnectionを作成
        val connection = LocalVpnConnection(this, vpnInterface)
        connection.updateLatency(outboundLatencyMs, inboundLatencyMs)
        val thread = Thread(connection, "LocalVpnConnectionThread")

        setConnectingThread(null)
        setConnection(Connection(thread, vpnInterface, null, connection))

        thread.start()
        Log.i(TAG, "Local VPN connection started")
    }

    /**
     * VPNインターフェースを確立（LocalVpnConnection用）
     */
    private fun establishVpnInterface(allowedPackages: MutableSet<String>): ParcelFileDescriptor? {
        try {
            val builder = Builder()
            builder.setSession("DtcSimulator-Local")
                .setMtu(1500)
                .addAddress("10.0.0.2", 24)
                .addRoute("0.0.0.0", 0)

            // 許可されたアプリケーションのみ追加
            for (packageName in allowedPackages) {
                try {
                    builder.addAllowedApplication(packageName)
                    Log.d(TAG, "Added allowed app: $packageName")
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

    /**
     * 接続スレッドを設定（既存のスレッドがあれば中断）
     */
    private fun setConnectingThread(thread: Thread?) {
        val oldThread = mConnectingThread.getAndSet(thread)
        oldThread?.interrupt()
    }

    /**
     * 接続を設定（既存の接続があればクローズ）
     */
    private fun setConnection(connection: Connection?) {
        val oldConnection = mConnection.getAndSet(connection)
        if (oldConnection != null) {
            try {
                // Send disconnect message to server
                if (oldConnection.serverVpnConnection != null) {
                    oldConnection.serverVpnConnection!!.stop()
                }
                if (oldConnection.localVpnConnection != null) {
                    oldConnection.localVpnConnection!!.stop()
                }
                oldConnection.thread!!.interrupt()
                oldConnection.parcelFileDescriptor.close()
            } catch (e: IOException) {
                Log.e(TAG, "Error closing VPN interface", e)
            }
        }
    }

    /**
     * 接続を切断
     */
    private fun disconnect() {
        setConnectingThread(null)
        setConnection(null)
        stopForeground(true)
    }

    /**
     * フォアグラウンドサービスとして通知を表示する
     */
    private fun startForegroundWithNotification() {
        createNotificationChannel()

        // MainActivityを開くPendingIntent
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // 通知の作成
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DTC Simulator")
            .setContentText("VPN service is running - Filtering network traffic")
            .setSmallIcon(R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        // Android 14 (API 34) 以降では、specialUseタイプを指定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        Log.i(TAG, "Foreground notification displayed")
    }

    /**
     * 通知チャンネルを作成する（Android 8.0以降）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "DTC Simulator VPN Service notifications"

            val manager = getSystemService(NotificationManager::class.java)
            if (manager != null) {
                manager.createNotificationChannel(channel)
                Log.d(TAG, "Notification channel created")
            }
        }
    }

    val isRunning: Boolean
        /**
         * VPNサービスが実行中かどうかを確認する
         *
         * @return 実行中の場合true
         */
        get() {
            val conn = mConnection.get()
            return conn != null && conn.thread != null && conn.thread!!.isAlive
        }

    val stats: VpnStats?
        /**
         * VPN統計情報を取得する
         *
         * @return 統計情報、接続が確立されていない場合はnull
         */
        get() {
            val conn = mConnection.get()
            if (conn != null) {
                if (conn.serverVpnConnection != null) {
                    return conn.serverVpnConnection!!.stats
                } else if (conn.localVpnConnection != null) {
                    return conn.localVpnConnection!!.stats
                }
            }
            return null
        }

    companion object {
        private const val TAG = "DtcVpnService"

        /**
         * Get the current service instance
         * @return current instance or null if service is not running
         */
        // Static instance for accessing from MainActivity
        var instance: DtcVpnService? = null
            private set

        // Notification constants
        private const val CHANNEL_ID = "DtcVpnServiceChannel"
        private const val CHANNEL_NAME = "DTC VPN Service"
        private const val NOTIFICATION_ID = 1

        // Default server settings (fallback values)
        private const val DEFAULT_SERVER_ADDRESS = "192.168.0.157"
        private const val DEFAULT_SERVER_PORT = 8000
    }
}
