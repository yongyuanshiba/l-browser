/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.browser

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import mozilla.components.browser.thumbnails.BrowserThumbnails
import mozilla.components.browser.toolbar.BrowserToolbar
import mozilla.components.concept.awesomebar.AwesomeBar.Suggestion
import mozilla.components.concept.engine.EngineView
import mozilla.components.feature.awesomebar.AwesomeBarFeature
import mozilla.components.feature.awesomebar.provider.SearchSuggestionProvider
import mozilla.components.feature.readerview.view.ReaderViewControlsBar
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.feature.tabs.WindowFeature
import mozilla.components.feature.tabs.toolbar.TabsToolbarFeature
import mozilla.components.feature.toolbar.WebExtensionToolbarFeature
import mozilla.components.support.base.feature.UserInteractionHandler
import mozilla.components.lib.state.ext.observe
import mozilla.components.support.base.feature.ViewBoundFeatureWrapper
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.ext.requireComponents
import org.mozilla.reference.browser.qr.ScanActivity
import org.mozilla.reference.browser.search.AwesomeBarWrapper
import org.mozilla.reference.browser.settings.Settings
import org.mozilla.reference.browser.tabs.HomepageExtension
import org.mozilla.reference.browser.tabs.TabsTrayFragment

/** Fragment used for browsing the web within the main app. */
class BrowserFragment : BaseBrowserFragment(), UserInteractionHandler {
    private val thumbnailsFeature = ViewBoundFeatureWrapper<BrowserThumbnails>()
    private val readerViewFeature = ViewBoundFeatureWrapper<ReaderViewIntegration>()
    private val webExtToolbarFeature = ViewBoundFeatureWrapper<WebExtensionToolbarFeature>()
    private val windowFeature = ViewBoundFeatureWrapper<WindowFeature>()

    private val awesomeBar: AwesomeBarWrapper
        get() = requireView().findViewById(R.id.awesomeBar)

    private val toolbar: BrowserToolbar
        get() = requireView().findViewById(R.id.toolbar)

    private val engineView: EngineView
        get() = requireView().findViewById<View>(R.id.engineView) as EngineView

    private val readerViewBar: ReaderViewControlsBar
        get() = requireView().findViewById(R.id.readerViewBar)

    private val readerViewAppearanceButton: FloatingActionButton
        get() = requireView().findViewById(R.id.readerViewAppearanceButton)

    override val shouldUseComposeUI: Boolean
        get() =
            PreferenceManager.getDefaultSharedPreferences(requireContext())
                .getBoolean(
                    getString(R.string.pref_key_compose_ui),
                    false,
                )

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)
        setupAwesomeBar()
        setupHomepageHeader(view)

        TabsToolbarFeature(
            toolbar = toolbar,
            sessionId = sessionId,
            store = requireComponents.core.store,
            showTabs = ::showTabs,
            lifecycleOwner = this,
        )

        thumbnailsFeature.bind(
            view,
            BrowserThumbnails(
                requireContext(),
                engineView,
                requireComponents.core.store,
            ),
        )

        readerViewFeature.bind(
            view,
            ReaderViewIntegration(
                requireContext(),
                requireComponents.core.engine,
                requireComponents.core.store,
                toolbar,
                readerViewBar,
                readerViewAppearanceButton,
            ),
        )

        webExtToolbarFeature.bind(
            view,
            WebExtensionToolbarFeature(
                toolbar,
                requireContext().components.core.store,
            ),
        )

        windowFeature.bind(
            view,
            WindowFeature(
                store = requireComponents.core.store,
                tabsUseCases = requireComponents.useCases.tabsUseCases,
            ),
        )

        engineView.setDynamicToolbarMaxHeight(resources.getDimensionPixelSize(R.dimen.browser_toolbar_height))
    }

    /** Last (visible, title) pair applied to the homepage header, so state emissions stay cheap. */
    private var homepageHeaderState: Pair<Boolean, String>? = null

    private fun setupHomepageHeader(view: View) {
        view.findViewById<ImageButton>(R.id.homepageHeaderScan).setOnClickListener {
            startActivity(Intent(requireContext(), ScanActivity::class.java))
        }
        requireComponents.core.store.observe(viewLifecycleOwner) { state ->
            val tab = state.selectedTab
            updateHomepageHeader(tab?.content?.url, tab?.content?.title)
        }
        val tab = requireComponents.core.store.state.selectedTab
        updateHomepageHeader(tab?.content?.url, tab?.content?.title)
    }

    override fun onResume() {
        super.onResume()
        // The header's switch lives in settings; re-read the state when coming back.
        val tab = requireComponents.core.store.state.selectedTab
        updateHomepageHeader(tab?.content?.url, tab?.content?.title)
    }

    /**
     * The native homepage header. The environment the user's homepage code comes from shows
     * one above its homepage — page title on the left, a scan entry on the right — as an
     * app-layer bar the page itself has no element for, so L provides it natively. It appears
     * only while the selected tab shows the homepage and the settings switch is on, and it
     * pushes the web content down instead of covering it.
     */
    private fun updateHomepageHeader(
        url: String?,
        title: String?,
    ) {
        val view = view ?: return
        val pageUrl = HomepageExtension.pageUrl
        val show =
            url != null && pageUrl != null && url == pageUrl &&
                Settings.isHomepageHeaderEnabled(requireContext())
        val text =
            title?.takeIf { it.isNotBlank() }
                ?: getString(R.string.homepage_header_default_title)
        val applied = show to text
        if (homepageHeaderState == applied) {
            return
        }
        homepageHeaderState = applied
        val header = view.findViewById<LinearLayout>(R.id.homepageHeader) ?: return
        header.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            view.findViewById<TextView>(R.id.homepageHeaderTitle)?.text = text
        }
        val swipeRefresh = view.findViewById<View>(R.id.swipeRefresh) ?: return
        val params = swipeRefresh.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val target = if (show) header.layoutParams.height else 0
        if (params.topMargin != target) {
            params.topMargin = target
            swipeRefresh.layoutParams = params
        }
    }

    private fun setupAwesomeBar() {
        AwesomeBarFeature(awesomeBar, toolbar, engineView)
            .addSearchProvider(
                requireComponents.core.store,
                requireComponents.useCases.searchUseCases.defaultSearch,
                fetchClient = requireComponents.core.client,
                mode = SearchSuggestionProvider.Mode.MULTIPLE_SUGGESTIONS,
                engine = requireComponents.core.engine,
                limit = 5,
                filterExactMatch = true,
            )
            .addSessionProvider(
                resources,
                requireComponents.core.store,
                requireComponents.useCases.tabsUseCases.selectTab,
            )
            .addHistoryProvider(
                requireComponents.core.historyStorage,
                requireComponents.useCases.sessionUseCases.loadUrl,
            )
            .addClipboardProvider(requireContext(), requireComponents.useCases.sessionUseCases.loadUrl)

        awesomeBar.setOnRemoveSuggestionButtonClicked {
            awesomeBar.addHiddenSuggestion(it)
            (it.suggestion as? Suggestion)?.let { s -> deleteHistorySuggestion(s) }
        }
    }

    private fun showTabs() {
        // For now we are performing manual fragment transactions here. Once we can use the new
        // navigation support library we may want to pass navigation graphs around.
        activity?.supportFragmentManager?.beginTransaction()?.apply {
            replace(R.id.container, TabsTrayFragment())
            commit()
        }
    }

    private fun deleteHistorySuggestion(suggestion: Suggestion) {
        lifecycleScope.launch(Dispatchers.IO) {
            suggestion.description?.let {
                requireComponents.core.historyStorage.deleteHistoryMetadataForUrl(it)
            }
        }
    }

    override fun onBackPressed(): Boolean = readerViewFeature.onBackPressed() || super.onBackPressed()

    companion object {
        /**
         * Fragments must keep a no-argument constructor so the system can recreate them, which is why the session id
         * travels in the arguments bundle instead of a constructor parameter.
         */
        fun create(sessionId: String? = null) =
            BrowserFragment().apply {
                arguments =
                    Bundle().apply {
                        putSessionId(sessionId)
                    }
            }
    }
}
