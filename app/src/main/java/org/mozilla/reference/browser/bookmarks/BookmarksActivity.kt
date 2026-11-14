/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.bookmarks

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.tabs.Homepage

/**
 * The bookmarks page: a plain list of the mobile root's bookmarks. Tapping one opens it in the
 * browser (routed back into [org.mozilla.reference.browser.BrowserActivity] with an ACTION_VIEW
 * intent scoped to this app, the same pattern the crash list uses); long-pressing deletes it.
 * Reached from the toolbar menu, the settings screen and the `via://bookmarks` hook.
 */
class BookmarksActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private var items: List<BookmarkNode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "书签"

        val container = FrameLayout(this)
        listView = ListView(this)
        val emptyView =
            TextView(this).apply {
                text = "暂无书签"
                gravity = Gravity.CENTER
                setPadding(0, PADDING_TOP_DP.dp, 0, 0)
            }
        container.addView(listView)
        container.addView(emptyView)
        listView.emptyView = emptyView
        setContentView(container)

        listView.setOnItemClickListener { _, _, position, _ ->
            items.getOrNull(position)?.url?.let { url -> openInBrowser(url) }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            items.getOrNull(position)?.let { node -> confirmDelete(node) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        loadBookmarks()
    }

    private fun loadBookmarks() {
        lifecycleScope.launch {
            val tree =
                components.core.bookmarksStorage
                    .getTree(Homepage.MOBILE_ROOT_GUID, recursive = false)
                    .getOrNull()
            items =
                tree?.children.orEmpty().filter {
                    it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank()
                }
            val rows =
                items.map { node ->
                    mapOf(
                        "title" to (node.title?.takeIf { it.isNotBlank() } ?: node.url.orEmpty()),
                        "url" to node.url.orEmpty(),
                    )
                }
            listView.adapter =
                SimpleAdapter(
                    this@BookmarksActivity,
                    rows,
                    android.R.layout.simple_list_item_2,
                    arrayOf("title", "url"),
                    intArrayOf(android.R.id.text1, android.R.id.text2),
                )
        }
    }

    private fun openInBrowser(url: String) {
        val intent =
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                setPackage(packageName)
            }
        startActivity(intent)
        finish()
    }

    private fun confirmDelete(node: BookmarkNode) {
        val label = node.title?.takeIf { it.isNotBlank() } ?: node.url.orEmpty()
        AlertDialog.Builder(this)
            .setTitle("删除书签")
            .setMessage(label)
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                lifecycleScope.launch {
                    val deleted = components.core.bookmarksStorage.deleteNode(node.guid).getOrDefault(false)
                    Toast.makeText(
                        this@BookmarksActivity,
                        if (deleted) "已删除" else "删除失败",
                        Toast.LENGTH_SHORT,
                    ).show()
                    if (deleted) {
                        loadBookmarks()
                    }
                }
            }
            .show()
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    private companion object {
        const val PADDING_TOP_DP = 48
    }
}
