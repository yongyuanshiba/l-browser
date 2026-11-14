/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.MessageHandler
import mozilla.components.concept.engine.webextension.Metadata
import mozilla.components.concept.engine.webextension.Port
import mozilla.components.concept.engine.webextension.WebExtension
import mozilla.components.support.base.log.logger.Logger
import org.json.JSONObject

/**
 * Carries the homepage in a built-in WebExtension page (moz-extension origin).
 *
 * The step-1 route — serving the homepage through the request interceptor — was measured to land
 * as a `data:` document with an opaque origin: localStorage never persisted there, and the user
 * homepage code's engine switcher (which stores its choice in localStorage) cannot work on it. An
 * extension page has a real, profile-stable origin instead. The extension itself is only a shell:
 * its page asks (through the background script, over the native-messaging port) for the homepage
 * document that [Homepage] assembles natively, then writes it into place with
 * document.open/write/close, so the homepage's inline scripts run as parser-inserted scripts. The
 * default CSP for extension pages is relaxed through the GeckoView config file (see
 * EngineProvider); the extension manifest deliberately carries no CSP key of its own.
 *
 * The page's moz-extension:// address is discovered through the official metadata field:
 * [WebExtension.getMetadata] exposes `baseUrl` — "Root URL for this extension's pages", produced
 * on the Gecko side from the extension policy's own URL. The install callback's [WebExtension.url]
 * is not it (that is the resource:// install location, measured). Native messaging is not used for
 * discovery — only as the homepage's data channel, and it only reaches the app's handlers because
 * the manifest carries the `geckoViewAddons` permission (without it, an extension's connectNative
 * calls never arrive at all — measured over four probe runs, then root-caused in the URL-discovery
 * research). The metadata of the object handed to the install callback can still be incomplete —
 * its baseUrl is empty until the extension policy exists — so discovery retries through
 * [Engine.listInstalledWebExtensions] until a value with the `moz-extension://` prefix shows up.
 */
object HomepageExtension {
    /** Fixed gecko id; must match `browser_specific_settings.gecko.id` in the extension manifest. */
    const val EXTENSION_ID = "l-homepage@jasmine.li"

    /** Where the extension ships inside the APK (see app/src/main/assets/extensions/). */
    private const val EXTENSION_LOCATION = "resource://android/assets/extensions/lprobe/"

    /** Page inside the extension that carries the homepage (a shell; see shell.js in the extension). */
    private const val PAGE_PATH = "shell.html"

    /**
     * Upper bound for the cold-start landing to wait on URL discovery. Discovery completes as soon
     * as the extension policy exists, so the bound only bites on genuinely slow starts; it is sized
     * for slow real devices, not for the software-emulated test rig (which needed more, and whose
     * probe build used a larger instrumentation window).
     */
    private const val URL_WAIT_MS = 120_000L

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

    /** Homepage assembly reads bookmarks and files; keep it off the message thread. */
    private val assemblyScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, throwable ->
                    Logger.error("HomepageExtension: assembly error: ${throwable.message}")
                },
        )

    private val pageUrlDeferred = CompletableDeferred<String?>()

    /** Application context for homepage assembly; set by [install]. */
    @Volatile
    private var appContext: Context? = null

    /** The homepage page URL once discovered; null before that or if discovery failed. */
    @Volatile
    var pageUrl: String? = null
        private set

    /** Installs the built-in extension. Called once, from BrowserApplication.onCreate. */
    fun install(context: Context, engine: Engine) {
        appContext = context.applicationContext
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
                // The homepage's data channel. The extension's background script relays the
                // shell page's request over this port; that only reaches us because the manifest
                // carries the `geckoViewAddons` permission.
                extension.registerBackgroundMessageHandler("browser", homepageMessageHandler)
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

    /**
     * The homepage's data channel: the shell page asks for the assembled homepage (one request
     * type, relayed by the background script), and the freshly assembled document is posted back
     * on the same port. Assembly happens per request, so bookmark tiles and the user's code parts
     * are always current. Nothing else is answered on this channel.
     */
    private val homepageMessageHandler =
        object : MessageHandler {
            override fun onPortConnected(port: Port) {
                Logger.info("HomepageExtension: homepage port connected (name=${port.name()})")
            }

            override fun onPortMessage(message: Any, port: Port) {
                val request = message as? JSONObject ?: return
                if (request.optString("type") != "l-homepage-request") {
                    return
                }
                val id = request.optInt("id", 0)
                val context = appContext
                if (context == null) {
                    Logger.error("HomepageExtension: homepage requested before install() set a context")
                    return
                }
                assemblyScope.launch {
                    val html =
                        runCatching { Homepage.createHomepagePage(context) }
                            .onFailure { throwable ->
                                Logger.error(
                                    "HomepageExtension: homepage assembly failed: ${throwable.message}",
                                )
                            }
                            .getOrNull()
                    val reply =
                        JSONObject()
                            .put("type", "l-homepage-html")
                            .put("id", id)
                    if (html != null) {
                        reply.put("html", html)
                    }
                    runCatching { port.postMessage(reply) }
                }
            }

            override fun onPortDisconnected(port: Port) {
                Logger.info("HomepageExtension: homepage port disconnected")
            }
        }
}
