/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.siterules

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/**
 * One per-host rule. A rule applies to its [host] and every subdomain of it.
 *
 * @property host Bare host name, lowercase, no scheme (e.g. "example.com").
 * @property userAgent UA string to override for this host; null keeps the engine default.
 * @property desktopMode Whether pages on this host should persistently use desktop mode.
 * @property forceDark Whether web content on this host should be forced into dark colors.
 * @property useWebView Escape-hatch marker: this host should be opened with the WebView
 *   compatibility engine. The WebView engine itself is not built yet; the flag is stored
 *   and shown in the management UI only.
 */
data class SiteRule(
    val host: String,
    val userAgent: String? = null,
    val desktopMode: Boolean = false,
    val forceDark: Boolean = false,
    val useWebView: Boolean = false,
)

/**
 * Persistence and matching for [SiteRule]s. Rules live as a JSON array in a dedicated
 * SharedPreferences file, so they survive restarts and can be edited from the settings UI
 * without a database.
 */
object SiteRules {
    private const val PREFS_NAME = "site_rules"
    private const val KEY_RULES = "rules_json"

    /** All stored rules, in storage order. Corrupt content degrades to an empty list. */
    fun load(context: Context): List<SiteRule> {
        val raw = prefs(context).getString(KEY_RULES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getJSONObject(index).toRule() }
        }.getOrDefault(emptyList())
    }

    /** Replaces the whole rule set. */
    fun save(
        context: Context,
        rules: List<SiteRule>,
    ) {
        val array = JSONArray()
        rules.forEach { array.put(it.toJson()) }
        prefs(context).edit().putString(KEY_RULES, array.toString()).apply()
    }

    /**
     * The rule governing [url], if any: a rule matches when the URL's host equals the rule
     * host or is a subdomain of it; the most specific (longest) matching host wins.
     */
    fun ruleForUrl(
        rules: List<SiteRule>,
        url: String,
    ): SiteRule? {
        val host = hostOf(url) ?: return null
        return rules
            .filter { host == it.host || host.endsWith(".${it.host}") }
            .maxByOrNull { it.host.length }
    }

    /** The bare lowercase host of [url], or null when it cannot be determined. */
    fun hostOf(url: String): String? =
        runCatching { Uri.parse(url).host }
            .getOrNull()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }

    /** Normalizes user input into a rule host: strips scheme, path and whitespace. */
    fun normalizeHost(input: String): String =
        input
            .trim()
            .lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore('?')
            .trim()
            .trimEnd('.')

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun SiteRule.toJson(): JSONObject =
        JSONObject().apply {
            put("host", host)
            put("userAgent", userAgent ?: JSONObject.NULL)
            put("desktopMode", desktopMode)
            put("forceDark", forceDark)
            put("useWebView", useWebView)
        }

    private fun JSONObject.toRule(): SiteRule =
        SiteRule(
            host = getString("host"),
            userAgent = if (isNull("userAgent")) null else getString("userAgent").takeIf { it.isNotBlank() },
            desktopMode = optBoolean("desktopMode", false),
            forceDark = optBoolean("forceDark", false),
            useWebView = optBoolean("useWebView", false),
        )
}
