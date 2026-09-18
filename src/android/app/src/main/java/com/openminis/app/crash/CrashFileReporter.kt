package com.openminis.app.crash

import android.content.Context
import org.acra.config.CoreConfiguration
import org.acra.data.CrashReportData
import org.acra.ReportField
import org.acra.sender.ReportSender
import org.acra.sender.ReportSenderFactory
import com.openminis.app.util.IsoTime
import java.io.File

/**
 * T283: Java/Kotlin crash → file. Writes a single text report into
 * `filesDir/logs/` using the same `.log` extension that
 * [com.openminis.app.logging.AppLogger.listLogFiles] already filters
 * for, so reports surface in LogManagementScreen without needing a
 * separate crash-files screen.
 *
 * Service-loader registered via
 * `META-INF/services/org.acra.sender.ReportSenderFactory`.
 *
 * No network. No HTTP sender. No external dependencies beyond
 * `acra-core`.
 */
class CrashFileSender : ReportSender {

    override fun send(context: Context, errorContent: CrashReportData) {
        val dir = File(context.filesDir, "logs").also { it.mkdirs() }
        val stamp = IsoTime.formatFileStamp()
        val out = File(dir, "crash-$stamp.log")

        val body = buildString {
            appendLine("=== Minis Java/Kotlin Crash ===")
            appendLine("Time: $stamp")
            appendLine("Version: ${errorContent.getString(ReportField.APP_VERSION_NAME)} " +
                "(${errorContent.getString(ReportField.APP_VERSION_CODE)})")
            appendLine("Android: ${errorContent.getString(ReportField.ANDROID_VERSION)} " +
                "(SDK ${errorContent.getString(ReportField.BUILD)})")
            appendLine("Device: ${errorContent.getString(ReportField.PHONE_MODEL)} " +
                "(${errorContent.getString(ReportField.BRAND)})")
            appendLine()
            appendLine("--- Stack Trace ---")
            appendLine(errorContent.getString(ReportField.STACK_TRACE))
            val logcat = errorContent.getString(ReportField.LOGCAT)
            if (!logcat.isNullOrBlank()) {
                appendLine()
                appendLine("--- Logcat (last 200 lines) ---")
                appendLine(logcat)
            }
        }
        out.writeText(body)
    }

}

/**
 * Service-loader factory. ACRA discovers and instantiates senders via
 * this factory at crash time on the dedicated `:acra` reporter process.
 */
class CrashFileSenderFactory : ReportSenderFactory {
    override fun create(context: Context, config: CoreConfiguration): ReportSender =
        CrashFileSender()

    override fun enabled(config: CoreConfiguration): Boolean = true
}
