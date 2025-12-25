package jp.muo.dtc_simulator

import android.util.Log

/**
 * NetworkProfileParser - Simple YAML-like parser for network profiles
 *
 * Supports limited YAML syntax for network simulation configuration:
 * - Top-level keys: delay, loss, bandwidth
 * - Simple values: key: value
 * - Nested values: key: { subkey: value, subkey: value }
 * - Percentile notation: p25, p50, p90, p95
 *
 * Example:
 * ```
 * delay:
 *   p25: 90
 *   p50: 145
 *   p90: 475
 *   p95: 590
 * loss: 9.0
 * bandwidth: 3072
 * ```
 */
object NetworkProfileParser {
    private const val TAG = "NetworkProfileParser"

    /**
     * Parse YAML-like text into NetworkProfile
     * @param yamlText Text configuration
     * @return NetworkProfile or null if parsing fails
     */
    fun parse(yamlText: String): NetworkProfile? {
        return try {
            val lines = yamlText.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }

            val rootMap = parseLines(lines)
            buildNetworkProfile(rootMap)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse network profile", e)
            null
        }
    }

    /**
     * Parse lines into a nested map structure
     */
    private fun parseLines(lines: List<String>): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        var currentKey: String? = null
        val currentNested = mutableMapOf<String, Any>()

        for (line in lines) {
            when {
                // Root-level key with nested content
                line.contains(":") && !line.contains("{") -> {
                    val (key, value) = line.split(":", limit = 2).map { it.trim() }

                    // Save previous nested content if any
                    if (currentKey != null && currentNested.isNotEmpty()) {
                        result[currentKey] = currentNested.toMap()
                        currentNested.clear()
                    }

                    if (value.isEmpty()) {
                        // Start of nested block
                        currentKey = key
                    } else {
                        // Simple key-value pair
                        result[key] = parseValue(value)
                        currentKey = null
                    }
                }

                // Nested key-value pair (indented)
                line.startsWith(" ") && line.contains(":") -> {
                    val (key, value) = line.split(":", limit = 2).map { it.trim() }
                    if (currentKey != null) {
                        val parsedValue = parseValue(value)

                        // Handle nested percentile values
                        if (key.startsWith("p") && value.trim().isEmpty()) {
                            // This is a percentile key with nested up/down
                            // We'll handle this in next iteration
                            continue
                        } else if (parsedValue is Map<*, *>) {
                            currentNested[key] = parsedValue
                        } else {
                            currentNested[key] = parsedValue
                        }
                    }
                }

                // Inline nested syntax: key: { subkey: value, ... }
                line.contains("{") && line.contains("}") -> {
                    val (key, nestedContent) = line.split(":", limit = 2).map { it.trim() }
                    val nestedMap = parseInlineNested(nestedContent)
                    result[key] = nestedMap
                    currentKey = null
                }
            }
        }

        // Save any remaining nested content
        if (currentKey != null && currentNested.isNotEmpty()) {
            result[currentKey] = currentNested.toMap()
        }

        return result
    }

    /**
     * Parse inline nested syntax: { key: value, key: value }
     */
    private fun parseInlineNested(content: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val cleaned = content.trim().removeSurrounding("{", "}").trim()

        cleaned.split(",").forEach { pair ->
            val (key, value) = pair.split(":", limit = 2).map { it.trim() }
            result[key] = parseValue(value)
        }

        return result
    }

    /**
     * Parse a value string to appropriate type
     */
    private fun parseValue(value: String): Any {
        val trimmed = value.trim()

        return when {
            trimmed.isEmpty() -> ""
            trimmed.toIntOrNull() != null -> trimmed.toInt()
            trimmed.toFloatOrNull() != null -> trimmed.toFloat()
            trimmed.startsWith("{") -> parseInlineNested(trimmed)
            else -> trimmed
        }
    }

    /**
     * Build NetworkProfile from parsed map
     */
    private fun buildNetworkProfile(map: Map<String, Any>): NetworkProfile {
        val delayConfig = map["delay"]?.let { parseDelayConfig(it) }
        val lossConfig = map["loss"]?.let { parseLossConfig(it) }
        val bandwidthConfig = map["bandwidth"]?.let { parseBandwidthConfig(it) }

        return NetworkProfile(delayConfig, lossConfig, bandwidthConfig)
    }

    /**
     * Parse delay configuration
     */
    private fun parseDelayConfig(value: Any): NetworkProfile.DelayConfig {
        return when (value) {
            is Int -> {
                // Simple value: delay: 120
                NetworkProfile.DelayConfig(value = value)
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any>

                val hasUpDown = map.containsKey("up") || map.containsKey("down")
                val hasPercentiles = map.keys.any { it.startsWith("p") }

                when {
                    hasUpDown -> {
                        // Up/Down split: { up: 70, down: 50 }
                        NetworkProfile.DelayConfig(
                            up = (map["up"] as? Number)?.toInt(),
                            down = (map["down"] as? Number)?.toInt()
                        )
                    }
                    hasPercentiles -> {
                        // Percentile distribution
                        NetworkProfile.DelayConfig(
                            p25 = map["p25"]?.let { parsePercentileValue(it) },
                            p50 = map["p50"]?.let { parsePercentileValue(it) },
                            p90 = map["p90"]?.let { parsePercentileValue(it) },
                            p95 = map["p95"]?.let { parsePercentileValue(it) }
                        )
                    }
                    else -> NetworkProfile.DelayConfig()
                }
            }
            else -> NetworkProfile.DelayConfig()
        }
    }

    /**
     * Parse percentile value
     */
    private fun parsePercentileValue(value: Any): NetworkProfile.PercentileValue {
        return when (value) {
            is Int -> NetworkProfile.PercentileValue(value = value)
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any>
                NetworkProfile.PercentileValue(
                    up = (map["up"] as? Number)?.toInt(),
                    down = (map["down"] as? Number)?.toInt()
                )
            }
            else -> NetworkProfile.PercentileValue()
        }
    }

    /**
     * Parse loss configuration
     */
    private fun parseLossConfig(value: Any): NetworkProfile.LossConfig {
        return when (value) {
            is Number -> {
                // Simple value: loss: 9.0
                NetworkProfile.LossConfig(value = value.toFloat())
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any>
                NetworkProfile.LossConfig(
                    up = (map["up"] as? Number)?.toFloat(),
                    down = (map["down"] as? Number)?.toFloat()
                )
            }
            else -> NetworkProfile.LossConfig()
        }
    }

    /**
     * Parse bandwidth configuration
     */
    private fun parseBandwidthConfig(value: Any): NetworkProfile.BandwidthConfig {
        return when (value) {
            is Int -> {
                // Simple value: bandwidth: 3072
                NetworkProfile.BandwidthConfig(value = value)
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any>
                NetworkProfile.BandwidthConfig(
                    up = (map["up"] as? Number)?.toInt(),
                    down = (map["down"] as? Number)?.toInt()
                )
            }
            else -> NetworkProfile.BandwidthConfig()
        }
    }
}
