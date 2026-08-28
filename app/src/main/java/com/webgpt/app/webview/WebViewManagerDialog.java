package com.webgpt.app.webview;

import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.content.pm.PackageInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.webgpt.app.R;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Material AlertDialog that hosts the WebView picker UI.
 *
 * Two tabs:
 * <ul>
 *   <li><b>Installed</b> — RecyclerView of every installed WebView provider.
 *       Tapping a row saves its package name to SharedPreferences.</li>
 *   <li><b>Downloader</b> — RecyclerView of {@link WebViewSource}s. Each row
 *       has a single "Download" button that opens the source's releases page
 *       in the browser (no in-app APK download — the user installs the
 *       WebView package manually from there).</li>
 * </ul>
 *
 * The neutral button "Learn more" opens a second Material AlertDialog that
 * explains why the user might want to switch WebViews (copy button / mic /
 * performance fixes, Thorium recommended for Huawei).
 *
 * Dialog stacking (v6.27): the info dialog opens ON TOP of this one instead
 * of replacing it. "Understood" dismisses only the info dialog, putting the
 * user back in the manager where they left off. Tapping OUTSIDE the info
 * dialog (or pressing back) cancels BOTH dialogs — the same "close all"
 * behavior tapping outside always had. A non-cancelable manager (the
 * unsupported-WebView gate in MainActivity) stays up either way, so the
 * forced pick flow cannot be bypassed from the info dialog.
 */
public final class WebViewManagerDialog extends MaterialAlertDialogBuilder {

    private static final String TAG = "WebViewManagerDialog";

    private final Activity activity;
    private final boolean is64bit;
    private WebViewInstalledAdapter installedAdapter;
    private final Map<WebViewImplementation, WebViewSource> webViewsImplementation = new LinkedHashMap<>();
    /** The shown manager dialog, so the info dialog can dismiss it on cancel. */
    private AlertDialog managerDialog;
    /**
     * Builder-level cancelable flag. android.app.Dialog has
     * {@code setCancelable()} but NO public getter ({@code isCancelable()}
     * exists only on DialogFragment), so the builder remembers the value
     * here — the info dialog's cancel handler needs it to know whether the
     * manager may be dismissed along with the info dialog.
     */
    private boolean managerCancelable = true;

    public WebViewManagerDialog(Activity activity) {
        this(activity, null);
    }

    public WebViewManagerDialog(final Activity activity,
                                DialogInterface.OnDismissListener onDismissListener) {
        super(activity);
        this.activity = activity;
        this.is64bit = Build.SUPPORTED_64_BIT_ABIS != null && Build.SUPPORTED_64_BIT_ABIS.length > 0;

        // ─── Build the three download sources ───────────────────────────────
        // Each row's "Download" button opens the corresponding releases page
        // in the user's browser. The user then installs the APK manually.
        webViewsImplementation.put(WebViewImplementation.GOOGLE, new WebViewSource(
                WebViewImplementation.GOOGLE,
                "Android System WebView",
                "Google Play Store",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openUrl(activity, "https://play.google.com/store/apps/details?id=com.google.android.webview");
                    }
                },
                true
        ));
        webViewsImplementation.put(WebViewImplementation.THORIUM, new WebViewSource(
                WebViewImplementation.THORIUM,
                "Thorium WebView",
                "github.com/Alex313031/Thorium-Android",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openUrl(activity, "https://github.com/Alex313031/Thorium-Android");
                    }
                },
                !isDefault("com.android.webview")
        ));
        webViewsImplementation.put(WebViewImplementation.MULCH, new WebViewSource(
                WebViewImplementation.MULCH,
                "Mulch WebView",
                "gitlab.com/divested-mobile/mulch",
                new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        openUrl(activity, "https://gitlab.com/divested-mobile/mulch");
                    }
                },
                true
        ));

        // ─── Inflate + bind ─────────────────────────────────────────────────
        final View view = activity.getLayoutInflater().inflate(R.layout.dialog_webview_manager, null);

        Button btnRefresh = view.findViewById(R.id.btn_refresh);
        MaterialButtonToggleGroup tglTab = view.findViewById(R.id.tgl_tab);
        View txtUnsupported = view.findViewById(R.id.txt_unsupported);
        final LinearLayout layInstalled = view.findViewById(R.id.lay_installed);
        final LinearLayout layDownloader = view.findViewById(R.id.lay_downloader);
        final RecyclerView lstInstalled = view.findViewById(R.id.lst_installed);
        TextView txtEmpty = view.findViewById(R.id.txt_empty);
        RecyclerView lstDownloader = view.findViewById(R.id.lst_downloader);

        txtUnsupported.setVisibility(WebViewUtil.isSupported() ? View.GONE : View.VISIBLE);

        tglTab.addOnButtonCheckedListener(new MaterialButtonToggleGroup.OnButtonCheckedListener() {
            @Override
            public void onButtonChecked(MaterialButtonToggleGroup group, int checkedId, boolean isChecked) {
                if (!isChecked) return;
                if (group.indexOfChild(view.findViewById(checkedId)) == 0) {
                    layInstalled.setVisibility(View.VISIBLE);
                    layDownloader.setVisibility(View.GONE);
                } else {
                    layInstalled.setVisibility(View.GONE);
                    layDownloader.setVisibility(View.VISIBLE);
                }
            }
        });

        lstInstalled.setLayoutManager(new LinearLayoutManager(activity));
        installedAdapter = new WebViewInstalledAdapter(activity, lstInstalled, txtEmpty);
        lstInstalled.setAdapter(installedAdapter);
        lstInstalled.setItemAnimator(null);

        lstDownloader.setLayoutManager(new LinearLayoutManager(activity));
        lstDownloader.setAdapter(new WebViewDownloaderAdapter(activity,
                webViewsImplementation.values().toArray(new WebViewSource[0])));

        btnRefresh.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                installedAdapter.reloadData();
                installedAdapter.notifyDataSetChanged();
            }
        });

        setTitle(R.string.webview_manager);
        // Use a literal English string for the positive button. Material's
        // default android.R.string.ok gets auto-translated by the framework
        // (e.g. to "Aceptar" on a Spanish device), which we don't want —
        // this app is English-only.
        setPositiveButton("OK", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int which) { d.dismiss(); }
        });
        // "Learn more" opens an in-app Material info dialog instead of a
        // browser URL. The dialog explains the benefits of switching WebView.
        // The listener below is a placeholder: show() re-wires the actual
        // button so clicking it does NOT auto-dismiss this dialog (the info
        // dialog opens on top of the manager instead of replacing it).
        setNeutralButton(R.string.learn_more, null);
        setOnDismissListener(onDismissListener);
        setView(view);
    }

    /**
     * Records the cancelable flag (Dialog exposes no getter) and applies it
     * to the manager dialog. MainActivity calls this with {@code false} for
     * the non-cancelable unsupported-WebView gate.
     */
    @Override
    public MaterialAlertDialogBuilder setCancelable(boolean cancelable) {
        managerCancelable = cancelable;
        return super.setCancelable(cancelable);
    }

    /**
     * Shows the manager dialog and re-wires its "Learn more" button so the
     * dialog stays open underneath the info dialog.
     *
     * AlertDialog buttons registered through the Builder auto-dismiss the
     * dialog on click. Setting a listener directly on the Button AFTER
     * show() bypasses that auto-dismiss, which is exactly what stacking
     * needs: Learn more keeps the manager alive, "Understood" in the info
     * dialog drops the user back into it.
     */
    @Override
    public AlertDialog show() {
        managerDialog = super.show();
        Button neutral = managerDialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (neutral != null) {
            neutral.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showInfoDialog();
                }
            });
        }
        return managerDialog;
    }

    /**
     * Material info dialog explaining why the user might want to switch
     * WebViews. Opens stacked on top of the manager dialog.
     *
     * <ul>
     *   <li><b>Understood</b> — dismisses ONLY the info dialog; the manager
     *       dialog is right behind it, so the user is back where they were.</li>
     *   <li><b>Outside tap / back button</b> (cancel) — closes the info
     *       dialog AND the manager dialog (the old "closes all popups"
     *       behavior). A non-cancelable manager stays up: its gate must not
     *       be bypassable by tapping outside a child dialog.</li>
     * </ul>
     */
    private void showInfoDialog() {
        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.webview_info_title)
                .setMessage(R.string.webview_info_body)
                .setPositiveButton(R.string.understood, null)
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    @Override public void onCancel(DialogInterface d) {
                        // managerCancelable instead of an isCancelable() call:
                        // Dialog has no getter (v6.27 build error on CI).
                        if (managerDialog != null && managerDialog.isShowing()
                                && managerCancelable) {
                            managerDialog.dismiss();
                        }
                    }
                })
                .show();
    }

    private boolean isDefault(String pkgName) {
        PackageInfo def = WebViewUtil.getDefaultProvider();
        return def != null && def.packageName.equals(pkgName);
    }

    /** True if the user changed the selection (needs restart to apply). */
    public boolean changedWebView() {
        if (installedAdapter == null) return false;
        PackageInfo sel = installedAdapter.getSelectedWebView();
        PackageInfo cur = WebViewUtil.getCurrentProvider();
        if (sel == null || cur == null) return false;
        return !sel.packageName.equals(cur.packageName);
    }

    private static void openUrl(Context context, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show();
        }
    }
}
