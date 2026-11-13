/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.concept.engine.Engine
import mozilla.components.support.base.log.logger.Logger

/**
 * Carries the homepage in a built-in WebExtension page (moz-extension origin).
 *
 * The step-1 route — serving the homepage through the request interceptor — was measured to land
 * as a `data:` document with an opaque origin: localStorage never persisted there, and the user
 * homepage code's engine switcher (which stores its choice in localStorage) cannot work on it. An
 * extension page has a real, profile-stable origin instead. This holder installs the extension at
 * app start and publishes its page URL: the cold-start landing waits for it, and the new-tab
 * action reads whatever is current.
 */
object HomepageExtension {
    /** Fixed gecko id; must match `browser_specific_settings.gecko.id` in the extension manifest. */
    const val EXTENSION_ID = "l-homepage@jasmine.li"

    /** Where the extension ships inside the APK (see app/src/main/assets/extensions/). */
    private const val EXTENSION_LOCATION = "resource://android/assets/extensions/lprobe"

    /** Page opened as the homepage while the carrier route is being proven. */
    private const val PAGE_PATH = "probe.html"

    /** Upper bound for the cold-start landing to wait on the extension install. */
    private const val INSTALL_WAIT_MS = 30_000L

    private val pageUrlDeferred = CompletableDeferred<String?>()

    /** The probe page URL once the extension is installed; null before that or after a failure. */
    @Volatile
    var pageUrl: String? = null
        private set

    /** Installs the built-in extension. Called once, from BrowserApplication.onCreate. */
    fun install(engine: Engine) {
        engine.installBuiltInWebExtension(
            id = EXTENSION_ID,
            url = EXTENSION_LOCATION,
            onSuccess = { extension ->
                val base = extension.url
                if (base.startsWith("moz-extension://")) {
                    val url = base.trimEnd('/') + "/" + PAGE_PATH
                    pageUrl = url
                    Logger.info("HomepageExtension: installed, page url is $url")
                    pageUrlDeferred.complete(url)
                } else {
                    Logger.error("HomepageExtension: unexpected extension url: $base")
                    pageUrlDeferred.complete(null)
                }
            },
            onError = { throwable ->
                Logger.error("HomepageExtension: install failed: ${throwable.message}")
                pageUrlDeferred.complete(null)
            },
        )
    }

    /**
     * Suspends until installation has resolved (or the wait times out); null when the extension
     * is unavailable, so the caller can fall back to a blank tab instead of hanging.
     */
    suspend fun awaitPageUrl(): String? = withTimeoutOrNull(INSTALL_WAIT_MS) { pageUrlDeferred.await() }
}
