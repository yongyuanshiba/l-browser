window.addEventListener('error', function (event) {
    console.log('L-HOME-ERROR: ' + event.message);
});
window.addEventListener('load', function () {
    var line = 'LPROBE href=' + location.href + ' origin=' + location.origin;
    try {
        var prev = window.localStorage.getItem('__l_probe__');
        window.localStorage.setItem('__l_probe__', 'probe-ok');
        line += ' ls_prev=' + prev + ' ls_read=' + window.localStorage.getItem('__l_probe__');
    } catch (e) {
        line += ' ls_error=' + e.name;
    }
    document.getElementById('out').textContent = line;
    console.log(line);

    // Gate: can a script supplied as text run via a blob: URL under the extension
    // page CSP? (Fallback route for the user homepage code's inline scripts if
    // 'unsafe-inline' itself is not honored.)
    try {
        var code = "document.getElementById('gate_out').dataset.blob = 'BLOB_OK';";
        var blobUrl = URL.createObjectURL(new Blob([code], { type: 'text/javascript' }));
        var script = document.createElement('script');
        script.src = blobUrl;
        document.head.appendChild(script);
    } catch (e) {
        console.log('L-HOME-ERROR: blob setup: ' + e.message);
    }

    // Gate: the native side pushes a token over the native-messaging port; the
    // background script mirrors it into storage.local. Poll for it here.
    var gateOut = document.getElementById('gate_out');
    var tries = 0;
    var timer = setInterval(function () {
        tries += 1;
        browser.storage.local.get('l_gate_token').then(function (res) {
            var blob = gateOut.dataset.blob || 'BLOB_MISSING';
            if (res && res.l_gate_token) {
                gateOut.textContent = 'GATE token=' + res.l_gate_token + ' blob=' + blob;
                clearInterval(timer);
            } else if (tries > 120) {
                gateOut.textContent = 'GATE token=MISSING blob=' + blob;
                clearInterval(timer);
            }
        });
    }, 500);
});
