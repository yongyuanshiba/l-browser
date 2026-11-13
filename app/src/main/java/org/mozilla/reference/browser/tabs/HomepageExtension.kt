/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject

/**
 * Carries the homepage in a built-in WebExtension page (moz-extension origin).
 *
 * The step-1 route — serving the homepage through the request interceptor — was measured to land
 * as a `data:` document with an opaque origin: localStorage never persisted there, and the user
 * homepage code's engine switcher (which stores its choice in localStorage) cannot work on it. An
 * extension page has a real, profile-stable origin instead.
 *
 * The native side cannot derive the page's moz-extension:// address itself: the install callback
 * reports the extension's resource:// location, not its runtime base URL. The extension therefore
 * reports its own URL from its background script over the native messaging port (see
 * assets/extensions/lprobe/background.js); this holder installs the extension at app start,
 * listens for that report, and publishes the URL — the cold-start landing waits for it, and the
 * new-tab action reads whatever is current.
 */
object HomepageExtension {
    /** Fixed gecko id; must match `browser_specific_settings.gecko.id` in the extension manifest. */
    const val EXTENSION_ID = "l-homepage@jasmine.li"

    /** Where the extension ships inside the APK (see app/src/main/assets/extensions/). */
    private const val EXTENSION_LOCATION = "resource://android/assets/extensions/lprobe/"

    /** Native messaging application name; must match `connectNative(...)` in background.js. */
    private const val MESSAGE_HANDLER_NAME = "browser"

    /** Upper bound for the cold-start landing to wait on the extension's URL report. */
    private const val URL_WAIT_MS = 30_000L

    private val pageUrlDeferred = CompletableDeferred<String?>()

    /** The homepage page URL once the extension has reported it; null before that or on failure. */
    @Volatile
    var pageUrl: String? = null
        private set

    private val urlMessageHandler =
        object : MessageHandler {
            override fun onPortConnected(port: Port) {
                Logger.info("HomepageExtension: native port connected: ${port.name()}")
            }

            override fun onPortMessage(message: Any, port: Port) {
                handleUrlMessage(message)
                runCatching { port.postMessage(JSONObject().put("ack", true)) }
            }

            override fun onMessage(message: Any, source: EngineSession?): Any? {
                handleUrlMessage(message)
                return null
            }
        }

    /** Installs the built-in extension. Called once, from BrowserApplication.onCreate. */
    fun install(engine: Engine) {
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_LOCATION,
            onSuccess = { extension ->
                Logger.info(
                    "HomepageExtension: installed (location ${extension.url}); " +
                        "waiting for the extension to report its page url",
                )
                extension.registerBackgroundMessageHandler(MESSAGE_HANDLER_NAME, urlMessageHandler)
            },
            onError = { throwable ->
                Logger.error("HomepageExtension: install failed: ${throwable.message}")
                pageUrlDeferred.complete(null)
            },
        )
    }

    private fun handleUrlMessage(message: Any) {
        if (pageUrlDeferred.isCompleted) {
            return
        }
        val url = (message as? JSONObject)?.optString("url").orEmpty()
        if (url.startsWith("moz-extension://")) {
            pageUrl = url
            Logger.info("HomepageExtension: homepage url reported by extension: $url")
            pageUrlDeferred.complete(url)
        } else {
            Logger.error("HomepageExtension: ignoring unexpected url message: $message")
        }
    }

    /**
     * Suspends until the extension has reported its page URL (or the wait times out); null when
     * the extension is unavailable, so the caller can fall back to a blank tab instead of hanging.
     */
    suspend fun awaitPageUrl(): String? = withTimeoutOrNull(URL_WAIT_MS) { pageUrlDeferred.await() }
}
