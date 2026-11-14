/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.siterules

import android.content.Context
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import mozilla.components.browser.state.selector.findTabOrCustomTab
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.SessionState
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.concept.engine.Engine
import mozilla.components.concept.engine.mediaquery.PreferredColorScheme
import mozilla.components.feature.session.SessionUseCases
import mozilla.components.lib.state.ext.flow
import mozilla.components.support.base.feature.LifecycleAwareFeature
import mozilla.components.support.base.log.logger.Logger

/**
 * Applies the per-host [SiteRule]s to the tab shown by this fragment: UA override on the
 * engine session, persistent desktop mode, and forced dark web content. Rules are matched
 * on every navigation and tab selection, so they take effect when a session is linked and
 * whenever the URL changes; they are read from storage on each application, so edits made
 * in the settings UI apply without a restart.
 *
 * Two application details worth knowing:
 * - The UA override is written straight onto the live engine session (Gecko applies it
 *   from the next load on), so after changing it the current page is reloaded exactly
 *   once per (tab, URL, rule) combination.
 * - Forced dark maps to the engine-wide preferred color scheme (the only level AC 160
 *   exposes it at), driven by the currently displayed tab: a matching tab turns it dark,
 *   any other tab restores the system scheme.
 */
class SiteRulesFeature(
    private val context: Context,
    private val store: BrowserStore,
    private val engine: Engine,
    private val sessionUseCases: SessionUseCases,
    private val sessionId: String? = null,
) : LifecycleAwareFeature {
    private val logger = Logger("SiteRulesFeature")
    private val scope = MainScope()
    private var job: Job? = null

    /** The (tab, URL, UA) combination this feature last reloaded for, to reload once. */
    private var lastReloadKey: String? = null
    private var lastReloadUa: String? = null

    /** The color scheme this feature last set on the engine, to avoid redundant writes. */
    private var appliedDark: Boolean? = null

    override fun start() {
        job?.cancel()
        job =
            scope.launch {
                store
                    .flow()
                    .map { state -> sessionId?.let { state.findTabOrCustomTab(it) } ?: state.selectedTab }
                    .distinctUntilChanged()
                    .collect { tab -> tab?.let { applyTo(it) } }
            }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private fun applyTo(tab: SessionState) {
        val url = tab.content.url
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return
        }
        val rule = SiteRules.ruleForUrl(SiteRules.load(context), url)
        applyUserAgent(tab, rule)
        applyDesktopMode(tab, rule)
        applyColorScheme(rule)
        // The useWebView flag is only stored for now: the WebView escape engine is not
        // built yet, so there is nothing to switch to in this round.
    }

    private fun applyUserAgent(
        tab: SessionState,
        rule: SiteRule?,
    ) {
        val engineSession = tab.engineState.engineSession ?: return
        val desired = rule?.userAgent
        if (engineSession.settings.userAgentString == desired) {
            return
        }
        engineSession.settings.userAgentString = desired
        // The override only takes effect from the next load on, so reload the current
        // page — but at most once per (tab, URL, UA) combination, to never loop.
        val key = "${tab.id}|${tab.content.url}"
        if (key != lastReloadKey || desired != lastReloadUa) {
            lastReloadKey = key
            lastReloadUa = desired
            logger.info("SiteRules: UA override changed for tab ${tab.id}; reloading")
            sessionUseCases.reload.invoke(tab.id)
        }
    }

    private fun applyDesktopMode(
        tab: SessionState,
        rule: SiteRule?,
    ) {
        // Without a rule the tab's desktop mode is left alone (the menu toggle stays
        // transient); with a rule its stored value is authoritative for the host.
        if (rule == null) {
            return
        }
        if (tab.content.desktopMode != rule.desktopMode) {
            sessionUseCases.requestDesktopSite.invoke(rule.desktopMode, tab.id)
        }
    }

    private fun applyColorScheme(rule: SiteRule?) {
        val dark = rule?.forceDark == true
        if (appliedDark == dark) {
            return
        }
        appliedDark = dark
        engine.settings.preferredColorScheme =
            if (dark) PreferredColorScheme.Dark else PreferredColorScheme.System
    }
}
