// L homepage extension background script.
//
// The native side cannot derive this extension's moz-extension:// address from the
// install location (a resource:// path), so the extension reports its own page URL
// over the native messaging port. Kept deliberately minimal (no port listeners):
// just connect and post, retried for a minute because the native message handler
// may be registered after this script first runs. The native side deduplicates.
function lReportUrl(attempt) {
    try {
        var port = browser.runtime.connectNative('browser');
        port.postMessage({
            type: 'l-homepage-url',
            url: browser.runtime.getURL('probe.html'),
        });
    } catch (e) {
        // Nothing useful to do here: delivery is observed on the native side.
    }
    if (attempt < 20) {
        setTimeout(function () {
            lReportUrl(attempt + 1);
        }, 3000);
    }
}

lReportUrl(1);
