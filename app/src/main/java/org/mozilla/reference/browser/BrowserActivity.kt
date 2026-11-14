/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.AttributeSet
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.snackbar.Snackbar.LENGTH_LONG
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.browser.state.state.WebExtensionState
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.intent.ext.EXTRA_SESSION_ID
import mozilla.components.lib.crash.Crash
import mozilla.components.support.base.feature.ActivityResultHandler
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.support.base.log.logger.Logger
import mozilla.components.support.ktx.android.view.setupPersistentInsets
import mozilla.components.support.utils.SafeIntent
import mozilla.components.support.webextensions.WebExtensionPopupObserver
import org.mozilla.reference.browser.addons.WebExtensionActionPopupActivity
import org.mozilla.reference.browser.browser.BrowserFragment
import org.mozilla.reference.browser.browser.CrashIntegration
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.qr.ScanResultHolder

/** Activity that holds the [BrowserFragment]. */
open class BrowserActivity : AppCompatActivity() {
    private lateinit var crashIntegration: CrashIntegration

    private val sessionId: String?
        get() = SafeIntent(intent).getStringExtra(EXTRA_SESSION_ID)

    private val webExtensionPopupObserver by lazy {
        WebExtensionPopupObserver(components.core.store, ::openPopup)
    }

    /** Returns a new instance of [BrowserFragment] to display. */
    open fun createBrowserFragment(sessionId: String?): Fragment = BrowserFragment.create(sessionId)

    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_main)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT))
        window.setupPersistentInsets()

        components.notificationsDelegate.bindToActivity(this)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().apply {
                replace(R.id.container, createBrowserFragment(sessionId))
                commit()
            }
        }

        crashIntegration =
            CrashIntegration(this, components.analytics.crashReporter) { crash ->
                onNonFatalCrash(crash)
            }
        lifecycle.addObserver(crashIntegration)

        NotificationManager.checkAndNotifyPolicy(this)
        lifecycle.addObserver(webExtensionPopupObserver)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    supportFragmentManager.fragments.forEach {
                        if (it is UserInteractionHandler && it.onBackPressed()) {
                            return
                        }
                    }
                }
            },
        )
    }

    @Suppress("DEPRECATION") // ComponentActivity wants us to use registerForActivityResult
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        Logger.info(
            "Activity onActivityResult received with " +
                "requestCode: $requestCode, resultCode: $resultCode, data: $data",
        )

        supportFragmentManager.fragments.forEach {
            if (it is ActivityResultHandler && it.onActivityResult(requestCode, data, resultCode)) {
                return
            }
        }

        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onResume() {
        super.onResume()
        // A finished QR scan parks its result here (the scanner is launched from the request
        // interceptor, which has no activity-result channel); consume it exactly once.
        ScanResultHolder.pendingResult?.let { result ->
            ScanResultHolder.pendingResult = null
            openScanResult(result)
        }
    }

    /** Opens a scan result: URLs navigate directly, anything else goes to the default search. */
    private fun openScanResult(result: String) {
        val text = result.trim()
        if (text.isEmpty()) {
            return
        }
        val url =
            when {
                text.matches(Regex("^[a-zA-Z][a-zA-Z0-9+.\\-]*://.*")) -> text
                !text.contains(Regex("\\s")) && text.matches(Regex("^[^\\s/]+\\.[a-zA-Z]{2,}([/?#].*)?$")) ->
                    "https://$text"
                else -> null
            }
        if (url != null) {
            if (components.core.store.state.selectedTab != null) {
                components.useCases.sessionUseCases.loadUrl.invoke(url)
            } else {
                components.useCases.tabsUseCases.addTab.invoke(url, selectTab = true)
            }
        } else {
            components.useCases.searchUseCases.defaultSearch.invoke(
                searchTerms = text,
                searchEngine = null,
                parentSessionId = null,
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        components.notificationsDelegate.unBindActivity(this)
    }

    override fun onUserLeaveHint() {
        supportFragmentManager.fragments.forEach {
            if (it is UserInteractionHandler && it.onHomePressed()) {
                return
            }
        }

        super.onUserLeaveHint()
    }

    override fun onCreateView(
        parent: View?,
        name: String,
        context: Context,
        attrs: AttributeSet,
    ): View? =
        when (name) {
            EngineView::class.java.name -> {
                components.core.engine.createView(context, attrs).asView()
            }

            else -> {
                super.onCreateView(parent, name, context, attrs)
            }
        }

    private fun onNonFatalCrash(crash: Crash) {
        Snackbar.make(findViewById(android.R.id.content), R.string.crash_report_non_fatal_message, LENGTH_LONG)
            .setAction(R.string.crash_report_non_fatal_action) {
                crashIntegration.sendCrashReport(crash)
            }
            .show()
    }

    private fun openPopup(webExtensionState: WebExtensionState) {
        val intent = Intent(this, WebExtensionActionPopupActivity::class.java)
        intent.putExtra("web_extension_id", webExtensionState.id)
        intent.putExtra("web_extension_name", webExtensionState.name)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
    }
}
