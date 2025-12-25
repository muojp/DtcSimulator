package jp.muo.dtc_simulator

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import jp.muo.dtc_simulator.AllowedAppsAdapter.AppViewHolder

/**
 * AllowedAppsAdapter
 *
 * RecyclerView adapter for displaying the list of allowed applications.
 * Shows app icon, name, package name, and checkbox for enable/disable control.
 */
class AllowedAppsAdapter(
    private val context: Context?,
    private val onCheckboxChanged: (AllowedAppInfo, Boolean) -> Unit
) : RecyclerView.Adapter<AppViewHolder>() {
    private var appList: MutableList<AllowedAppInfo>
    private var fullAppList: MutableList<AllowedAppInfo>  // Original unfiltered list
    private var isVpnRunning: Boolean = false

    /**
     * Creates a new adapter instance.
     *
     * @param context Application context
     * @param onCheckboxChanged Callback when checkbox state changes
     */
    init {
        this.appList = ArrayList<AllowedAppInfo>()
        this.fullAppList = ArrayList<AllowedAppInfo>()
    }

    /**
     * Updates the VPN running state and refreshes all checkboxes.
     *
     * @param running true if VPN is currently running
     */
    fun setVpnRunning(running: Boolean) {
        isVpnRunning = running
        notifyDataSetChanged()  // Refresh all items to update checkbox enabled state
    }

    /**
     * Updates the app list and refreshes the RecyclerView.
     *
     * @param appList New list of allowed apps
     */
    fun setAppList(appList: MutableList<AllowedAppInfo>) {
        this.fullAppList = appList
        this.appList = ArrayList(appList)  // Create a copy
        notifyDataSetChanged()
    }

    /**
     * Filters the app list based on search query.
     * Searches in both app name and package name.
     *
     * @param query Search query (case-insensitive)
     */
    fun filter(query: String) {
        if (query.isEmpty()) {
            // No filter - show all apps
            appList = ArrayList(fullAppList)
        } else {
            // Filter by app name or package name
            val queryLowerCase = query.lowercase()
            appList = fullAppList.filter { app ->
                val appName = app.appName?.lowercase() ?: ""
                val packageName = app.packageName.lowercase()
                appName.contains(queryLowerCase) || packageName.contains(queryLowerCase)
            }.toMutableList()
        }
        notifyDataSetChanged()
    }

    /**
     * Sorts the app list by checked state (checked apps first), then by name.
     * This is used for the satellite-disabled tab to prioritize checked apps.
     */
    fun sortByCheckedState() {
        // Sort both the full list and filtered list
        fullAppList.sortWith(compareByDescending<AllowedAppInfo> { it.isEnabled }
            .thenBy { it.appName?.lowercase() ?: it.packageName.lowercase() })

        appList.sortWith(compareByDescending<AllowedAppInfo> { it.isEnabled }
            .thenBy { it.appName?.lowercase() ?: it.packageName.lowercase() })

        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_allowed_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = appList[position]
        holder.bind(appInfo, isVpnRunning, onCheckboxChanged)
    }

    override fun getItemCount(): Int {
        return appList.size
    }

    /**
     * ViewHolder for app list items
     */
    class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ivAppIcon: ImageView = itemView.findViewById(R.id.iv_app_icon)
        private val tvAppName: TextView = itemView.findViewById(R.id.tv_app_name)
        private val tvPackageName: TextView = itemView.findViewById(R.id.tv_package_name)
        private val tvSystemBadge: TextView = itemView.findViewById(R.id.tv_system_badge)
        private val cbEnabled: CheckBox = itemView.findViewById(R.id.cb_app_enabled)

        /**
         * Binds app info to the view holder.
         *
         * @param appInfo App information to display
         * @param isVpnRunning true if VPN is currently running
         * @param onCheckboxChanged Callback when checkbox state changes
         */
        fun bind(
            appInfo: AllowedAppInfo,
            isVpnRunning: Boolean,
            onCheckboxChanged: (AllowedAppInfo, Boolean) -> Unit
        ) {
            // Set app name
            tvAppName.text = appInfo.appName

            // Set package name
            tvPackageName.text = appInfo.packageName

            // Set app icon
            if (appInfo.appIcon != null) {
                ivAppIcon.setImageDrawable(appInfo.appIcon)
            } else {
                // Use default icon if app icon is not available
                ivAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            // Show/hide system app badge
            if (appInfo.isSystemApp) {
                tvSystemBadge.visibility = View.VISIBLE
            } else {
                tvSystemBadge.visibility = View.GONE
            }

            // Configure checkbox
            // Remove listener first to avoid triggering on programmatic change
            cbEnabled.setOnCheckedChangeListener(null)

            // Set checkbox state
            cbEnabled.isChecked = appInfo.isEnabled

            // Disable checkbox if VPN is running
            cbEnabled.isEnabled = !isVpnRunning

            // Set checkbox listener
            cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                if (!isVpnRunning) {
                    // Update app info
                    appInfo.isEnabled = isChecked
                    // Notify callback
                    onCheckboxChanged(appInfo, isChecked)
                }
            }
        }
    }
}
