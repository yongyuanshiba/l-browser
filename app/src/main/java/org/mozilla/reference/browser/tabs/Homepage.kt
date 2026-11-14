/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import android.content.Context
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components

/**
 * Builds the `about:homepage` page, which the app serves itself (same pattern as [PrivatePage]).
 *
 * The page is assembled from `res/raw/homepage.html` on every load: the skeleton ships the DOM contract
 * that user homepage code (written against the Via homepage) expects — the element ids/classes, plus a
 * head shim providing the `window.search`, `window.initGesture` and `u` globals that code relies on.
 * Later stages fill the remaining assembly tokens: bookmark boxes, and the user's own CSS/HTML layer.
 */
object Homepage {
    /** The synthetic URL the interceptor serves this page under. */
    const val URL = "about:homepage"

    /**
     * The search input's placeholder. The shim also exposes it as the global `u`: user homepage code
     * strips the placeholder text out of the input value with a regular expression built from `u`
     * when deciding whether the input counts as empty. The placeholder must therefore stay free of
     * regular-expression metacharacters, and the two must never drift apart.
     */
    const val SEARCH_INPUT_PLACEHOLDER = "搜索或输入网址"

    /** Used only while the store has no default search engine yet (first run before search init). */
    private const val FALLBACK_SEARCH_TEMPLATE = "https://cn.bing.com/search?q=%s"

    /** Assembles the full homepage HTML for one load. */
    fun createHomepagePage(context: Context): String =
        context.resources
            .openRawResource(R.raw.homepage)
            .bufferedReader()
            .use { it.readText() }
            .replace("<!--L:SHIM-->", buildShim(context))
            .replace("<!--L:BOXES-->", "")
            .replace("/*L:PART1CSS*/", "")
            .replace("<!--L:PART2-->", "")

    /**
     * The head shim. It must run before any user script: `window.search` is read (and later replaced)
     * by the engine-switcher script, `window.initGesture` is redefined by user code with
     * [Object.defineProperty] — so both are installed with plain assignments, keeping them writable
     * and configurable. The form's submit is routed through `window.search` looked up at event time,
     * never through a cached reference, for the same reason.
     */
    private fun buildShim(context: Context): String {
        val template =
            searchTemplate(context)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
        return """
            <script>
            window.initGesture = function () {};
            var u = "$SEARCH_INPUT_PLACEHOLDER";
            var L_SEARCH_TEMPLATE = "$template";
            window.search = function () {
                var input = document.getElementById('search_input');
                if (!input) { return false; }
                var v = (input.value || '').replace(/^\s+|\s+${'$'}/g, '');
                if (!v || v === input.placeholder) { return false; }
                var url;
                if (/^[a-zA-Z][a-zA-Z0-9+.\-]*:\/\//.test(v)) {
                    url = v;
                } else if (!/\s/.test(v) && /^[^\s\/]+\.[a-zA-Z]{2,}([/?#].*)?${'$'}/.test(v)) {
                    url = 'https://' + v;
                } else {
                    url = L_SEARCH_TEMPLATE.replace('%s', encodeURIComponent(v));
                }
                window.location.href = url;
                return false;
            };
            document.addEventListener('DOMContentLoaded', function () {
                var form = document.getElementById('search_form');
                if (form) {
                    form.addEventListener('submit', function (event) {
                        event.preventDefault();
                        window.search();
                    });
                }
            });
            window.addEventListener('error', function (event) {
                console.log('L-HOME-ERROR: ' + event.message);
            });
            </script>
            """.trimIndent()
    }

    /**
     * The default search engine's URL template with `%s` as the query placeholder, taken from the
     * store's search state — the same engine the toolbar's default search uses. Falls back to a
     * static template while the state has no engine yet.
     */
    private fun searchTemplate(context: Context): String {
        val engine =
            runCatching {
                context.components.core.store.state.search.selectedOrDefaultSearchEngine
            }.getOrNull()
        val url = engine?.resultUrls?.firstOrNull()?.replace("{searchTerms}", "%s")
        if (url != null && url.contains("%s")) {
            return url
        }
        Logger.warn("Homepage: default search engine not ready, using fallback search template")
        return FALLBACK_SEARCH_TEMPLATE
    }
}
