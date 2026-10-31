package org.webgpt.app.webview

import android.content.Context
import android.content.pm.PackageInfo
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.RadioButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.webgpt.app.R
import org.webgpt.app.appPrefs

/**
 * RecyclerView adapter for the "Installed" tab of [WebViewManagerDialog].
 *
 * One row per installed WebView provider (including the default): icon, name,
 * package name, version and a radio button. Tapping a row selects it (writes
 * the package name to SharedPreferences under [WebViewUtil.PREF_WEBVIEW_IMPL])
 * and redraws all rows so the new radio button is checked.
 */
class WebViewInstalledAdapter(
    private val context: Context,
    private val recyclerView: RecyclerView,
    private val emptyView: View
) : RecyclerView.Adapter<WebViewInstalledAdapter.ViewHolder>() {

    private val prefs = context.appPrefs
    private val values = mutableListOf<PackageInfo>()

    var selectedWebView: PackageInfo? = null
        private set

    init {
        reloadData()
    }

    /** Rescans installed providers, restores the saved selection and shows or hides the empty view. */
    fun reloadData() {
        WebViewUtil.reloadProviderList(context)
        values.clear()
        values.addAll(WebViewUtil.availableProviders.values)

        val saved = prefs.getString(
            WebViewUtil.PREF_WEBVIEW_IMPL,
            WebViewUtil.defaultProvider?.packageName.orEmpty()
        )
        selectedWebView = values.firstOrNull { it.packageName == saved } ?: values.firstOrNull()

        emptyView.visibility = if (values.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (values.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_webview_installed, parent, false)
        val holder = ViewHolder(view)
        view.setOnClickListener {
            @Suppress("DEPRECATION")
            val pos = holder.adapterPosition
            if (pos < 0 || pos >= values.size) return@setOnClickListener
            val info = values[pos]
            selectedWebView = info
            prefs.edit().putString(WebViewUtil.PREF_WEBVIEW_IMPL, info.packageName).apply()
            notifyItemRangeChanged(0, values.size)
        }
        // The whole row is the touch target, not the radio button.
        holder.rdoCheck.isClickable = false
        return holder
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val info = values[position]
        holder.txtName.text = WebViewUtil.label(context, info)
        holder.txtPackage.text = info.packageName
        @Suppress("DEPRECATION")
        holder.txtVersion.text = "${info.versionName} (${info.versionCode})"
        holder.imgIcon.setImageDrawable(
            runCatching { info.applicationInfo?.let { context.packageManager.getApplicationIcon(it) } }.getOrNull()
        )
        holder.rdoCheck.isChecked = selectedWebView?.packageName == info.packageName
    }

    override fun getItemCount(): Int = values.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txt_webview_name)
        val txtPackage: TextView = view.findViewById(R.id.txt_webview_package)
        val txtVersion: TextView = view.findViewById(R.id.txt_webview_version)
        val imgIcon: ImageView = view.findViewById(R.id.img_icon)
        val rdoCheck: RadioButton = view.findViewById(R.id.rdo_check)
    }
}
