package org.webgpt.app.webview

/**
 * Identifier for a downloadable WebView source. Used as the key in the
 * `webViewsImplementation` map inside [WebViewManagerDialog].
 *
 * Keep this in sync with the order in which rows appear in the downloader tab.
 */
enum class WebViewImplementation {
    GOOGLE,
    THORIUM,
    MULCH
}
