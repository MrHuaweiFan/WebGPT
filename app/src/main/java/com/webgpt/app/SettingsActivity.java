package com.webgpt.app;

import android.app.Activity;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import com.webgpt.app.webview.UpdateChecker;
import com.webgpt.app.webview.WebViewManagerDialog;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.io.File;

public class SettingsActivity extends Activity {

    private static final String PREFS_NAME = "webgpt_prefs";

    private Button checkUpdateButton;
    private TextView updateStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.AppTheme);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        final SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        // ─── WebView Manager button ───────────────────────────────────────
        Button webviewManagerButton = findViewById(R.id.webview_manager_button);
        if (webviewManagerButton != null) {
            webviewManagerButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final WebViewManagerDialog[] dialog = new WebViewManagerDialog[1];
                    dialog[0] = new WebViewManagerDialog(SettingsActivity.this,
                            new DialogInterface.OnDismissListener() {
                                @Override
                                public void onDismiss(DialogInterface d) {
                                    if (dialog[0] != null && dialog[0].changedWebView()) {
                                        Toast.makeText(SettingsActivity.this,
                                                R.string.webview_restart_required,
                                                Toast.LENGTH_LONG).show();
                                        restartApp();
                                    }
                                }
                            });
                    dialog[0].show();
                }
            });
        }

        // ─── Optimize WebView toggle ──────────────────────────────────────
        // Persists immediately; takes effect after the Apply & restart.
        MaterialSwitch optimizeSwitch = findViewById(R.id.webview_optimize_switch);
        if (optimizeSwitch != null) {
            optimizeSwitch.setChecked(prefs.getBoolean("webview_optimize", false));
            optimizeSwitch.setOnCheckedChangeListener((button, isChecked) -> {
                prefs.edit().putBoolean("webview_optimize", isChecked).apply();
                Log.i("SettingsActivity", "Optimize WebView → " + isChecked);
            });
        }

        // ─── Page loading progress toggle ─────────────────────────────────
        // Off by default. MainActivity re-reads this pref every time the
        // loading screen appears, so the change takes effect on the next
        // page load — no restart needed (unlike the optimize toggle above).
        MaterialSwitch progressSwitch = findViewById(R.id.loading_progress_switch);
        if (progressSwitch != null) {
            progressSwitch.setChecked(prefs.getBoolean("loading_progress", false));
            progressSwitch.setOnCheckedChangeListener((button, isChecked) -> {
                prefs.edit().putBoolean("loading_progress", isChecked).apply();
                Log.i("SettingsActivity", "Loading progress bar → " + isChecked);
            });
        }

        // ─── Check for updates ────────────────────────────────────────────
        checkUpdateButton = findViewById(R.id.check_update_button);
        updateStatus = findViewById(R.id.update_status);
        refreshUpdateButtonState();

        if (checkUpdateButton != null) {
            checkUpdateButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onCheckUpdateClicked();
                }
            });
        }

        // ─── Version label ────────────────────────────────────────────────
        TextView aboutVersion = findViewById(R.id.about_version);
        if (aboutVersion != null) {
            aboutVersion.setText(getAppVersion());
        }

        // ─── Apply button ─────────────────────────────────────────────────
        // Toggles persist immediately via their OnCheckedChangeListener.
        // This button just restarts the app to activate changes that need a
        // fresh process (webview optimize hook, etc.).
        Button applyButton = findViewById(R.id.apply_button);
        if (applyButton != null) {
            applyButton.setOnClickListener(v -> restartApp());
        }

        // Cleanup any stale APK from a previous update attempt.
        // (The update flow now opens the browser instead of downloading in-app,
        // so there shouldn't be any — but clean up just in case.)
        File staleApk = UpdateChecker.findDownloadedApk(this);
        if (staleApk != null) staleApk.delete();
    }

    /**
     * The button always says "Check for updates" now — there's no in-app
     * download/install step anymore. When an update is found, we just open
     * the release URL in the browser.
     */
    private void refreshUpdateButtonState() {
        checkUpdateButton.setText(R.string.check_for_updates);
    }

    private void onCheckUpdateClicked() {
        if (updateStatus != null) {
            updateStatus.setText(R.string.update_checking);
        }
        checkUpdateButton.setEnabled(false);
        UpdateChecker.checkForUpdates(getAppVersion(), new UpdateChecker.Callback() {
            @Override
            public void onResult(boolean updateAvailable, String latestVersion,
                                 String releaseUrl, String errorMessage) {
                runOnUiThread(() -> {
                    checkUpdateButton.setEnabled(true);
                    if (errorMessage != null && updateStatus != null) {
                        updateStatus.setText("Check failed: " + errorMessage);
                        return;
                    }
                    if (!updateAvailable) {
                        if (updateStatus != null) {
                            updateStatus.setText(getString(R.string.update_up_to_date)
                                    + " (v" + getAppVersion() + ")");
                        }
                        return;
                    }
                    // Update available → show a dialog and open the browser.
                    if (updateStatus != null) {
                        updateStatus.setText(getString(R.string.update_available, latestVersion));
                    }
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(SettingsActivity.this)
                            .setTitle("Update available")
                            .setMessage("Version " + latestVersion + " is available. "
                                    + "Open the download page in your browser?")
                            .setPositiveButton("Open", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface d, int which) {
                                    openUrl(releaseUrl);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                });
            }
        });
    }

    /** Open a URL in the user's default browser. */
    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }

    private String getAppVersion() {
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            return pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private void restartApp() {
        Intent i = getBaseContext().getPackageManager()
                .getLaunchIntentForPackage(getBaseContext().getPackageName());
        if (i != null) {
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(i);
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }
}
