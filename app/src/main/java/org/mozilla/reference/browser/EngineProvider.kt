/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import java.io.File
import mozilla.components.browser.engine.gecko.GeckoEngine
import mozilla.components.browser.engine.gecko.fetch.GeckoViewFetchClient
import mozilla.components.concept.engine.DefaultSettings
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.fetch.Client
import mozilla.components.feature.webcompat.WebCompatFeature
import mozilla.components.lib.crash.handler.CrashHandlerService
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * Holds the process-wide [GeckoRuntime]. GeckoView allows only one runtime per process, so the engine, the fetch client
 * and site permission storage must all share it.
 */
object EngineProvider {
    private var runtime: GeckoRuntime? = null

    /** Synchronized so that concurrent callers cannot create a second [GeckoRuntime]. */
    @Synchronized
    fun getOrCreateRuntime(context: Context): GeckoRuntime {
        if (runtime == null) {
            val builder = GeckoRuntimeSettings.Builder()

            builder.crashHandler(CrashHandlerService::class.java)

            // About config it's no longer enabled by default
            builder.aboutConfigEnabled(true)
            builder.extensionsWebAPIEnabled(true)

            // Stage-4 probe P: hand GeckoView a config file that relaxes the default
            // content security policy for extension pages, so the homepage shell can
            // deliver the assembled homepage (inline scripts included) through
            // document.write. Whether this probe change stays is decided when the
            // homepage carrier route is finalised; the DNS application below is
            // untouched by it.
            builder.configFilePath(writeExtensionCspConfig(context).absolutePath)

            runtime = GeckoRuntime.create(context, builder.build())

            // The runtime is (re)created once per process, so applying the DNS policy here re-applies it
            // on every app start.
            DnsSettings.applyOnStartup(context, runtime!!)
        }

        return runtime!!
    }

    /**
     * Writes the GeckoView config file (YAML) that sets the default CSP for
     * WebExtension pages. The homepage extension's manifest deliberately carries no
     * `content_security_policy` key of its own, so this default is the one that
     * applies to it. Rewritten on every runtime creation; the file lives in the
     * app's private directory.
     */
    private fun writeExtensionCspConfig(context: Context): File {
        val file = File(context.filesDir, "l-geckoview-config.yaml")
        file.writeText(
            "prefs:\n" +
                "  extensions.webextensions.default-content-security-policy: " +
                "\"script-src 'self' 'unsafe-inline' 'wasm-unsafe-eval'\"\n",
        )
        return file
    }

    /** Re-applies the DNS policy after the user changed it on the settings page. */
    fun reapplyDnsSettings(
        context: Context,
        mode: String,
    ) {
        DnsSettings.applyModeChange(getOrCreateRuntime(context), mode)
    }

    /** Creates the engine on the shared runtime and installs [WebCompatFeature] on it. */
    fun createEngine(
        context: Context,
        defaultSettings: DefaultSettings,
    ): Engine {
        val runtime = getOrCreateRuntime(context)

        return GeckoEngine(context, defaultSettings, runtime).also {
            WebCompatFeature.install(it)
        }
    }

    /** Backed by the shared runtime, so requests made through this client use Gecko's network stack. */
    fun createClient(context: Context): Client {
        val runtime = getOrCreateRuntime(context)
        return GeckoViewFetchClient(context, runtime)
    }
}
