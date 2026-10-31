package org.webgpt.app

import android.content.Context
import android.net.Uri
import java.util.Locale

/**
 * Decides where a navigation goes: inside the app, to the system browser, or nowhere.
 *
 * The allowlist itself lives in [SiteConfig.ALLOWED_HOSTS]. This file holds the
 * rules for the sign-in chain, which are the same for any site that logs in through Google.
 */
object NavigationPolicy {

    enum class Decision {
        /** Let the WebView load the URL itself. */
        LOAD,

        /** Hand the URL to the system browser or another app. */
        OPEN_EXTERNALLY,

        /** Drop the navigation. */
        BLOCK,
    }

    /**
     * Second sign-in hop, confirmed on a device: after accounts.youtube.com,
     * Google sets the session cookie on its country domain, accounts.google.<suffix>.
     * The suffix depends on the account's country, so the hop is checked against
     * Google's own list (https://www.google.com/supported_domains, fetched
     * 2026-10-18, without ".com", which [isAllowedHost] already covers). It is an
     * exact "accounts.google." + suffix match, never a pattern.
     */
    private val GOOGLE_COUNTRY_SUFFIXES: Set<String> = (
        "ad ae al am as at az ba be bf bg bi bj bs bt by ca cat cd cf cg ch " +
            "ci cl cm cn co.ao co.bw co.ck co.cr co.id co.il co.in co.jp co.ke " +
            "co.kr co.ls co.ma co.mz co.nz co.th co.tz co.ug co.uk co.uz co.ve " +
            "co.vi co.za co.zm co.zw com.af com.ag com.ar com.au com.bd com.bh " +
            "com.bn com.bo com.br com.bz com.co com.cu com.cy com.do com.ec " +
            "com.eg com.et com.fj com.gh com.gi com.gt com.hk com.jm com.kh " +
            "com.kw com.lb com.ly com.mm com.mt com.mx com.my com.na com.ng " +
            "com.ni com.np com.om com.pa com.pe com.pg com.ph com.pk com.pr " +
            "com.py com.qa com.sa com.sb com.sg com.sl com.sv com.tj com.tr " +
            "com.tw com.ua com.uy com.vc com.vn cv cz de dj dk dm dz ee es fi " +
            "fm fr ga ge gg gl gm gr gy hn hr ht hu ie im iq is it je jo kg ki " +
            "kz la li lk lt lu lv md me mg mk ml mn mu mv mw ne nl no nr nu pl " +
            "pn ps pt ro rs ru rw sc se sh si sk sm sn so sr st td tg tl tm tn " +
            "to tt vu ws"
        ).split(" ").toSet()

    private const val GOOGLE_ACCOUNTS_PREFIX = "accounts.google."

    /**
     * True for exactly the listed domain or any subdomain of it ("chatgpt.com",
     * "auth.openai.com"), never for look-alikes such as "evilchatgpt.com".
     */
    fun isAllowedHost(host: String?): Boolean =
        host != null && SiteConfig.ALLOWED_HOSTS.any { hostIs(host, it) }

    /** True for the site's own hosts only (not the sign-in hosts of [SiteConfig.ALLOWED_HOSTS]). */
    fun isSiteHost(host: String?): Boolean =
        host != null && SiteConfig.SITE_HOSTS.any { hostIs(host, it) }

    /**
     * True when a popup page has landed back on the site after signing in: the
     * site's own host (or a subdomain) and not one of its /auth/ pages.
     */
    fun isLoginFinishedUrl(url: String): Boolean {
        val host = parse(url)?.host ?: return false
        return hostIs(host, SiteConfig.HOME_HOST) && !url.contains("/auth/")
    }

    private fun hostIs(host: String, domain: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return h == domain || h.endsWith(".$domain")
    }

    private fun parse(url: String?): Uri? =
        if (url == null) null else runCatching { Uri.parse(url) }.getOrNull()

    private fun Uri.isHttps(): Boolean = "https".equals(scheme, ignoreCase = true)

    /** True for the pages that mark a popup as a sign-in popup. */
    fun isLoginChainUrl(url: String?): Boolean {
        val uri = parse(url) ?: return false
        val host = uri.host ?: return false
        return uri.isHttps() && SiteConfig.SIGN_IN_CHAIN_HOSTS.any { hostIs(host, it) }
    }

    /** True for an https URL on a [SiteConfig.LOGIN_HOP_HOSTS] host or on accounts.google.<country>. */
    private fun isLoginHopUrl(uri: Uri): Boolean {
        val host = uri.host ?: return false
        if (!uri.isHttps()) return false
        if (SiteConfig.LOGIN_HOP_HOSTS.any { hostIs(host, it) }) return true
        val h = host.lowercase(Locale.ROOT)
        return h.startsWith(GOOGLE_ACCOUNTS_PREFIX) &&
            h.removePrefix(GOOGLE_ACCOUNTS_PREFIX) in GOOGLE_COUNTRY_SUFFIXES
    }

    /**
     * True if the page a WebView is on right now belongs to the sign-in chain
     * (accounts.google.com, auth.openai.com or a login hop host). A navigation
     * started from such a page is part of the login. One started from a chat page
     * is a link the user tapped.
     */
    fun isSignInContextUrl(url: String?): Boolean {
        val uri = parse(url) ?: return false
        return isLoginChainUrl(url) || isLoginHopUrl(uri)
    }

    /**
     * Main-frame host policy. Only ChatGPT, OpenAI and Google's sign-in entry
     * point load from anywhere. The rest of the allowlist (google.com, gemini,
     * googleusercontent.com, gstatic.com) loads in the app only while a sign-in
     * is in progress: the login chain hops through those hosts, but a link the
     * user taps in a chat goes to the browser. Iframes keep the full allowlist
     * (see [decideFrame]) so embedded Google widgets work.
     */
    private fun isMainFrameHostAllowed(uri: Uri, signInContext: Boolean): Boolean {
        val host = uri.host ?: return false
        if (SiteConfig.MAIN_FRAME_HOSTS.any { hostIs(host, it) } || isLoginHopUrl(uri)) return true
        return signInContext && isAllowedHost(host)
    }

    /**
     * Policy for the main frame and for popups.
     *
     * blob:, data:, about: and javascript: load in place, as in the original
     * release. Blocking them would break any site feature that navigates to
     * generated content. Only file: and content: are blocked, which keeps local
     * files out of reach of the page.
     */
    fun decideMainFrame(url: String?, signInContext: Boolean): Decision {
        val uri = parse(url) ?: return Decision.LOAD
        return when (uri.scheme?.lowercase(Locale.ROOT)) {
            "http", "https" ->
                if (uri.host != null && !isMainFrameHostAllowed(uri, signInContext)) {
                    Decision.OPEN_EXTERNALLY
                } else {
                    Decision.LOAD
                }
            "mailto", "tel", "sms", "geo", "intent", "market" -> Decision.OPEN_EXTERNALLY
            "file", "content" -> Decision.BLOCK
            else -> Decision.LOAD
        }
    }

    /**
     * Policy for iframes: the full allowlist plus the login hops. URLs without a
     * host (blob:, data:) must load. Blocking them broke ChatGPT's embedded
     * investigation panel with "Error loading application: Runtime error", since
     * that panel is an iframe app whose URL has no host.
     */
    fun decideFrame(url: String?): Decision {
        val uri = parse(url) ?: return Decision.LOAD
        val host = uri.host ?: return Decision.LOAD
        return if (!isAllowedHost(host) && !isLoginHopUrl(uri)) Decision.OPEN_EXTERNALLY else Decision.LOAD
    }
}

/**
 * Carries out [this] decision for [url].
 *
 * @return the value for shouldOverrideUrlLoading: true if the WebView must not load the URL.
 */
internal fun NavigationPolicy.Decision.apply(context: Context, url: String): Boolean = when (this) {
    NavigationPolicy.Decision.LOAD -> false
    NavigationPolicy.Decision.OPEN_EXTERNALLY -> {
        context.openInBrowser(url)
        true
    }
    NavigationPolicy.Decision.BLOCK -> true
}
