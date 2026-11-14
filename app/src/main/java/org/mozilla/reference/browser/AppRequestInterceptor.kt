/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

@file:Suppress("ForbiddenComment")

package org.mozilla.reference.browser

import android.content.Context
import android.content.Intent
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import mozilla.components.browser.errorpages.ErrorPages
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.request.ErrorType
import mozilla.components.concept.engine.request.RequestInterceptor
import org.mozilla.reference.browser.bookmarks.BookmarksActivity
import org.mozilla.reference.browser.ext.components
import org.mozilla.reference.browser.qr.ScanActivity
import org.mozilla.reference.browser.tabs.PrivatePage

class AppRequestInterceptor(private val context: Context) : RequestInterceptor {
    override fun onLoadRequest(
        engineSession: EngineSession,
        uri: String,
        lastUri: String?,
        hasUserGesture: Boolean,
        isSameDomain: Boolean,
        isRedirect: Boolean,
        isDirectNavigation: Boolean,
        isSubframeRequest: Boolean,
    ): RequestInterceptor.InterceptionResponse? =
        when (uri) {
            // Via's hook URLs: the bookmarks page and (step 5) the QR scanner are native screens,
            // so the load itself is denied and the screen is opened instead.
            "via://bookmarks", "v://bookmarks" -> {
                val intent = Intent(context, BookmarksActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                RequestInterceptor.InterceptionResponse.Deny
            }

            "via://scanner", "v://scanner" -> {
                val intent = Intent(context, ScanActivity::class.java)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
                RequestInterceptor.InterceptionResponse.Deny
            }

            "about:privatebrowsing" -> {
                val page = PrivatePage.createPrivateBrowsingPage(context, uri)
                RequestInterceptor.InterceptionResponse.Content(page, encoding = "base64")
            }

            "about:crashes" -> {
                val intent = Intent(context, CrashListActivity::class.java)
                intent.addFlags(FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)

                RequestInterceptor.InterceptionResponse.Url("about:blank")
            }

            else ->
                context.components.services.appLinksInterceptor.onLoadRequest(
                    engineSession,
                    uri,
                    lastUri,
                    hasUserGesture,
                    isSameDomain,
                    isRedirect,
                    isDirectNavigation,
                    isSubframeRequest,
                )
        }

    override fun onErrorRequest(
        session: EngineSession,
        errorType: ErrorType,
        uri: String?,
    ): RequestInterceptor.ErrorResponse {
        val errorPage = ErrorPages.createUrlEncodedErrorPage(context, errorType, uri)
        return RequestInterceptor.ErrorResponse(errorPage)
    }

    override fun interceptsAppInitiatedRequests() = true
}
