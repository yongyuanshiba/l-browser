/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.reference.browser.components

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import mozilla.components.concept.base.crash.Breadcrumb
import mozilla.components.lib.crash.Crash
import mozilla.components.lib.crash.service.CrashReporterService

/**
 * A [CrashReporterService] that never sends anything anywhere: for every reported crash it only
 * writes a plain-text record (time, exception type, stack trace) into the app-private
 * `crash-reports` directory and keeps everything on-device.
 *
 * This service has to exist because lib-crash requires at least one registered service —
 * `CrashReporter` refuses to be constructed with an empty service list and throws
 * `IllegalArgumentException: No crash reporter services defined`. The crash list shown in
 * `about:crashes` is backed by lib-crash's own local database, so it keeps working with this
 * local-only service attached; [createCrashReportUrl] always returns null because on-device
 * records have no web page.
 */
class LocalCrashReporterService(context: Context) : CrashReporterService {
    private val appContext: Context = context.applicationContext

    override val id: String = ID

    override val name: String = NAME

    override fun createCrashReportUrl(identifier: String): String? = null

    override fun report(crash: Crash.UncaughtExceptionCrash): String? {
        writeRecord(crash.uuid) {
            appendLine("Type: uncaught exception")
            appendLine("Time: ${formatTime(crash.timestamp)} (${crash.timestamp})")
            appendRuntimeTags(crash.runtimeTags)
            appendLine()
            appendLine("Throwable: ${crash.throwable}")
            appendLine()
            appendLine(stackTraceOf(crash.throwable))
            appendBreadcrumbs(crash.breadcrumbs)
        }
        return crash.uuid
    }

    override fun report(crash: Crash.NativeCodeCrash): String? {
        writeRecord(crash.uuid) {
            appendLine("Type: native code crash")
            appendLine("Time: ${formatTime(crash.timestamp)} (${crash.timestamp})")
            appendLine("Process type: ${crash.processType}")
            appendLine("Process visibility: ${crash.processVisibility}")
            appendLine("Remote type: ${crash.remoteType}")
            appendLine("Fatal: ${crash.isFatal}")
            appendLine("Minidump path: ${crash.minidumpPath}")
            appendLine("Extras path: ${crash.extrasPath}")
            appendRuntimeTags(crash.runtimeTags)
            appendBreadcrumbs(crash.breadcrumbs)
        }
        return crash.uuid
    }

    override fun report(throwable: Throwable, breadcrumbs: ArrayList<Breadcrumb>): String? {
        val identifier = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        writeRecord(identifier) {
            appendLine("Type: caught exception")
            appendLine("Time: ${formatTime(now)} ($now)")
            appendLine()
            appendLine("Throwable: $throwable")
            appendLine()
            appendLine(stackTraceOf(throwable))
            appendBreadcrumbs(breadcrumbs)
        }
        return identifier
    }

    /** Writes one record file. A crash reporter must never crash the app, so failures are only logged. */
    private fun writeRecord(identifier: String, content: StringBuilder.() -> Unit) {
        try {
            val text = buildString {
                appendLine("Crash report id: $identifier")
                appendLine("Recorded by: $NAME (on-device only, never sent anywhere)")
                appendLine()
                append(StringBuilder().apply(content))
            }
            val directory = File(appContext.filesDir, DIRECTORY_NAME).apply { mkdirs() }
            File(directory, "$identifier.txt").writeText(text)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write local crash record $identifier", e)
        }
    }

    private fun StringBuilder.appendRuntimeTags(runtimeTags: Map<String, String>) {
        if (runtimeTags.isEmpty()) {
            return
        }
        appendLine()
        appendLine("Runtime tags:")
        runtimeTags.toSortedMap().forEach { (key, value) -> appendLine("  $key: $value") }
    }

    private fun StringBuilder.appendBreadcrumbs(breadcrumbs: List<Breadcrumb>) {
        if (breadcrumbs.isEmpty()) {
            return
        }
        appendLine()
        appendLine("Breadcrumbs:")
        breadcrumbs.forEach { breadcrumb ->
            appendLine("  [${formatTime(breadcrumb.date.time)}] ${breadcrumb.category} - ${breadcrumb.message}")
        }
    }

    private fun stackTraceOf(throwable: Throwable): String {
        val writer = StringWriter()
        PrintWriter(writer).use { throwable.printStackTrace(it) }
        return writer.toString().trimEnd()
    }

    private fun formatTime(timestampMillis: Long): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date(timestampMillis))
    }

    companion object {
        /** Stable service id, persisted with each crash record in the lib-crash database. */
        const val ID = "local"

        /** Human-readable service name, displayed next to records in the crash list UI. */
        const val NAME = "Local crash log"

        private const val TAG = "LocalCrashReporter"
        private const val DIRECTORY_NAME = "crash-reports"
    }
}
