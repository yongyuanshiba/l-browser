/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.extensions

import mozilla.components.concept.engine.Engine
import mozilla.components.support.base.log.logger.Logger

/**
 * Installs the third-party extensions L ships as built-ins: uBlock Origin (content blocking)
 * and Violentmonkey (userscripts). Both are unpacked from the official AMO packages into
 * `assets/extensions/` at build time and installed through the same built-in channel the
 * homepage carrier uses (see HomepageExtension).
 *
 * Why this channel, and what it implies for updates:
 * - The bundled copies are the official AMO builds (version pinned at intake, per-file
 *   hashes verified against the AMO release). The built-in channel is the initial-install
 *   vehicle only: GeckoView's built-in install (`ensureBuiltIn` under the Android
 *   Components wrapper) does not need the package to be signed, and on every start it
 *   compares the bundled manifest version against the installed one, installing the
 *   bundled copy when the version differs.
 * - Updates after install are AMO's job (owner decision, 2026-11-14: channel A). Each
 *   bundled manifest carries `browser_specific_settings.gecko.update_url` pointing at the
 *   AMO update service, and WebExtensionSupport registers every loaded extension with the
 *   stock AddonUpdater (see BrowserApplication), whose update-permission prompts surface
 *   to the user like those of any AMO-installed add-on.
 * - One interplay is stated plainly because it is not yet settled by observation: if an
 *   AMO update moves the installed version ahead of the bundled one, ensureBuiltIn's
 *   version comparison at the next start decides whether the AMO copy survives. Only the
 *   first real AMO update settles that; until then the AMO wiring is verified at config
 *   and log level only, and nobody claims an AMO update has been seen succeeding.
 * - The extensions keep their own dashboards and appear in the existing Add-ons UI like
 *   any installed add-on, so the user can inspect or disable them. Allowing them in
 *   private browsing is a separate, per-add-on switch (AddonManager) and is deliberately
 *   not forced here.
 */
object BuiltinExtensions {
    /** One bundled extension: fixed gecko id (must match its manifest) and APK location. */
    private data class BundledExtension(val id: String, val location: String, val label: String)

    private val bundled =
        listOf(
            BundledExtension(
                id = "uBlock0@raymondhill.net",
                location = "resource://android/assets/extensions/ublock-origin/",
                label = "uBlock Origin",
            ),
            BundledExtension(
                id = "{aecec67f-0d10-4fa7-b7ce-62d987b8b5bc}",
                location = "resource://android/assets/extensions/violentmonkey/",
                label = "Violentmonkey",
            ),
        )

    /** Installs (or version-upgrades) every bundled extension. Called once, from BrowserApplication.onCreate. */
    fun install(engine: Engine) {
        for (extension in bundled) {
            engine.installBuiltInWebExtension(
                id = extension.id,
                url = extension.location,
                onSuccess = { installed ->
                    Logger.info(
                        "BuiltinExtensions: ${extension.label} ready " +
                            "(id=${installed.id}, location=${installed.url})",
                    )
                },
                onError = { throwable ->
                    Logger.error(
                        "BuiltinExtensions: ${extension.label} install failed: ${throwable.message}",
                    )
                },
            )
        }
    }
}
