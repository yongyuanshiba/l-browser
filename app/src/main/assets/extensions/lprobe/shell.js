// L homepage shell.
//
// This page is only a carrier: it asks the extension's background script for
// the assembled homepage (which the app builds natively: skeleton, bookmark
// tiles, search template and the user's own code parts) and then replaces
// this document with it through document.open/write/close, so the homepage's
// own inline scripts are inserted by the parser and run with their normal
// semantics — execution order, one shared lexical scope across script blocks
// and a live document.currentScript, exactly as in a served page.
(function () {
    var attempts = 0;
    var MAX_ATTEMPTS = 40;

    function fail() {
        var out = document.getElementById('shell_out');
        if (out) {
            out.textContent = '主页加载失败，点此重试';
            out.addEventListener('click', function () { location.reload(); });
        }
    }

    function ask() {
        attempts++;
        browser.runtime.sendMessage({ type: 'l-homepage-request' }).then(function (html) {
            if (html) {
                document.open();
                document.write(html);
                document.close();
            } else if (attempts < MAX_ATTEMPTS) {
                setTimeout(ask, 500);
            } else {
                fail();
            }
        }, function () {
            if (attempts < MAX_ATTEMPTS) {
                setTimeout(ask, 500);
            } else {
                fail();
            }
        });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', ask);
    } else {
        ask();
    }
})();
