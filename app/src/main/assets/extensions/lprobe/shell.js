// Probe P shell: once this shell page has loaded, replace the whole document
// with a test document whose scripts are inline (parser-inserted through
// document.write), mimicking how the formal homepage would be delivered if
// the extension-page default CSP can be relaxed through the runtime config
// file. The written document renders its own verdict line continuously.
window.addEventListener('load', function () {
    setTimeout(function () {
        var html =
            '<!DOCTYPE html><html><head><meta charset="utf-8">' +
            '<title>LPROBE-P-DOC</title></head><body>' +
            '<pre id="p_out">P pending</pre>' +
            '<script>' +
            'window.__pEvents = [];' +
            'document.addEventListener("DOMContentLoaded", function () { window.__pEvents.push("DCL"); });' +
            'window.addEventListener("load", function () { window.__pEvents.push("LOAD"); });' +
            'let __pA = 40;' +
            'const __pB = 2;' +
            'window.__pCs = document.currentScript ? "CS_OK" : "CS_NULL";' +
            'try {' +
            '  window.__pPrev = window.localStorage.getItem("__l_p_ls__");' +
            '  window.localStorage.setItem("__l_p_ls__", "p-ls-ok");' +
            '} catch (e) { window.__pPrev = "ERR:" + e.name; }' +
            '<\/script>' +
            '<script>' +
            'var __pSum = -1; var __pErr = "";' +
            'try { __pSum = __pA + __pB; } catch (e) { __pErr = e.name; }' +
            'var __pTicks = 0;' +
            'var __pTimer = setInterval(function () {' +
            '  __pTicks++;' +
            '  var shell = "SHELL_MISSING";' +
            '  try { shell = window.localStorage.getItem("__l_p_shell__") || "SHELL_MISSING"; }' +
            '  catch (e) { shell = "ERR:" + e.name; }' +
            '  document.getElementById("p_out").textContent =' +
            '    "PROBE-P shell=" + shell +' +
            '    " sum=" + __pSum + (__pErr ? "(" + __pErr + ")" : "") +' +
            '    " cs=" + window.__pCs +' +
            '    " ls_prev=" + window.__pPrev +' +
            '    " events=" + window.__pEvents.join(",") +' +
            '    " t=" + __pTicks;' +
            '  if (__pTicks >= 40) { clearInterval(__pTimer); }' +
            '}, 500);' +
            '<\/script>' +
            '</body></html>';
        document.open();
        document.write(html);
        document.close();
    }, 300);
});
