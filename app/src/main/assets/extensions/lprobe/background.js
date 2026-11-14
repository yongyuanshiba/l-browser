// L homepage extension background script.
//
// The assembled homepage HTML lives on the native side (the skeleton, the
// bookmark tiles, the search template and the user's own code parts are all
// read there). The shell page asks for it with a runtime message; this script
// relays the request over the native-messaging port — a channel that only
// exists because the manifest carries the `geckoViewAddons` permission — and
// hands the reply back. The native handler answers this one request type and
// nothing else, so homepage code that reaches this channel can only ever
// fetch its own homepage.
var lPort = null;
var lNextId = 1;
var lPending = {};

function lEnsurePort() {
    if (lPort) {
        return lPort;
    }
    lPort = browser.runtime.connectNative('browser');
    lPort.onMessage.addListener(function (msg) {
        if (msg && msg.type === 'l-homepage-html') {
            var resolve = lPending[msg.id];
            delete lPending[msg.id];
            if (resolve) {
                resolve(msg.html || null);
            }
        }
    });
    lPort.onDisconnect.addListener(function () {
        lPort = null;
        var pending = lPending;
        lPending = {};
        for (var id in pending) {
            pending[id](null);
        }
    });
    return lPort;
}

browser.runtime.onMessage.addListener(function (msg) {
    if (!msg || msg.type !== 'l-homepage-request') {
        return undefined;
    }
    return new Promise(function (resolve) {
        var id = lNextId++;
        lPending[id] = resolve;
        try {
            lEnsurePort().postMessage({ type: 'l-homepage-request', id: id });
        } catch (e) {
            delete lPending[id];
            resolve(null);
        }
    });
});
