package org.webgpt.app.webview

import android.app.Activity
import android.content.DialogInterface
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.webgpt.app.R
import org.webgpt.app.openInBrowser

/**
 * Material AlertDialog that hosts the WebView picker UI.
 *
 * Two tabs:
 *  - **Installed**: every installed WebView provider. Tapping a row saves its
 *    package name to SharedPreferences.
 *  - **Downloader**: [WebViewSource] rows. Each "Download" button opens the
 *    source's page in the browser; the user installs the APK manually.
 *
 * The neutral "Learn more" button opens a second dialog on top of this one that
 * explains why switching WebViews can help (copy button, microphone and
 * performance fixes; Thorium is recommended for Huawei).
 *
 * Dialog stacking: "Understood" dismisses only the info dialog and returns the
 * user to the manager. Tapping outside the info dialog (or pressing back)
 * cancels BOTH dialogs. A non-cancelable manager (the unsupported-WebView gate
 * in MainActivity) stays up either way, so the forced pick cannot be bypassed.
 */
class WebViewManagerDialog @JvmOverloads constructor(
    private val activity: Activity,
    onDismissListener: DialogInterface.OnDismissListener? = null
) : MaterialAlertDialogBuilder(activity) {

    private val installedAdapter: WebViewInstalledAdapter

    /** The shown manager dialog, so the info dialog can dismiss it on cancel. */
    private var managerDialog: AlertDialog? = null

    /**
     * `android.app.Dialog` has no public `isCancelable()` getter, so the builder
     * remembers the value itself. The info dialog's cancel handler needs it to
     * know whether the manager may be dismissed together with it.
     */
    private var managerCancelable = true

    init {
        val sources = buildSources()
        val view = activity.layoutInflater.inflate(R.layout.dialog_webview_manager, null)

        val tabs = view.findViewById<MaterialButtonToggleGroup>(R.id.tgl_tab)
        val installedTab = view.findViewById<LinearLayout>(R.id.lay_installed)
        val downloaderTab = view.findViewById<LinearLayout>(R.id.lay_downloader)
        val installedList = view.findViewById<RecyclerView>(R.id.lst_installed)
        val downloaderList = view.findViewById<RecyclerView>(R.id.lst_downloader)

        view.findViewById<View>(R.id.txt_unsupported).visibility =
            if (WebViewUtil.isSupported()) View.GONE else View.VISIBLE

        tabs.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val showInstalled = group.indexOfChild(view.findViewById<View>(checkedId)) == 0
            installedTab.visibility = if (showInstalled) View.VISIBLE else View.GONE
            downloaderTab.visibility = if (showInstalled) View.GONE else View.VISIBLE
        }

        installedAdapter = WebViewInstalledAdapter(activity, installedList, view.findViewById<TextView>(R.id.txt_empty))
        installedList.layoutManager = LinearLayoutManager(activity)
        installedList.adapter = installedAdapter
        installedList.itemAnimator = null

        downloaderList.layoutManager = LinearLayoutManager(activity)
        downloaderList.adapter = WebViewDownloaderAdapter(activity, sources.toTypedArray())

        view.findViewById<View>(R.id.btn_refresh).setOnClickListener {
            installedAdapter.reloadData()
            installedAdapter.notifyDataSetChanged()
        }

        setTitle(R.string.webview_manager)
        // A literal English string: Material's default android.R.string.ok is
        // auto-translated by the framework (e.g. "Aceptar"), and this app is English-only.
        setPositiveButton("OK", null)
        // The listener is a placeholder. show() attaches the real one afterwards so
        // that tapping "Learn more" does not auto-dismiss this dialog.
        setNeutralButton(R.string.learn_more, null)
        setOnDismissListener(onDismissListener)
        setView(view)
    }

    /** Remembers the cancelable flag (see [managerCancelable]) and applies it to the dialog. */
    override fun setCancelable(cancelable: Boolean): MaterialAlertDialogBuilder {
        managerCancelable = cancelable
        return super.setCancelable(cancelable)
    }

    /**
     * Shows the manager and re-wires its "Learn more" button. Buttons registered
     * through the builder dismiss the dialog on click; a listener set on the
     * Button after show() does not, which lets the info dialog stack on top.
     */
    override fun show(): AlertDialog {
        val dialog = super.show()
        managerDialog = dialog
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { showInfoDialog() }
        return dialog
    }

    /** True if the user picked a different WebView than the one currently running (needs a restart). */
    fun changedWebView(): Boolean {
        val selected = installedAdapter.selectedWebView ?: return false
        val current = WebViewUtil.currentProvider ?: return false
        return selected.packageName != current.packageName
    }

    private fun showInfoDialog() {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.webview_info_title)
            .setMessage(R.string.webview_info_body)
            .setPositiveButton(R.string.understood, null)
            .setOnCancelListener {
                if (managerCancelable) managerDialog?.takeIf { it.isShowing }?.dismiss()
            }
            .show()
    }

    private fun buildSources(): List<WebViewSource> = listOf(
        WebViewSource(
            WebViewImplementation.GOOGLE,
            "Android System WebView",
            "Google Play Store",
            openOnClick("https://play.google.com/store/apps/details?id=com.google.android.webview"),
            supported = true
        ),
        WebViewSource(
            WebViewImplementation.THORIUM,
            "Thorium WebView",
            "github.com/Alex313031/Thorium-Android",
            openOnClick("https://github.com/Alex313031/Thorium-Android"),
            // Thorium cannot replace the AOSP "com.android.webview" default.
            supported = WebViewUtil.defaultProvider?.packageName != "com.android.webview"
        ),
        WebViewSource(
            WebViewImplementation.MULCH,
            "Mulch WebView",
            "gitlab.com/divested-mobile/mulch",
            openOnClick("https://gitlab.com/divested-mobile/mulch"),
            supported = true
        )
    )

    private fun openOnClick(url: String) = View.OnClickListener { activity.openInBrowser(url) }
}
