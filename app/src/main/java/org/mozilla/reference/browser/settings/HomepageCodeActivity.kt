/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.tabs.Homepage

/**
 * The homepage code editor: two plain text boxes holding the user's two homepage code parts
 * (part 1 = CSS, part 2 = HTML/scripts), pasted in and saved to the app's files directory, where
 * [Homepage] reads them on every homepage assembly while the custom layer is switched on.
 */
class HomepageCodeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_homepage_code)
        title = "主页代码"

        val part1Edit = findViewById<EditText>(R.id.homepage_part1_edit)
        val part2Edit = findViewById<EditText>(R.id.homepage_part2_edit)
        part1Edit.setText(readPart(1))
        part2Edit.setText(readPart(2))

        findViewById<Button>(R.id.homepage_code_save).setOnClickListener {
            writePart(1, part1Edit.text.toString())
            writePart(2, part2Edit.text.toString())
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun readPart(part: Int): String =
        runCatching { Homepage.homepageCodeFile(this, part).readText() }.getOrDefault("")

    private fun writePart(part: Int, content: String) {
        val file = Homepage.homepageCodeFile(this, part)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}
