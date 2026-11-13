/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import java.io.File

/**
 * Stage-4 probe F: carries the homepage as a plain file in the app-private
 * directory (`files/home/index.html`), loaded through its `file://` URL.
 *
 * A file document has no extension CSP, so the homepage's inline scripts run
 * natively; the open questions this probe answers are whether the (possibly
 * isolated) content process can read app-private files at all, and whether
 * localStorage on the file origin survives a process kill and a rewrite of the
 * file at the same path. Every launch rewrites the page with a bumped
 * generation marker, so a force-stop + relaunch reads back both the previous
 * generation's storage and the new generation's content in one screenshot.
 * The home page auto-navigates to a sibling directory's page after rendering,
 * which observes whether another file page shares the same storage bucket.
 *
 * Probe-only: removed when the homepage carrier route is finalised.
 */
object FileProbe {
    /** Writes the probe pages and returns the home page's file:// URL. */
    fun prepare(context: Context): String {
        val homeDir = File(context.filesDir, "home").apply { mkdirs() }
        val otherDir = File(context.filesDir, "other").apply { mkdirs() }

        val genFile = File(homeDir, "probe-gen.txt")
        val generation = (runCatching { genFile.readText().trim().toInt() }.getOrNull() ?: 0) + 1
        genFile.writeText(generation.toString())

        File(otherDir, "other.html").writeText(otherPage())
        val index = File(homeDir, "index.html")
        index.writeText(homePage(generation))
        return "file://" + index.absolutePath
    }

    private fun homePage(generation: Int): String =
        """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <title>LPROBE-F</title>
        </head>
        <body>
        <pre id="f_out">F pending</pre>
        <script>
        try {
            var prev = window.localStorage.getItem('__l_f__');
            window.localStorage.setItem('__l_f__', 'f-ls-ok');
            window.localStorage.setItem('__l_f_from_home__', 'from-home');
            document.getElementById('f_out').textContent =
                'F-HOME gen=$generation href=' + location.href +
                ' ls_prev=' + prev +
                ' ls_read=' + window.localStorage.getItem('__l_f__');
        } catch (e) {
            document.getElementById('f_out').textContent = 'F-HOME gen=$generation ls_error=' + e.name;
        }
        setTimeout(function () { location.href = '../other/other.html'; }, 15000);
        </script>
        </body>
        </html>
        """.trimIndent()

    private fun otherPage(): String =
        """
        <!DOCTYPE html>
        <html>
        <head>
        <meta charset="utf-8">
        <title>LPROBE-F-OTHER</title>
        </head>
        <body>
        <pre id="o_out">OTHER pending</pre>
        <script>
        try {
            document.getElementById('o_out').textContent =
                'F-OTHER href=' + location.href +
                ' ls=' + window.localStorage.getItem('__l_f__') +
                ' from_home=' + window.localStorage.getItem('__l_f_from_home__');
        } catch (e) {
            document.getElementById('o_out').textContent = 'F-OTHER ls_error=' + e.name;
        }
        </script>
        </body>
        </html>
        """.trimIndent()
}
