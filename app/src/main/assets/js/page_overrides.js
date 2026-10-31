(function () {
  function dbg(m) {
    try {
      var b = window.AndroidBridge;
      if (b && b.debugLog) b.debugLog(String(m));
    } catch (e) {}
  }
  /* 1. navigator.share -> system share sheet / clipboard */ try {
    if (!window._shareOverridden) {
      window._shareOverridden = true;
      navigator.share = function (data) {
        try {
          var b = window.AndroidBridge;
          var d = data || {};
          var files = d.files && d.files.length ? d.files : null;
          var bm = b
            ? (b.shareText ? 'S' : '') +
              (b.shareFile ? 'F' : '') +
              (b.copyToClipboard ? 'C' : '') +
              (b.debugLog ? 'D' : '')
            : 'none';
          dbg(
            'share called: files=' +
              (files ? files.length : 0) +
              ' text=' +
              (d.text ? 'yes' : 'no') +
              ' url=' +
              (d.url ? 'yes' : 'no') +
              ' bridge=' +
              bm,
          );
          if (!b) return Promise.reject(new Error('No share support'));
          if (files && files.length === 1 && b.shareFile) {
            dbg('dispatching shareFile');
            var f = files[0];
            var fr = new FileReader();
            fr.onloadend = function () {
              try {
                b.shareFile(
                  String(d.title || d.text || f.name || ''),
                  String(fr.result),
                  String(f.name || 'file'),
                  String(f.type || ''),
                );
              } catch (e) {}
            };
            fr.onerror = function () {
              try {
                dbg('share: file read failed');
              } catch (e) {}
              try {
                if (d.text) b.copyToClipboard(String(d.text));
              } catch (e) {}
            };
            fr.readAsDataURL(f);
            return Promise.resolve();
          }
          if (files && files.length > 1)
            return Promise.reject(new Error('Multiple files not supported'));
          if ((d.text || d.url) && b.shareText) {
            dbg('dispatching shareText');
            b.shareText(String(d.text || ''), String(d.url || ''));
            return Promise.resolve();
          }
          if (d.text && b.copyToClipboard) {
            b.copyToClipboard(String(d.text));
            return Promise.resolve();
          }
          return Promise.reject(new Error('Nothing to share'));
        } catch (e) {
          return Promise.reject(e);
        }
      };
      navigator.canShare = function (data) {
        try {
          var d = data || {};
          if (d.files && d.files.length) return d.files.length === 1;
          return !!(d.text || d.url || d.title);
        } catch (e) {
          return false;
        }
      };
      try {
        navigator.share.toString = function () {
          return 'function share() { [native code] }';
        };
      } catch (e) {}
      try {
        navigator.canShare.toString = function () {
          return 'function canShare() { [native code] }';
        };
      } catch (e) {}
    }
  } catch (e) {}
  /* 2. document.execCommand('copy') fallback */ try {
    if (!window._execOverridden) {
      window._execOverridden = true;
      var origExec = document.execCommand.bind(document);
      document.execCommand = function (cmd, showUI, value) {
        if (cmd === 'copy') {
          var sel = window.getSelection();
          if (sel && sel.toString()) {
            try {
              if (window.AndroidBridge) window.AndroidBridge.copyToClipboard(sel.toString());
            } catch (e) {}
            return true;
          }
        }
        return origExec(cmd, showUI, value);
      };
    }
  } catch (e) {}
  /* 3. blob lifetime keeper (capped: 16 blobs / 128MB) */ try {
    if (!window._blobKeep) {
      window._blobKeep = true;
      var origCreate = URL.createObjectURL;
      var origRevoke = URL.revokeObjectURL;
      var store = {};
      var order = [];
      var total = 0;
      function evict() {
        try {
          while (order.length > 16 || total > 134217728) {
            var u = order.shift();
            if (u === undefined) break;
            var blob = store[u];
            if (blob && blob.size) total -= blob.size;
            delete store[u];
            try {
              origRevoke.call(URL, u);
            } catch (e) {}
          }
        } catch (e) {}
      }
      URL.createObjectURL = function (blob) {
        var u = origCreate.call(URL, blob);
        try {
          store[u] = blob;
          order.push(u);
          if (blob && blob.size) total += blob.size;
          evict();
        } catch (e) {}
        return u;
      };
      URL.revokeObjectURL = function (u) {
        setTimeout(function () {
          try {
            delete store[u];
            var i = order.indexOf(u);
            if (i >= 0) order.splice(i, 1);
            origRevoke.call(URL, u);
          } catch (e) {}
        }, 600000);
      };
      window.__webgptBlobs = store;
    }
  } catch (e) {}
  /* 4. blob download interception: prototype click + capture listener */ function webgptSendChunks(
    name,
    mime,
    b64,
  ) {
    try {
      var b = window.AndroidBridge;
      if (!b) return false;
      if (!b.onBlobChunk) {
        if (b.onBlobDownload) b.onBlobDownload(String(name), 'data:' + mime + ';base64,' + b64);
        return true;
      }
      var CH = 262144;
      var total = Math.ceil(b64.length / CH);
      if (total < 1) total = 1;
      for (var i = 0; i < total; i++) {
        b.onBlobChunk(
          String(name),
          String(mime),
          i,
          total,
          b64.substring(i * CH, Math.min((i + 1) * CH, b64.length)),
        );
      }
      return true;
    } catch (e) {
      return false;
    }
  }
  function webgptFindBlob(href) {
    try {
      var st = window.__webgptBlobs;
      if (st && st[href]) return st[href];
      for (var i = 0; i < window.frames.length; i++) {
        try {
          var fs = window.frames[i].__webgptBlobs;
          if (fs && fs[href]) return fs[href];
        } catch (e) {}
      }
      try {
        var ps = window.parent.__webgptBlobs;
        if (ps && ps[href]) return ps[href];
      } catch (e) {}
      return null;
    } catch (e) {
      return null;
    }
  }
  function webgptSendDataUrl(name, dataUrl) {
    try {
      var b = window.AndroidBridge;
      if (!b) return false;
      var comma = dataUrl.indexOf(',');
      if (comma < 0) return false;
      var meta = dataUrl.substring(5, comma);
      var semi = meta.indexOf(';');
      var mime = semi > 0 ? meta.substring(0, semi) : 'application/octet-stream';
      return webgptSendChunks(name, mime, dataUrl.substring(comma + 1));
    } catch (e) {
      return false;
    }
  }
  function webgptFetchBlob(href, name, retry) {
    try {
      dbg('blob download: ' + name);
      var blobObj = webgptFindBlob(href);
      if (blobObj) {
        dbg('blob from store: ' + (blobObj.size || '?') + ' bytes');
        var fr = new FileReader();
        fr.onloadend = function () {
          try {
            dbg('blob read ok');
            webgptSendDataUrl(name || 'download', String(fr.result));
          } catch (e) {}
        };
        fr.onerror = function () {
          dbg('blob read failed');
          if (retry) retry();
        };
        fr.readAsDataURL(blobObj);
        return;
      }
      dbg('blob store miss');
      try {
        var xhr = new XMLHttpRequest();
        xhr.open('GET', href, false);
        xhr.overrideMimeType('text/plain; charset=x-user-defined');
        xhr.send();
        var s = xhr.responseText || '';
        if ((xhr.status === 200 || xhr.status === 0) && s.length > 0) {
          var ct = xhr.getResponseHeader('Content-Type') || '';
          if (ct.indexOf(';') > 0) ct = ct.split(';')[0];
          if (!ct) ct = 'application/octet-stream';
          var parts = [];
          for (var i = 0; i < s.length; i += 0x2000) {
            var end = Math.min(i + 0x2000, s.length);
            var buf = new Array(end - i);
            for (var j = i; j < end; j++) buf[j - i] = s.charCodeAt(j) & 0xff;
            parts.push(String.fromCharCode.apply(null, buf));
          }
          var b64 = btoa(parts.join(''));
          dbg('blob sync ok: ' + s.length + ' bytes');
          webgptSendChunks(name || 'download', ct, b64);
          return;
        }
        dbg('blob sync status ' + xhr.status);
      } catch (e) {
        dbg('blob sync xhr failed');
      }
      fetch(href)
        .then(function (r) {
          return r.blob();
        })
        .then(function (b) {
          dbg('blob fetch ok: ' + b.size + ' bytes');
          var fr = new FileReader();
          fr.onloadend = function () {
            try {
              dbg('blob read ok');
              webgptSendDataUrl(name || 'download', String(fr.result));
            } catch (e) {}
          };
          fr.onerror = function () {
            dbg('blob read failed');
            if (retry) retry();
          };
          fr.readAsDataURL(b);
        })
        .catch(function (e) {
          dbg('blob fetch failed');
          if (retry) retry();
        });
    } catch (e) {
      dbg('blob hook error');
      if (retry) retry();
    }
  }
  try {
    if (!window._dlHook) {
      window._dlHook = true;
      var origClick = HTMLAnchorElement.prototype.click;
      HTMLAnchorElement.prototype.click = function () {
        try {
          var href = this.href || '';
          if (href.indexOf('blob:') === 0 && this.hasAttribute('download')) {
            var self = this,
              args = arguments;
            webgptFetchBlob(href, this.getAttribute('download') || 'download', function () {
              try {
                origClick.apply(self, args);
              } catch (e) {}
            });
            return;
          }
        } catch (e) {}
        return origClick.apply(this, arguments);
      };
      document.addEventListener(
        'click',
        function (ev) {
          try {
            var t = ev && ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
            if (t) {
              var href = t.href || '';
              if (href.indexOf('blob:') === 0) {
                var b3 = window.AndroidBridge;
                if (b3 && b3.onBlobChunk) {
                  try {
                    ev.preventDefault();
                    ev.stopPropagation();
                  } catch (e2) {}
                }
                webgptFetchBlob(href, t.getAttribute('download') || 'download', null);
              }
            }
          } catch (e) {}
        },
        true,
      );
    }
  } catch (e) {}
  /* 5. window.open — NO HOOK. An earlier debug-only hook here     * (dbg('window.open: '+url) before origOpen.apply) broke the     * user-gesture context that WebView needs for onCreateWindow     * to fire. The toast appeared but the popup was never     * created, so external links (X, Reddit, LinkedIn) never     * reached the user's default browser. Routing now happens     * entirely in the popup's shouldOverrideUrlLoading via     * openUrlInBrowser — same as the official release. */ /* 6. beacon: proves the script is running (main frame, once per load) */ try {
    if (window.top === window && !window.__webgptBeacon) {
      window.__webgptBeacon = true;
      dbg('overrides active (main frame)');
    }
  } catch (e) {}
  try {
    if (!window.__webgptLoad) {
      var L = { lastMut: Date.now(), lastStart: Date.now(), n: 0 };
      window.__webgptLoad = L;
      var origFetch = window.fetch;
      if (origFetch) {
        window.fetch = function () {
          L.lastStart = Date.now();
          return origFetch.apply(window, arguments);
        };
      }
      var origOpen = XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open = function () {
        L.lastStart = Date.now();
        return origOpen.apply(this, arguments);
      };
      var mo = new MutationObserver(function (muts) {
        L.n += muts.length;
        L.lastMut = Date.now();
      });
      mo.observe(document, {
        childList: true,
        subtree: true,
        attributes: true,
        characterData: true,
      });
    }
  } catch (e) {}
})();
