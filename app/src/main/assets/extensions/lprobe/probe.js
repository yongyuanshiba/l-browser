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

    // Probe E1a: the manifest CSP for this build is exactly
    // "script-src 'self' 'unsafe-eval'; object-src 'self'" — no other tokens.
    // Both the direct eval operator and the Function constructor must run.
    var evalResult;
    try {
        evalResult = String(eval('1+1'));
    } catch (e) {
        evalResult = 'ERR:' + e.name;
    }
    var fnResult;
    try {
        fnResult = String(new Function('return 2')());
    } catch (e) {
        fnResult = 'ERR:' + e.name;
    }
    line += ' E1 eval=' + evalResult + ' fn=' + fnResult;

    document.getElementById('out').textContent = line;
    console.log(line);
});
