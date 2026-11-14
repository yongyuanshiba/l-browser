/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.qr

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.commit
import mozilla.components.feature.qr.QrFragment
import mozilla.components.feature.qr.QrFragmentFactory
import org.mozilla.reference.browser.R

/**
 * Hands a scan result from [ScanActivity] back to the browser. The scan is launched from the
 * request interceptor (no activity-result caller exists), so the result waits here until
 * [org.mozilla.reference.browser.BrowserActivity] resumes and picks it up.
 */
object ScanResultHolder {
    @Volatile
    var pendingResult: String? = null
}

/**
 * The `via://scanner` screen: hosts AC's [QrFragment] the way its own QrScanActivity does, with
 * Chinese prompts. A non-empty result is parked in [ScanResultHolder] for the browser activity;
 * backing out or an empty result simply closes the screen and leaves nothing behind.
 */
class ScanActivity : AppCompatActivity() {
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                launchQrFragment()
            } else {
                Toast.makeText(this, "需要相机权限才能扫码", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        supportFragmentManager.fragmentFactory = QrFragmentFactory { R.string.scan_qr_hint }

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        supportFragmentManager.setFragmentResultListener(
            QrFragment.RESULT_REQUEST_KEY,
            this,
        ) { requestKey, bundle ->
            if (requestKey == QrFragment.RESULT_REQUEST_KEY) {
                bundle.getString(QrFragment.RESULT_BUNDLE_KEY)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { ScanResultHolder.pendingResult = it }
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkCameraPermission()
    }

    private fun launchQrFragment() {
        if (supportFragmentManager.findFragmentById(R.id.scan_fragment_container) != null) {
            return
        }
        supportFragmentManager.commit {
            add(R.id.scan_fragment_container, QrFragment::class.java, null)
        }
    }

    private fun checkCameraPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED -> launchQrFragment()

            else -> requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
}
