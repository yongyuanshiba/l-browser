// L homepage extension background script (gate build).
//
// The formal homepage's data (search template, bookmark tiles, the user's own code
// parts) lives on the native side. It reaches extension pages through this script:
// the native side pushes it over a native-messaging port (which requires the
// `geckoViewAddons` permission in the manifest), and this script mirrors it into
// `browser.storage.local`, where every page of this extension can read it.
//
// Gate build: the native side pushes a small token message on port connect; mirror
// it into storage so the probe page can prove the whole chain end to end.
var lPort = browser.runtime.connectNative("browser");
lPort.onMessage.addListener(function (msg) {
    if (msg && msg.type === "l-gate") {
        browser.storage.local.set({ l_gate_token: msg.token });
        lPort.postMessage({ type: "l-gate-ack", token: msg.token });
    }
});
