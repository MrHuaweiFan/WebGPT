package org.webgpt.app

import android.webkit.WebView

/**
 * Everything that ties the app to one website. Porting the app to another
 * site (WebMini, WebGLM, ...) starts here.
 */
object SiteConfig {

    const val HOME_URL = "https://chatgpt.com/"

    /**
     * Desktop-class Chrome token on a Pixel 8. The default WebView user agent
     * contains "wv", which makes the site treat the app as an embedded browser.
     */
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    /**
     * Sent with every top-level load. An empty X-Requested-With stops the
     * WebView from announcing the app package, which makes ChatGPT show its
     * "install the app" banner.
     */
    val EXTRA_HEADERS: Map<String, String> = mapOf("X-Requested-With" to "")

    /**
     * Domains that load inside the app (each one plus all of its subdomains).
     * Everything else opens in the system browser.
     *
     * Bare "auth0.com" is deliberately absent: OpenAI's login runs on
     * auth.openai.com (covered by openai.com), while *.auth0.com hosts arbitrary
     * third-party tenants that must not be trusted.
     *
     * The Google entries are required for "Continue with Google". After
     * accounts.google.com, the account picker and consent screens hop through
     * myaccount.google.com, www.google.com and oauthaccount.googleusercontent.com
     * before landing back on auth.openai.com. Without them every hop was sent to
     * the external browser mid-login, and the popup teardown that followed
     * crashed the app on Android 15.
     */
    val ALLOWED_HOSTS: List<String> = listOf(
        "chatgpt.com",
        "openai.com",
        "accounts.google.com",
        "google.com",
        "googleusercontent.com",
        "gstatic.com",
    )
}

/** Loads [url] with [SiteConfig.EXTRA_HEADERS]. */
internal fun WebView.loadSiteUrl(url: String) = loadUrl(url, SiteConfig.EXTRA_HEADERS)
