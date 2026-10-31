# WEBSITE_SPECIFICS.md

Everything in WebGPT that exists because of `chatgpt.com`, or because of how WebGPT looks. Two
questions are answered here: what failed with the site and why the code is as it is now, and what
was added to a generic WebView app to adapt it to ChatGPT. Generic WebView code is documented by its
comments and is not repeated. Where this file and the code disagree, the code is right. The longer
previous version of this file is at tag `v6.29`.

The app has three layers. The **shell** is the generic WebView app: `MainActivity`, the popup,
download, share and bridge classes and the `webview/` package (the WebView switcher). The **site
layer** is `SiteConfig.kt` and the three scripts in `app/src/main/assets/js/`. The **brand layer**
is the Android resources and the manifest.

## Where the ChatGPT-specific code lives

| If this changes on chatgpt.com or in the brand | Edit |
|---|---|
| URL, user agent, headers, host lists, sign-in hosts, camera and microphone hosts, update repository | `SiteConfig.kt` (Site constants, Navigation and host policy) |
| The sign-in finished rule and the host decisions that use the lists | `NavigationPolicy.kt` |
| The composer element | `SiteConfig.COMPOSER_SELECTORS` (fills the probe, drop and focus scripts built in `ShareHandler.kt`); two selectors inside `page_ready_watcher.js`; `isComposer()` inside `focus_guard.js` |
| Page-ready markers | `page_ready_watcher.js` (three markers) and `COMPOSER_READY_JS` in `ShareHandler.kt` (the two visible ones) |
| Existing-chat paths `/c/` and `/g/` | `focus_guard.js`, `isExistingChat()` |
| Share, copy and blob behavior inside the site's iframes | `page_overrides.js`: generic mechanics, but shaped by what ChatGPT does |
| Colors, texts, logos, labels | the resources listed in WebGPT presentation |

Only `SiteConfig.kt` and the scripts in `assets/js/` name the site's hosts and selectors. The names
`WebGPT` and `chatgpt` also appear in resources, in the page-global names `__webgpt*` (the contract
between the app and its scripts) and in the preference file name `webgpt_prefs`.

## Site constants

| Constant | Value | Why |
|---|---|---|
| `SiteConfig.HOME_URL` | `https://chatgpt.com/` | First load, offline retry, the reload after a sign-in popup, and renderer recovery when no chat page is known. |
| `SiteConfig.USER_AGENT` | `Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36` | The default WebView string contains `wv`, and the site treats that as an embedded browser. Popups copy the main WebView's user agent; the native download fallback uses it when the download listener gives none. |
| `SiteConfig.EXTRA_HEADERS` | `X-Requested-With` with an empty value, on every main-frame load (`loadSiteUrl`) | Suppresses the default header, which carries the app's package name. chatgpt.com reads it and shows an install-the-app banner. |
| Third-party cookies | off on the main WebView, on in every popup | Cross-site trackers inside chatgpt.com cannot follow the user. The sign-in chain needs them, so popups keep them; popups share the main cookie jar. |
| Referer | `SiteConfig.HOME_URL` | Sent by the native fetches that carry the WebView's cookies and user agent: image share, image download, file download. |
| JavaScript names | page globals `__webgptLoad`, `__webgptFileB64`, `__webgptShareActiveUntil`, `__webgptBlobs`, `__webgptBeacon`, `__webgptFocusGuard`, `_webgptReadyWatch`; bridge object `AndroidBridge` | The contract between the app and its scripts. |
| `SiteConfig.LOG_TAG` | `WebGPTApp` | Every log line of the app. |
| `SiteConfig.IMAGE_FILE_PREFIX` | `chatgpt_image_` | Saved images are `chatgpt_image_<millis>.<extension>` in Download. |
| `SiteConfig.RELEASES_REPO` | `MrHuaweiFan/WebGPT`, user agent `WebGPT-Android-Updater/6.0` | The update check in Settings reads the releases of this repository. |

Trap: `WebView.restoreState()` reloads the current page without the extra header. After a restore
the app navigates to that URL again explicitly (`restoreWebViewState` in `MainActivity.kt`).
Removing this brings the install-the-app banner back after the process was killed.

## Navigation and host policy

**Host lists** (`SiteConfig.kt`). A host matches a list entry if it is equal to it or is a
subdomain of it (dot boundary, case-insensitive). `evilchatgpt.com` and `chatgpt.com.evil.com` do
not match.

| List | Hosts | Note |
|---|---|---|
| `ALLOWED_HOSTS` | `chatgpt.com`, `openai.com`, `accounts.google.com`, `google.com`, `googleusercontent.com`, `gstatic.com` | `auth.openai.com` is covered by `openai.com`. Bare `auth0.com` is absent on purpose: it hosts third-party tenants. |
| `MAIN_FRAME_HOSTS` | `chatgpt.com`, `openai.com`, `accounts.google.com` | Load in the app from anywhere in the main frame. |
| `SIGN_IN_CHAIN_HOSTS` | `accounts.google.com`, `auth.openai.com` | A page on these marks a popup, or a navigation, as part of a sign-in. |
| `LOGIN_HOP_HOSTS` | `accounts.youtube.com` | Google's session-sync hop. After two-step verification it was sent to the system browser, which opened Google's consent page there (HTTP 400) and left the popup stuck. Confirmed on a device. |
| Country hops | `accounts.google.<suffix>` | Google sets the session cookie on the account's country domain. Matched exactly against Google's own list (186 suffixes from `https://www.google.com/supported_domains`, fetched 2026-10-18, without `.com`; `GOOGLE_COUNTRY_SUFFIXES` in `NavigationPolicy.kt`), never by pattern. Confirmed on a device. |
| `SITE_HOSTS` | `chatgpt.com`, `openai.com` | Camera and microphone origins (Camera and microphone). |

The two hop rows are an exception for navigation only. They are not in `ALLOWED_HOSTS`, so the
bridge gate, the popup trust revocation and the camera and microphone check still treat these hosts
as foreign.

**Policy by case** (`NavigationPolicy.decideMainFrame` and `decideFrame`).

| Case | Rule |
|---|---|
| Main frame on a `MAIN_FRAME_HOSTS` host or a hop host | Loads in the app. |
| Main frame on another `ALLOWED_HOSTS` host (`google.com` with every subdomain, `googleusercontent.com`, `gstatic.com`) | In the app only while a sign-in is in progress: the page the WebView is on is on the sign-in chain or a hop host, or the popup has been on it. A link to `google.com` tapped in a chat opens in the system browser. |
| Main frame, any other http(s) host | System browser. |
| `mailto:`, `tel:`, `sms:`, `geo:`, `intent:`, `market:` | Handed to the system. |
| `file:`, `content:` | Blocked. |
| `blob:`, `data:`, `about:`, `javascript:` | Load in place. |
| Iframe without a host (`blob:`, `data:`) | Loads in place. |
| Iframe, http(s) | The whole `ALLOWED_HOSTS` list and the hop hosts, no sign-in rule, so embedded Google widgets keep working; other hosts go to the system browser. |
| Popup main-frame navigation off the lists | System browser, and the popup is removed. Without this a black screen stayed after opening X, Reddit or LinkedIn links. |
| Android below 7 | The legacy callback has no main-frame flag, so the iframe rule applies to everything. |

**The sign-in chain.** `chatgpt.com`, `auth.openai.com`, `accounts.google.com`, then
`myaccount.google.com`, `www.google.com` and `oauthaccount.googleusercontent.com`, back to
`auth.openai.com`, then `chatgpt.com`. `accounts.google.com` alone is not enough. A missing hop sent
real sign-in traffic to the system browser in the middle of the login and, together with the
destroy-in-callback crash (Sign-in and popups), crashed the app on Android 15.

**Do not harden.** Blocking `blob:` or `data:` iframes broke ChatGPT's investigation panel ("Error
loading application: Runtime error"), so iframes without a host load in place. The injected share
and blob scripts contain no origin check: ChatGPT runs its share and export UI in `blob:` and
cross-origin iframes, and a script-side allowlist blocked exactly those calls. The origin gate lives
in the app (`WebAppBridge.hostAllowed`). It guards `pageReady`, `copyToClipboard`, `onBlobResult`,
`onBlobFailed`, `onBlobDownload`, `onBlobChunk` and `onFileDropResult`, and it reads the WebView's
URL on the UI thread. The two share-sheet calls, `shareText` and `shareFile`, are ungated on
purpose: the system share sheet is the consent screen, and a gate broke sharing from the site's own
share popup, whose URL is `blob:` or `about:blank`. `window.open` is not hooked (Injected scripts).

**The gate.** The main WebView is allowed while its top-level URL is on `ALLOWED_HOSTS`. A popup is
allowed while its own URL is on it, or while it is a blank shell (no URL, `about:` or `blob:`:
ChatGPT's share popup itself has such a URL). A popup loses trust for good once its top frame was
seen on a foreign http(s) page (`revokeTrustIfForeign`, called from the popup's `onPageStarted`,
which also fires for redirect hops that skip `shouldOverrideUrlLoading`). A WebView never inherits
trust from another one. The caller is identified by the top-level URL only, because
`addJavascriptInterface` cannot tell which frame is calling.

## The page contract

**Composer.** The prompt box is the first match of: `#prompt-textarea`;
`div[contenteditable="true"][role="textbox"]`; `div[contenteditable="true"]`;
`textarea[placeholder]`. There is no single chain in the code, on purpose:

- The probe, the focus script and the drop script, all built in `ShareHandler.kt`, use all four
  (`SiteConfig.composerLookupJs()`).
- The ready watcher tests only the first two (`getElementById` and the role-textbox selector). It is
  strict so that a loose match cannot report "ready" on a page that is not a chat.
- The focus guard asks `isComposer()`: the id `prompt-textarea`, an element inside
  `#prompt-textarea`, the role-textbox contenteditable, or any `textarea`. Its autofocus list has
  three selectors.

**Page-ready markers.**

- `[data-splash-headline-option]`: the new-chat greeting wrapper. It must be visible: on mobile it
  exists but lays out as an empty, zero-height box.
- `[data-testid="use-case-prompt-chips"] button`: the mobile suggestion chips. Visible.
- `[data-testid^="conversation-turn"]`: a restored conversation. Presence only, because chats never
  render the splash.

The ready watcher uses all three. The composer probe uses the first two and relies on the settle
rule for chats. Visible means `getClientRects()` returns a box with non-zero width and height.

**Chat paths.** `/c/` (conversations) and `/g/` followed by at least one more character (gems). On
these paths the site focuses the composer by itself.

**Login finished.** A popup page that finished loading on `chatgpt.com` or a subdomain, with no
`/auth/` in the URL (`NavigationPolicy.isLoginFinishedUrl`).

**File input.** The first `input[type=file]` in the document: the input behind the site's "+ then
Files" flow and the primary target of the share-in drop.

**Selector rules.** Match attributes, ids and test ids, never visible text: greetings and chip labels
rotate and are localized. Presence is not visibility. `[data-testid*=composer]` matches the "+"
attach button, not the text box; that confusion once made the settle code click "+" and open menus.

**When a marker breaks.** Build the debug APK (experimental builds enable `chrome://inspect`), or
open the site in desktop Chrome with device emulation and the app's user agent. Try `#prompt-textarea`,
then contenteditables, then textareas. Snapshot the DOM right after load and again 3 s later:
elements that appear late and carry a test id are the markers; check the mobile DOM for CSS-hidden
ones. Read `location.pathname` in a conversation. Last verified against the live site: 2026-10-29.

## Injected scripts

**Registration** (`PageScripts.installAtDocumentStart`). At document start, in every frame and on
every navigation, before any site script (`WebViewCompat.addDocumentStartJavaScript`), in this order:
`page_overrides.js`, `page_ready_watcher.js`, `focus_guard.js`. The order matters: the watcher reads
`__webgptLoad`, which the overrides install. Popups get only the page overrides. On every page finish (and so for WebViews
without document-start support), `onPageFinished` evaluates the page overrides in the main WebView
and in popups, and the ready watcher in the main WebView only; the focus guard has no fallback. All
three are idempotent (`window.__...` guards). `evaluateJavascript` at page finish reaches only the
main frame, which is why the share and export features of the site (iframes) need document-start
injection.

**Page overrides** (`page_overrides.js`). Six parts:

1. `navigator.share` and `navigator.canShare` call the bridge. One file: `shareFile` (read with
   `FileReader`). Several files: rejected. Text or url: `shareText` (system share sheet). Text only,
   if that bridge method is missing: `copyToClipboard`. `canShare` is honest: exactly one file, or
   text, url or title. Both functions' `toString()` return a native-looking string.
2. `document.execCommand('copy')` copies the current selection through `copyToClipboard`. This makes
   the site's Copy button work on WebViews without a reliable async Clipboard API.
3. Blob lifetime keeper. Wraps `URL.createObjectURL` and `revokeObjectURL`, stores the Blob object
   by URL in `window.__webgptBlobs`, delays the real revoke by 600000 ms and keeps at most 16 blobs
   and 134217728 bytes (oldest evicted). chatgpt.com revokes export blob URLs right after triggering
   the download, long before the WebView's download listener can fetch them. Revoking a URL does not
   destroy the Blob; reading the kept object with `FileReader` still works.
4. Blob download interception. A hook on `HTMLAnchorElement.prototype.click` and a capture-phase
   click listener catch `a[download]` with a `blob:` href. The file is looked up in the keeper (also
   in child frames and in `window.parent`, because the export UI runs in an iframe), then by
   synchronous XHR, then by async `fetch()`. It reaches the app in 262144-character base64 chunks
   (`onBlobChunk`) or whole (`onBlobDownload`).
5. Load tracker `__webgptLoad`: `lastStart` is the time of the last `fetch` or XHR start, `lastMut`
   the time of the last DOM mutation. It feeds the settle rule: no mutation for 2000 ms and no
   request start for 1500 ms.
6. Telemetry: `dbg()` goes to `AndroidBridge.debugLog`, which logs only in experimental builds, and
   a beacon "overrides active (main frame)" once per load.

`window.open` is not hooked: a debug hook once broke the user-gesture context, `onCreateWindow` never
fired and external links stopped opening.

**Ready watcher** (`page_ready_watcher.js`). chatgpt.com's `load` event and the WebView's progress
say "loaded" long before React has hydrated. Hiding the loading screen on `onPageFinished` left it
over a usable page, or took it away over an unusable one. The watcher runs in the main frame only,
once (`_webgptReadyWatch`), and only when the host name contains `chatgpt.com` or `openai.com` (a
substring test; harmless, because `pageReady()` is checked again in the app). It polls every 200 ms
and calls `AndroidBridge.pageReady()` once when the composer exists and one of these holds: a visible
splash headline marker, a visible chips button, a conversation turn, or the settle rule. It also
fires unconditionally 8000 ms after it started. Everything the user sees during a load must consume
this signal, not the raw WebView progress.

**Focus guard** (`focus_guard.js`). chatgpt.com calls `composer.focus()` by itself when an existing
chat opens, on load, on SPA remount and on resume, which pops the keyboard without a tap. The guard
runs in the main frame only, on `chatgpt.com`, `chat.openai.com`, `openai.com` and their subdomains.
On a chat path, unless a share is in progress, it:

- replaces `HTMLElement.prototype.focus` with a version that does nothing for the composer. A focus
  listener with a deferred blur failed: React re-focuses in `requestAnimationFrame` and microtasks
  faster than the blur lands;
- blurs untrusted `focusin` events on the composer (real taps are trusted and pass);
- blurs the composer when the page becomes hidden and again when it becomes visible;
- every 500 ms removes `autofocus` from the composer elements (SPA remounts add it back).

The share bypass is the window `window.__webgptShareActiveUntil`: 6000 ms from the last write by
`ShareHandler.markShareActive`, `settleComposerAfterAutoAttach` or the drop script. Only a share
arms it (`ACTION_SEND` or `ACTION_PROCESS_TEXT`); a launcher tap does not. If the site never
auto-focuses, the guard is dead weight.

**Composer probe** (`COMPOSER_READY_JS` in `ShareHandler.kt`). Polled from the app every 700 ms while
a shared text or file waits for the page. Returns `ready|<domAge>|<netAge>` when the composer exists
and (a visible splash headline or chips button, or the settle rule), otherwise
`wait|<domAge>|<netAge>`; when `__webgptLoad` is missing it returns whether a composer exists. When
its deadline passes (20000 ms for text on a loaded page, 25000 ms otherwise) the action runs anyway.

The scripts the app builds in Kotlin around the composer are described where they are used:
`fileDropScript` and `settleComposerAfterAutoAttach` in Sharing into the app, `blobFetchScript` in
Downloads and exports.

## Flows adapted to ChatGPT

### Sign-in and popups

- **Where sign-in runs.** In a popup WebView (`onCreateWindow`, `PopupManager.create`). Third-party
  cookies are on in popups only, and a popup shares the main cookie jar. A popup is marked as a
  sign-in popup once its top frame has been on a `SIGN_IN_CHAIN_HOSTS` host
  (`NavigationPolicy.isLoginChainUrl`). The mark lets the policy load the rest of the allowlist in
  that popup.
- **How it ends.** When a popup page finishes loading on `chatgpt.com` or a subdomain and the URL has
  no `/auth/`, the app loads that URL into the main WebView (with the extra header) and removes the
  popup. If a marked popup ends any other way (routed to the browser, closed by the page, renderer
  lost), the app flushes the cookies and reloads the home URL in the main WebView, once per popup
  (`reloadMainAfterLogin`). A popup that was never on the chain (the site's share menu, an external
  link) never reloads the chat.
- **Links that open a new window.** The site opens external links, and its share menu, in a new
  window, so each one first creates a popup. The popup is created but not attached to the layout
  (`attachOnce`). It is attached the first time something will render in the app: the first
  navigation the policy does not send away, or `onPageStarted` for `blob:` and `about:blank` popups
  (the share menu), which never call `shouldOverrideUrlLoading`. A navigation that goes to the system
  browser removes the popup while it is still invisible. A fullscreen popup attached at creation
  flashed a black or white frame between the tap and the browser opening, on every external link.
  Back closes only popups that are attached.
- **Teardown is deferred.** `remove` detaches the view at once and posts `destroy()` to the next
  message-loop pass; the destroy is immediate only in `onDestroy`. `WebView.destroy()` called inside
  a WebView callback (`shouldOverrideUrlLoading`, `onPageFinished`, `onRenderProcessGone`, the
  download listener) crashed the app on Android 15 when a login popup was torn down. The F-Droid
  review reported it as a crash on any login button: the popup had reached a Google host that was not
  yet allowed and was being routed to the browser. The main WebView's recreation uses the same
  deferral.
- **Trust.** See the gate in Navigation and host policy.

### Sharing into the app

Android cannot push a file or text into a web page; the app has to feed the composer. Everything
below is what chatgpt.com required to make that work.

- **Entry points.** `ACTION_SEND` with `text/*` (label "Ask WebGPT") or any other type (label "Send
  to WebGPT"), and `ACTION_PROCESS_TEXT` (label "Ask WebGPT", in the text selection menu). One text
  or one file per share. A share first sets the focus-guard bypass window (6000 ms), so its own
  `focus()` is not blocked. When a text share arrives in the sender's task (some OEM share paths launch
  a `singleTask` activity inside the sender's task; file shares are never relayed, because a
  re-launch would lose their URI grants), `MainActivity` starts `ShareRelayActivity` in
  the same task and finishes; the relay waits until it is resumed and forwards the share with
  `FLAG_ACTIVITY_NEW_TASK`. Forwarding straight from `onCreate` left several OEM task managers with a
  task they never brought to the front (the text was copied and the toast appeared, but WebGPT never
  opened).
- **Text.** The app copies the text to the clipboard (label from `app_name`; below Android 13 it also
  shows the toast "Text copied — paste it into WebGPT", from Android 13 the system shows its own
  notice), waits until the composer is really ready, focuses it and opens the keyboard. The user
  pastes. The wait polls the composer probe every 700 ms: up to 20000 ms when the page is already
  loaded. When the page is still loading the share is queued, started by the first page-ready signal
  or by `onPageFinished` (whichever comes first), and then waits up to 25000 ms. When the deadline
  passes the action runs anyway.
- **File, step by step.**
  1. The app copies the file into the cache folder `shared_files` under a sanitized name (the
     sharing app's display name is never trusted as a path) and, if the file is smaller than 50 MiB
     (`MAX_INJECT_BYTES`), reads it as base64. The cache copy stays as the manual fallback (step 7).
  2. The toast "Please wait — the file will attach automatically" appears, only when the file can be
     injected. Tapping "+" during the wait interferes with the automatic attach.
  3. When the page is ready (same wait, 25000 ms) the base64 is staged into the page in chunks of
     524288 characters (`window.__webgptFileB64`).
  4. Primary path. The drop script builds a `File` and a `DataTransfer`, assigns them to the `files`
     of the first `input[type=file]` (fallback: the prototype's setter), dispatches `input` and
     `change`, and focuses the composer. That runs the site's own attach handler with no drag events,
     so the site's full-screen drop overlay never shows. Earlier builds fired synthetic drag events,
     and the overlay had no reliable teardown.
  5. Fallback path, only when no file input exists. Synthetic `dragenter`, `dragover` and `drop` on
     the composer, then `drop`, `dragend` and `dragleave` on the document, and 350 ms later Escape
     keydown and keyup on the document and a focus; these tear the overlay down. This is the only
     Escape in the pipeline: an Escape in the settle passes cancels the pending attachment.
  6. Commit window. chatgpt.com commits a pending attachment only while the composer is focused,
     within about one second of the file landing; unattended, it drops the file silently. So the app
     focuses the composer in passes and summons the keyboard: programmatic focus alone does not
     reliably show the keyboard. A pass is focus only (`focus({preventScroll: true})`, dispatched
     `focus` and `focusin`), no click and no Escape. No click, because the old selector
     `[data-testid*=composer]` matched the "+" attach button and the settle code opened menus.

     | After | Composer focus passes | Keyboard |
     |---|---|---|
     | The drop script | 500, 1500, 2500 ms | with every pass, and again at 800 and 2000 ms |
     | A file chooser hand-over (step 7) | 200, 500, 900, 1400 ms | with every pass |

  7. Chooser fallback. When injection is impossible (50 MiB or more, or the drop reports a failure)
     the site's own "+ then Files" opens the file chooser, and the app answers it with the pending
     file, with no tap. The file is handed over only to the main WebView's chooser (never a popup's),
     only within 60000 ms of the share (`SHARE_ATTACH_WINDOW_MS`; an expired one is dropped), and it
     is cleared when the drop succeeds and when the app stops. The toast "Couldn't attach
     automatically — tap + and choose Files" appears when the drop reports a failure while a pending
     file exists, and 25000 ms after the share if the file is still pending.
  8. Double-attach guard. Right after a programmatic attach chatgpt.com re-opens its file input.
     While the pending file still exists (the drop script's success report clears it), a chooser
     request from the main WebView within 4000 ms of the injection is cancelled
     (`RETRIGGER_IGNORE_MS`). Otherwise the pending file would be handed over a second time, which
     trips the site's attach limit and the attachment vanishes.

The timings are observed behavior of chatgpt.com; re-test them whenever the site changes its attach
flow.

### Downloads and exports

- **The ChatGPT fact.** The chat exports (PDF, Word, Markdown) and the generated files are `blob:`
  anchor downloads created inside iframes, and the site revokes the blob URL right after triggering
  the download. A plain `DownloadListener` therefore receives a dead URL. The capture happens in the
  page instead (Injected scripts, parts 3 and 4): the bytes reach the app through the bridge as
  base64, in 262144-character chunks (`onBlobChunk`, reassembled by `BlobTransfers`) or whole
  (`onBlobDownload`), and the app saves them into the public Download folder under the anchor's
  `download` name, with the extension corrected from the payload's MIME type. A name that already
  ends in the right extension is kept; a wrong short extension is replaced.
- **The dead-URL fallback.** The `DownloadListener` still fires for the same `blob:` URL after a
  successful bridge save. A `blob:` request within 8000 ms of such a save is ignored
  (`FALLBACK_SUPPRESS_MS`), so a good save is not followed by failure toasts. A second delivery with the same
  data-URL length within 4000 ms is dropped. Otherwise the app fetches the `blob:` inside the page
  (`blobFetchScript`: `fetch`, then `FileReader.readAsDataURL`, then `onBlobResult`), shows
  "Downloading...", and gives up after 30000 ms with the toast "Download did not complete. Hold down
  on the image to open the share & download menu." (The image menu passes `announceStart = false`,
  which skips "Downloading..." and "Downloading <name>...".)
- **Plain downloads.** http(s) downloads go through a native fetch that carries the WebView's
  cookies, its user agent and the Referer of Site constants; `data:` URLs are saved directly. Below
  Android 10 every save, including blob exports and images, first asks for the storage permission and
  continues after the answer (`Downloads.withStoragePermission`).
- **Images.** The long-press menu (Share image, Download image) is generic. Its ChatGPT parts are the
  Referer and the file name `chatgpt_image_<millis>.<extension>`. Images that exist only inside the
  page (`blob:`) are saved through the blob path; inline `data:` images are saved and shared
  directly. The menu shows "Downloading image..." and the save ends with "Saved to
  Download/<name>"; the blob path's own start toasts are suppressed there, so the user sees exactly
  those two.

### Loading, keyboard and viewport

- **One loading screen per launch.** `initialLoadComplete` is false from launch (and again after a
  lost renderer or an offline retry) until the loading screen is dismissed. While it is false,
  `onPageStarted` shows the loading screen after a 200 ms delay; once it is true, no navigation shows
  the screen again. chatgpt.com is a single-page app: opening its settings tab fires `onPageStarted`
  and `onPageFinished` again, and a returning loading screen flashed over the chat. The flag is set
  before the fade starts, so a navigation during the fade cannot bring the screen back.
- **The delay.** If the page comes from the cache within 200 ms (typical after Android killed the
  renderer in the background) the loading screen never shows; the user sees a brief WebView
  background instead of a loading-screen flash.
- **What dismisses it.** The page-ready signal (`AndroidBridge.pageReady`, origin-gated), then a
  400 ms fade (`accelerate_quad`). If the watcher never runs (a bridge failure, an ancient WebView) a
  timer hides the screen 3500 ms after `onPageFinished`. The WebView progress and `onPageFinished`
  are never the signal.
- **The progress bar (optional).** Its value is the WebView progress mapped onto the first 90%
  (`MAX_RAW_PROGRESS`), because chatgpt.com reaches 100 as soon as its HTML shell has loaded. Only the
  dismissal of the loading screen completes it to 100, in the same instant the fade starts, so the
  bar can never claim "loaded" over a page that is still hydrating. It sits inside the loading screen
  and moves only while the screen is visible and `initialLoadComplete` is false. Setting: Settings,
  "Show page loading progress" (preference `loading_progress`, off by default), read again each time
  the loading screen appears.
- **Keyboard and viewport.** The manifest pins `windowSoftInputMode=adjustResize`. With no mode set,
  some OEMs resolve the default differently inside floating or freeform windows: the window is panned,
  the composer stays under the keyboard and the page's `visualViewport` logic never sees a resize.
  The app opens the keyboard itself only for shares; the focus guard keeps it closed when an existing
  chat opens.
- **A lost renderer** resets `initialLoadComplete`, recreates the main WebView and reloads the last site page
  it showed (tracked in `onPageStarted` and `doUpdateVisitedHistory`, so SPA chat URLs count; sign-in pages
  are never remembered). If that reload loses the renderer again before a page finishes, it loads the home URL.

### Camera and microphone

- **On demand.** The site asks for the microphone (voice mode) and the camera (photo capture)
  through the web permission API, which reaches `onPermissionRequest` in the main WebView and in
  popups (`WebPermissions`). The app denies a request whose origin is not a `SITE_HOSTS` host, asks
  Android for the missing runtime permissions (camera, record audio) and grants the page only the
  resources the user approved. A new request while one is waiting denies the earlier one. Apps built
  on the v6.24 code granted a resource only when Android had already granted the permission and
  never asked, so the voice-mode microphone button did nothing and showed no message.
- **Which origins.** `chatgpt.com`, `openai.com` and their subdomains only. Google's sign-in hosts
  never ask for the camera or the microphone.
- **Camera capture in the file chooser.** The manifest declares CAMERA, so Android requires the
  permission to be held before a capture intent starts, or the capture fails without a message. The
  app therefore asks first and shows the chooser after the answer.

## What failed, and why the code is as it is

### Behaviors of the site that shaped the code

| Behavior of chatgpt.com | Without the fix | The code |
|---|---|---|
| Reads the `X-Requested-With` header (the app's package name) | Treats the app as a WebView and shows an install-the-app banner | Blank header on every main-frame load; explicit reload after `restoreState()` |
| Revokes export blob URLs right after triggering a download | The download listener receives a dead URL; exports fail | Blob keeper (600000 ms) and capture of the Blob object in the page |
| Runs the share and export UI in `blob:` and cross-origin iframes | A script-side origin allowlist blocked exactly the calls to fix; page-finish injection reaches only the main frame | Document-start scripts in every frame; no origin check in the injected script |
| The investigation and deep-research panel is a hostless iframe app | "Error loading application: Runtime error" when `blob:` and `data:` iframes were blocked | Iframes without a host load in place |
| Focuses the composer by itself in an existing chat: on load, on SPA remount, on resume | The keyboard opens without a tap | Focus guard: patch of `HTMLElement.prototype.focus`. A focus listener with a deferred blur lost, because React re-focuses first |
| Adds `autofocus` back on SPA remounts | The keyboard returns after each remount | `autofocus` stripped every 500 ms |
| Commits a pending attachment only while the composer is focused, about 1 s after the file lands | An unattended share is dropped silently | Staggered focus passes and keyboard |
| Re-opens its file input after a programmatic attach | The pending file is handed over twice, the attach limit trips, the attachment vanishes | 4000 ms guard in the file chooser |
| Cancels a pending attachment on Escape | The attachment vanished | No Escape in the settle passes; the only Escape tears down the drag fallback's overlay |
| `[data-testid*=composer]` matches the "+" attach button | The settle code clicked "+" and opened menus | Focus only; selectors that match editable elements only |
| Synthetic drag events show a full-screen drop overlay with no reliable teardown | Overlay stuck on screen | Primary path sets `input.files`; no drag events |
| Greeting and chip text is localized and rotates | Matching text breaks | Only attributes and test ids are matched |
| The greeting wrapper exists but is CSS-hidden (zero height) on mobile | Presence tests fire too early | Visibility test with `getClientRects()` |
| The `load` event and the WebView progress reach 100 before the UI is hydrated | The loading screen goes away over an unusable page, or stays over a usable one; the bar claims "loaded" | Page-ready watcher, settle rule, progress capped at 90% |
| Is a single-page app: its settings tab fires `onPageStarted` and `onPageFinished` again | The loading screen flashed over the chat | `initialLoadComplete` flag |
| Sign-in crosses many hosts and ends back on `chatgpt.com` | A missing hop sent sign-in traffic to the browser mid-login and, with the Android 15 destroy crash, killed the app | The whole chain is allowed; hop hosts; deferred popup teardown |
| The Copy button needs the async Clipboard API or `document.execCommand('copy')`, and some WebViews provide neither reliably | Copy does nothing | `execCommand('copy')` is bridged to the native clipboard |
| Google blocks OAuth in Android WebViews for some accounts | Google sign-in refuses | Known limit; email sign-in works |

### Do not simplify

These look removable. Each one broke something.

| Looks removable | What breaks |
|---|---|
| Blank `X-Requested-With`, and the explicit reload after `restoreState()` | The install-the-app banner; after process death it returns if the reload is dropped |
| Third-party cookies on in popups | The sign-in chain cannot complete |
| Hosts of the sign-in chain on the allowlist, hop hosts matched by an exact list | Real sign-in traffic goes to the browser mid-login |
| Iframes without a host load in place; no script-side origin check | The investigation panel fails; share and export calls are blocked |
| No hook on `window.open` | It breaks the user-gesture context `onCreateWindow` needs: popups and external links die silently |
| Prototype patch of `focus`, not a focus listener | The keyboard still pops up |
| Document-start injection, not `onPageFinished` | Iframe features do not work, and the site can capture the unpatched functions first |
| `input.files` as the primary attach path | The drop overlay gets stuck |
| Focus-only settle passes; Escape only in the drag fallback | The attachment is cancelled, or "+" gets clicked |
| The 4000 ms double-attach guard; main chooser only; the 60000 ms window | The file attaches twice and the attachment vanishes; a popup's chooser takes the file |
| Deferred popup attachment and deferred popup teardown | A fullscreen flash on every external link; the Android 15 crash |
| `initialLoadComplete`, and the page-ready signal instead of `onPageFinished` | The loading screen flashes over the chat or hides too early |
| Progress capped at 90% | The bar claims "loaded" over a page that is not usable |
| The blob keeper | Exports fail |
| `adjustResize` | The composer stays under the keyboard in floating windows |
| Progress colors as literals, and the pinned splash theme | Wrong colors at night; a white flash at launch before Android 12 |

### Experiments that failed

- The composer "+" keyboard fix (`__webgptPlusFix`) patched the "+" button to fix the keyboard, broke
  other things and was removed.
- The "smooth keyboard tracking" IME sync (`ime_sync_switch`) was removed.
- The desktop-mode toggle switched the user agent, wiped the session cookie on every cold start and
  logged users out. It was deleted in v6.25.
- Script-side origin gates, scheme allowlists and `window.open` hooks broke the share and export
  features and external links (Do not simplify).

None of these exists in the code.

## WebGPT presentation

**Identity.**

| Item | Value | Where |
|---|---|---|
| Package | `org.webgpt.app` | `app/build.gradle` (`namespace`, `applicationId`) |
| App name | `WebGPT` | `app_name` |
| Settings title | "WebGPT Settings" | `settings_title` |
| Settings header pair | "Settings" and "WebGPT" | literals in `activity_settings.xml` |
| Share-target labels | "Ask WebGPT" (text shares and the text selection menu), "Send to WebGPT" (files and everything else) | the intent filters in `AndroidManifest.xml` |
| Clipboard label | the app name | `copyText` in `Clipboard.kt` |
| Launcher icon | adaptive; background `ic_launcher_background.xml`, foreground `ic_launcher_foreground.xml`; legacy PNGs in `mipmap-*` | `res/drawable`, `res/mipmap-*` |
| Loading logos | `logo_black.png`, `logo_white.png`: the WebGPT mark, black and white | `res/drawable` |
| Store listing | short and full description, icon, screenshots, one changelog file per versionCode | `fastlane/metadata/android/en-US/` |
| Update source | releases of `MrHuaweiFan/WebGPT` | Site constants |

The unofficial-client and trademark notice is in the README.

**Palette.** The Material Theme Builder export for a blue seed color, applied by `AppTheme` (parent
`Theme.Material3.DayNight.NoActionBar`), every token pinned in `values/colors.xml` and
`values-night/colors.xml`.

| Token | Day | Night |
|---|---|---|
| `md_primary` | `#2F628C` | `#9BCBFB` |
| `md_secondaryContainer` | `#CEE5FF` | `#0D4A73` |
| `md_background` and `md_surface` | `#FCFCFC` | `#000000` |
| `status_bar_bg` (status bar and navigation bar) | `#FCFCFC` | `#000000` |
| `window_light_status_bar` (icon tint of both bars) | true | false |

Dark mode is pure black, not Material's default off-black. The two bars follow a live day or night
switch without a restart: `uiMode` is in `configChanges` (so the WebView survives the switch), and
`applySystemBarColors()` re-applies the colors on every configuration change.

**Page background.** The WebView, the popup WebViews, the window and the root layout are painted
`#FFFFFF` (light) and `#0D0D0D` (dark), matching chatgpt.com's own surfaces, so the flash before the
first paint and the flash while the surface is rebuilt on resume are invisible
(`applyWebViewBackground`, `applyBackgroundColors`). This is not the palette background: the loading
screen uses `md_background` and the bars use `status_bar_bg`.

**Launch splash.** `Theme.AppSplash` (parent `Theme.SplashScreen`): `windowSplashScreenBackground`
and `android:windowBackground` are `md_background`; `windowSplashScreenAnimatedIcon` is
`splash_icon_none` (fully transparent); `postSplashScreenTheme` is `AppTheme`. On Android 12 and later
an exit listener crossfades the splash into the loading screen over 300 ms (`SPLASH_FADE_MS`), so no
OEM launch animation plays. At launch the user sees exactly the loading screen's background color, in
light and dark mode, then the logo fades in. There is no real icon because the system renders the
splash icon in its own fixed-size container, masked per OEM, and a plain bitmap logo looked wrong on
several devices. The pins exist because before Android 12 the core-splashscreen base themes resolve
to `DeviceDefault.Light` with no night variant, so without them a dark-mode launch flashed white.

**Loading screen.** Background `?android:attr/colorBackground` (which `AppTheme` pins to
`md_background`). The logo is centered, 120 dp, `logo_black` on light and `logo_white` on dark, chosen
when the screen is shown. Animation (`LoadingScreen.startLogoAnimation`): property animators on a
hardware layer (the earlier view animation stuttered while the page loaded): one revolution per
2000 ms, linear, and an alpha pulse 0.3, 1, 0.3 with a 1000 ms reverse half-cycle. Show delay 200 ms,
fade-out 400 ms.

**Progress bar colors.** Day: indicator `loading_progress_indicator` `#2F628C`, track
`loading_progress_track` `#CEE5FF`. Night: swapped (indicator `#CEE5FF`, track `#2F628C`), through
`values-night/colors.xml`. Both are literals, never `@color/md_*` references: a reference is
re-resolved for the current configuration, and the library's default track color is the theme's
`colorSecondaryContainer` (pale blue by day, `#0D4A73` at night), which caused a "still wrong in dark
mode" report. The bar needs material 1.12.0 or later (rounded ends, gap and stop dot are 1.12
defaults).

**Texts that name WebGPT or ChatGPT.**

| Text | Where |
|---|---|
| Welcome dialog: title "Welcome to WebGPT"; the body points to the hidden Settings in Android's App info, recommends Thorium and says how to download or share an image | `welcome_title`, `welcome_body` |
| WebView info dialog: names the copy button and the microphone as things a different WebView can fix | `webview_info_title`, `webview_info_body` |
| Offline dialog: "Connection problem", "Couldn't load chatgpt.com. Check your internet connection and try again.", "Retry", "Close app" | literals in `WebClients.kt`; the host comes from `SiteConfig.HOME_HOST` |
| Settings card "Google sign-in": alternate between the full Gmail address and the bare username a few times (Google checks whether the address is a real Gmail account and usually lets the user through after a couple of attempts) | literal in `activity_settings.xml` |
| Share toasts | Sharing into the app |

## Known limits and privacy

**Limits.**

- Google can refuse OAuth in Android WebViews for some accounts ("This browser or app may not be
  secure"). Email sign-in works, and the Settings card carries the workaround.
- Voice input may refuse to start even with the permissions granted; choosing another WebView in
  Settings can fix it. The WebView info dialog says the same about the Copy button.
- Shared text is copied to the clipboard and the user pastes; only a shared file is attached
  automatically. A file of 50 MiB or more needs "+ then Files", and the app hands the file over.
- Only `ACTION_SEND` and `ACTION_PROCESS_TEXT` are handled: one text or one file per share.
  `navigator.share` with several files is rejected.
- Links to other sites open in the system browser; only ChatGPT and OpenAI pages and the sign-in
  pages open in the app. A link to a Google page tapped in a chat opens in the browser.

**Privacy.**

- Third-party cookies are blocked in the main WebView; popups accept them.
- Shared text goes through the system clipboard. Shared files are copied into the app's private cache
  folder `shared_files`. The 48-hour cache sweep (`CacheCleaner`) removes every file in that folder older than 48 hours
  and, in the cache root, the one-shot files whose names start with `camera_capture_`,
  `shared_image_` or `shared_file_`.
- Shared text is never logged in release builds. Debug telemetry (`debugLog`, the console mirror) is
  written to the log only in experimental builds.
- The update check runs only when the user taps "Check for updates" in Settings. It contacts the
  GitHub releases API of the update source and, when an update exists and the user confirms, opens the
  release's `app-release.apk` link (the release page if it has no such file) in the default browser;
  the app never downloads or installs an APK itself.
