/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import org.mozilla.geckoview.ExperimentalGeckoViewApi
import org.mozilla.geckoview.GeckoPreferenceController
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.reference.browser.settings.Settings

/**
 * Central place that applies this app's DNS resolution policy to the Gecko runtime.
 *
 * Product decision (stage 3): name resolution must go through the system resolver, so that a system-wide
 * encrypted DNS configuration (e.g. Private DNS / DNS-over-TLS) stays in effect. Gecko's own DNS-over-HTTPS
 * (TRR) must therefore not bypass it. This object pins the three supported [GeckoRuntimeSettings] knobs that
 * control TRR; it deliberately does not implement any DoH/DoT client of its own.
 *
 * The user can opt out on the settings page ([MODE_BROWSER_DEFAULT]); the choice is persisted with the other
 * app settings and re-applied on every process start by [EngineProvider.getOrCreateRuntime].
 */
object DnsSettings {
    /** Persisted value: force system resolution. This is the default when nothing was chosen yet. */
    const val MODE_SYSTEM = "system"

    /** Persisted value: leave TRR entirely to Gecko, as a stock GeckoView build would behave. */
    const val MODE_BROWSER_DEFAULT = "browser_default"

    private const val PREF_TRR_MODE = "network.trr.mode"
    private const val PREF_TRR_URI = "network.trr.uri"
    private const val PREF_TRR_DEFAULT_PROVIDER_URI = "network.trr.default_provider_uri"

    /** The mode currently persisted in the app settings. */
    fun currentMode(context: Context): String = Settings.getDnsMode(context)

    /** Whether [mode] is the forced system-resolution policy. Unknown values fall back to it. */
    fun isSystemMode(mode: String): Boolean = mode != MODE_BROWSER_DEFAULT

    /**
     * Applies the persisted policy to a freshly created [runtime]. Called on every process start from
     * [EngineProvider.getOrCreateRuntime]; in browser-default mode there is nothing to apply, because any
     * values pinned by an earlier forced phase were already cleared when the user switched (see
     * [applyModeChange]).
     */
    fun applyOnStartup(
        context: Context,
        runtime: GeckoRuntime,
    ) {
        if (isSystemMode(currentMode(context))) {
            pinSystemResolution(runtime)
        }
    }

    /**
     * Applies a mode the user has just selected on the settings page to the running [runtime], so the change
     * takes effect without waiting for the next process start. Switching back to browser default clears the
     * pinned user values, restoring the build's compiled-in defaults.
     */
    fun applyModeChange(
        runtime: GeckoRuntime,
        mode: String,
    ) {
        if (isSystemMode(mode)) {
            pinSystemResolution(runtime)
        } else {
            clearPinnedValues()
        }
    }

    /**
     * The forced policy: TRR mode off, no TRR provider URI, and no default provider URI. The last one is what
     * disables TRR's automatic/opportunistic selection in mode-off state: without a default provider there is
     * nothing to upgrade to, so resolution stays with the native (system) resolver.
     */
    private fun pinSystemResolution(runtime: GeckoRuntime) {
        runtime.settings
            .setTrustedRecursiveResolverMode(GeckoRuntimeSettings.TRR_MODE_OFF)
            .setTrustedRecursiveResolverUri("")
            .setDefaultRecursiveResolverUri("")
    }

    /** Removes the user values written by [pinSystemResolution]; compiled-in defaults apply again. */
    @OptIn(ExperimentalGeckoViewApi::class)
    private fun clearPinnedValues() {
        // Fire-and-forget: the returned GeckoResults are not needed to decide anything here.
        GeckoPreferenceController.clearGeckoUserPref(PREF_TRR_MODE)
        GeckoPreferenceController.clearGeckoUserPref(PREF_TRR_URI)
        GeckoPreferenceController.clearGeckoUserPref(PREF_TRR_DEFAULT_PROVIDER_URI)
    }
}
