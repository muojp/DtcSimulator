package jp.muo.dtc_simulator

import android.content.Context
import android.util.Log
import org.yaml.snakeyaml.Yaml

/**
 * NetworkProfileManager - Manages multiple network simulation profiles
 *
 * Parses YAML-like configuration text and provides access to named profiles.
 *
 * Example YAML format:
 * ```
 * - name: "LEO Satellite"
 *   delay:
 *     p50: 145
 *   loss: 9.0
 *   bandwidth: 3072
 *
 * - name: "GEO Satellite"
 *   delay:
 *     p50: 600
 *   loss: 3.0
 *   bandwidth: 512
 * ```
 */
class NetworkProfileManager(private val context: Context) {
    private val profiles = mutableListOf<NetworkProfile>()

    companion object {
        private const val TAG = "NetworkProfileManager"
        private const val PREFS_NAME = "DtcSimulatorPrefs"
        private const val PREF_YAML_CONFIG = "yaml_config"

        /**
         * Default profiles included with the app
         */
        fun getDefaultProfiles(): List<NetworkProfile> {
            return listOf(
                NetworkProfile(
                    name = "LEO Satellite (Starlink-like)",
                    delay = NetworkProfile.DelayConfig(
                        p25 = NetworkProfile.PercentileValue(up = 60, down = 30),
                        p50 = NetworkProfile.PercentileValue(up = 80, down = 65),
                        p90 = NetworkProfile.PercentileValue(up = 300, down = 175),
                        p95 = NetworkProfile.PercentileValue(up = 350, down = 240)
                    ),
                    loss = NetworkProfile.LossConfig(up = 5.0f, down = 4.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(up = 3072, down = 5120)
                ),
                NetworkProfile(
                    name = "GEO Satellite",
                    delay = NetworkProfile.DelayConfig.fromValue(600),
                    loss = NetworkProfile.LossConfig(value = 3.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(value = 512)
                ),
                NetworkProfile(
                    name = "3G Mobile",
                    delay = NetworkProfile.DelayConfig.fromValue(200),
                    loss = NetworkProfile.LossConfig(value = 5.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(value = 768)
                ),
                NetworkProfile(
                    name = "Edge Network",
                    delay = NetworkProfile.DelayConfig.fromValue(50),
                    loss = NetworkProfile.LossConfig(value = 2.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(value = 128)
                ),
                NetworkProfile(
                    name = "Flets",
                    delay = NetworkProfile.DelayConfig.fromValue(0),
                    loss = NetworkProfile.LossConfig(value = 0f),
                    bandwidth = NetworkProfile.BandwidthConfig(value = 128000)
                )
            )
        }
    }

    init {
        // Load saved configuration from SharedPreferences
        loadFromPreferences()

        // If no profiles loaded, use defaults
        if (profiles.isEmpty()) {
            profiles.addAll(getDefaultProfiles())
        }
    }

    /**
     * Get all available profiles
     */
    fun getAllProfiles(): List<NetworkProfile> {
        return profiles.toList()
    }

    /**
     * Get profile by name
     */
    fun getProfileByName(name: String): NetworkProfile? {
        return profiles.find { it.name == name }
    }

    /**
     * Get profile by index
     */
    fun getProfileByIndex(index: Int): NetworkProfile? {
        return profiles.getOrNull(index)
    }

    /**
     * Load profiles from YAML-like text configuration
     */
    fun loadFromYaml(yamlText: String): Boolean {
        try {
            val parsed = parseYaml(yamlText)
            if (parsed.isNotEmpty()) {
                profiles.clear()
                profiles.addAll(parsed)
                saveToPreferences(yamlText)
                Log.i(TAG, "Loaded ${parsed.size} profiles from YAML")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse YAML", e)
        }
        return false
    }

    /**
     * Reset to default profiles
     */
    fun resetToDefaults() {
        profiles.clear()
        profiles.addAll(getDefaultProfiles())
        clearPreferences()
        Log.i(TAG, "Reset to ${profiles.size} default profiles")
    }

    /**
     * Parse YAML text into NetworkProfile list using SnakeYAML
     */
    private fun parseYaml(text: String): List<NetworkProfile> {
        return try {
            val yaml = Yaml()
            val data = yaml.load<Any>(text)

            when (data) {
                // YAML array of profiles
                is List<*> -> {
                    data.mapNotNull { item ->
                        (item as? Map<*, *>)?.let { map ->
                            @Suppress("UNCHECKED_CAST")
                            buildProfile(map.mapKeys { it.key.toString() } as Map<String, Any>)
                        }
                    }
                }
                // Single profile object
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    listOf(buildProfile(data.mapKeys { it.key.toString() } as Map<String, Any>))
                }
                else -> {
                    Log.e(TAG, "Unexpected YAML structure: ${data?.javaClass}")
                    emptyList()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse YAML", e)
            emptyList()
        }
    }

    /**
     * Build NetworkProfile from parsed map
     */
    private fun buildProfile(map: Map<String, Any>): NetworkProfile {
        val name = map["name"] as? String ?: "Unnamed Profile"

        val delay = (map["delay"] as? Map<*, *>)?.let { delayMap ->
            NetworkProfile.DelayConfig(
                up = delayMap["up"] as? Int,
                down = delayMap["down"] as? Int,
                p25 = parsePercentile(delayMap["p25"]),
                p50 = parsePercentile(delayMap["p50"]),
                p90 = parsePercentile(delayMap["p90"]),
                p95 = parsePercentile(delayMap["p95"])
            )
        } ?: (map["delay"] as? Int)?.let {
            // Convert single value to up/down split (60/40)
            val upMs = (it * 0.6).toInt()
            val downMs = (it * 0.4).toInt()
            NetworkProfile.DelayConfig(up = upMs, down = downMs)
        }

        val loss = (map["loss"] as? Map<*, *>)?.let { lossMap ->
            NetworkProfile.LossConfig(
                value = (lossMap["value"] as? Number)?.toFloat(),
                up = (lossMap["up"] as? Number)?.toFloat(),
                down = (lossMap["down"] as? Number)?.toFloat()
            )
        } ?: (map["loss"] as? Number)?.let { NetworkProfile.LossConfig(value = it.toFloat()) }

        val bandwidth = (map["bandwidth"] as? Map<*, *>)?.let { bwMap ->
            NetworkProfile.BandwidthConfig(
                value = bwMap["value"] as? Int,
                up = bwMap["up"] as? Int,
                down = bwMap["down"] as? Int
            )
        } ?: (map["bandwidth"] as? Int)?.let { NetworkProfile.BandwidthConfig(value = it) }

        return NetworkProfile(name, delay, loss, bandwidth)
    }

    /**
     * Parse percentile value (can be int or map)
     */
    private fun parsePercentile(value: Any?): NetworkProfile.PercentileValue? {
        return when (value) {
            is Int -> NetworkProfile.PercentileValue(value = value)
            is Map<*, *> -> NetworkProfile.PercentileValue(
                value = value["value"] as? Int,
                up = value["up"] as? Int,
                down = value["down"] as? Int
            )
            else -> null
        }
    }

    /**
     * Save configuration to SharedPreferences
     */
    private fun saveToPreferences(yamlText: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_YAML_CONFIG, yamlText)
            .apply()
    }

    /**
     * Load configuration from SharedPreferences
     */
    private fun loadFromPreferences() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val yamlText = prefs.getString(PREF_YAML_CONFIG, null)

        if (yamlText != null) {
            try {
                val parsed = parseYaml(yamlText)
                if (parsed.isNotEmpty()) {
                    profiles.addAll(parsed)
                    Log.i(TAG, "Loaded ${parsed.size} profiles from preferences")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load profiles from preferences", e)
            }
        }
    }

    /**
     * Clear saved configuration
     */
    private fun clearPreferences() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PREF_YAML_CONFIG)
            .apply()
    }

    /**
     * Get saved YAML text (if any)
     */
    fun getSavedYamlText(): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_YAML_CONFIG, null)
    }
}
