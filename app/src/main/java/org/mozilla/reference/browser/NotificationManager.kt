/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager as AndroidNotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.preference.PreferenceManager
import mozilla.components.support.base.log.logger.Logger
import org.mozilla.reference.browser.IntentRequestCodes.REQUEST_CODE_DATA_REPORTING

/** Manages notification channels and allows displaying different supported types of notifications. */
object NotificationManager {
    // Data Reporting
    private const val PRIVACY_NOTICE_URL = "https://www.mozilla.org/en-US/privacy/firefox/"
    private const val DATA_REPORTING_VERSION = 1
    private const val DATA_REPORTING_TAG = "org.mozilla.reference.browser.DataReporting"
    private const val DATA_REPORTING_NOTIFICATION_ID = 1
    private const val PREFS_POLICY_VERSION = "datareporting.policy.dataSubmissionPolicyVersion"
    private const val PREFS_POLICY_NOTIFIED_TIME = "datareporting.policy.dataSubmissionPolicyNotifiedTime"

    // Default
    private const val NOTIFICATION_CHANNEL_ID = "default-notification-channel"

    private val logger = Logger("NotificationManager")

    /**
     * [BrowserActivity] calls this from onCreate; the policy version stored after notifying limits the data policy
     * notice to the first run.
     */
    fun checkAndNotifyPolicy(context: Context) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(context)
        val currentVersion = preferences.getInt(PREFS_POLICY_VERSION, -1)

        if (currentVersion < 1) {
            // This is a first run, so notify user about data policy.
            notifyDataPolicy(context, preferences)
        }
    }

    /** Launch a notification of the data policy, and record notification time and version. */
    @SuppressLint("MissingPermission", "NotifyUsage")
    private fun notifyDataPolicy(
        context: Context,
        preferences: SharedPreferences,
    ) {
        val resources = context.resources

        val notificationTitle = resources.getString(R.string.datareporting_notification_title)
        val notificationSummary = resources.getString(R.string.datareporting_notification_summary)

        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                data = PRIVACY_NOTICE_URL.toUri()
                setPackage(context.packageName)
            }

        val flags =
            if (SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_IMMUTABLE
            } else {
                0
            }

        val pendingIntent = PendingIntent.getActivity(context, REQUEST_CODE_DATA_REPORTING, intent, flags)

        val notificationBuilder =
            NotificationCompat.Builder(
                    context,
                    getNotificationChannelId(context),
                )
                .apply {
                    setContentTitle(notificationTitle)
                    setContentText(notificationSummary)
                    setSmallIcon(R.drawable.ic_notification)
                    setAutoCancel(true)
                    setContentIntent(pendingIntent)
                    setStyle(NotificationCompat.BigTextStyle().bigText(notificationSummary))
                }

        NotificationManagerCompat.from(context)
            .notify(DATA_REPORTING_TAG, DATA_REPORTING_NOTIFICATION_ID, notificationBuilder.build())

        preferences.edit {
            putLong(PREFS_POLICY_NOTIFIED_TIME, System.currentTimeMillis())
            putInt(PREFS_POLICY_VERSION, DATA_REPORTING_VERSION)
        }
    }

    private fun getNotificationChannelId(
        context: Context,
        channelId: String = NOTIFICATION_CHANNEL_ID,
        channelName: String = context.resources.getString(R.string.default_notification_channel),
        description: String? = null,
        channelImportance: Int? = null,
    ): String {
        val importance = channelImportance ?: AndroidNotificationManager.IMPORTANCE_DEFAULT
        createNotificationChannelIfNeeded(
            context,
            channelId,
            channelName,
            description,
            importance,
        )

        return channelId
    }

    private fun createNotificationChannelIfNeeded(
        context: Context,
        channelId: String,
        channelName: String,
        channelDescription: String?,
        importance: Int = AndroidNotificationManager.IMPORTANCE_DEFAULT,
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as AndroidNotificationManager

        if (null != notificationManager.getNotificationChannel(channelId)) {
            return
        }

        val channel =
            NotificationChannel(
                    channelId,
                    channelName,
                    importance,
                )
                .apply {
                    description = channelDescription
                }

        notificationManager.createNotificationChannel(channel)
    }
}
