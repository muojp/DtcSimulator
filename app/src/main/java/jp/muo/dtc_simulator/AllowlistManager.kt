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

        // Additional system packages that might be needed
        // DNS and network services
        allowedPackages.add("com.google.android.gms")
        Log.i(TAG, "Added system package: com.google.android.gms (Google Mobile Services)")
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

    companion object {
        private const val TAG = "AllowlistManager"
        private const val META_KEY = "android.telephony.PROPERTY_SATELLITE_DATA_OPTIMIZED"
    }
}
