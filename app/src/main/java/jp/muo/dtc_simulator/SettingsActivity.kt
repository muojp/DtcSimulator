package jp.muo.dtc_simulator

import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText

/**
 * SettingsActivity - Configuration settings screen
 *
 * Features:
 * 1. Server configuration (address, secret) for Server Mode
 * 2. Network profiles configuration (multiple YAML profiles)
 */
class SettingsActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "SettingsActivity"
        const val PREF_NETWORK_PROFILE = "network_profile"
    }

    private lateinit var profileManager: NetworkProfileManager

    // Server configuration views
    private var etServerAddress: TextInputEditText? = null
    private var etServerSecret: TextInputEditText? = null
    private var btnSaveSettings: MaterialButton? = null

    // Network profiles views
    private var etYamlConfig: EditText? = null
    private var tvProfileCount: TextView? = null
    private var btnSaveProfiles: Button? = null
    private var btnResetProfiles: Button? = null
    private var btnHelpProfiles: Button? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        profileManager = NetworkProfileManager(this)

        setupToolbar()
        initViews()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
    }

    private fun initViews() {
        // Server configuration
        etServerAddress = findViewById(R.id.et_server_address)
        etServerSecret = findViewById(R.id.et_server_secret)
        btnSaveSettings = findViewById(R.id.btn_save_settings)

        // Network profiles
        etYamlConfig = findViewById(R.id.et_yaml_config)
        tvProfileCount = findViewById(R.id.tv_profile_count)
        btnSaveProfiles = findViewById(R.id.btn_save_profiles)
        btnResetProfiles = findViewById(R.id.btn_reset_profiles)
        btnHelpProfiles = findViewById(R.id.btn_help_profiles)
    }

    private fun loadSettings() {
        // Load server settings
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)

        val savedAddress = prefs.getString(
            MainActivity.PREF_SERVER_ADDRESS,
            BuildConfig.DEFAULT_SERVER_ADDRESS
        ) ?: BuildConfig.DEFAULT_SERVER_ADDRESS

        val savedSecret = prefs.getString(
            MainActivity.PREF_SERVER_SECRET,
            BuildConfig.DEFAULT_SERVER_SECRET
        ) ?: BuildConfig.DEFAULT_SERVER_SECRET

        etServerAddress?.setText(savedAddress)
        etServerSecret?.setText(savedSecret)

        // Load network profiles
        val savedYaml = profileManager.getSavedYamlText()
        if (savedYaml != null) {
            etYamlConfig?.setText(savedYaml)
        } else {
            // Show example with default profiles
            val example = buildExampleYaml()
            etYamlConfig?.setText(example)
        }

        updateProfileCount()
    }

    private fun setupListeners() {
        // Server configuration
        btnSaveSettings?.setOnClickListener {
            saveServerSettings()
        }

        // Network profiles
        btnSaveProfiles?.setOnClickListener {
            saveProfilesConfiguration()
        }

        btnResetProfiles?.setOnClickListener {
            showResetConfirmation()
        }

        btnHelpProfiles?.setOnClickListener {
            showHelpDialog()
        }
    }

    private fun saveServerSettings() {
        val address = etServerAddress?.text?.toString()?.trim() ?: ""
        val secret = etServerSecret?.text?.toString()?.trim() ?: ""

        // Validate inputs
        if (address.isEmpty()) {
            Toast.makeText(this, R.string.error_server_address_empty, Toast.LENGTH_SHORT).show()
            return
        }

        if (secret.isEmpty()) {
            Toast.makeText(this, R.string.error_server_secret_empty, Toast.LENGTH_SHORT).show()
            return
        }

        // Validate address:port format
        val parts = address.split(":")
        if (parts.size != 2) {
            Toast.makeText(this, R.string.error_server_address_format, Toast.LENGTH_SHORT).show()
            return
        }

        val port = parts[1].toIntOrNull()
        if (port == null || port <= 0 || port > 65535) {
            Toast.makeText(this, R.string.error_server_port_invalid, Toast.LENGTH_SHORT).show()
            return
        }

        // Save to SharedPreferences
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
        prefs.edit().apply {
            putString(MainActivity.PREF_SERVER_ADDRESS, address)
            putString(MainActivity.PREF_SERVER_SECRET, secret)
            apply()
        }

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun saveProfilesConfiguration() {
        val yamlText = etYamlConfig?.text?.toString() ?: ""

        if (yamlText.isBlank()) {
            Toast.makeText(this, "Configuration is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val success = profileManager.loadFromYaml(yamlText)

        if (success) {
            updateProfileCount()
            Toast.makeText(
                this,
                "Saved ${profileManager.getAllProfiles().size} profiles",
                Toast.LENGTH_SHORT
            ).show()
            setResult(RESULT_OK)
        } else {
            Toast.makeText(
                this,
                "Failed to parse YAML. Please check format.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showResetConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Reset to Defaults")
            .setMessage("This will reset all profiles to default values. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                profileManager.resetToDefaults()
                val example = buildExampleYaml()
                etYamlConfig?.setText(example)
                updateProfileCount()
                Toast.makeText(this, "Reset to default profiles", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHelpDialog() {
        val helpText = """
            |YAML Configuration Format:
            |
            |- name: "Profile Name"
            |  delay:
            |    value: 100
            |    # OR use up/down split (compact):
            |    # p50: {up: 80, down: 65}
            |    # p90: {up: 300, down: 175}
            |  loss: 5.0
            |  # OR split by direction:
            |  # loss: {up: 5.0, down: 4.0}
            |  bandwidth: 1024
            |  # OR split by direction:
            |  # bandwidth: {up: 3072, down: 5120}
            |
            |Multiple profiles:
            |- name: "Profile 1"
            |  delay:
            |    value: 100
            |  loss: 2.0
            |  bandwidth: 512
            |
            |- name: "Profile 2"
            |  delay:
            |    p50: {up: 200, down: 150}
            |  loss: {up: 5.0, down: 3.0}
            |  bandwidth: 1024
            |
            |Units:
            |- delay: milliseconds (ms)
            |- loss: percentage (0-100)
            |- bandwidth: kilobits/sec (kbps)
        """.trimMargin()

        MaterialAlertDialogBuilder(this)
            .setTitle("YAML Format Help")
            .setMessage(helpText)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateProfileCount() {
        val count = profileManager.getAllProfiles().size
        tvProfileCount?.text = "$count profile(s) loaded"
    }

    private fun buildExampleYaml(): String {
        val defaults = NetworkProfileManager.getDefaultProfiles()
        val sb = StringBuilder()

        for (profile in defaults) {
            sb.append("- name: \"${profile.name}\"\n")

            profile.delay?.let { delay ->
                if (delay.hasPercentiles()) {
                    // Percentile distribution format
                    sb.append("  delay:\n")
                    delay.p25?.let {
                        if (it.up != null && it.down != null) {
                            sb.append("    p25: {up: ${it.up}, down: ${it.down}}\n")
                        } else {
                            sb.append("    p25: ${it.value ?: 0}\n")
                        }
                    }
                    delay.p50?.let {
                        if (it.up != null && it.down != null) {
                            sb.append("    p50: {up: ${it.up}, down: ${it.down}}\n")
                        } else {
                            sb.append("    p50: ${it.value ?: 0}\n")
                        }
                    }
                    delay.p90?.let {
                        if (it.up != null && it.down != null) {
                            sb.append("    p90: {up: ${it.up}, down: ${it.down}}\n")
                        } else {
                            sb.append("    p90: ${it.value ?: 0}\n")
                        }
                    }
                    delay.p95?.let {
                        if (it.up != null && it.down != null) {
                            sb.append("    p95: {up: ${it.up}, down: ${it.down}}\n")
                        } else {
                            sb.append("    p95: ${it.value ?: 0}\n")
                        }
                    }
                } else if (delay.up != null && delay.down != null) {
                    // Simple delay: try to output as single value if it's 60/40 split
                    val totalMs = (delay.up / 0.6).toInt()
                    val expectedDown = (totalMs * 0.4).toInt()
                    if (expectedDown == delay.down) {
                        // Output as simple value
                        sb.append("  delay: $totalMs\n")
                    } else {
                        // Output as up/down map
                        sb.append("  delay: {up: ${delay.up}, down: ${delay.down}}\n")
                    }
                }
            }

            profile.loss?.let { loss ->
                if (loss.up != null && loss.down != null) {
                    sb.append("  loss: {up: ${loss.up}, down: ${loss.down}}\n")
                } else {
                    loss.value?.let { sb.append("  loss: $it\n") }
                }
            }

            profile.bandwidth?.let { bw ->
                if (bw.up != null && bw.down != null) {
                    sb.append("  bandwidth: {up: ${bw.up}, down: ${bw.down}}\n")
                } else {
                    bw.value?.let { sb.append("  bandwidth: $it\n") }
                }
            }

            sb.append("\n")
        }

        return sb.toString()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
