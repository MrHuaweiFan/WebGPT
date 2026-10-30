package org.webgpt.app

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.IdRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.webgpt.app.webview.UpdateChecker
import org.webgpt.app.webview.WebViewManagerDialog

class SettingsActivity : Activity() {

    private lateinit var checkUpdateButton: Button
    private lateinit var updateStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<Button>(R.id.webview_manager_button).setOnClickListener { showWebViewManager() }

        // Persists immediately; takes effect after "Apply & restart".
        bindPrefSwitch(R.id.webview_optimize_switch, App.PREF_WEBVIEW_OPTIMIZE, "Optimize WebView")
        // Off by default. MainActivity re-reads it whenever the loading screen
        // appears, so it applies on the next page load without a restart.
        bindPrefSwitch(R.id.loading_progress_switch, App.PREF_LOADING_PROGRESS, "Loading progress bar")

        checkUpdateButton = findViewById(R.id.check_update_button)
        updateStatus = findViewById(R.id.update_status)
        checkUpdateButton.setText(R.string.check_for_updates)
        checkUpdateButton.setOnClickListener { checkForUpdates() }

        findViewById<TextView>(R.id.about_version).text = appVersion

        // The toggles persist on their own. This button restarts the process so
        // changes that need a fresh start (the optimize hook) take effect.
        findViewById<Button>(R.id.apply_button).setOnClickListener { restartApp() }

        // Remove any APK left over from the old in-app update flow.
        UpdateChecker.findDownloadedApk(this)?.delete()
    }

    private fun showWebViewManager() {
        lateinit var dialog: WebViewManagerDialog
        dialog = WebViewManagerDialog(this, DialogInterface.OnDismissListener {
            if (dialog.changedWebView()) {
                Toast.makeText(this, R.string.webview_restart_required, Toast.LENGTH_LONG).show()
                restartApp()
            }
        })
        dialog.show()
    }

    private fun bindPrefSwitch(@IdRes id: Int, key: String, label: String) {
        findViewById<MaterialSwitch>(id).apply {
            isChecked = appPrefs.getBoolean(key, false)
            setOnCheckedChangeListener { _, checked ->
                appPrefs.edit().putBoolean(key, checked).apply()
                Log.i(TAG, "$label -> $checked")
            }
        }
    }

    private fun checkForUpdates() {
        updateStatus.setText(R.string.update_checking)
        checkUpdateButton.isEnabled = false
        UpdateChecker.checkForUpdates(appVersion) { updateAvailable, latestVersion, releaseUrl, errorMessage ->
            runOnUiThread { showUpdateResult(updateAvailable, latestVersion, releaseUrl, errorMessage) }
        }
    }

    private fun showUpdateResult(
        updateAvailable: Boolean,
        latestVersion: String,
        releaseUrl: String?,
        errorMessage: String?
    ) {
        checkUpdateButton.isEnabled = true
        when {
            errorMessage != null -> updateStatus.text = "Check failed: $errorMessage"
            !updateAvailable || releaseUrl == null ->
                updateStatus.text = getString(R.string.update_up_to_date) + " (v$appVersion)"
            else -> {
                updateStatus.text = getString(R.string.update_available, latestVersion)
                MaterialAlertDialogBuilder(this)
                    .setTitle("Update available")
                    .setMessage("Version $latestVersion is available. Open the download page in your browser?")
                    .setPositiveButton("Open") { _, _ -> openInBrowser(releaseUrl) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private val appVersion: String
        @Suppress("DEPRECATION")
        get() = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "0"
        } catch (e: PackageManager.NameNotFoundException) {
            "0"
        }

    private fun restartApp() {
        packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            startActivity(it)
        }
        finishAffinity()
        Process.killProcess(Process.myPid())
    }

    private companion object {
        const val TAG = "SettingsActivity"
    }
}
