package jp.muo.dtc_simulator

import android.content.Context
import android.util.Log

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
                        p25 = NetworkProfile.PercentileValue(value = 20),
                        p50 = NetworkProfile.PercentileValue(value = 40),
                        p90 = NetworkProfile.PercentileValue(value = 80),
                        p95 = NetworkProfile.PercentileValue(value = 120)
                    ),
                    loss = NetworkProfile.LossConfig(value = 1.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(
                        up = 10240,  // 10 Mbps
                        down = 102400  // 100 Mbps
                    )
                ),
                NetworkProfile(
                    name = "GEO Satellite",
                    delay = NetworkProfile.DelayConfig(value = 600),
                    loss = NetworkProfile.LossConfig(value = 3.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(value = 512)  // 512 kbps
                ),
                NetworkProfile(
                    name = "3G Mobile",
                    delay = NetworkProfile.DelayConfig(value = 200),
                    loss = NetworkProfile.LossConfig(value = 5.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(value = 384)  // 384 kbps
                ),
                NetworkProfile(
                    name = "Edge Network",
                    delay = NetworkProfile.DelayConfig(value = 50),
                    loss = NetworkProfile.LossConfig(value = 2.0f),
                    bandwidth = NetworkProfile.BandwidthConfig(value = 5120)  // 5 Mbps
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
     * Parse YAML-like text into NetworkProfile list
     */
    private fun parseYaml(text: String): List<NetworkProfile> {
        val result = mutableListOf<NetworkProfile>()
        val lines = text.lines()

        var currentProfile: MutableMap<String, Any>? = null
        var currentSection: String? = null
        var indentLevel = 0

        for (line in lines) {
            val trimmed = line.trim()

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue

            // Detect indent level
            val currentIndent = line.takeWhile { it == ' ' || it == '\t' }.length

            // New profile marker
            if (trimmed.startsWith("-") && currentIndent == 0) {
                // Save previous profile
                if (currentProfile != null) {
                    result.add(buildProfile(currentProfile))
                }
                currentProfile = mutableMapOf()
                currentSection = null

                // Check if inline name after dash
                val afterDash = trimmed.substring(1).trim()
                if (afterDash.startsWith("name:")) {
                    val name = afterDash.substring(5).trim().removeSurrounding("\"", "'")
                    currentProfile["name"] = name
                }
                continue
            }

            if (currentProfile == null && currentIndent == 0) {
                // Start first profile implicitly
                currentProfile = mutableMapOf()
            }

            if (currentProfile == null) continue

            // Parse key-value pairs
            if (trimmed.contains(":")) {
                val parts = trimmed.split(":", limit = 2)
                val key = parts[0].trim()
                val value = parts.getOrNull(1)?.trim() ?: ""

                when {
                    // Top-level keys
                    currentIndent <= 2 && key in listOf("name", "delay", "loss", "bandwidth") -> {
                        if (value.isNotEmpty() && !value.startsWith("{")) {
                            // Inline value
                            currentProfile[key] = parseValue(value)
                            currentSection = null
                        } else {
                            // Section header
                            currentSection = key
                            if (!currentProfile.containsKey(key)) {
                                currentProfile[key] = mutableMapOf<String, Any>()
                            }
                        }
                    }
                    // Nested keys within a section
                    currentSection != null && currentIndent > 2 -> {
                        val sectionMap = currentProfile[currentSection] as? MutableMap<String, Any>
                        if (sectionMap != null) {
                            sectionMap[key] = parseValue(value)
                        }
                    }
                }
            }
        }

        // Save last profile
        if (currentProfile != null) {
            result.add(buildProfile(currentProfile))
        }

        return result
    }

    /**
     * Parse string value to appropriate type
     */
    private fun parseValue(value: String): Any {
        val clean = value.removeSurrounding("\"", "'").trim()

        return when {
            clean.isEmpty() -> ""
            clean.toIntOrNull() != null -> clean.toInt()
            clean.toFloatOrNull() != null -> clean.toFloat()
            else -> clean
        }
    }

    /**
     * Build NetworkProfile from parsed map
     */
    private fun buildProfile(map: Map<String, Any>): NetworkProfile {
        val name = map["name"] as? String ?: "Unnamed Profile"

        val delay = (map["delay"] as? Map<*, *>)?.let { delayMap ->
            NetworkProfile.DelayConfig(
                value = delayMap["value"] as? Int,
                up = delayMap["up"] as? Int,
                down = delayMap["down"] as? Int,
                p25 = parsePercentile(delayMap["p25"]),
                p50 = parsePercentile(delayMap["p50"]),
                p90 = parsePercentile(delayMap["p90"]),
                p95 = parsePercentile(delayMap["p95"])
            )
        } ?: (map["delay"] as? Int)?.let { NetworkProfile.DelayConfig(value = it) }

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
