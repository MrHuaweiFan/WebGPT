(function () {
  try {
    if (window.top !== window) return;
    var hst = location.hostname || '';
    if (hst.indexOf('chatgpt.com') < 0 && hst.indexOf('openai.com') < 0) return;
    if (window._webgptReadyWatch) return;
    window._webgptReadyWatch = true;
    var sent = false,
      started = Date.now();
    function fire() {
      if (sent) return;
      sent = true;
      try {
        if (window.AndroidBridge && window.AndroidBridge.pageReady)
          window.AndroidBridge.pageReady();
      } catch (e) {}
    }
    function vis(el) {
      try {
        var r = el && el.getClientRects();
        /* rendered AND non-zero size: on mobile the greeting wrapper           itself is NOT display:none (only its inner child carries           hidden sm:block), so it still lays out as an EMPTY flex           box — a zero-height client rect. length>0 alone would           mistake that for a visible greeting. */ return !!(
          r &&
          r.length &&
          r[0].height > 0 &&
          r[0].width > 0
        );
      } catch (e) {
        return false;
      }
    }
    function tick() {
      if (sent) return true;
      var now = Date.now();
      if (now - started > 8000) {
        fire();
        return true;
      }
      try {
        var box =
          document.getElementById('prompt-textarea') ||
          document.querySelector('div[contenteditable="true"][role="textbox"]');
        if (box) {
          if (vis(document.querySelector('[data-splash-headline-option]'))) {
            fire();
            return true;
          }
          if (vis(document.querySelector('[data-testid="use-case-prompt-chips"] button'))) {
            fire();
            return true;
          }
          if (document.querySelector('[data-testid^="conversation-turn"]')) {
            fire();
            return true;
          }
          var L = window.__webgptLoad;
          if (L && now - L.lastMut > 2000 && now - L.lastStart > 1500) {
            fire();
            return true;
          }
        }
      } catch (e) {}
      return false;
    }
    var iv = setInterval(function () {
      if (tick()) clearInterval(iv);
    }, 200);
  } catch (e) {}
})();
