/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.extensions

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.webextension.InstallationMethod
import mozilla.components.support.base.log.logger.Logger

/**
 * Ships two third-party add-ons with the app — uBlock Origin (content blocking) and
 * Violentmonkey (userscripts) — as their official AMO-signed XPI files, byte-identical to the
 * AMO releases (versions pinned at intake; per-file hashes verified against the release).
 *
 * How they get installed (route C, settled 2026-11-14 after the unpacked-folder route proved
 * impossible to land through the available upload channels):
 * - On first start, each shipped XPI is copied out of the APK assets into internal storage and
 *   installed through the runtime's regular install call ([Engine.installWebExtension] with
 *   [InstallationMethod.FROM_FILE]) — the same channel as a user installing an add-on from a
 *   file. The installed add-on is an ordinary profile add-on: its AMO signature is verified by
 *   the regular install path, it shows up in the Add-ons UI, and the user can remove it.
 * - The copy step exists because the regular install call rejects `resource://` URLs outright
 *   (verified in the AC 160 GeckoEngine: "url shouldn't be a resource url"), while the built-in
 *   channel ([Engine.installBuiltInWebExtension], `ensureBuiltIn`) only accepts resource URLs
 *   pointing at unpacked folders — and its every-start version comparison could fight store
 *   updates. Neither stock call takes a bundled XPI as-is; copying the bytes out is the bridge.
 * - Updates after install are AMO's job (owner decision, 2026-11-14: channel A). Neither
 *   manifest carries an `update_url`, so the default AMO update host applies, and
 *   WebExtensionSupport registers every loaded add-on with the stock AddonUpdater (see
 *   BrowserApplication), update-permission prompts included.
 * - Auto-install happens at most once per bundled version: a SharedPreferences marker is set
 *   when the add-on is seen installed or an install succeeds. If the user uninstalls the
 *   add-on it stays uninstalled; a later build of L shipping a newer bundled version gets its
 *   own single attempt. A failed attempt leaves the marker unset, so the next start retries —
 *   and every failure is only ever logged, never thrown into startup.
 */
object BuiltinExtensions {
    /** One shipped add-on: fixed gecko id (must match its manifest), APK asset, bundled version. */
    private data class BundledXpi(
        val id: String,
        val assetPath: String,
        val version: String,
        val label: String,
        /** False while the XPI itself has not landed in the APK yet; the entry is then skipped. */
        val shipped: Boolean,
    )

    private val bundled =
        listOf(
            BundledXpi(
                id = "uBlock0@raymondhill.net",
                assetPath = "extensions/ublock-origin.xpi",
                version = "1.76.2",
                label = "uBlock Origin",
                shipped = true,
            ),
            BundledXpi(
                id = "{aecec67f-0d10-4fa7-b7ce-609a2db280cf}",
                assetPath = "extensions/violentmonkey.xpi",
                version = "2.50.0",
                label = "Violentmonkey",
                // The XPI reaches the repo through the browser web upload (binary files cannot
                // go through the connector); flip to true in the same step that lands it.
                shipped = true,
            ),
        )

    private const val PREFS_NAME = "l_bundled_extensions"
    private const val KEY_PREFIX = "auto_install_done_"

    /** Asset copies are plain file IO; keep them off the main thread. */
    private val installScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.IO +
                CoroutineExceptionHandler { _, throwable ->
                    Logger.error("BuiltinExtensions: worker error: ${throwable.message}")
                },
        )

    /** Entry point: called once, from BrowserApplication.onCreate. */
    fun install(context: Context, engine: Engine) {
        val appContext = context.applicationContext
        for (extension in bundled) {
            if (!extension.shipped) {
                Logger.info(
                    "BuiltinExtensions: ${extension.label} bundle not shipped in this build; skipping",
                )
                continue
            }
            ensureAutoInstalled(appContext, engine, extension)
        }
    }

    private fun ensureAutoInstalled(context: Context, engine: Engine, extension: BundledXpi) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val markerKey = KEY_PREFIX + extension.id + "_" + extension.version
        engine.listInstalledWebExtensions(
            onSuccess = { installed ->
                val existing = installed.firstOrNull { it.id == extension.id }
                when {
                    existing != null -> {
                        Logger.info(
                            "BuiltinExtensions: ${extension.label} already installed " +
                                "(version=${existing.getMetadata()?.version}); skipping auto-install",
                        )
                        if (!prefs.getBoolean(markerKey, false)) {
                            prefs.edit().putBoolean(markerKey, true).apply()
                        }
                    }
                    prefs.getBoolean(markerKey, false) -> {
                        Logger.info(
                            "BuiltinExtensions: ${extension.label} auto-install already done for " +
                                "bundled ${extension.version} and the add-on is absent; not retrying",
                        )
                    }
                    else -> {
                        installScope.launch { copyAndInstall(context, engine, extension, markerKey) }
                    }
                }
            },
            onError = { throwable ->
                Logger.error(
                    "BuiltinExtensions: listing installed extensions failed: ${throwable.message}",
                )
            },
        )
    }

    private suspend fun copyAndInstall(
        context: Context,
        engine: Engine,
        extension: BundledXpi,
        markerKey: String,
    ) {
        val targetDir = File(context.filesDir, "bundled-extensions").apply { mkdirs() }
        val target = File(targetDir, extension.assetPath.substringAfterLast('/'))
        val copied =
            runCatching {
                context.assets.open(extension.assetPath).use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }.getOrElse { throwable ->
                // The code can land before the XPI itself (the bundle arrives through a separate
                // upload step); a missing bundle is a log line, not a crash, and the marker
                // stays unset so a later build that does ship it still gets its attempt.
                Logger.error(
                    "BuiltinExtensions: ${extension.label} bundled xpi unreadable " +
                        "(${extension.assetPath}): ${throwable.message}",
                )
                return
            }
        Logger.info(
            "BuiltinExtensions: installing ${extension.label} ${extension.version} " +
                "from bundled xpi (${copied.length()} bytes)",
        )
        withContext(Dispatchers.Main) {
            engine.installWebExtension(
                url = copied.toURI().toString(),
                installationMethod = InstallationMethod.FROM_FILE,
                onSuccess = { installed ->
                    Logger.info(
                        "BuiltinExtensions: ${extension.label} ready " +
                            "(id=${installed.id}, version=${installed.getMetadata()?.version})",
                    )
                    context
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(markerKey, true)
                        .apply()
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
