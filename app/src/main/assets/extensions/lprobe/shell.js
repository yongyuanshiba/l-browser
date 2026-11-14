// L homepage shell.
//
// This page is only a carrier: it asks the extension's background script for
// the assembled homepage (which the app builds natively: skeleton, bookmark
// tiles, search template and the user's own code parts) and then replaces
// this document with it through document.open/write/close, so the homepage's
// own inline scripts are inserted by the parser and run with their normal
// semantics — execution order, one shared lexical scope across script blocks
// and a live document.currentScript, exactly as in a served page.
//
// A repeat entry would otherwise pay the whole round trip (native assembly
// plus a full document parse) before anything shows. The shell therefore
// keeps the last assembled document in this origin's localStorage: on the
// next load it writes the cached copy immediately, then asks for a fresh
// assembly in the background and swaps it in only if it actually changed and
// the user has not started interacting yet. A changed copy that arrives
// after interaction is kept for the next entry instead.
(function () {
    var CACHE_KEY = 'l-homepage-html';
    var attempts = 0;
    var MAX_ATTEMPTS = 40;
    var interacted = false;

    ['pointerdown', 'keydown', 'touchstart'].forEach(function (type) {
        window.addEventListener(type, function () {
            interacted = true;
        }, { capture: true, passive: true });
    });

    function fail() {
        var out = document.getElementById('shell_out');
        if (out) {
            out.textContent = '主页加载失败，点此重试';
            out.addEventListener('click', function () { location.reload(); });
        }
    }

    function writeHtml(html) {
        document.open();
        document.write(html);
        document.close();
    }

    function cacheHtml(html) {
        try {
            localStorage.setItem(CACHE_KEY, html);
        } catch (e) {
            // Quota or storage trouble: caching is an optimisation only.
        }
    }

    function cachedHtml() {
        try {
            return localStorage.getItem(CACHE_KEY);
        } catch (e) {
            return null;
        }
    }

    // One request for a fresh assembly; onHtml receives the document, or
    // null when the round trip failed.
    function fetchHtml(onHtml) {
        browser.runtime.sendMessage({ type: 'l-homepage-request' }).then(function (html) {
            onHtml(html || null);
        }, function () {
            onHtml(null);
        });
    }

    // The cold path: nothing cached yet, so wait for the assembly (with the
    // historical retry cadence) before writing anything.
    function ask() {
        attempts++;
        fetchHtml(function (html) {
            if (html) {
                cacheHtml(html);
                writeHtml(html);
            } else if (attempts < MAX_ATTEMPTS) {
                setTimeout(ask, 500);
            } else {
                fail();
            }
        });
    }

    function start() {
        var cached = cachedHtml();
        if (!cached) {
            ask();
            return;
        }
        writeHtml(cached);
        fetchHtml(function (html) {
            if (!html || html === cached) {
                return;
            }
            cacheHtml(html);
            if (!interacted) {
                writeHtml(html);
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
