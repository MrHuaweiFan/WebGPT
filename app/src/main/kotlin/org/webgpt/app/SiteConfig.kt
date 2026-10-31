package org.webgpt.app

import android.webkit.WebView

/**
 * Everything that ties the app to one website. Porting the app to another
 * site (WebMini, WebGLM, ...) starts here.
 */
object SiteConfig {

    /** The site's own host. Offline text, "sign-in finished" rule and the site host list build on it. */
    const val HOME_HOST = "chatgpt.com"

    const val HOME_URL = "https://$HOME_HOST/"

    /** Tag of every log line the app writes. */
    const val LOG_TAG = "WebGPTApp"

    /** Name prefix of images saved into Download. */
    const val IMAGE_FILE_PREFIX = "chatgpt_image_"

    /** GitHub repository whose releases the Settings update check reads. */
    const val RELEASES_REPO = "MrHuaweiFan/WebGPT"
    const val UPDATER_USER_AGENT = "WebGPT-Android-Updater/6.0"

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

    /**
     * The site's own hosts (each with its subdomains). Only these may be granted
     * the camera and microphone: voice mode and photo capture run on them, and
     * Google's sign-in pages never ask.
     */
    val SITE_HOSTS: List<String> = listOf("chatgpt.com", "openai.com")

    /** Hosts that load in the app from anywhere in the main frame. */
    val MAIN_FRAME_HOSTS: List<String> = listOf("chatgpt.com", "openai.com", "accounts.google.com")

    /** Pages that mark a popup, or a navigation context, as "signing in". */
    val SIGN_IN_CHAIN_HOSTS: List<String> = listOf("accounts.google.com", "auth.openai.com")

    /**
     * Extra https hosts that may load during sign-in although they are not in
     * [ALLOWED_HOSTS]. accounts.youtube.com is Google's session-sync hop. It was
     * confirmed on a device: it was the host sent to the browser right after 2-step
     * verification, which opened Google's consent page there (HTTP 400) and left the
     * popup stuck.
     *
     * Kept apart from [ALLOWED_HOSTS] on purpose: the bridge gate, the camera and
     * microphone origin check and popup trust revocation must still treat these
     * hosts as foreign.
     */
    val LOGIN_HOP_HOSTS: List<String> = listOf("accounts.youtube.com")

    /**
     * Where the prompt box can be, first match wins. Used by every script the app
     * builds in Kotlin. The injected page scripts keep their own, stricter copies
     * (see WEBSITE_SPECIFICS.md, "The page contract").
     */
    val COMPOSER_SELECTORS: List<String> = listOf(
        "#prompt-textarea",
        "div[contenteditable=\"true\"][role=\"textbox\"]",
        "div[contenteditable=\"true\"]",
        "textarea[placeholder]",
    )

    /** A JavaScript expression that evaluates to the composer element, or null. */
    fun composerLookupJs(): String =
        COMPOSER_SELECTORS.joinToString(" || ", "(", ")") { "document.querySelector('$it')" }
}

/** Loads [url] with [SiteConfig.EXTRA_HEADERS]. */
internal fun WebView.loadSiteUrl(url: String) = loadUrl(url, SiteConfig.EXTRA_HEADERS)
