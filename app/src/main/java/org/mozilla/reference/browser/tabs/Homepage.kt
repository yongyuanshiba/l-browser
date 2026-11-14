/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.tabs

import android.content.Context
import android.net.Uri
import android.util.Base64
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import mozilla.components.browser.state.state.selectedOrDefaultSearchEngine
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.settings.Settings

/**
 * Builds the homepage document.
 *
 * The document is assembled from `res/raw/homepage.html` on every request: the skeleton ships the
 * DOM contract that user homepage code (written against the Via homepage) expects — the element
 * ids/classes, plus a head shim providing the `window.search`, `window.initGesture` and `u` globals
 * that code relies on. The homepage extension's shell page fetches the assembled document over the
 * native message channel (see [HomepageExtension]) and writes it into place, so it lives on the
 * extension's moz-extension origin — a real, profile-stable origin whose localStorage persists.
 */
object Homepage {
    /**
     * The search input's placeholder. The shim also exposes it as the global `u`: user homepage code
     * strips the placeholder text out of the input value with a regular expression built from `u`
     * when deciding whether the input counts as empty. The placeholder must therefore stay free of
     * regular-expression metacharacters, and the two must never drift apart.
     */
    const val SEARCH_INPUT_PLACEHOLDER = "搜索或输入网址"

    /** Used only while the store has no default search engine yet (first run before search init). */
    private const val FALLBACK_SEARCH_TEMPLATE = "https://cn.bing.com/search?q=%s"

    /** Upper bound for the synchronous bookmark read during page assembly. */
    private const val BOOKMARK_READ_TIMEOUT_MS = 2000L

    /** The last assembled page and the fingerprint of the inputs it was built from. */
    @Volatile
    private var cachedPage: String? = null

    @Volatile
    private var cachedFingerprint: Long = 0L

    /** Assembles the full homepage HTML for one load. */
    fun createHomepagePage(context: Context): String {
        val part1 = customPart(context, 1)
        val part2 = customPart(context, 2)
        val boxes = buildBoxes(context)
        val template = searchTemplate(context)
        val wallpaper = homepageWallpaperFile(context)
        val wallpaperStamp =
            if (wallpaper.isFile) {
                wallpaper.length() to wallpaper.lastModified()
            } else {
                null
            }
        // Re-entering the homepage re-runs this assembly every time, yet its inputs
        // only change when the user edits the code parts, a bookmark changes, the
        // default search engine changes or the wallpaper changes. An unchanged input
        // set therefore reuses the last assembled document instead of rebuilding it —
        // worth it alone for skipping the wallpaper's base64 encoding.
        val fingerprint = fingerprintOf(part1, part2, boxes, template, wallpaperStamp)
        cachedPage?.let { cached ->
            if (fingerprint == cachedFingerprint) {
                return cached
            }
        }
        var page =
            context.resources
                .openRawResource(R.raw.homepage)
                .bufferedReader()
                .use { it.readText() }
                .replace("<!--L:SHIM-->", buildShim(template))
                .replace("<!--L:BOXES-->", boxes)
        page = injectWallpaper(page, wallpaper, wallpaperStamp != null)
        page =
            page
                .replace("/*L:PART1CSS*/", part1)
                .replace("<!--L:PART2-->", part2)
        // While user code is present, flag the document so the skeleton's
        // custom-mode parity rules apply (they are scoped to body.l-custom in
        // homepage.html and restore the base-layout anchors the code was
        // written against). With no code saved, the default layer is untouched.
        val result =
            if (part1.isNotBlank() || part2.isNotBlank()) {
                page.replace("<body>", "<body class=\"l-custom\">")
            } else {
                page
            }
        cachedPage = result
        cachedFingerprint = fingerprint
        return result
    }

    /** A cheap mix of every assembly input, so a changed input can never hit the cache. */
    private fun fingerprintOf(
        part1: String,
        part2: String,
        boxes: String,
        template: String,
        wallpaperStamp: Pair<Long, Long>?,
    ): Long {
        var hash = 1125899906842597L
        fun mix(value: Long) {
            hash = hash * 31L + value
        }
        mix(part1.length.toLong())
        mix(part1.hashCode().toLong())
        mix(part2.length.toLong())
        mix(part2.hashCode().toLong())
        mix(boxes.length.toLong())
        mix(boxes.hashCode().toLong())
        mix(template.hashCode().toLong())
        mix(wallpaperStamp?.first ?: -1L)
        mix(wallpaperStamp?.second ?: -1L)
        return hash
    }

    /** Where the user's homepage wallpaper lives in the app's files directory, once set. */
    fun homepageWallpaperFile(context: Context): File =
        File(File(context.filesDir, "homepage"), "wallpaper.jpg")

    /**
     * Puts the user's wallpaper behind the homepage: a style block carrying the image as
     * a data URI (cover, centred, fixed) goes immediately before the user-CSS style
     * element, so the user's own CSS can still override it. The shell page lives on a
     * moz-extension origin that cannot load file:// subresources, which is why the image
     * travels inside the document. With no wallpaper set the document is returned
     * untouched — byte-identical to the no-wallpaper assembly.
     */
    private fun injectWallpaper(
        page: String,
        wallpaper: File,
        present: Boolean,
    ): String {
        if (!present) {
            return page
        }
        val base64 =
            runCatching {
                Base64.encodeToString(wallpaper.readBytes(), Base64.NO_WRAP)
            }.getOrNull() ?: return page
        val style =
            "<style>body{background-image:url(\"data:image/jpeg;base64," + base64 +
                "\");background-size:cover;background-position:center;" +
                "background-repeat:no-repeat;background-attachment:fixed;}</style>"
        return page.replace(
            "<style>/*L:PART1CSS*/</style>",
            style + "<style>/*L:PART1CSS*/</style>",
        )
    }

    /** Where one of the user's two homepage code parts lives in the app's files directory. */
    fun homepageCodeFile(context: Context, part: Int): File =
        File(File(context.filesDir, "homepage"), if (part == 1) "part1.css" else "part2.html")

    /**
     * The user's own homepage code for one part, or empty while the custom layer is switched off
     * (or nothing was saved yet). Skeleton tokens are substituted before this runs, so user code is
     * always the last thing inserted and is never itself scanned for assembly tokens.
     */
    private fun customPart(context: Context, part: Int): String {
        if (!Settings.isHomepageCustomEnabled(context)) {
            return ""
        }
        return runCatching { homepageCodeFile(context, part).readText() }.getOrDefault("")
    }

    /**
     * The Places root GUID of the mobile bookmarks folder — where the app's "加入书签" action files
     * new bookmarks, and what the homepage renders. Places defines these root GUIDs itself; the
     * concept-storage module no longer ships a constants type for them.
     */
    const val MOBILE_ROOT_GUID = "mobile______"

    /**
     * Renders the bookmark tiles for `#box_container` from the mobile root's children. The DOM shape
     * is a contract mirroring the Via homepage's own tile markup: the link carries the bookmark
     * title in its `title` attribute (user homepage code selects `.box > [title]` and filters tiles
     * on it), `.title` is the tile's colour layer (initial character plus an inline background
     * colour), and `.url` carries the bookmark's display title — despite the class name, that is
     * the text line the tile design shows.
     *
     * Assembly runs on a background thread off the message handler, so reading synchronously is safe; the read is
     * still bounded so a stuck store can never hang a homepage load — it just renders the empty hint.
     */
    private fun buildBoxes(context: Context): String {
        val storage = context.components.core.bookmarksStorage
        val tree =
            runCatching {
                runBlocking {
                    withTimeoutOrNull(BOOKMARK_READ_TIMEOUT_MS) {
                        storage.getTree(MOBILE_ROOT_GUID, recursive = false)
                    }?.getOrNull()
                }
            }.getOrNull()
        val items =
            tree?.children.orEmpty().filter {
                it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank()
            }
        if (items.isEmpty()) {
            return "<div class=\"l-empty-hint\">暂无书签</div>"
        }
        return items.joinToString(separator = "") { node -> boxHtml(node) }
    }

    /**
     * One bookmark tile. The colour in `.title` is derived deterministically from the host: the
     * environment this markup mirrors takes the colour from the site's icon, and L has no favicon
     * pipeline yet — a stable host-derived pastel keeps the colourful-tile design (and tints the
     * default layer's initial badge) until one exists.
     */
    private fun boxHtml(node: BookmarkNode): String {
        val url = node.url.orEmpty()
        val title = node.title?.takeIf { it.isNotBlank() } ?: url
        val host =
            runCatching { Uri.parse(url).host }
                .getOrNull()
                ?.removePrefix("www.")
                .orEmpty()
        val initial =
            if (title.isEmpty()) "" else String(Character.toChars(title.codePointAt(0)))
        val hue = ((host.ifBlank { title }).lowercase().hashCode() and 0x7fffffff) % 360
        // Child order matters to user homepage CSS: `.title` is the tile's
        // translucent colour layer and `.url` the text line painted above it,
        // so the title layer is emitted first.
        return "<div class=\"box\"><a href=\"" + escapeAttribute(url) + "\" title=\"" +
            escapeAttribute(title) + "\"><div class=\"title\" style=\"background-color: hsl(" +
            hue + ", 68%, 72%)\">" + escapeText(initial) +
            "</div><div class=\"url\">" + escapeText(title) +
            "</div><div class=\"overlay\"></div></a></div>"
    }

    private fun escapeText(value: String): String =
        value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun escapeAttribute(value: String): String =
        escapeText(value).replace("\"", "&quot;")

    /**
     * The head shim. It must run before any user script: `window.search` is read (and later replaced)
     * by the engine-switcher script, `window.initGesture` is redefined by user code with
     * [Object.defineProperty] — so both are installed with plain assignments, keeping them writable
     * and configurable. The form's submit is routed through `window.search` looked up at event time,
     * never through a cached reference, for the same reason.
     */
    private fun buildShim(searchTemplate: String): String {
        val template =
            searchTemplate
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
