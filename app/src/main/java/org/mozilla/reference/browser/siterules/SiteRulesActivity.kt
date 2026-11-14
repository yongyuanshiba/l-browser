/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.siterules

import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * The site rules page: lists the per-host rules from [SiteRules] and lets the user add,
 * edit and delete them. A rule matches its host and all subdomains. Reached from settings.
 * The UI is built in code, like the bookmarks page.
 */
class SiteRulesActivity : AppCompatActivity() {
    private lateinit var listView: ListView
    private var rules: List<SiteRule> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "站点规则"

        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val addButton =
            Button(this).apply {
                text = "添加规则"
                setOnClickListener { showEditDialog(existing = null) }
            }
        container.addView(addButton)

        listView = ListView(this)
        val emptyView =
            TextView(this).apply {
                text = "暂无站点规则"
                gravity = Gravity.CENTER
                setPadding(0, dp(PADDING_TOP_DP), 0, 0)
            }
        container.addView(listView)
        container.addView(emptyView)
        listView.emptyView = emptyView
        setContentView(container)

        listView.setOnItemClickListener { _, _, position, _ ->
            rules.getOrNull(position)?.let { showEditDialog(existing = it) }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        SiteRules.ensurePresets(this)
        rules = SiteRules.load(this)
        val rows =
            rules.map { rule ->
                mapOf("host" to rule.host, "summary" to summarize(rule))
            }
        listView.adapter =
            SimpleAdapter(
                this,
                rows,
                android.R.layout.simple_list_item_2,
                arrayOf("host", "summary"),
                intArrayOf(android.R.id.text1, android.R.id.text2),
            )
    }

    private fun summarize(rule: SiteRule): String {
        val parts = mutableListOf<String>()
        if (!rule.userAgent.isNullOrBlank()) {
            parts += "UA 伪装"
        }
        if (rule.desktopMode) {
            parts += "桌面模式"
        }
        if (rule.forceDark) {
            parts += "强制暗色"
        }
        if (rule.useWebView) {
            parts += "兼容内核"
        }
        return if (parts.isEmpty()) "无特殊设置" else parts.joinToString(" · ")
    }

    private fun showEditDialog(existing: SiteRule?) {
        val hostInput =
            EditText(this).apply {
                hint = "域名，如 example.com（子域名自动匹配）"
                setText(existing?.host ?: "")
                isEnabled = existing == null
            }
        val uaInput =
            EditText(this).apply {
                hint = "自定义 UA（留空表示不覆盖）"
                setText(existing?.userAgent ?: "")
            }
        val desktopSwitch = switchRow("桌面模式", existing?.desktopMode == true)
        val darkSwitch = switchRow("网页强制暗色", existing?.forceDark == true)
        val webViewSwitch = switchRow("用兼容内核打开（逃生舱，暂未启用）", existing?.useWebView == true)

        val form =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(FORM_PADDING_DP), dp(FORM_PADDING_DP), dp(FORM_PADDING_DP), 0)
                addView(hostInput)
                addView(uaInput)
                addView(desktopSwitch)
                addView(darkSwitch)
                addView(webViewSwitch)
            }

        val builder =
            AlertDialog.Builder(this)
                .setTitle(if (existing == null) "添加规则" else "编辑规则")
                .setView(form)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
        if (existing != null) {
            builder.setNeutralButton("删除") { _, _ ->
                SiteRules.save(this, rules.filterNot { it.host == existing.host })
                refresh()
            }
        }
        val dialog = builder.create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val host = SiteRules.normalizeHost(hostInput.text.toString())
            if (host.isBlank() || !host.contains('.')) {
                Toast.makeText(this, "请填写有效域名", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val rule =
                SiteRule(
                    host = host,
                    userAgent = uaInput.text.toString().trim().takeIf { it.isNotBlank() },
                    desktopMode = desktopSwitch.isChecked,
                    forceDark = darkSwitch.isChecked,
                    useWebView = webViewSwitch.isChecked,
                )
            val updated = rules.filterNot { it.host == host } + rule
            SiteRules.save(this, updated)
            refresh()
            dialog.dismiss()
        }
    }

    private fun switchRow(
        label: String,
        checked: Boolean,
    ): Switch =
        Switch(this).apply {
            text = label
            isChecked = checked
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val PADDING_TOP_DP = 48
        const val FORM_PADDING_DP = 16
    }
}
