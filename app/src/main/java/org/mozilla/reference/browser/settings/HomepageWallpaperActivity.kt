/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.mozilla.reference.browser.tabs.Homepage

/**
 * The homepage wallpaper page: pick an image and it becomes the homepage's background.
 * The picked image is downscaled and stored in the app's files directory (see
 * [Homepage.homepageWallpaperFile]); [Homepage] inlines it when assembling the homepage.
 * Clearing deletes the stored copy and the homepage goes back to its plain background.
 */
class HomepageWallpaperActivity : AppCompatActivity() {
    private lateinit var statusView: TextView
    private lateinit var previewView: ImageView
    private lateinit var clearButton: Button

    private val pickImage =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                saveWallpaper(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "主页壁纸"

        val container =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
        statusView = TextView(this)
        container.addView(statusView)
        previewView =
            ImageView(this).apply {
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(PREVIEW_HEIGHT_DP),
                    )
                scaleType = ImageView.ScaleType.CENTER_CROP
            }
        container.addView(previewView)
        val pickButton =
            Button(this).apply {
                text = "选择图片"
                setOnClickListener { pickImage.launch("image/*") }
            }
        container.addView(pickButton)
        clearButton =
            Button(this).apply {
                text = "清除壁纸"
                setOnClickListener { removeWallpaper() }
            }
        container.addView(clearButton)
        val hint =
            TextView(this).apply {
                text = "壁纸只用在主页，铺满屏幕、不随页面滚动。"
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, 0)
            }
        container.addView(hint)
        setContentView(container)
        refresh()
    }

    private fun refresh() {
        val file = Homepage.homepageWallpaperFile(this)
        val set = file.isFile && file.length() > 0
        statusView.text = if (set) "当前壁纸预览：" else "当前未设置壁纸"
        clearButton.isEnabled = set
        previewView.setImageBitmap(if (set) BitmapFactory.decodeFile(file.path) else null)
    }

    private fun saveWallpaper(uri: Uri) {
        lifecycleScope.launch {
            val saved =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = decodeSampled(uri) ?: error("undecodable image")
                        val file = Homepage.homepageWallpaperFile(this@HomepageWallpaperActivity)
                        file.parentFile?.mkdirs()
                        val temp = File(file.parentFile, "wallpaper.tmp")
                        FileOutputStream(temp).use { out ->
                            check(
                                bitmap.compress(
                                    Bitmap.CompressFormat.JPEG,
                                    JPEG_QUALITY,
                                    out,
                                ),
                            )
                        }
                        check(temp.renameTo(file))
                    }.isSuccess
                }
            if (saved) {
                Toast.makeText(this@HomepageWallpaperActivity, "壁纸已保存", Toast.LENGTH_SHORT)
                    .show()
                refresh()
            } else {
                Toast.makeText(
                        this@HomepageWallpaperActivity,
                        "这张图读不了，换一张试试",
                        Toast.LENGTH_LONG,
                    )
                    .show()
            }
        }
    }

    /** Decodes the picked image sampled down, so the stored copy's long edge stays bounded. */
    private fun decodeSampled(uri: Uri): Bitmap? {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SAMPLE_STEP_PX) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun removeWallpaper() {
        val file = Homepage.homepageWallpaperFile(this)
        if (file.exists()) {
            file.delete()
        }
        Toast.makeText(this, "壁纸已清除", Toast.LENGTH_SHORT).show()
        refresh()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val PREVIEW_HEIGHT_DP = 240

        /** Halving steps stop once the sampled long edge lands in [1024, 2048). */
        private const val SAMPLE_STEP_PX = 1024
        private const val JPEG_QUALITY = 88
    }
}
