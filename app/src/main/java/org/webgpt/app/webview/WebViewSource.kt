package org.webgpt.app.webview

import android.view.View

/**
 * Description of one downloadable WebView source, shown as a row in the
 * downloader tab of [WebViewManagerDialog].
 *
 * @property name e.g. "Thorium WebView"
 * @property source short display URL, e.g. "github.com/Alex313031/Thorium-Android"
 * @property supported false: the button shows "Unsupported" and is disabled
 */
class WebViewSource(
    val id: WebViewImplementation,
    val name: String,
    val source: String,
    val onDownload: View.OnClickListener,
    val supported: Boolean
)
