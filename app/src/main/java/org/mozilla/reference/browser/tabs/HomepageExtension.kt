/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.Metadata
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.support.base.log.logger.Logger

/**
 * Carries the homepage in a built-in WebExtension page (moz-extension origin).
 *
 * The step-1 route — serving the homepage through the request interceptor — was measured to land
 * as a `data:` document with an opaque origin: localStorage never persisted there, and the user
 * homepage code's engine switcher (which stores its choice in localStorage) cannot work on it. An
 * extension page has a real, profile-stable origin instead.
 *
 * The page's moz-extension:// address is discovered through the official metadata field:
 * [WebExtension.getMetadata] exposes `baseUrl` — "Root URL for this extension's pages", produced
 * on the Gecko side from the extension policy's own URL. Neither the install callback's
 * [WebExtension.url] (that is the resource:// install location, measured) nor a native-messaging
 * self-report can provide it: without the `geckoViewAddons` permission, an extension's
 * connectNative calls never reach the app's message handlers at all (measured over four probe
 * runs; the messaging chain has been removed). The metadata of the object handed to the install
 * callback can still be incomplete — its baseUrl is empty until the extension policy exists — so
 * discovery retries through [Engine.listInstalledWebExtensions] until a value with the
 * `moz-extension://` prefix shows up.
 */
object HomepageExtension {
    /** Fixed gecko id; must match `browser_specific_settings.gecko.id` in the extension manifest. */
    const val EXTENSION_ID = "l-homepage@jasmine.li"

    /** Where the extension ships inside the APK (see app/src/main/assets/extensions/). */
    private const val EXTENSION_LOCATION = "resource://android/assets/extensions/lprobe/"

    /** Page inside the extension that carries the homepage (the probe page while verifying). */
    private const val PAGE_PATH = "probe.html"

    /** Upper bound for the cold-start landing to wait on URL discovery. */
    private const val URL_WAIT_MS = 30_000L

    /** Discovery retry cadence; the loop stops as soon as a valid base URL is published. */
    private const val DISCOVERY_INTERVAL_MS = 500L
    private const val DISCOVERY_MAX_ATTEMPTS = 240

    private val discoveryScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Main +
                CoroutineExceptionHandler { _, throwable ->
                    Logger.error("HomepageExtension: discovery error: ${throwable.message}")
                },
        )

    private val pageUrlDeferred = CompletableDeferred<String?>()

    /** The homepage page URL once discovered; null before that or if discovery failed. */
    @Volatile
    var pageUrl: String? = null
        private set

    /** Installs the built-in extension. Called once, from BrowserApplication.onCreate. */
    fun install(engine: Engine) {
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_LOCATION,
            onSuccess = { extension ->
                Logger.info(
                    "HomepageExtension: installed (location ${extension.url}); " +
                        "discovering runtime base url",
                )
                publishBaseUrl(extension.getMetadata(), "install")
                discoverBaseUrl(engine)
            },
            onError = { throwable ->
                Logger.error("HomepageExtension: install failed: ${throwable.message}")
                pageUrlDeferred.complete(null)
            },
        )
    }

    /**
     * Polls the installed-extension list until the homepage extension reports a usable base URL.
     * The install-callback object can predate the extension policy, so its metadata alone is not
     * trusted; every later state is read fresh from the engine.
     */
    private fun discoverBaseUrl(engine: Engine) {
        discoveryScope.launch {
            var lastSeen: String? = null
            repeat(DISCOVERY_MAX_ATTEMPTS) { attempt ->
                if (pageUrl != null) {
                    return@launch
                }
                engine.listInstalledWebExtensions(
                    onSuccess = { extensions ->
                        val extension = extensions.firstOrNull { it.id == EXTENSION_ID }
                        val metadata = extension?.getMetadata()
                        if (metadata?.baseUrl != lastSeen) {
                            lastSeen = metadata?.baseUrl
                            Logger.info(
                                "HomepageExtension: discovery attempt ${attempt + 1}: " +
                                    "base url = ${metadata?.baseUrl ?: "null"}",
                            )
                        }
                        publishBaseUrl(metadata, "list")
                    },
                    onError = { throwable ->
                        Logger.error("HomepageExtension: list failed: ${throwable.message}")
                    },
                )
                delay(DISCOVERY_INTERVAL_MS)
            }
            if (pageUrl == null) {
                Logger.error(
                    "HomepageExtension: discovery exhausted; last base url = ${lastSeen ?: "null"}",
                )
            }
        }
    }

    /**
     * Publishes the page URL from a candidate extension [Metadata]. An empty base URL (policy not
     * created yet) and anything without the moz-extension:// prefix are rejected and logged, never
     * published. The successful read is logged with the extension's required permissions so the
     * discovery leaves evidence in logcat.
     */
    private fun publishBaseUrl(metadata: Metadata?, source: String): Boolean {
        if (pageUrl != null) {
            return true
        }
        val baseUrl = metadata?.baseUrl
        if (baseUrl.isNullOrEmpty() || !baseUrl.startsWith("moz-extension://")) {
            return false
        }
        val url = baseUrl.trimEnd('/') + "/" + PAGE_PATH
        pageUrl = url
        Logger.info(
            "HomepageExtension: homepage url discovered ($source): $url " +
                "(baseUrl=$baseUrl, requiredPermissions=${metadata.requiredPermissions})",
        )
        if (!pageUrlDeferred.isCompleted) {
            pageUrlDeferred.complete(url)
        }
        return true
    }

    /**
     * Suspends until the homepage page URL is discovered (or the wait times out); null when the
     * extension is unavailable, so the caller can fall back to a blank tab instead of hanging.
     */
    suspend fun awaitPageUrl(): String? = withTimeoutOrNull(URL_WAIT_MS) { pageUrlDeferred.await() }
}
