package com.openminis.app.logging

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileWriter
import java.io.OutputStream
import java.io.PrintStream
import java.io.PrintWriter
import com.openminis.app.text.BoundedText
import com.openminis.app.util.IsoTime

/**
 * Daily-rotating file logger that mirrors iOS LoggingManager.
 * Writes log entries to files named yyyy-MM-dd.log in app's files/logs/ directory.
 * Retains logs for 15 days by default.
 */
object AppLogger {

    private const val TAG = "AppLogger"
    private const val LOG_DIR = "logs"
    // [T-android-log-retention-15d] 15 days, matching iOS logRetentionDays
    // (d83bc894). The previous 14 came from the March parity-scaffolding
    // batch with no recorded rationale — plain historical drift, not intent.
    private const val MAX_AGE_DAYS = 15

    private const val PREF_NAME = "logging_prefs"
    private const val KEY_ENABLED = "logging_enabled"

    /**
     * [T-android-log-level] Detail level, mirroring iOS's Info / Verbose
     * picker (0f60a5d78). Android had no level at all: with logging on, every
     * AppLogger.debug line AND every Log.d/Log.v from the framework and
     * libraries (logcat tail at `*:V`) went to the file. Info, the default,
     * drops both; Verbose restores the previous everything-mode.
     */
    private const val KEY_VERBOSE = "logging_verbose"
    @Volatile
    private var verbose: Boolean = false

    /**
     * [T-android-log-size-cap] Per-file cap, mirroring iOS maxLogFileSize
     * (500 MB on both since T-log-cap-500mb). Android had none, so one busy
     * day could grow a single daily log without bound. Past the cap the file
     * is cut to its newest half.
     */
    // `var` only so tests can shrink them instead of writing 100 MB to disk.
    @androidx.annotation.VisibleForTesting
    internal var MAX_LOG_FILE_BYTES = 500L * 1024 * 1024
    @androidx.annotation.VisibleForTesting
    internal var SIZE_CHECK_EVERY_BYTES = 1L * 1024 * 1024
    private var bytesSinceSizeCheck = 0L

    private var logDir: File? = null

    /**
     * T-android-safemode-lateinit-crash: application context captured as
     * early as possible so the read-only helpers below can find
     * `filesDir/logs` even when [init] never ran.
     *
     * [init] is called from MinisApp.onCreate AFTER the safe-mode
     * early-return, so on a launch following a crash burst [logDir] stays
     * null — and every reader keyed off it ([listLogFiles],
     * [listLogFileMetas], [readLog], [totalSize]) reported "no logs".
     * That is precisely the launch on which the user goes looking for the
     * crash records, which is why they saw an empty list while the files
     * were sitting on disk the whole time. The crash writers were never
     * affected: CrashFileSender and NativeCrashHandler each build
     * `filesDir/logs` from their own Context.
     */
    @Volatile
    private var appContext: Context? = null

    /**
     * Capture the context for [resolveLogDir] without doing any of
     * [init]'s side effects (no mkdirs, no prefs read, no stdout capture,
     * no pruning). Safe to call from the very top of Application.onCreate,
     * before any safe-mode decision is made.
     */
    fun primeContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /**
     * Log directory for READ paths. Prefers the [init]-assigned [logDir];
     * falls back to deriving it from [appContext]. Returns null only when
     * neither is available, and never creates the directory — readers
     * treat a missing directory as "no logs", which is correct.
     */
    private fun resolveLogDir(): File? =
        logDir ?: appContext?.let { File(it.filesDir, LOG_DIR) }

    private var currentDate: String = ""
    private var writer: PrintWriter? = null
    private var currentFile: File? = null
    private var logFileCapped: Boolean = false
    private var enabled: Boolean = false

    // Saved references to the JVM's original stdout/stderr. Captured on the
    // first startCapture() so stopCapture() can restore them — without this we
    // would never be able to detach our PrintStream wrapper, and the redirection
    // would survive the toggle being flipped off.
    private var originalOut: PrintStream? = null
    private var originalErr: PrintStream? = null
    private var captureActive: Boolean = false

    // Logcat tail child process — captures Log.d/i/w/e/v from the framework,
    // third-party libraries, and project code that calls android.util.Log
    // directly. Without this only stdout/stderr (println, stack traces) end
    // up in the file, which is < 1% of the actual log volume on Android.
    private var logcatTailer: LogcatTailer? = null

    /**
     * Initialize the logger with app context. Call once from Application.onCreate().
     * If logging was previously enabled (persisted in SharedPreferences),
     * automatically begins capturing stdout/stderr — mirrors iOS
     * `LoggingManager.startIfEnabled()`.
     */
    fun init(context: Context) {
        primeContext(context)
        logDir = File(context.filesDir, LOG_DIR).also { it.mkdirs() }
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_ENABLED, false)
        verbose = prefs.getBoolean(KEY_VERBOSE, false)
        pruneOldLogs()
        if (enabled) startCapture()
    }

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun isVerbose(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERBOSE, false)

    /** [T-android-log-level] Takes effect immediately, including the logcat filter. */
    @Synchronized
    fun setVerbose(context: Context, value: Boolean) {
        verbose = value
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VERBOSE, value).apply()
        if (captureActive) {
            logcatTailer?.stop()
            logcatTailer = newLogcatTailer().also { it.start() }
        }
    }

    private fun newLogcatTailer() =
        LogcatTailer(minPriority = if (verbose) 'V' else 'I') { line -> writeLogcatLine(line) }

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        if (value) startCapture() else stopCapture()
    }

    /**
     * Redirect [System.out] and [System.err] through line-buffered writers
     * that prepend a timestamp to each line and append it to today's log file
     * before forwarding the original bytes to the previous stream. The forward
     * is essential — without it, anything written through stdout (println,
     * Throwable.printStackTrace, third-party libs that write to System.err)
     * would silently disappear from logcat.
     *
     * `Log.d/i/w/e` calls go through the Android native logging bridge and
     * are NOT captured by this redirection — only stdout/stderr.
     */
    @Synchronized
    private fun startCapture() {
        if (captureActive) return
        if (logDir == null) return // init() not called yet
        if (originalOut == null) originalOut = System.out
        if (originalErr == null) originalErr = System.err
        System.setOut(PrintStream(LineCapturingStream(originalOut!!, "STDOUT"), true))
        System.setErr(PrintStream(LineCapturingStream(originalErr!!, "STDERR"), true))
        // Spawn the logcat tail before emitting the session-start marker so
        // the marker itself shows up in the captured stream as a sanity check.
        logcatTailer = newLogcatTailer().also { it.start() }
        captureActive = true
        info("AppLogger", "Logging session started — capturing stdout/stderr + logcat tail")
        // [T-android-mem-probe-trust] Device/ROM/heap identity, immediately
        // after the session marker. The 2026-08-15 field log carried none of
        // this: the hardware had to be guessed from an incidental
        // `/proc/vivo_rsc/…` line in the logcat tail, and triage then compared
        // it against unrelated hardware. Emitted per logging session (not per
        // launch) so it is present in every attached log, including the
        // post-crash one.
        appContext?.let { com.openminis.app.diagnostics.EnvironmentBanner.log(it) }
    }

    @Synchronized
    private fun stopCapture() {
        if (!captureActive) return
        originalOut?.let { System.setOut(it) }
        originalErr?.let { System.setErr(it) }
        logcatTailer?.stop()
        logcatTailer = null
        captureActive = false
        // Close the daily writer so any buffered bytes are flushed; getWriter()
        // will reopen on the next file write.
        try {
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        currentFile = null
        logFileCapped = false
        currentDate = ""
    }

    /**
     * Append a logcat tail line to today's log file. Lines that AppLogger
     * itself produced (tag prefix `Minis.`) are skipped — [log] already wrote
     * them via [writer], so without this filter every `info()` / `warning()`
     * / etc. call would appear twice in the file (once from [log], once
     * echoed back through logcat).
     */
    private fun writeLogcatLine(rawLine: String) {
        if (!enabled) return
        // logcat -v time format: "MM-DD HH:MM:SS.mmm L/Tag(pid): message"
        // Extract the tag to filter our own output.
        val slashIdx = rawLine.indexOf('/')
        val parenIdx = if (slashIdx >= 0) rawLine.indexOf('(', slashIdx) else -1
        if (slashIdx >= 0 && parenIdx > slashIdx) {
            val tag = rawLine.substring(slashIdx + 1, parenIdx).trim()
            // [T-android-log-stdout-double-write] `System.out` / `System.err`
            // must be filtered for the same reason `Minis.*` is: startCapture()
            // redirects both streams into this file, AND the platform copies
            // them to logcat, where the tailer reads them straight back. Every
            // println — including the per-tick [T-HANG-DIAG] diagnostics —
            // therefore landed in the log TWICE, once as `[STDOUT] …` and again
            // as `[LOGCAT] … I/System.out(pid): …`. Verified on the 2026-09-15
            // field log: 8 T-HANG-DIAG lines for 4 actual events.
            //
            // The cost is not just noise. It doubles the write+flush work on
            // every println (writeLogcatLine flushes per line), doubles the
            // bytes in a file that is already large enough to have needed
            // tag-level suppression at the logcat command line, and makes any
            // frequency read off these logs wrong by 2x.
            if (tag.startsWith("Minis.") || tag == "AppLogger" ||
                tag == "System.out" || tag == "System.err"
            ) {
                return
            }
        }
        // [T-u12-merge] Ours writes the tail through the capped path with
        // quiet=true (a failed write must not itself be logged, or the tailer
        // re-reads it forever); upstream 1.14's swallow-the-whole-line guard is
        // kept around it so a formatter throw cannot escape either.
        try {
            val today = IsoTime.formatLocalDate()
            appendFileLine(today, "[LOGCAT] $rawLine", quiet = true)
        } catch (_: Exception) {
            // Swallow — must not feed back into logcat or we loop forever.
        }
    }

    /**
     * OutputStream wrapper that:
     *   1. Forwards every byte to [delegate] (the original stdout/stderr) so
     *      logcat / adb still receives the output unchanged.
     *   2. Buffers bytes into [buffer] until a `\n` arrives, then writes the
     *      complete line — prefixed with `[HH:mm:ss.SSS] [LEVEL] [tag]` — to
     *      the daily log file. Partial lines are flushed on close().
     */
    private class LineCapturingStream(
        private val delegate: PrintStream,
        private val tag: String,
    ) : OutputStream() {
        private val buffer = ByteArrayOutputStream(256)

        override fun write(b: Int) {
            // Always forward first; any failure to capture must NOT swallow output.
            delegate.write(b)
            if (b == '\n'.code) {
                emitLine()
            } else {
                buffer.write(b)
            }
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            var lineStart = off
            val end = off + len
            for (i in off until end) {
                if (b[i] == '\n'.code.toByte()) {
                    if (i > lineStart) buffer.write(b, lineStart, i - lineStart)
                    emitLine()
                    lineStart = i + 1
                }
            }
            if (lineStart < end) buffer.write(b, lineStart, end - lineStart)
        }

        override fun flush() {
            delegate.flush()
        }

        private fun emitLine() {
            val line = try {
                buffer.toString("UTF-8")
            } catch (_: Exception) {
                buffer.toString()
            }
            buffer.reset()
            // Drop empty lines so the file isn't full of bare timestamps.
            if (line.isEmpty()) return
            writeFileLine(tag, line)
        }
    }

    /**
     * Append a captured stdout/stderr line to today's log file. Catches all
     * I/O failures so a flaky filesystem can't crash the app's stdout.
     */
    @Synchronized
    private fun writeFileLine(channel: String, line: String) {
        if (!enabled) return
        // [T-u12-merge] Ours: IsoTime + the capped appendFileLine (which does
        // its own catching and reports through Log.w). Upstream's duplicate
        // try/catch around SimpleDateFormat is not needed — and its
        // dateFormat/timestampFormat vals are not part of the merged file,
        // while SimpleDateFormat is not thread-safe on a logger called from
        // every thread.
        val ms = System.currentTimeMillis()
        val today = IsoTime.formatLocalDate(ms)
        val timestamp = IsoTime.formatLocalTimeMillis(ms)
        appendFileLine(today, "[$timestamp] [$channel] $line")
    }

    /**
     * Log a message at INFO level with a category tag.
     */
    fun info(category: String, message: String) {
        log("INFO", category, message)
    }

    fun warning(category: String, message: String) {
        log("WARN", category, message)
    }

    fun error(category: String, message: String) {
        log("ERROR", category, message)
    }

    /**
     * Categories whose DEBUG logs we silently drop. Useful for high-volume
     * categories that fire on every scroll frame / token (e.g. the chat
     * scroll-follow path under tag "ChatScrollFollow") — gating at the
     * caller would require touching dozens of sites; gating here keeps the
     * Log.d + file-write cost off the hot path. The string formatting at
     * each caller still pays for itself (we can't fix that without lambdas)
     * but `Log.d → liblog → LogcatTailer → file write` is more expensive
     * than the string build alone.
     */
    private val mutedDebugCategories = setOf("ChatScrollFollow", "ScrollSrc")

    fun debug(category: String, message: String) {
        if (category in mutedDebugCategories) return
        log("DEBUG", category, message)
    }

    // [T-u12-merge] Ours' copy of the `log()` header and its eager IsoTime
    // stamping used to sit at this point; the header is declared once below
    // (right after trace(), where both sides' bodies matched), and the
    // stamping moved behind upstream's gates — see log(). Upstream's lazy
    // debug variant is adopted as-is: ChatScreen's "ScrollSrc" call sites use
    // the lambda form.
    /**
     * [T-android-fab-up-anr] Lazy variant — the "can't fix that without
     * lambdas" the note above refers to.
     *
     * For a muted category this costs one set lookup and never builds the
     * message at all. Worth it where a call site can fire many times inside a
     * single frame: the scroll-source diagnostic ran once per programmatic
     * scroll on a path that could issue hundreds in one gesture, formatting
     * six fields of layout state each time, with nothing consuming the result.
     */
    inline fun debug(category: String, message: () -> String) {
        if (isDebugMuted(category)) return
        debug(category, message())
    }

    /** Exposed for [debug]'s inline body; not part of the logging surface. */
    fun isDebugMuted(category: String): Boolean = category in mutedDebugCategories

    /**
     * [T-android-log-hotpath] True when a per-event diagnostic has a reader:
     * a debug build (adb logcat), or Verbose logging on (the only level at
     * which DEBUG lines reach the log file).
     */
    val traceEnabled: Boolean
        get() = com.openminis.app.BuildConfig.DEBUG || (enabled && verbose)

    /**
     * [T-android-log-hotpath] For diagnostics that fire per streamed event /
     * chunk. [debug] builds its message and writes it to logcat on every
     * call even when nothing reads it — at the default Info level a release
     * build never saves a DEBUG line, yet paid the string build, a logcat JNI
     * write and (before this change) two date formats per SSE event. Here
     * the message is not even built unless [traceEnabled].
     */
    inline fun trace(category: String, message: () -> String) {
        if (!traceEnabled || isDebugMuted(category)) return
        debug(category, message())
    }

    private fun log(level: String, category: String, message: String) {
        // Also output to logcat
        val logcatTag = "Minis.$category"
        when (level) {
            "ERROR" -> Log.e(logcatTag, message)
            "WARN" -> Log.w(logcatTag, message)
            "DEBUG" -> Log.d(logcatTag, message)
            else -> Log.i(logcatTag, message)
        }

        // Write to file (only if enabled)
        if (!enabled) return
        // [T-android-log-level] Upstream 1.14's gate, adopted: DEBUG reaches
        // the file only at Verbose; it still went to logcat above for adb
        // users. Without it the Verbose toggle would only move the logcat
        // tail's minPriority and never the AppLogger.debug lines, which is
        // exactly what the level picker promises to control.
        if (level == "DEBUG" && !verbose) return
        // [T-android-log-hotpath] Stamp only a line that is actually written —
        // upstream's point, kept: a logcat-only call (or one with file logging
        // off) used to pay for the formatting first. Ours computes those two
        // fields with IsoTime rather than upstream's shared SimpleDateFormat,
        // because AppLogger is called from every thread and SimpleDateFormat
        // is not thread-safe.
        val ms = System.currentTimeMillis()
        val today = IsoTime.formatLocalDate(ms)
        val timestamp = IsoTime.formatLocalTimeMillis(ms)
        appendFileLine(today, "[$timestamp] [$level] [$category] $message")
    }

    @Synchronized
    private fun appendFileLine(today: String, line: String, quiet: Boolean = false) {
        if (!enabled) return
        try {
            val clipped = BoundedText.clampLogLine(line)
            val w = getWriter(today)
            val file = currentFile
            val size = file?.length() ?: 0L
            val lineBytes = clipped.toByteArray(Charsets.UTF_8).size + 1
            if (!BoundedText.canAppendLog(size, lineBytes)) {
                if (!logFileCapped) {
                    logFileCapped = true
                    w.println("[AppLogger] daily log reached ${BoundedText.MAX_LOG_FILE_BYTES} bytes; info/debug dropped, ERROR/WARN kept")
                    w.flush()
                }
                val keepPriority = !quiet && BoundedText.isPriorityLogLine(clipped) &&
                    BoundedText.canAppendPriorityLog(size, lineBytes)
                if (!keepPriority) return
            }
            // [T-u12-merge] Write through upstream's appendLine instead of
            // println/clamp here: appendFileLine stays the only entry point and
            // the cap above cannot be bypassed, while upstream's periodic
            // truncateIfNeeded() rotation keeps running for a file that is
            // already over the cap (written by a build that predates it).
            appendLine(today, clipped)
        } catch (e: Exception) {
            if (!quiet) Log.w(TAG, "Failed to write log: ${e.message}")
        }
    }

    /**
     * The single file-write path for every captured line (AppLogger calls,
     * stdout/stderr, logcat tail), so the size cap cannot be bypassed.
     */
    @Synchronized
    private fun appendLine(date: String, text: String) {
        val w = getWriter(date)
        w.println(text)
        w.flush()
        bytesSinceSizeCheck += text.length + 1
        if (bytesSinceSizeCheck >= SIZE_CHECK_EVERY_BYTES) {
            bytesSinceSizeCheck = 0
            truncateIfNeeded(date)
        }
    }

    /**
     * [T-android-log-size-cap] Keep the newest half once the file passes the
     * cap. Streams through a temp file with FileChannel.transferTo rather than
     * reading the tail into memory: that would be a ~50 MB heap allocation on
     * the logging path of devices that are often already short on memory.
     */
    @Synchronized
    private fun truncateIfNeeded(date: String) {
        val dir = logDir ?: return
        val file = File(dir, "minis-$date.log")
        val size = file.length()
        if (size <= MAX_LOG_FILE_BYTES) return
        writer?.close()
        writer = null
        currentDate = ""
        val tmp = File(dir, "minis-$date.log.truncating")
        try {
            java.io.FileInputStream(file).channel.use { src ->
                java.io.FileOutputStream(tmp, false).use { out ->
                    out.write("[log truncated: kept the newest ${MAX_LOG_FILE_BYTES / 2 / (1024 * 1024)} MB]\n".toByteArray())
                    val start = size - MAX_LOG_FILE_BYTES / 2
                    var pos = start
                    while (pos < size) {
                        val n = src.transferTo(pos, size - pos, out.channel)
                        if (n <= 0) break
                        pos += n
                    }
                }
            }
            // [T-android-log-size-cap] 替换必须用 REPLACE_EXISTING 语义。原来的
            // `tmp.renameTo(file)` 在**目标已存在**时返回 false（Windows/NTFS 就是
            // 这样，Java 也明确声明 renameTo 的行为"inherently platform-dependent"），
            // 而紧跟的 `tmp.delete()` 把切好的新文件删掉、把没切过的旧文件原样留下
            // ——于是「超过上限就砍掉最旧的一半」在那种平台上等于不存在：日志无限
            // 增长，正是 [T-android-log-size-cap] 要修的行为，且是静默的（只看得到
            // 文件越来越大）。Files.move(REPLACE_EXISTING) 在 POSIX 与 NTFS 上都是
            // 替换语义；minSdk 26 起 java.nio.file 可用。
            java.nio.file.Files.move(
                tmp.toPath(),
                file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: Exception) {
            tmp.delete()
            Log.w(TAG, "log truncate failed: ${e.message}")
        }
    }

    @Synchronized
    private fun getWriter(date: String): PrintWriter {
        if (date != currentDate || writer == null) {
            writer?.close()
            val dir = logDir ?: throw IllegalStateException("AppLogger not initialized")
            val file = File(dir, "minis-$date.log")
            currentFile = file
            logFileCapped = file.length() >= BoundedText.MAX_LOG_FILE_BYTES
            writer = PrintWriter(FileWriter(file, true))
            currentDate = date
        }
        return writer!!
    }

    /**
     * List available log files, newest first.
     */
    fun listLogFiles(): List<File> {
        val dir = resolveLogDir() ?: return emptyList()
        return dir.listFiles { f -> f.extension == "log" }
            ?.sortedByDescending { it.name }
            ?: emptyList()
    }

    /**
     * Lightweight metadata for a single log file. Captures name + size +
     * mtime ONCE so the UI never re-stat's the file during recomposition
     * (LogManagementScreen used to call file.length() per row per
     * recomposition, which scaled badly once dozens of crash files piled
     * up alongside the daily logs).
     */
    data class LogFileMeta(
        val name: String,
        val sizeBytes: Long,
        val lastModified: Long,
    )

    /**
     * Capped, prefix-filtered log listing for the UI.
     *
     * - `prefix`: filename starts-with filter (e.g. `"minis-"` for daily
     *   logs, `"crash-"` / `"native-crash-"` for crash reports). Empty
     *   string returns all `.log` files.
     * - `limit`: keep at most this many files, sorted by name descending
     *   (newest first, since both daily and crash filenames embed
     *   YYYY-MM-DD prefixes that sort correctly).
     *
     * Captures size + mtime per file via a single `stat` per entry, so a
     * caller iterating the result list never has to re-stat. Total size
     * is computed by the caller (sum of `sizeBytes`) — no second
     * directory walk needed.
     *
     * Pure data; safe to call from `Dispatchers.IO`.
     */
    fun listLogFileMetas(prefix: String, limit: Int): List<LogFileMeta> {
        val dir = resolveLogDir() ?: return emptyList()
        val files = dir.listFiles { f ->
            f.extension == "log" && (prefix.isEmpty() || f.name.startsWith(prefix))
        } ?: return emptyList()
        // Sort then cap BEFORE the per-file stat — File.listFiles already
        // populated name internally, but length()/lastModified() are
        // separate stat syscalls we'd rather skip on the tail.
        return files
            .sortedByDescending { it.name }
            .take(limit)
            .map { LogFileMeta(it.name, it.length(), it.lastModified()) }
    }

    /**
     * Read content of a specific log file.
     */
    fun readLog(
        filename: String,
        offset: Int = 0,
        limit: Int = BoundedText.MAX_LOG_READ_BYTES,
    ): String? {
        val dir = resolveLogDir() ?: return null
        val file = File(dir, filename)
        if (!file.exists()) return null
        val bytes = limit.coerceAtMost(BoundedText.MAX_LOG_READ_BYTES).toLong()
        return com.openminis.app.data.body.Admission.occupy(bytes) {
            BoundedText.readFileRange(
                file,
                offset.coerceAtLeast(0).toLong(),
                bytes.toInt(),
            )
        }
    }

    fun logFileSize(filename: String): Long {
        val dir = resolveLogDir() ?: return -1L
        val file = File(dir, filename)
        return if (file.exists()) file.length() else -1L
    }

    /**
     * Delete all log files.
     */
    @Synchronized
    fun clearLogs() {
        logDir?.listFiles()?.forEach { it.delete() }
        // [T-logging-zombie-fd-android] The open writer still references the
        // just-deleted file; a FileWriter on an unlinked inode keeps writing to
        // the zombie file (invisible on disk) until currentDate changes or the
        // writer is nulled. Drop it and reset currentDate so the next
        // getWriter() reopens a fresh minis-<date>.log on the following write.
        // @Synchronized shares getWriter()'s monitor so this can't race a write.
        writer?.close()
        writer = null
        currentFile = null
        logFileCapped = false
        currentDate = ""
    }

    /**
     * Total size of all log files in bytes.
     */
    fun totalSize(): Long {
        // Read path — uses the same fallback as the listing helpers so the
        // storage footer isn't reported as 0 B next to a populated list.
        // Deletion paths (clearLogs / pruneOldLogs) deliberately stay on
        // the raw logDir: a process that never finished init has no
        // business unlinking the user's crash evidence.
        return resolveLogDir()?.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun pruneOldLogs() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60 * 60 * 1000
        logDir?.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) {
                file.delete()
            }
        }
    }
}
