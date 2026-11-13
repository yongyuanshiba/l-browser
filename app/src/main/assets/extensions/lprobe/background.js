// L homepage extension background script.
//
// The native side cannot derive this extension's moz-extension:// address from the
// install location (a resource:// path), so the extension reports its own page URL
// over the native messaging port. Retried because the native message handler may
// be registered after this script first runs; stops once the native side acks.
var lHomepageUrlMessage = {
    type: 'l-homepage-url',
    url: browser.runtime.getURL('probe.html'),
};
var lAcked = false;

function lReportUrl(attempt) {
    if (lAcked) {
        return;
    }
    try {
        var port = browser.runtime.connectNative('browser');
        port.onMessage.addListener(function (message) {
            if (message && message.ack) {
                lAcked = true;
                console.log('LPROBE-BG ack received from native side');
            }
        });
        port.onDisconnect.addListener(function () {
            var error = browser.runtime.lastError;
            console.log(
                'LPROBE-BG port disconnected (attempt ' + attempt + ')' +
                    (error ? ': ' + error.message : ''),
            );
        });
        port.postMessage(lHomepageUrlMessage);
        console.log(
            'LPROBE-BG posted homepage url (attempt ' + attempt + '): ' + lHomepageUrlMessage.url,
        );
    } catch (e) {
        console.log('L-HOME-ERROR bg connect failed (attempt ' + attempt + '): ' + e.message);
    }
    if (attempt < 15) {
        setTimeout(function () {
            lReportUrl(attempt + 1);
        }, 2000);
    }
}

lReportUrl(1);
