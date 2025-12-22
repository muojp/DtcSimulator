package jp.muo.dtc_simulator

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import jp.muo.dtc_simulator.AllowedAppsAdapter.AppViewHolder

/**
 * AllowedAppsAdapter
 *
 * RecyclerView adapter for displaying the list of allowed applications.
 * Shows app icon, name, and package name for each allowed app.
 */
class AllowedAppsAdapter(private val context: Context?) : RecyclerView.Adapter<AppViewHolder>() {
    private var appList: MutableList<AllowedAppInfo>

    /**
     * Creates a new adapter instance.
     *
     * @param context Application context
     */
    init {
        this.appList = ArrayList<AllowedAppInfo>()
    }

    /**
     * Updates the app list and refreshes the RecyclerView.
     *
     * @param appList New list of allowed apps
     */
    fun setAppList(appList: MutableList<AllowedAppInfo?>?) {
        this.appList = appList?.filterNotNull()?.toMutableList() ?: ArrayList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.item_allowed_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = appList[position]
        holder.bind(appInfo)
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

        /**
         * Binds app info to the view holder.
         *
         * @param appInfo App information to display
         */
        fun bind(appInfo: AllowedAppInfo) {
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
        }
    }
}
