package jp.muo.dtc_simulator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.util.Locale

/**
 * MainActivity
 *
 * Main activity for DTC Simulator application.
 * Provides UI controls for:
 * - Starting/stopping VPN service
 * - Scanning installed applications for allowed meta-data
 * - Displaying list of allowed applications
 */
class MainActivity : AppCompatActivity() {
    // UI Components
    private var toolbar: MaterialToolbar? = null
    private var tvVpnDescription: TextView? = null
    private var rgVpnMode: RadioGroup? = null
    private var rbServerMode: RadioButton? = null
    private var rbLocalMode: RadioButton? = null
    private var btnStartVpn: MaterialButton? = null
    private var btnTestUdp: MaterialButton? = null
    private var tvUdpDescription: TextView? = null
    private var rvAllowedApps: RecyclerView? = null
    private var tvAllowedCount: TextView? = null
    private var tvEmptyState: TextView? = null
    private var tvUdpResult: TextView? = null

    // Latency Simulation UI Components
    private var tvOutboundLatency: TextView? = null
    private var sliderOutboundLatency: com.google.android.material.slider.Slider? = null
    private var tvInboundLatency: TextView? = null
    private var sliderInboundLatency: com.google.android.material.slider.Slider? = null

    // Statistics UI Components
    private var cardStatistics: MaterialCardView? = null
    private var tvStatsSent: TextView? = null
    private var tvStatsSentPackets: TextView? = null
    private var tvStatsReceived: TextView? = null
    private var tvStatsReceivedPackets: TextView? = null
    private var tvStatsOutgoingBuffer: TextView? = null
    private var tvStatsIncomingBuffer: TextView? = null

    // Business Logic
    private var allowlistManager: AllowlistManager? = null
    private var adapter: AllowedAppsAdapter? = null
    private var isVpnRunning = false

    // Statistics Update Timer
    private var statsHandler: Handler? = null
    private var statsUpdateRunnable: Runnable? = null

    private val vpnStateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action == DtcVpnService.ACTION_VPN_STOPPED) {
                Log.d(TAG, "Received ACTION_VPN_STOPPED broadcast")
                isVpnRunning = false
                updateVpnButton()
                stopStatsUpdates()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize AllowlistManager
        allowlistManager = AllowlistManager(this)

        // Initialize UI components
        initializeViews()
        setupToolbar()
        setupRecyclerView()
        setupButtons()
        setupVpnModeListener()
        setupLatencyControls()
        setupStatsTimer()
        updateUdpEchoDescription()
        checkNotificationPermission()

        // Register VPN state receiver
        Log.d(TAG, "onCreate: Registering ACTION_VPN_STOPPED receiver")
        val filter = android.content.IntentFilter(DtcVpnService.ACTION_VPN_STOPPED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(vpnStateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(vpnStateReceiver, filter)
        }

        // Auto-scan on startup (background thread)
        Thread {
            try {
                allowlistManager!!.scanAndBuildAllowlist()
                runOnUiThread {
                    updateAllowedAppsList()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during auto-scan", e)
            }
        }.start()
    }

    /**
     * Setup latency simulation controls
     */
    private fun setupLatencyControls() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Load saved values
        val savedOutboundPercent = prefs.getFloat(PREF_OUTBOUND_LATENCY_PERCENT, 0f)
        val savedInboundPercent = prefs.getFloat(PREF_INBOUND_LATENCY_PERCENT, 0f)

        // Set slider values
        sliderOutboundLatency?.value = savedOutboundPercent
        sliderInboundLatency?.value = savedInboundPercent

        // Update text displays
        tvOutboundLatency?.text = "${calculateLatency(savedOutboundPercent)} ms"
        tvInboundLatency?.text = "${calculateLatency(savedInboundPercent)} ms"

        val listener = { slider: com.google.android.material.slider.Slider, value: Float, _: Boolean ->
            val latencyMs = calculateLatency(value)
            val isOutbound = slider.id == R.id.slider_outbound_latency

            if (isOutbound) {
                tvOutboundLatency?.text = "$latencyMs ms"
                // Save to SharedPreferences
                prefs.edit().putFloat(PREF_OUTBOUND_LATENCY_PERCENT, value).apply()
            } else {
                tvInboundLatency?.text = "$latencyMs ms"
                // Save to SharedPreferences
                prefs.edit().putFloat(PREF_INBOUND_LATENCY_PERCENT, value).apply()
            }

            // Update service if running
            updateServiceLatency()
        }

        sliderOutboundLatency?.addOnChangeListener(listener)
        sliderInboundLatency?.addOnChangeListener(listener)
    }

    /**
     * Check and request notification permission for Android 13+
     */
    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION_REQUEST_CODE)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Notification permission granted")
            } else {
                Toast.makeText(this, "Notification permission is required for VPN service to run correctly in background", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Map slider value (0-100) to exponential latency (ms)
     * 0% -> 0ms
     * 25% -> 10ms
     * 50% -> 100ms
     * 75% -> 1000ms
     * 100% -> 10000ms
     */
    private fun calculateLatency(percent: Float): Int {
        if (percent <= 0f) return 0
        return Math.pow(10.0, percent / 25.0).toInt()
    }

    /**
     * Update latency settings in the running VPN service
     */
    private fun updateServiceLatency() {
        val service = DtcVpnService.instance ?: return
        
        val outboundPercent = sliderOutboundLatency?.value ?: 0f
        val inboundPercent = sliderInboundLatency?.value ?: 0f
        
        val outboundMs = calculateLatency(outboundPercent)
        val inboundMs = calculateLatency(inboundPercent)
        
        service.updateLatency(outboundMs, inboundMs)
    }

    /**
     * Initialize all view references
     */
    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        tvVpnDescription = findViewById(R.id.tv_vpn_description)
        rgVpnMode = findViewById(R.id.rg_vpn_mode)
        rbServerMode = findViewById(R.id.rb_server_mode)
        rbLocalMode = findViewById(R.id.rb_local_mode)
        btnStartVpn = findViewById(R.id.btn_start_vpn)
        btnTestUdp = findViewById(R.id.btn_test_udp)
        tvUdpDescription = findViewById(R.id.tv_udp_description)
        rvAllowedApps = findViewById(R.id.rv_allowed_apps)
        tvAllowedCount = findViewById(R.id.tv_allowed_count)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        tvUdpResult = findViewById(R.id.tv_udp_result)

        // Latency views
        tvOutboundLatency = findViewById(R.id.tv_outbound_latency)
        sliderOutboundLatency = findViewById(R.id.slider_outbound_latency)
        tvInboundLatency = findViewById(R.id.tv_inbound_latency)
        sliderInboundLatency = findViewById(R.id.slider_inbound_latency)

        // Statistics views
        cardStatistics = findViewById(R.id.card_statistics)
        tvStatsSent = findViewById(R.id.tv_stats_sent)
        tvStatsSentPackets = findViewById(R.id.tv_stats_sent_packets)
        tvStatsReceived = findViewById(R.id.tv_stats_received)
        tvStatsReceivedPackets = findViewById(R.id.tv_stats_received_packets)
        tvStatsOutgoingBuffer = findViewById(R.id.tv_stats_outgoing_buffer)
        tvStatsIncomingBuffer = findViewById(R.id.tv_stats_incoming_buffer)
    }

    /**
     * Setup toolbar as action bar
     */
    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        if (supportActionBar != null) {
            supportActionBar!!.setTitle(R.string.app_name)
        }
    }

    /**
     * Setup RecyclerView with adapter and layout manager
     */
    private fun setupRecyclerView() {
        adapter = AllowedAppsAdapter(this)
        rvAllowedApps!!.setLayoutManager(LinearLayoutManager(this))
        rvAllowedApps!!.setAdapter(adapter)
    }

    /**
     * Setup button click listeners
     */
    private fun setupButtons() {
        btnStartVpn!!.setOnClickListener { _: View? -> toggleVpn() }
        btnTestUdp!!.setOnClickListener { _: View? -> testUdpEcho() }
    }

    /**
     * Update UDP echo test description with current server address
     */
    private fun updateUdpEchoDescription() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val serverAddressPort = prefs.getString(PREF_SERVER_ADDRESS, BuildConfig.DEFAULT_SERVER_ADDRESS) ?: BuildConfig.DEFAULT_SERVER_ADDRESS

        // Extract IP address from "IP:PORT" format
        val parts = serverAddressPort.split(":")
        val host = if (parts.isNotEmpty()) parts[0] else "192.168.0.157"

        // Use UDP echo port from BuildConfig
        val port = BuildConfig.DEFAULT_UDP_ECHO_PORT

        tvUdpDescription?.text = "Test connectivity to the UDP echo server at $host:$port"
    }

    /**
     * Setup VPN mode radio group listener
     */
    private fun setupVpnModeListener() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Restore last-used VPN mode
        val lastVpnMode = prefs.getString(PREF_VPN_MODE, VPN_MODE_LOCAL) ?: VPN_MODE_LOCAL
        when (lastVpnMode) {
            VPN_MODE_SERVER -> rbServerMode?.isChecked = true
            VPN_MODE_LOCAL -> rbLocalMode?.isChecked = true
        }

        rgVpnMode?.setOnCheckedChangeListener { _, checkedId ->
            val selectedMode = when (checkedId) {
                R.id.rb_server_mode -> VPN_MODE_SERVER
                R.id.rb_local_mode -> VPN_MODE_LOCAL
                else -> VPN_MODE_LOCAL
            }

            // Save selected mode
            prefs.edit().putString(PREF_VPN_MODE, selectedMode).apply()

            if (checkedId == R.id.rb_server_mode) {
                // Check if server config is saved
                val hasServerAddress = prefs.contains(PREF_SERVER_ADDRESS)
                val hasServerSecret = prefs.contains(PREF_SERVER_SECRET)

                // If not configured, open settings
                if (!hasServerAddress || !hasServerSecret) {
                    openSettings()
                }
            }
        }
    }

    /**
     * Open settings activity
     */
    private fun openSettings() {
        val intent = Intent(this, SettingsActivity::class.java)
        startActivity(intent)
    }

    /**
     * Toggle VPN on/off
     */
    private fun toggleVpn() {
        Log.d(TAG, "toggleVpn: isVpnRunning=$isVpnRunning")
        if (isVpnRunning) {
            stopVpn()
        } else {
            startVpn()
        }
    }

    /**
     * Start VPN service
     * Requests VPN permission if not already granted
     */
    private fun startVpn() {
        Log.d(TAG, "startVpn called")
        val intent = VpnService.prepare(this)
        if (intent != null) {
            // VPN permission is required
            Log.i(TAG, "VPN permission required, starting activity for result")
            startActivityForResult(intent, VPN_REQUEST_CODE)
        } else {
            // VPN permission already granted
            Log.i(TAG, "VPN permission already granted, starting service")
            startVpnService()
        }
    }

    /**
     * Stop VPN service
     */
    private fun stopVpn() {
        Log.d(TAG, "stopVpn: sending stop signal to service")
        val stopIntent = Intent(this, DtcVpnService::class.java).apply {
            action = DtcVpnService.ACTION_STOP_VPN
        }
        startService(stopIntent)
        
        val serviceIntent = Intent(this, DtcVpnService::class.java)
        val stopped = stopService(serviceIntent)
        Log.d(TAG, "stopService returned: $stopped")
        
        isVpnRunning = false
        updateVpnButton()
        stopStatsUpdates()
        Toast.makeText(this, R.string.vpn_stopped, Toast.LENGTH_SHORT).show()
    }

    /**
     * Handle VPN permission result
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                startVpnService()
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * Actually start the VPN service
     */
    private fun startVpnService() {
        val serviceIntent = Intent(this, DtcVpnService::class.java)

        // Get selected VPN mode and add to intent
        val vpnMode = if (rbServerMode?.isChecked == true) {
            VPN_MODE_SERVER
        } else {
            VPN_MODE_LOCAL
        }
        serviceIntent.putExtra(EXTRA_VPN_MODE, vpnMode)

        // Add server configuration from SharedPreferences (only used in server mode)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val serverAddress = prefs.getString(PREF_SERVER_ADDRESS, BuildConfig.DEFAULT_SERVER_ADDRESS) ?: BuildConfig.DEFAULT_SERVER_ADDRESS
        val serverSecret = prefs.getString(PREF_SERVER_SECRET, BuildConfig.DEFAULT_SERVER_SECRET) ?: BuildConfig.DEFAULT_SERVER_SECRET
        serviceIntent.putExtra(EXTRA_SERVER_ADDRESS, serverAddress)
        serviceIntent.putExtra(EXTRA_SERVER_SECRET, serverSecret)

        // Add current latency settings
        val outboundMs = calculateLatency(sliderOutboundLatency?.value ?: 0f)
        val inboundMs = calculateLatency(sliderInboundLatency?.value ?: 0f)
        serviceIntent.putExtra(EXTRA_OUTBOUND_LATENCY, outboundMs)
        serviceIntent.putExtra(EXTRA_INBOUND_LATENCY, inboundMs)

        startService(serviceIntent)
        isVpnRunning = true
        updateVpnButton()
        startStatsUpdates()
        Toast.makeText(this, R.string.vpn_running, Toast.LENGTH_SHORT).show()
    }

    /**
     * Update VPN button text and icon based on VPN state
     */
    private fun updateVpnButton() {
        if (isVpnRunning) {
            btnStartVpn!!.setText(R.string.stop_vpn)
            btnStartVpn!!.setIcon(getDrawable(android.R.drawable.ic_media_pause))
            tvVpnDescription?.visibility = View.GONE
            // Disable VPN mode selection when VPN is running
            rgVpnMode?.isEnabled = false
            rbServerMode?.isEnabled = false
            rbLocalMode?.isEnabled = false
        } else {
            btnStartVpn!!.setText(R.string.start_vpn)
            btnStartVpn!!.setIcon(getDrawable(android.R.drawable.ic_media_play))
            tvVpnDescription?.visibility = View.VISIBLE
            // Enable VPN mode selection when VPN is stopped
            rgVpnMode?.isEnabled = true
            rbServerMode?.isEnabled = true
            rbLocalMode?.isEnabled = true
        }
    }

    /**
     * Update the RecyclerView with the current list of allowed apps
     */
    private fun updateAllowedAppsList() {
        val allowedPackages = allowlistManager!!.getAllowedPackages()
        val appInfoList: MutableList<AllowedAppInfo?> = ArrayList()

        val selfPackage = packageName

        // Convert package names to AppInfo objects with labels and icons
        for (packageName in allowedPackages) {
            // Filter out DtcSimulator itself from UI display
            if (packageName == selfPackage) {
                continue
            }

            try {
                val appInfo = AllowedAppInfo(this, packageName)
                appInfoList.add(appInfo)
            } catch (_: Exception) {
                // If we can't get app info, create a minimal entry
                appInfoList.add(AllowedAppInfo(packageName))
            }
        }

        // Sort alphabetically by app name
        appInfoList.sortWith(Comparator { a: AllowedAppInfo?, b: AllowedAppInfo? ->
                    val nameA = a?.appName ?: a?.packageName ?: ""
                    val nameB = b?.appName ?: b?.packageName ?: ""
                    nameA.compareTo(nameB, ignoreCase = true)
                })

        // Update adapter
        adapter!!.setAppList(appInfoList)

        // Update count badge
        val count = appInfoList.size
        tvAllowedCount!!.text = "$count apps"

        // Show/hide empty state
        if (count == 0) {
            rvAllowedApps!!.visibility = View.GONE
            tvEmptyState!!.visibility = View.VISIBLE
        } else {
            rvAllowedApps!!.visibility = View.VISIBLE
            tvEmptyState!!.visibility = View.GONE
        }
    }

    override fun onResume() {
        super.onResume()

        // Check if VPN is actually running
        val service = DtcVpnService.instance
        isVpnRunning = service?.isRunning == true
        updateVpnButton()

        if (isVpnRunning) {
            startStatsUpdates()
            // Sync latency to service just in case
            updateServiceLatency()
        }

        // Refresh the list when returning to the activity
        // (in case new apps were installed)
        updateAllowedAppsList()

        // Update UDP echo description (in case server address changed in settings)
        updateUdpEchoDescription()
    }

    /**
     * Setup statistics update timer
     */
    private fun setupStatsTimer() {
        statsHandler = Handler()
        statsUpdateRunnable = object : Runnable {
            override fun run() {
                updateStatisticsDisplay()
                statsHandler!!.postDelayed(this, STATS_UPDATE_INTERVAL_MS.toLong())
            }
        }
    }

    /**
     * Start statistics updates
     */
    private fun startStatsUpdates() {
        if (statsHandler != null && statsUpdateRunnable != null) {
            statsHandler!!.post(statsUpdateRunnable!!)
            cardStatistics!!.visibility = View.VISIBLE
        }
    }

    /**
     * Stop statistics updates
     */
    private fun stopStatsUpdates() {
        if (statsHandler != null && statsUpdateRunnable != null) {
            statsHandler!!.removeCallbacks(statsUpdateRunnable!!)
            cardStatistics!!.visibility = View.GONE
        }
    }

    /**
     * Update statistics display with current VPN stats
     */
    private fun updateStatisticsDisplay() {
        val stats = this.vpnStats

        if (stats != null) {
            tvStatsSent!!.text = formatBytes(stats.sentBytes)
            tvStatsSentPackets!!.text = "${stats.sentPackets} packets"

            tvStatsReceived!!.text = formatBytes(stats.receivedBytes)
            tvStatsReceivedPackets!!.text = "${stats.receivedPackets} packets"

            tvStatsOutgoingBuffer!!.text = "${stats.outgoingBufferBytes} packets"
            tvStatsIncomingBuffer!!.text = "${stats.incomingBufferBytes} packets"
        } else {
            // No stats available, show zeros
            tvStatsSent!!.text = "0 B"
            tvStatsSentPackets!!.text = "0 packets"
            tvStatsReceived!!.text = "0 B"
            tvStatsReceivedPackets!!.text = "0 packets"
            tvStatsOutgoingBuffer!!.text = "0 packets"
            tvStatsIncomingBuffer!!.text = "0 packets"
        }
    }

    private val vpnStats: VpnStats?
        /**
         * Get VPN statistics from service
         */
        get() {
            val service = DtcVpnService.instance
            if (service != null) {
                return service.stats
            }
            return null
        }

    /**
     * Format bytes to human-readable format
     */
    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024) {
            return "$bytes B"
        } else if (bytes < 1024 * 1024) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        } else {
            return String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Test UDP echo server connectivity
     */
    private fun testUdpEcho() {
        // Disable button and show progress
        btnTestUdp!!.isEnabled = false
        btnTestUdp!!.setText(R.string.testing_udp_echo)
        tvUdpResult!!.visibility = View.GONE

        // Get VPN server address from SharedPreferences and extract IP
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val serverAddressPort = prefs.getString(PREF_SERVER_ADDRESS, BuildConfig.DEFAULT_SERVER_ADDRESS) ?: BuildConfig.DEFAULT_SERVER_ADDRESS

        // Extract IP address from "IP:PORT" format
        val parts = serverAddressPort.split(":")
        val host = if (parts.isNotEmpty()) parts[0] else "192.168.0.157"

        // Use UDP echo port from BuildConfig
        val port = BuildConfig.DEFAULT_UDP_ECHO_PORT

        // Run test using UdpEchoTester
        UdpEchoTester.testEcho(this, host, port) { result ->
            // Re-enable button
            btnTestUdp!!.isEnabled = true
            btnTestUdp!!.setText(R.string.test_udp_echo)

            // Display result
            tvUdpResult!!.visibility = View.VISIBLE

            val resultText = buildString {
                append("Status: ${if (result.success) "SUCCESS" else "FAILED"}\n")
                append("Message: ${result.message}\n")

                if (result.responseData != null) {
                    append("Response: ${result.responseData}\n")
                }

                if (result.roundTripTimeMs != null) {
                    append("RTT: ${result.roundTripTimeMs}ms\n")
                }

                if (result.error != null) {
                    append("Error: ${result.error.javaClass.simpleName}\n")
                }
            }

            tvUdpResult!!.text = resultText
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                openSettings()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopStatsUpdates()
        try {
            unregisterReceiver(vpnStateReceiver)
            Log.d(TAG, "onDestroy: Unregistered vpnStateReceiver")
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: Error unregistering receiver", e)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val VPN_REQUEST_CODE = 1
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 2
        private const val STATS_UPDATE_INTERVAL_MS = 1000 // 1 second

        // VPN Mode constants
        const val EXTRA_VPN_MODE = "VPN_MODE"
        const val VPN_MODE_SERVER = "SERVER"
        const val VPN_MODE_LOCAL = "LOCAL"

        // Server Config constants
        const val EXTRA_SERVER_ADDRESS = "SERVER_ADDRESS"
        const val EXTRA_SERVER_SECRET = "SERVER_SECRET"

        // Latency constants
        const val EXTRA_OUTBOUND_LATENCY = "OUTBOUND_LATENCY"
        const val EXTRA_INBOUND_LATENCY = "INBOUND_LATENCY"

        // SharedPreferences constants
        const val PREFS_NAME = "DtcSimulatorPrefs"
        const val PREF_SERVER_ADDRESS = "server_address"
        const val PREF_SERVER_SECRET = "server_secret"
        private const val PREF_VPN_MODE = "vpn_mode"
        private const val PREF_OUTBOUND_LATENCY_PERCENT = "outbound_latency_percent"
        private const val PREF_INBOUND_LATENCY_PERCENT = "inbound_latency_percent"
    }
}
