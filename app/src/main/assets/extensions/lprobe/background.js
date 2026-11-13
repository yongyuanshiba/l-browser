// L homepage extension background script.
//
// Intentionally inert. The native side discovers this extension's page URL from the
// WebExtension metadata (baseUrl), so the extension no longer reports anything itself.
// The previous self-report chain (runtime.connectNative) was removed: without the
// `geckoViewAddons` permission those calls never reach the app's message handlers.
