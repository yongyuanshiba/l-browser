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

    var gateOut = document.getElementById('gate_out');

    // Probe E1b: the manifest CSP for this build is exactly
    // "script-src 'self' blob:" — a provably valid value with no token that
    // could poison the whole policy. Re-run the blob: injection route from
    // probe 1.2 under this clean CSP to test the hypothesis that 1.2's blob
    // failure was collateral damage from the invalid 'unsafe-inline' token
    // sitting in the same manifest value.
    try {
        var blobCode = "document.getElementById('gate_out').dataset.blob = 'BLOB_OK';";
        var blobUrl = URL.createObjectURL(new Blob([blobCode], { type: 'text/javascript' }));
        var blobScript = document.createElement('script');
        blobScript.src = blobUrl;
        document.head.appendChild(blobScript);
    } catch (e) {
        console.log('L-HOME-ERROR: blob setup: ' + e.message);
    }

    // Render continuously for ~30s, as in probe 1.2, so slow execution on the
    // software-rendered emulator cannot race the verdict.
    var ticks = 0;
    var timer = setInterval(function () {
        ticks += 1;
        gateOut.textContent =
            'PROBE-BLOB blob=' + (gateOut.dataset.blob || 'BLOB_MISSING') +
            ' t=' + ticks;
        if (ticks >= 60) {
            clearInterval(timer);
        }
    }, 500);
});
