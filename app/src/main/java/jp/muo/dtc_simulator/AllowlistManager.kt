package jp.muo.dtc_simulator

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.util.Collections

/**
 * AllowlistManager
 *
 * Manages the allowlist of applications that are allowed to use data communication
 * through the DTC Simulator VPN service.
 *
 * Applications are allowlisted based on the presence of a specific meta-data tag
 * in their AndroidManifest.xml:
 *
 * <meta-data android:name="android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED" android:value="PACKAGE_NAME"></meta-data>
 */
class AllowlistManager(context: Context) {
    private val context: Context = context.applicationContext
    private val allowedPackages: MutableSet<String> = HashSet()
    private val lock = Any()

    // Cache for all installed apps
    private var cachedAllApps: List<AllowedAppInfo>? = null
    private var lastScanTimestamp: Long = 0

    // SharedPreferences for app enabled states
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Scans all installed applications and builds the allowlist based on meta-data tags.
     * This method is thread-safe and can be called from background threads.
     *
     * Applications are added to the allowlist if they have the required meta-data tag
     * with a value matching their package name.
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun scanAndBuildAllowlist() {
        synchronized(lock) {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(
                PackageManager.GET_META_DATA
            )

            allowedPackages.clear()
            Log.i(TAG, "Starting allowlist scan...")

            var scannedCount = 0
            var allowedCount = 0

            for (appInfo in installedApps) {
                scannedCount++
                if (hasRequiredMetaData(appInfo)) {
                    allowedPackages.add(appInfo.packageName)
                    allowedCount++
                    Log.i(TAG, "Allowed: " + appInfo.packageName)
                }
            }

            // Add system packages
            addSystemPackages()
            Log.i(
                TAG, String.format(
                    "Allowlist scan complete: %d apps scanned, %d allowed (including system packages)",
                    scannedCount, allowedPackages.size
                )
            )
        }
    }

    /**
     * Checks if an application has the required meta-data tag.
     *
     * @param appInfo Application info to check
     * @return true if the app has the required meta-data with matching package name
     */
    private fun hasRequiredMetaData(appInfo: ApplicationInfo): Boolean {
        val metaData = appInfo.metaData ?: return false

        val value = metaData.getString(META_KEY)
        val hasMetaData = appInfo.packageName == value

        if (hasMetaData) {
            Log.d(TAG, "Found meta-data in " + appInfo.packageName + ": " + value)
        }

        return hasMetaData
    }

    /**
     * Adds essential system packages to the allowlist.
     * These packages are necessary for proper system operation and VPN functionality:
     * - Google Play Services (for network services)
     * - DTC Simulator itself (to allow the app to function)
     */
    private fun addSystemPackages() {
        // Self package - allow DTC Simulator to communicate
        val selfPackage = context.packageName
        allowedPackages.add(selfPackage)
        Log.i(TAG, "Added system package: $selfPackage (DTC Simulator)")
    }

    /**
     * Gets an immutable copy of the set of allowed package names.
     * This method is thread-safe.
     *
     * @return Unmodifiable set of allowed package names
     */
    fun getAllowedPackages(): MutableSet<String> {
        synchronized(lock) {
            return Collections.unmodifiableSet(HashSet<String>(allowedPackages))
        }
    }

    val allowlistSize: Int
        /**
         * Gets the number of packages currently in the allowlist.
         * This method is thread-safe.
         *
         * @return Number of allowed packages
         */
        get() {
            synchronized(lock) {
                return allowedPackages.size
            }
        }

    /**
     * Gets all installed applications on the device.
     * This includes both satellite-enabled and satellite-disabled apps.
     * Results are cached for performance.
     *
     * @param forceRefresh If true, ignores cache and rescans all apps
     * @return List of all installed applications
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun getAllInstalledApps(forceRefresh: Boolean = false): List<AllowedAppInfo> {
        synchronized(lock) {
            // Check if cached data is still valid
            if (!forceRefresh &&
                cachedAllApps != null &&
                System.currentTimeMillis() - lastScanTimestamp < CACHE_VALIDITY_MS
            ) {
                Log.d(TAG, "Returning cached all apps list (${cachedAllApps!!.size} apps)")
                return cachedAllApps!!
            }

            Log.i(TAG, "Scanning all installed applications...")
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val appList = mutableListOf<AllowedAppInfo>()
            val selfPackage = context.packageName

            for (appInfo in installedApps) {
                // Skip DtcSimulator itself
                if (appInfo.packageName == selfPackage) {
                    continue
                }

                // Filter out system apps (unless they have satellite meta-data)
                val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val hasMetaData = hasRequiredMetaData(appInfo)

                if (isSystemApp && !hasMetaData) {
                    // Skip system apps that don't have satellite meta-data
                    continue
                }

                try {
                    val allowedAppInfo = AllowedAppInfo(context, appInfo.packageName)
                    allowedAppInfo.hasSatelliteMetaData = hasMetaData
                    appList.add(allowedAppInfo)
                } catch (_: Exception) {
                    // If we can't get app info, create minimal entry
                    val minimalInfo = AllowedAppInfo(appInfo.packageName)
                    minimalInfo.hasSatelliteMetaData = hasMetaData
                    appList.add(minimalInfo)
                }
            }

            // Sort alphabetically by app name
            appList.sortWith(Comparator { a, b ->
                val nameA = a.appName ?: a.packageName
                val nameB = b.appName ?: b.packageName
                nameA.compareTo(nameB, ignoreCase = true)
            })

            // Update cache
            cachedAllApps = appList
            lastScanTimestamp = System.currentTimeMillis()

            Log.i(TAG, "All apps scan complete: ${appList.size} apps found")
            return appList
        }
    }

    /**
     * Gets only satellite-enabled applications (apps with the required meta-data tag).
     * Also includes system packages (DtcSimulator itself and Google Play Services).
     *
     * @return List of satellite-enabled applications
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun getSatelliteEnabledApps(): List<AllowedAppInfo> {
        synchronized(lock) {
            Log.i(TAG, "Getting satellite-enabled apps...")
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            val satelliteApps = mutableListOf<AllowedAppInfo>()
            val selfPackage = context.packageName

            for (appInfo in installedApps) {
                // Skip DtcSimulator itself (will be added as system package below)
                if (appInfo.packageName == selfPackage) {
                    continue
                }

                if (hasRequiredMetaData(appInfo)) {
                    try {
                        val allowedAppInfo = AllowedAppInfo(context, appInfo.packageName)
                        allowedAppInfo.hasSatelliteMetaData = true
                        satelliteApps.add(allowedAppInfo)
                        Log.d(TAG, "Found satellite app: ${appInfo.packageName}")
                    } catch (_: Exception) {
                        val minimalInfo = AllowedAppInfo(appInfo.packageName)
                        minimalInfo.hasSatelliteMetaData = true
                        satelliteApps.add(minimalInfo)
                    }
                }
            }

            // Add system packages
            try {
                val selfInfo = AllowedAppInfo(context, selfPackage)
                selfInfo.hasSatelliteMetaData = false
                satelliteApps.add(selfInfo)
                Log.d(TAG, "Added system package: $selfPackage (DtcSimulator)")
            } catch (_: Exception) {
                val selfInfo = AllowedAppInfo(selfPackage)
                selfInfo.hasSatelliteMetaData = false
                satelliteApps.add(selfInfo)
            }

            // Sort alphabetically by app name
            satelliteApps.sortWith(Comparator { a, b ->
                val nameA = a.appName ?: a.packageName
                val nameB = b.appName ?: b.packageName
                nameA.compareTo(nameB, ignoreCase = true)
            })

            Log.i(TAG, "Satellite-enabled apps: ${satelliteApps.size} apps")
            return satelliteApps
        }
    }

    /**
     * Gets the final list of allowed package names for VPN filtering.
     * This merges satellite-enabled apps with user overrides from SharedPreferences.
     *
     * Logic:
     * 1. Start with satellite-enabled apps (meta-data tagged + system packages)
     * 2. Apply user overrides:
     *    - Remove if explicitly disabled in Tab 1
     *    - Add if explicitly enabled in Tab 2
     * 3. Always include system packages (DtcSimulator + Google Play Services)
     *
     * @return Set of package names allowed for VPN access
     */
    fun getFinalAllowedPackages(): Set<String> {
        synchronized(lock) {
            val result = mutableSetOf<String>()
            val enabledStates = getAllEnabledStates()

            // Get satellite-enabled apps
            val satelliteApps = getSatelliteEnabledApps()
            for (app in satelliteApps) {
                val override = enabledStates[app.packageName]
                if (override == null || override == true) {
                    // No override, or explicitly enabled - include it
                    result.add(app.packageName)
                } else {
                    // Explicitly disabled - exclude it
                    Log.d(TAG, "Excluding disabled satellite app: ${app.packageName}")
                }
            }

            // Get all apps and check for manually enabled ones
            val allApps = getAllInstalledApps()
            for (app in allApps) {
                if (!app.hasSatelliteMetaData) {
                    // This is a non-satellite app
                    val override = enabledStates[app.packageName]
                    if (override == true) {
                        // Manually enabled - include it
                        result.add(app.packageName)
                        Log.d(TAG, "Including manually enabled app: ${app.packageName}")
                    }
                }
            }

            // Always include system packages (even if somehow disabled)
            result.add(context.packageName)  // DtcSimulator itself
            result.add("com.google.android.gms")  // Google Play Services

            Log.i(TAG, "Final allowed packages: ${result.size} packages")
            return result
        }
    }

    /**
     * Saves the enabled state of an app to SharedPreferences.
     *
     * @param packageName Package name of the app
     * @param enabled true if app is enabled for VPN, false otherwise
     */
    fun saveAppEnabledState(packageName: String, enabled: Boolean) {
        val states = prefs.getStringSet(PREF_APP_ENABLED_STATES, HashSet()) ?: HashSet()
        val mutableStates = states.toMutableSet()

        // Remove any existing state for this package
        mutableStates.removeAll { it.startsWith("$packageName:") }

        // Add new state
        mutableStates.add("$packageName:$enabled")

        prefs.edit().putStringSet(PREF_APP_ENABLED_STATES, mutableStates).apply()
        Log.d(TAG, "Saved enabled state for $packageName: $enabled")
    }

    /**
     * Gets the enabled state of an app from SharedPreferences.
     *
     * @param packageName Package name of the app
     * @return true if enabled, false if disabled, null if no override exists
     */
    fun getAppEnabledState(packageName: String): Boolean? {
        val states = prefs.getStringSet(PREF_APP_ENABLED_STATES, HashSet()) ?: HashSet()

        val entry = states.find { it.startsWith("$packageName:") }
        return if (entry != null) {
            entry.substringAfter(":").toBoolean()
        } else {
            null
        }
    }

    /**
     * Gets all enabled state overrides from SharedPreferences.
     *
     * @return Map of package name to enabled state
     */
    fun getAllEnabledStates(): Map<String, Boolean> {
        val states = prefs.getStringSet(PREF_APP_ENABLED_STATES, HashSet()) ?: HashSet()
        val result = mutableMapOf<String, Boolean>()

        for (state in states) {
            val parts = state.split(":")
            if (parts.size == 2) {
                result[parts[0]] = parts[1].toBoolean()
            }
        }

        return result
    }

    companion object {
        private const val TAG = "AllowlistManager"
        private const val META_KEY = "android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED"
        private const val PREFS_NAME = "DtcSimulatorPrefs"
        private const val PREF_APP_ENABLED_STATES = "app_enabled_states"
        private const val CACHE_VALIDITY_MS = 5 * 60 * 1000  // 5 minutes
    }
}
