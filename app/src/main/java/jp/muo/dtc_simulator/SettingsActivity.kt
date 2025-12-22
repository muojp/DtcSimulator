package jp.muo.dtc_simulator

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

/**
 * SettingsActivity
 *
 * Server configuration settings screen
 */
class SettingsActivity : AppCompatActivity() {
    private var toolbar: MaterialToolbar? = null
    private var etServerAddress: TextInputEditText? = null
    private var etServerSecret: TextInputEditText? = null
    private var btnSaveSettings: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initializeViews()
        setupToolbar()
        loadSettings()
        setupSaveButton()
    }

    private fun initializeViews() {
        toolbar = findViewById(R.id.toolbar)
        etServerAddress = findViewById(R.id.et_server_address)
        etServerSecret = findViewById(R.id.et_server_secret)
        btnSaveSettings = findViewById(R.id.btn_save_settings)
    }

    private fun setupToolbar() {
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar?.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
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
    }

    private fun setupSaveButton() {
        btnSaveSettings?.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
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
        finish()
    }
}
