package jp.muo.dtc_simulator

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * AllowedAppInfo
 *
 * Model class representing information about an allowed application.
 * Includes package name, display name, and icon.
 */
class AllowedAppInfo {
    /**
     * Gets the package name.
     *
     * @return Package name
     */
    @JvmField
    val packageName: String

    /**
     * Gets the application name (human-readable).
     *
     * @return Application name
     */
    var appName: String? = null
        private set

    /**
     * Gets the application icon.
     *
     * @return Application icon drawable, or null if not available
     */
    var appIcon: Drawable? = null
        private set

    /**
     * Checks if this is a system application.
     *
     * @return true if system app, false otherwise
     */
    var isSystemApp: Boolean = false
        private set

    /**
     * Indicates if this app has the satellite meta-data tag.
     * true = meta-data tag present (satellite-enabled)
     * false = no meta-data tag (satellite-disabled)
     */
    var hasSatelliteMetaData: Boolean = false

    /**
     * Indicates if this app is enabled for VPN access.
     * This state can be modified by user via checkbox.
     */
    var isEnabled: Boolean = false

    /**
     * Creates an AllowedAppInfo with full application details.
     *
     * @param context Application context
     * @param packageName Package name of the application
     * @throws PackageManager.NameNotFoundException if package doesn't exist
     */
    constructor(context: Context, packageName: String) {
        this.packageName = packageName
        loadAppInfo(context)
    }

    /**
     * Creates a minimal AllowedAppInfo with only the package name.
     * Used as a fallback when full app info cannot be retrieved.
     *
     * @param packageName Package name of the application
     */
    constructor(packageName: String) {
        this.packageName = packageName
        this.appName = packageName
        this.appIcon = null
        this.isSystemApp = false
    }

    /**
     * Loads application information from PackageManager.
     *
     * @param context Application context
     * @throws PackageManager.NameNotFoundException if package doesn't exist
     */
    @Throws(PackageManager.NameNotFoundException::class)
    private fun loadAppInfo(context: Context) {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(packageName, 0)

        // Get app name
        val label = pm.getApplicationLabel(appInfo)
        this.appName = label.toString()

        // Get app icon
        this.appIcon = pm.getApplicationIcon(appInfo)

        // Check if system app
        this.isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    override fun toString(): String {
        return "$appName ($packageName)"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        val that = other as AllowedAppInfo
        return packageName == that.packageName
    }

    override fun hashCode(): Int {
        return packageName.hashCode()
    }
}
