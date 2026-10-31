(function () {
  if (window.__webgptFocusGuard) return;
  window.__webgptFocusGuard = true;
  try {
    if (window.top !== window) return;
  } catch (e) {
    return;
  }
  function hostOk() {
    try {
      var h = (location.hostname || '').toLowerCase();
      return (
        h === 'chatgpt.com' ||
        h === 'chat.openai.com' ||
        h === 'openai.com' ||
        h.endsWith('.chatgpt.com') ||
        h.endsWith('.openai.com')
      );
    } catch (e) {
      return false;
    }
  }
  if (!hostOk()) return;
  function isExistingChat() {
    try {
      var p = location.pathname || '';
      return p.indexOf('/c/') === 0 || (p.indexOf('/g/') === 0 && p.length > 3);
    } catch (e) {
      return false;
    }
  }
  function isComposer(el) {
    if (!el || !el.matches) return false;
    try {
      if (el.id === 'prompt-textarea') return true;
      if (el.closest && el.closest('#prompt-textarea')) return true;
      if (el.matches('div[contenteditable="true"][role="textbox"]')) return true;
      if (el.tagName === 'TEXTAREA') return true;
      return false;
    } catch (e) {
      return false;
    }
  }
  function shareActive() {
    try {
      return Date.now() < (window.__webgptShareActiveUntil || 0);
    } catch (e) {
      return false;
    }
  }
  function shouldSuppress(el) {
    try {
      return isExistingChat() && !shareActive() && isComposer(el);
    } catch (e) {
      return false;
    }
  }
  try {
    var origFocus = HTMLElement.prototype.focus;
    HTMLElement.prototype.focus = function () {
      try {
        if (shouldSuppress(this)) return;
      } catch (e) {}
      return origFocus.apply(this, arguments);
    };
  } catch (e) {}
  document.addEventListener(
    'focusin',
    function (e) {
      try {
        if (shareActive() || !isExistingChat()) return;
        var t = e.target;
        if (!isComposer(t)) return;
        if (e.isTrusted) return;
        try {
          t.blur();
        } catch (_) {}
      } catch (_) {}
    },
    true,
  );
  document.addEventListener('visibilitychange', function () {
    try {
      if (document.visibilityState === 'hidden') {
        if (shareActive() || !isExistingChat()) return;
        var ae = document.activeElement;
        if (ae && isComposer(ae)) {
          try {
            ae.blur();
          } catch (_) {}
        }
        return;
      }
      if (document.visibilityState !== 'visible') return;
      if (shareActive() || !isExistingChat()) return;
      var ae2 = document.activeElement;
      if (ae2 && isComposer(ae2)) {
        try {
          ae2.blur();
        } catch (_) {}
      }
    } catch (_) {}
  });
  try {
    var stripAutofocus = function () {
      try {
        var sels = [
          '#prompt-textarea[autofocus]',
          'div[contenteditable="true"][role="textbox"][autofocus]',
          'textarea[autofocus]',
        ];
        for (var i = 0; i < sels.length; i++) {
          var els = document.querySelectorAll(sels[i]);
          for (var j = 0; j < els.length; j++) {
            try {
              els[j].removeAttribute('autofocus');
            } catch (_) {}
          }
        }
      } catch (e) {}
    };
    setInterval(stripAutofocus, 500);
  } catch (e) {}
})();
