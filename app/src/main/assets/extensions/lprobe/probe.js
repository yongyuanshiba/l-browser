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

    // Route 1: script whose code comes from a blob: URL.
    try {
        var blobCode = "document.getElementById('gate_out').dataset.blob = 'BLOB_OK';";
        var blobUrl = URL.createObjectURL(new Blob([blobCode], { type: 'text/javascript' }));
        var blobScript = document.createElement('script');
        blobScript.src = blobUrl;
        document.head.appendChild(blobScript);
    } catch (e) {
        console.log('L-HOME-ERROR: blob setup: ' + e.message);
    }

    // Route 2: script whose code comes from a data: URL.
    try {
        var dataCode = "document.getElementById('gate_out').dataset.data = 'DATA_OK';";
        var dataScript = document.createElement('script');
        dataScript.src = 'data:text/javascript;base64,' + btoa(dataCode);
        document.head.appendChild(dataScript);
    } catch (e) {
        console.log('L-HOME-ERROR: data setup: ' + e.message);
    }

    // Route 3: script element with inline text assigned via the DOM.
    try {
        var domScript = document.createElement('script');
        domScript.textContent = "document.getElementById('gate_out').dataset.dom = 'DOM_OK';";
        document.head.appendChild(domScript);
    } catch (e) {
        console.log('L-HOME-ERROR: dom setup: ' + e.message);
    }

    // Render continuously for ~30s so slow script execution cannot race the
    // verdict: the final line reports every route's settled state.
    var ticks = 0;
    var timer = setInterval(function () {
        ticks += 1;
        browser.storage.local.get('l_gate_token').then(function (res) {
            var token = (res && res.l_gate_token) || 'MISSING';
            gateOut.textContent =
                'GATE token=' + token +
                ' blob=' + (gateOut.dataset.blob || 'BLOB_MISSING') +
                ' data=' + (gateOut.dataset.data || 'DATA_MISSING') +
                ' dom=' + (gateOut.dataset.dom || 'DOM_MISSING') +
                ' t=' + ticks;
            if (ticks >= 60) {
                clearInterval(timer);
            }
        });
    }, 500);
});
