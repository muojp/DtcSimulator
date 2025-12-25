package jp.muo.dtc_simulator

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
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
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
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
    private var tabAppCategories: TabLayout? = null
    private var etAppSearch: TextInputEditText? = null
    private var rvSatelliteEnabledApps: RecyclerView? = null
    private var rvSatelliteDisabledApps: RecyclerView? = null
    private var tvAllowedCount: TextView? = null
    private var tvEmptyState: TextView? = null
    private var tvUdpResult: TextView? = null

    // Latency Simulation UI Components
    private var tvOutboundLatency: TextView? = null
    private var sliderOutboundLatency: com.google.android.material.slider.Slider? = null
    private var tvInboundLatency: TextView? = null
    private var sliderInboundLatency: com.google.android.material.slider.Slider? = null
    private var tvPacketLoss: TextView? = null
    private var sliderPacketLoss: com.google.android.material.slider.Slider? = null
    private var tvBandwidth: TextView? = null
    private var sliderBandwidth: com.google.android.material.slider.Slider? = null

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
    private var adapterSatelliteEnabled: AllowedAppsAdapter? = null
    private var adapterSatelliteDisabled: AllowedAppsAdapter? = null
    private var isVpnRunning = false
    private var currentTabIndex: Int = 0  // 0 = satellite-enabled, 1 = satellite-disabled

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
        setupTabLayout()
        setupSearchField()
        setupButtons()
        setupVpnModeListener()
        setupLatencyControls()
        setupLossAndBandwidthControls()
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

        // Auto-load app lists on startup (background thread)
        loadAppLists()
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
     * Setup packet loss and bandwidth controls
     */
    private fun setupLossAndBandwidthControls() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // Load saved values
        val savedLossPercent = prefs.getFloat(PREF_PACKET_LOSS_PERCENT, 0f)
        val savedBandwidthPercent = prefs.getFloat(PREF_BANDWIDTH_PERCENT, 0f)

        // Set slider values
        sliderPacketLoss?.value = savedLossPercent
        sliderBandwidth?.value = savedBandwidthPercent

        // Update text displays
        tvPacketLoss?.text = String.format(Locale.US, "%.1f %%", savedLossPercent)
        tvBandwidth?.text = formatBandwidth(calculateBandwidth(savedBandwidthPercent))

        // Packet Loss listener
        sliderPacketLoss?.addOnChangeListener { _, value, _ ->
            tvPacketLoss?.text = String.format(Locale.US, "%.1f %%", value)
            prefs.edit().putFloat(PREF_PACKET_LOSS_PERCENT, value).apply()
            updateServiceNetworkParams()
        }

        // Bandwidth listener
        sliderBandwidth?.addOnChangeListener { _, value, _ ->
            val bandwidthKbps = calculateBandwidth(value)
            tvBandwidth?.text = formatBandwidth(bandwidthKbps)
            prefs.edit().putFloat(PREF_BANDWIDTH_PERCENT, value).apply()
            updateServiceNetworkParams()
        }
    }

    /**
     * Calculate bandwidth from slider value (0-100)
     * Using exponential scale:
     * 0% -> 0 kbps (unlimited)
     * 25% -> 128 kbps
     * 50% -> 1024 kbps (1 Mbps)
     * 75% -> 8192 kbps (8 Mbps)
     * 100% -> 65536 kbps (64 Mbps)
     */
    private fun calculateBandwidth(percent: Float): Int {
        if (percent <= 0f) return 0
        return (128 * Math.pow(2.0, percent / 12.5)).toInt()
    }

    /**
     * Format bandwidth to human-readable string
     */
    private fun formatBandwidth(kbps: Int): String {
        if (kbps == 0) return "Unlimited"
        if (kbps < 1024) return "$kbps kbps"
        return "${kbps / 1024} Mbps"
    }

    /**
     * Update network parameters (loss, bandwidth) in the running VPN service
     */
    private fun updateServiceNetworkParams() {
        val service = DtcVpnService.instance ?: return

        val lossPercent = sliderPacketLoss?.value ?: 0f
        val bandwidthPercent = sliderBandwidth?.value ?: 0f
        val bandwidthKbps = calculateBandwidth(bandwidthPercent)

        // TODO: Add methods to DtcVpnService to update these parameters
        // service.updatePacketLoss(lossPercent)
        // service.updateBandwidth(bandwidthKbps)
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
        tabAppCategories = findViewById(R.id.tab_app_categories)
        etAppSearch = findViewById(R.id.et_app_search)
        rvSatelliteEnabledApps = findViewById(R.id.rv_satellite_enabled_apps)
        rvSatelliteDisabledApps = findViewById(R.id.rv_satellite_disabled_apps)
        tvAllowedCount = findViewById(R.id.tv_allowed_count)
        tvEmptyState = findViewById(R.id.tv_empty_state)
        tvUdpResult = findViewById(R.id.tv_udp_result)

        // Latency views
        tvOutboundLatency = findViewById(R.id.tv_outbound_latency)
        sliderOutboundLatency = findViewById(R.id.slider_outbound_latency)
        tvInboundLatency = findViewById(R.id.tv_inbound_latency)
        sliderInboundLatency = findViewById(R.id.slider_inbound_latency)
        tvPacketLoss = findViewById(R.id.tv_packet_loss)
        sliderPacketLoss = findViewById(R.id.slider_packet_loss)
        tvBandwidth = findViewById(R.id.tv_bandwidth)
        sliderBandwidth = findViewById(R.id.slider_bandwidth)

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
     * Setup RecyclerViews with adapters and layout managers
     */
    private fun setupRecyclerView() {
        // Create adapters with checkbox change callback
        adapterSatelliteEnabled = AllowedAppsAdapter(this) { appInfo, isChecked ->
            onAppCheckboxChanged(appInfo, isChecked)
        }
        adapterSatelliteDisabled = AllowedAppsAdapter(this) { appInfo, isChecked ->
            onAppCheckboxChanged(appInfo, isChecked)
        }

        // Setup satellite-enabled RecyclerView
        rvSatelliteEnabledApps!!.layoutManager = LinearLayoutManager(this)
        rvSatelliteEnabledApps!!.adapter = adapterSatelliteEnabled

        // Setup satellite-disabled RecyclerView
        rvSatelliteDisabledApps!!.layoutManager = LinearLayoutManager(this)
        rvSatelliteDisabledApps!!.adapter = adapterSatelliteDisabled
    }

    /**
     * Setup TabLayout for switching between satellite-enabled and satellite-disabled apps
     */
    private fun setupTabLayout() {
        // Add tabs
        tabAppCategories?.addTab(tabAppCategories!!.newTab().setText(R.string.tab_satellite_enabled))
        tabAppCategories?.addTab(tabAppCategories!!.newTab().setText(R.string.tab_satellite_disabled))

        // Set tab selection listener
        tabAppCategories?.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                currentTabIndex = tab?.position ?: 0
                updateRecyclerViewVisibility()
                // Clear search when switching tabs
                etAppSearch?.text?.clear()
                updateEmptyState()
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {
                // Clear filter for the tab being left
                val unselectedIndex = tab?.position ?: 0
                when (unselectedIndex) {
                    0 -> adapterSatelliteEnabled?.filter("")
                    1 -> adapterSatelliteDisabled?.filter("")
                }
            }

            override fun onTabReselected(tab: TabLayout.Tab?) {
                // No action needed
            }
        })

        // Select the first tab by default
        tabAppCategories?.selectTab(tabAppCategories?.getTabAt(0))
    }

    /**
     * Update RecyclerView visibility based on selected tab
     */
    private fun updateRecyclerViewVisibility() {
        when (currentTabIndex) {
            0 -> {
                // Show satellite-enabled apps
                rvSatelliteEnabledApps?.visibility = View.VISIBLE
                rvSatelliteDisabledApps?.visibility = View.GONE
            }
            1 -> {
                // Show satellite-disabled apps
                rvSatelliteEnabledApps?.visibility = View.GONE
                rvSatelliteDisabledApps?.visibility = View.VISIBLE
            }
        }
    }

    /**
     * Update empty state visibility and message based on current tab
     */
    private fun updateEmptyState() {
        val currentCount = when (currentTabIndex) {
            0 -> adapterSatelliteEnabled?.itemCount ?: 0
            1 -> adapterSatelliteDisabled?.itemCount ?: 0
            else -> 0
        }

        if (currentCount == 0) {
            tvEmptyState?.visibility = View.VISIBLE
            // Update empty state message based on tab
            tvEmptyState?.text = when (currentTabIndex) {
                0 -> getString(R.string.no_satellite_enabled_apps)
                1 -> getString(R.string.no_satellite_disabled_apps)
                else -> getString(R.string.no_allowed_apps)
            }
        } else {
            tvEmptyState?.visibility = View.GONE
        }
    }

    /**
     * Setup search field for filtering apps
     */
    private fun setupSearchField() {
        etAppSearch?.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                // No action needed
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                // Filter current adapter based on search query
                val query = s?.toString() ?: ""
                filterCurrentAdapter(query)
            }

            override fun afterTextChanged(s: Editable?) {
                // No action needed
            }
        })
    }

    /**
     * Filter the current adapter based on search query
     */
    private fun filterCurrentAdapter(query: String) {
        when (currentTabIndex) {
            0 -> adapterSatelliteEnabled?.filter(query)
            1 -> adapterSatelliteDisabled?.filter(query)
        }
        updateEmptyState()
    }

    /**
     * Callback when app checkbox state changes
     */
    private fun onAppCheckboxChanged(appInfo: AllowedAppInfo, isChecked: Boolean) {
        if (isVpnRunning) {
            Toast.makeText(this, R.string.cannot_change_while_vpn_running, Toast.LENGTH_SHORT).show()
            return
        }

        // Save to SharedPreferences
        allowlistManager?.saveAppEnabledState(appInfo.packageName, isChecked)

        // Update the count badge to reflect the change
        updateTabCounts()

        // For satellite-disabled tab, sort by checked state (checked apps on top)
        if (currentTabIndex == 1) {
            adapterSatelliteDisabled?.sortByCheckedState()
        }

        Log.d(TAG, "App ${appInfo.packageName} enabled state changed to: $isChecked")
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
            // Disable checkboxes when VPN is running
            adapterSatelliteEnabled?.setVpnRunning(true)
            adapterSatelliteDisabled?.setVpnRunning(true)
        } else {
            btnStartVpn!!.setText(R.string.start_vpn)
            btnStartVpn!!.setIcon(getDrawable(android.R.drawable.ic_media_play))
            tvVpnDescription?.visibility = View.VISIBLE
            // Enable VPN mode selection when VPN is stopped
            rgVpnMode?.isEnabled = true
            rbServerMode?.isEnabled = true
            rbLocalMode?.isEnabled = true
            // Enable checkboxes when VPN is stopped
            adapterSatelliteEnabled?.setVpnRunning(false)
            adapterSatelliteDisabled?.setVpnRunning(false)
        }
    }

    /**
     * Load app lists for both tabs (satellite-enabled and satellite-disabled)
     * Runs on background thread for performance
     */
    private fun loadAppLists() {
        Thread {
            try {
                Log.i(TAG, "Loading app lists...")

                // Get satellite-enabled apps
                val satelliteApps = allowlistManager!!.getSatelliteEnabledApps().toMutableList()

                // Get all installed apps
                val allApps = allowlistManager!!.getAllInstalledApps()

                // Filter satellite-disabled apps (those without meta-data tag)
                val satelliteDisabledApps = allApps.filter { !it.hasSatelliteMetaData }.toMutableList()

                // Load enabled states from SharedPreferences
                val enabledStates = allowlistManager!!.getAllEnabledStates()

                // Apply enabled states to satellite-enabled apps
                for (app in satelliteApps) {
                    app.isEnabled = enabledStates[app.packageName] ?: true  // Default enabled
                }

                // Apply enabled states to satellite-disabled apps
                for (app in satelliteDisabledApps) {
                    app.isEnabled = enabledStates[app.packageName] ?: false  // Default disabled
                }

                // Update UI on main thread
                runOnUiThread {
                    // Update adapters
                    adapterSatelliteEnabled?.setAppList(satelliteApps)
                    adapterSatelliteDisabled?.setAppList(satelliteDisabledApps)

                    // Sort satellite-disabled apps by checked state (checked apps on top)
                    adapterSatelliteDisabled?.sortByCheckedState()

                    // Update tab counts
                    updateTabCounts()

                    // Update empty state
                    updateEmptyState()

                    Log.i(TAG, "App lists loaded: ${satelliteApps.size} satellite-enabled, ${satelliteDisabledApps.size} satellite-disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading app lists", e)
                runOnUiThread {
                    Toast.makeText(this, "Error loading app lists: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    /**
     * Update tab count badge with total enabled apps
     */
    private fun updateTabCounts() {
        val enabledAppsCount = allowlistManager?.getFinalAllowedPackages()?.size ?: 0
        tvAllowedCount?.text = "$enabledAppsCount apps"
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

        // Refresh the lists when returning to the activity
        // (in case new apps were installed)
        loadAppLists()

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
        private const val PREF_PACKET_LOSS_PERCENT = "packet_loss_percent"
        private const val PREF_BANDWIDTH_PERCENT = "bandwidth_percent"
    }
}
