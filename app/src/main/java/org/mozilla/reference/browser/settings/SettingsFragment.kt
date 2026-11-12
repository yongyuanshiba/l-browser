/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.settings

import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.Preference.OnPreferenceChangeListener
import androidx.preference.Preference.OnPreferenceClickListener
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlin.system.exitProcess
import mozilla.components.support.ktx.android.view.showKeyboard
import org.mozilla.reference.browser.DnsSettings
import org.mozilla.reference.browser.EngineProvider
import org.mozilla.reference.browser.R
import org.mozilla.reference.browser.R.string.pref_key_about_page
import org.mozilla.reference.browser.R.string.pref_key_dns_mode
import org.mozilla.reference.browser.R.string.pref_key_dns_status
import org.mozilla.reference.browser.R.string.pref_key_make_default_browser
import org.mozilla.reference.browser.R.string.pref_key_override_amo_collection
import org.mozilla.reference.browser.R.string.pref_key_privacy
import org.mozilla.reference.browser.R.string.pref_key_remote_debugging
import org.mozilla.reference.browser.autofill.AutofillPreference
import org.mozilla.reference.browser.ext.getPreferenceKey
import org.mozilla.reference.browser.ext.requireComponents

private typealias RBSettings = org.mozilla.reference.browser.settings.Settings

/** Top-level settings page. */
class SettingsFragment : PreferenceFragmentCompat() {
    /** Implemented by the hosting activity, which owns the action bar. */
    interface ActionBarUpdater {
        /** Called on navigation because the fragments are swapped in one container and the title does not follow. */
        fun updateTitle(titleResId: Int)
    }

    override fun onCreatePreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
    }

    override fun onResume() {
        super.onResume()

        setupPreferences()
        getActionBarUpdater().apply {
            updateTitle(R.string.settings)
        }
    }

    private fun setupPreferences() {
        val makeDefaultBrowserKey = requireContext().getPreferenceKey(pref_key_make_default_browser)
        val remoteDebuggingKey = requireContext().getPreferenceKey(pref_key_remote_debugging)
        val aboutPageKey = requireContext().getPreferenceKey(pref_key_about_page)
        val privacyKey = requireContext().getPreferenceKey(pref_key_privacy)
        val customAddonsKey = requireContext().getPreferenceKey(pref_key_override_amo_collection)
        val autofillPreferenceKey = requireContext().getPreferenceKey(R.string.pref_key_autofill)
        val dnsModeKey = requireContext().getPreferenceKey(pref_key_dns_mode)
        val dnsStatusKey = requireContext().getPreferenceKey(pref_key_dns_status)

        val preferenceMakeDefaultBrowser = findPreference<Preference>(makeDefaultBrowserKey)
        val preferenceRemoteDebugging = findPreference<SwitchPreferenceCompat>(remoteDebuggingKey)
        val preferenceAboutPage = findPreference<Preference>(aboutPageKey)
        val preferencePrivacy = findPreference<Preference>(privacyKey)
        val preferenceCustomAddons = findPreference<Preference>(customAddonsKey)
        val preferenceAutofill = findPreference<AutofillPreference>(autofillPreferenceKey)
        val preferenceDnsMode = findPreference<ListPreference>(dnsModeKey)
        val preferenceDnsStatus = findPreference<Preference>(dnsStatusKey)

        if (!AutofillPreference.isSupported(requireContext())) {
            preferenceAutofill?.isVisible = false
        } else {
            (preferenceAutofill as AutofillPreference).updateSwitch()
        }

        preferenceMakeDefaultBrowser?.onPreferenceClickListener = getClickListenerForMakeDefaultBrowser()
        preferenceRemoteDebugging?.onPreferenceChangeListener = getChangeListenerForRemoteDebugging()
        preferenceDnsMode?.onPreferenceChangeListener = getChangeListenerForDnsMode(preferenceDnsMode, preferenceDnsStatus)
        updateDnsPreferences(preferenceDnsMode, preferenceDnsStatus, DnsSettings.currentMode(requireContext()))
        preferenceAboutPage?.onPreferenceClickListener = getAboutPageListener()
        preferencePrivacy?.onPreferenceClickListener = getClickListenerForPrivacy()
        preferenceCustomAddons?.onPreferenceClickListener = getClickListenerForCustomAddons()
    }

    private fun getClickListenerForMakeDefaultBrowser(): OnPreferenceClickListener = OnPreferenceClickListener {
        val intent = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        startActivity(intent)
        true
    }

    private fun getClickListenerForPrivacy(): OnPreferenceClickListener = OnPreferenceClickListener {
        parentFragmentManager
            .beginTransaction()
            .replace(R.id.container, PrivacySettingsFragment())
            .addToBackStack(null)
            .commit()
        getActionBarUpdater().apply {
            updateTitle(R.string.privacy_settings)
        }
        true
    }

    private fun getChangeListenerForRemoteDebugging(): OnPreferenceChangeListener =
        OnPreferenceChangeListener { _, newValue ->
            requireComponents.core.engine.settings.remoteDebuggingEnabled = newValue as Boolean
            true
        }

    private fun getChangeListenerForDnsMode(
        preferenceDnsMode: ListPreference?,
        preferenceDnsStatus: Preference?,
    ): OnPreferenceChangeListener =
        OnPreferenceChangeListener { _, newValue ->
            val mode = newValue as String
            EngineProvider.reapplyDnsSettings(requireContext(), mode)
            updateDnsPreferences(preferenceDnsMode, preferenceDnsStatus, mode)
            true
        }

    /** Shows the chosen entry and the status line matching [mode] under the DNS resolution preference. */
    private fun updateDnsPreferences(
        preferenceDnsMode: ListPreference?,
        preferenceDnsStatus: Preference?,
        mode: String,
    ) {
        if (preferenceDnsMode != null) {
            val index = preferenceDnsMode.findIndexOfValue(mode)
            if (index >= 0) {
                preferenceDnsMode.summary = preferenceDnsMode.entries[index]
            }
        }
        preferenceDnsStatus?.summary = getString(
            if (DnsSettings.isSystemMode(mode)) {
                R.string.preferences_dns_status_system
            } else {
                R.string.preferences_dns_status_browser_default
            },
        )
    }

    private fun getAboutPageListener(): OnPreferenceClickListener = OnPreferenceClickListener {
        parentFragmentManager.beginTransaction().replace(R.id.container, AboutFragment()).addToBackStack(null).commit()
        true
    }

    private fun getActionBarUpdater() = activity as ActionBarUpdater

    private fun getClickListenerForCustomAddons(): OnPreferenceClickListener = OnPreferenceClickListener {
        val context = requireContext()
        val dialogView = View.inflate(context, R.layout.amo_collection_override_dialog, null)
        val userView = dialogView.findViewById<EditText>(R.id.custom_amo_user)
        val collectionView = dialogView.findViewById<EditText>(R.id.custom_amo_collection)

        AlertDialog.Builder(context)
            .apply {
                setTitle(context.getString(R.string.preferences_customize_amo_collection))
                setView(dialogView)
                setNegativeButton(R.string.customize_addon_collection_cancel) { dialog: DialogInterface, _ ->
                    dialog.cancel()
                }

                setPositiveButton(R.string.customize_addon_collection_ok) { _, _ ->
                    RBSettings.setOverrideAmoUser(context, userView.text.toString())
                    RBSettings.setOverrideAmoCollection(context, collectionView.text.toString())

                    Toast.makeText(
                            context,
                            getString(R.string.toast_customize_addon_collection_done),
                            Toast.LENGTH_LONG,
                        )
                        .show()

                    Handler(Looper.getMainLooper())
                        .postDelayed(
                            {
                                exitProcess(0)
                            },
                            AMO_COLLECTION_OVERRIDE_EXIT_DELAY,
                        )
                }

                collectionView.setText(RBSettings.getOverrideAmoCollection(context))
                userView.setText(RBSettings.getOverrideAmoUser(context))
                userView.requestFocus()
                userView.showKeyboard()
                create()
            }
            .show()
        true
    }

    companion object {
        private const val AMO_COLLECTION_OVERRIDE_EXIT_DELAY = 3000L
    }
}
