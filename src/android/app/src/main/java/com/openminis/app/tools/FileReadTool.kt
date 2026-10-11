package com.openminis.app.tools

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.sandbox.PRootKernel
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.util.ArrayDeque

object FileReadTool {
    const val NAME = "file_read"
    const val MAX_LENGTH_HARD_CAP = 80_000
    const val DEFAULT_MAX_LENGTH = 15_000

    fun resolveMaxLength(requested: Int, userCap: Int): Int {
        val cap = userCap.coerceIn(2_000, MAX_LENGTH_HARD_CAP)
        return requested.coerceIn(1, cap)
    }

    fun resolveLineLimit(requested: Int?, userCap: Int): Int? {
        if (userCap <= 0) return requested?.coerceAtLeast(1)
        val cap = userCap.coerceAtMost(20_000)
        return if (requested == null) cap else requested.coerceIn(1, cap)
    }


    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Read a file from the Linux filesystem. Faster than shell_execute for reading files — no shell overhead. Returns file content with metadata. Rejects binary files.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Read Python script contents', 'Check system configuration file'). Use the same language as the user."),
            "path" to AgentToolParam("string", "Absolute Linux path to read (e.g. /var/minis/workspace/data.csv)"),
            "offset" to AgentToolParam("integer", "1-based line number to start reading from (default: 1). Ignored when direction is 'tail'. If a previous read was truncated, its header ends with next_offset=N — pass that as offset to continue from where it stopped."),
            "lines" to AgentToolParam("integer", "Maximum number of lines to return (default: all lines up to max_length)"),
            "max_length" to AgentToolParam("integer", "Maximum character length of returned content (capped in Settings → Tool limits; hard cap 80000)"),
            "direction" to AgentToolParam("string", "Read direction: 'head' (from start, default) or 'tail' (from end of file)"),
        ),
        required = listOf("tool_title", "path"),
        propertyOrdering = listOf("tool_title", "path", "offset", "lines", "direction", "max_length"),
    )

    fun execute(argsJson: String, sessionId: String, context: Context): ToolExecutionResult {
        return try {
            val args = JSONObject(argsJson)
            val path = args.optString("path", "")
            val toolTitle = args.optString("tool_title", NAME)
            val offset = args.optInt("offset", 1).coerceAtLeast(1)
            // T-FILEREAD-CAP: hard upper bound on returned content length.
            // Pre-cap, the agent could ask for `max_length=1_000_000` and we
            // would happily inline a 400 KB base64 image into a tool_result —
            // which then renders as a single user-message bubble and locks
            // up Compose's StaticLayout / LineBreaker for tens of seconds
            // (see HangDetector report for session
            // e84882d7-2087-47f8-9300-ff2c897fe0b4: 820 KB partsJson, 43 s
            // hang in nComputeLineBreaks). Cap at 80 KB regardless of
            // requested value; the truncation tail below tells the agent the
            // full file size so it can paginate with offset/lines if needed.
            // iOS mirrors this cap in AIChatViewModel.executeFileRead.
            val userChars = com.openminis.app.data.ToolLimitPrefs.fileReadMaxChars()
            val requestedChars = if (args.has("max_length")) args.optInt("max_length") else userChars
            val maxLength = resolveMaxLength(requestedChars, userChars)
            val direction = args.optString("direction", "head")

            if (path.isBlank()) {
                return ToolExecutionResult("Error: 'path' is required", false, toolTitle = toolTitle)
            }

            // T123: per-session resolver — see FileWriteTool for rationale.
            val file = identityFile(context, path)
                ?: PRootKernel.resolveSessionHostPath(sessionId, path, context)
                ?: return ToolExecutionResult(
                    "Error: Cannot resolve path: $path${guestNamespaceHint(path)}",
                    false,
                    toolTitle = toolTitle,
                )

            if (!file.exists()) {
                return ToolExecutionResult("Error: File not found: $path${guestNamespaceHint(path)}", false, toolTitle = toolTitle)
            }

            if (file.isDirectory) {
                return ToolExecutionResult("Error: Path is a directory: $path", false, toolTitle = toolTitle)
            }

            val size = file.length()

            // Binary detection: check first 8192 bytes for null bytes.
            // [T-fileread-read-cap] The old `minOf(8192, size.toInt())` went
            // negative for files > 2 GB (ByteArray(size) then threw
            // NegativeArraySizeException and the read reported "Error reading
            // file"), so clamp in Long space first.
            val isBinary = file.inputStream().use { input ->
                val buf = ByteArray(minOf(8192L, size).toInt())
                val read = input.read(buf)
                if (read > 0) buf.take(read).any { it == 0.toByte() } else false
            }

            if (isBinary) {
                return ToolExecutionResult(
                    "[$path | $size bytes | binary file — cannot display contents]",
                    true, toolTitle = toolTitle
                )
            }

            val requestedLines = resolveLineLimit(
                if (args.has("lines")) args.optInt("lines") else null,
                com.openminis.app.data.ToolLimitPrefs.fileReadMaxLines(),
            )

            // [T-fileread-read-cap] Bounded, windowed read — `file.readLines()`
            // is gone. It materialised the whole file into a List<String> and
            // then `joinToString` copied it again, and only *then* was the
            // result truncated, so `max_length`/line caps never bounded memory
            // (a multi-GB log, or a 400 MB single-line bundle, was read in
            // full; with the default line cap of 0 nothing was cut at all).
            // The window walks the file forward only as far as `offset`/`lines`
            // and a char budget ~max_length, keeps the tail in a bounded ring,
            // and reports the exact total only when it actually reached EOF.
            val window = readFileWindow(
                file = file,
                direction = direction,
                offset = offset,
                requestedLines = requestedLines,
                maxLength = maxLength,
            )
            val selectedLines = window.lines
            // Line counting is itself byte-capped: under the cap the count is
            // exact, past it a lower bound — hence the "N+" the header prints.
            val scan = window.exactTotalLines?.let { LineScan(it, true) }
                ?: scanLines(file, FILE_READ_MAX_SCAN_BYTES)
            val walkReachedEof = window.exactTotalLines != null
            val totalLines = scan.count
            val totalLabel = if (scan.exact) "$totalLines" else "$totalLines+"

            val showStart = if (direction == "tail") {
                (totalLines - selectedLines.size + 1).coerceAtLeast(1)
            } else {
                offset
            }
            val showEnd = showStart + selectedLines.size - 1

            var content = selectedLines.joinToString("\n")

            // [T-fileread-truncation-header] The header used to report the line
            // range chosen BEFORE truncation, and said nothing about having
            // truncated at all — only the body gained a trailing
            // "... (truncated)". So a cut-off read still announced
            // "showing 1-1324 of 1324", which the agent took as the whole file
            // and never paged on.
            //
            // Recompute the range that actually survived and hand back the
            // offset to resume from. Confined to the truncating branch; a read
            // that fits is byte-identical to before.
            var effectiveStart = showStart
            var effectiveEnd = showEnd
            var nextOffset: Int? = null
            var wasTruncated = false
            if (content.length > maxLength) {
                wasTruncated = true
                if (direction == "tail") {
                    // tail asks for the END of the file; take() returned the
                    // start of the tail window instead — the opposite.
                    content = content.takeLast(maxLength)
                    // Drop a leading partial line so the first line is whole.
                    val firstNewline = content.indexOf('\n')
                    if (firstNewline in 0 until content.length - 1) {
                        content = content.substring(firstNewline + 1)
                    }
                    // The window is anchored at EOF, so the range is only
                    // meaningful when the total line count is known.
                    if (scan.exact) effectiveStart = effectiveEnd - content.count { it == '\n' }
                    // No next_offset for tail: paging forward from the end of
                    // the file is meaningless.
                } else {
                    content = content.take(maxLength)
                    // Back off to the last complete line, so the next page does
                    // not re-read or split a line.
                    val lastNewline = content.lastIndexOf('\n')
                    if (lastNewline > 0) content = content.substring(0, lastNewline)
                    effectiveEnd = showStart + content.count { it == '\n' }
                    // A window that stopped short of EOF always has a next
                    // page; when it did reach EOF the old test decides.
                    if (!walkReachedEof || effectiveEnd < totalLines) nextOffset = effectiveEnd + 1
                }
            }

            val rangeLabel = if (direction == "tail" && !scan.exact) {
                // Line numbers cannot be anchored: the walk never saw line 1
                // (the file is larger than the read cap).
                "the last ${selectedLines.size} lines"
            } else {
                "$effectiveStart-$effectiveEnd of $totalLabel"
            }
            var header = "[$path | $size bytes | $totalLabel lines | showing $rangeLabel"
            if (wasTruncated) {
                header += " | truncated at $maxLength chars"
                // Named to match the tool's own `offset` parameter so the model
                // can copy it straight into the next call.
                header += if (nextOffset != null) ", next_offset=$nextOffset"
                          else ", retry with a smaller lines value"
            }
            if (size > FILE_READ_MAX_SCAN_BYTES) {
                // [T-fileread-read-cap] Say it out loud: the file was never
                // read whole, so the line numbers above are lower bounds.
                header += " | read capped at $FILE_READ_MAX_SCAN_BYTES bytes (line counts are lower bounds)"
            }
            header += "]"
            ToolExecutionResult("$header\n$content", true, toolTitle = toolTitle)
        } catch (e: Exception) {
            ToolExecutionResult("Error reading file: ${e.message}", false)
        }
    }
}

// ==== [T-fileread-read-cap] bounded read primitives (top-level, unit-tested) ====

/**
 * [T-fileread-read-cap] Hard cap on one line. A single "line" in a log or a
 * minified bundle can be hundreds of MB; both the window walk and `execute`'s
 * `take(maxLength)` need a bounded unit. Head keeps the line *start*, tail
 * keeps the line *end* (see [BoundedLineReader]).
 */
internal const val FILE_READ_MAX_LINE_CHARS = 256 * 1024

/**
 * [T-fileread-read-cap] Hard cap on how far one read walks the file hunting for
 * line boundaries — line counting, and a head walk whose `offset` skips a long
 * way in. Past this a file is never read whole; totals degrade to lower bounds
 * and the caller prints them as "N+".
 */
internal const val FILE_READ_MAX_SCAN_BYTES = 4 * 1024 * 1024

private const val READER_BUFFER_CHARS = 16 * 1024
private const val SCAN_BUFFER_BYTES = 64 * 1024
private const val LF = 10
private const val CR = 13

/**
 * Streaming line reader with a per-line hard cap. Splitting, terminators
 * (`\n`, `\r`, `\r\n`) and the trailing-line rule deliberately match
 * `BufferedReader.readLine()` — i.e. the old `File.readLines()` — so offsets,
 * totals and truncation semantics do not fork.
 *
 * Over-long lines are clipped at [maxLineChars] instead of materialised:
 * - `keepTail = false` (head): the line start is kept, the rest is consumed but
 *   dropped, so the caller sees where the line begins;
 * - `keepTail = true` (tail): the line END is kept — otherwise the retained
 *   fragment is the file's ~8% mark and the real end is unreachable
 *   [T-fileread-tail-longline].
 */
internal class BoundedLineReader(
    stream: InputStream,
    private val maxLineChars: Int = FILE_READ_MAX_LINE_CHARS,
    private val keepTail: Boolean = false,
) {
    private val source = InputStreamReader(stream, Charsets.UTF_8)
    private val buf = CharArray(READER_BUFFER_CHARS)
    private var bufLen = 0
    private var bufPos = 0
    private var pushedBack = -1
    private var eof = false

    /** Next line, or null at EOF. */
    fun readLine(): String? {
        if (eof) return null
        val head = StringBuilder()
        var ring: CharRing? = null
        var sawAny = false
        while (true) {
            val c = next()
            if (c < 0) {
                eof = true
                break
            }
            sawAny = true
            if (c == LF) break
            if (c == CR) {
                // "\r\n" is one terminator; a lone "\r" is one too.
                val n = next()
                if (n >= 0 && n != LF) pushedBack = n
                break
            }
            val ch = c.toChar()
            val r = ring
            when {
                r != null -> r.append(ch)
                head.length < maxLineChars -> head.append(ch)
                keepTail -> {
                    // [T-fileread-tail-longline] Keep the line END with a char
                    // ring: allocated lazily (only an over-long line pays) and
                    // appended in O(1). A StringBuilder plus `delete(0, 1)`
                    // would be O(n²) over 256K chars.
                    val fresh = CharRing(maxLineChars)
                    for (i in 0 until head.length) fresh.append(head[i])
                    fresh.append(ch)
                    ring = fresh
                }
                // head direction: the line start is what matters; the rest of
                // the line is consumed but not materialised.
                else -> Unit
            }
        }
        // EOF with nothing read is "no line"; EOF right after a terminator is
        // an empty line, exactly like readLine().
        if (!sawAny) return null
        val r = ring
        return if (r != null) r.materialize() else head.toString()
    }

    private fun next(): Int {
        if (pushedBack >= 0) {
            val c = pushedBack
            pushedBack = -1
            return c
        }
        if (bufPos >= bufLen) {
            val n = source.read(buf, 0, buf.size)
            if (n <= 0) return -1
            bufLen = n
            bufPos = 0
        }
        return buf[bufPos++].code
    }

    /** Fixed-size char ring: O(1) append, no front deletion, no growth. */
    private class CharRing(private val cap: Int) {
        private val buf = CharArray(cap)
        private var start = 0
        private var count = 0

        fun append(c: Char) {
            if (count < cap) {
                buf[(start + count) % cap] = c
                count++
            } else {
                buf[start] = c
                start = (start + 1) % cap
            }
        }

        fun materialize(): String {
            val out = CharArray(count)
            for (i in 0 until count) out[i] = buf[(start + i) % cap]
            return String(out)
        }
    }
}

/** Line count plus whether the whole file was seen ([exact] false = lower bound). */
internal data class LineScan(val count: Int, val exact: Boolean)

/**
 * [T-fileread-read-cap] Counts lines without holding any of them, stopping after
 * [capBytes] bytes. Terminator runs are counted the way `readLines()` splits
 * them; a file that ends without a terminator contributes its last line.
 */
internal fun scanLines(file: File, capBytes: Int = FILE_READ_MAX_SCAN_BYTES): LineScan {
    if (capBytes <= 0) return LineScan(0, file.length() == 0L)
    var lines = 0
    var consumed = 0L
    var sinceTerminator = 0
    var lastWasCr = false
    var eof = false
    file.inputStream().use { input ->
        val buf = ByteArray(SCAN_BUFFER_BYTES)
        while (consumed < capBytes) {
            val want = minOf(buf.size.toLong(), capBytes - consumed).toInt()
            val n = input.read(buf, 0, want)
            if (n <= 0) {
                eof = true
                break
            }
            var i = 0
            while (i < n) {
                val b = buf[i].toInt()
                when {
                    b == LF -> {
                        if (!lastWasCr) lines++
                        lastWasCr = false
                        sinceTerminator = 0
                    }
                    b == CR -> {
                        lines++
                        lastWasCr = true
                        sinceTerminator = 0
                    }
                    else -> {
                        lastWasCr = false
                        sinceTerminator++
                    }
                }
                i++
            }
            consumed += n
        }
        if (!eof && consumed >= capBytes) {
            // One byte past the cap says whether the file ended exactly at it;
            // without the probe a file with no trailing newline and length ==
            // capBytes would be under-counted by one line.
            if (input.read() < 0) eof = true
        }
    }
    val count = if (eof && sinceTerminator > 0) lines + 1 else lines
    return LineScan(count, eof)
}

internal fun scanLineCount(file: File, capBytes: Int = FILE_READ_MAX_SCAN_BYTES): Int =
    scanLines(file, capBytes).count

/**
 * Lines selected for one read.
 *
 * [exactTotalLines] is non-null only when the walk reached EOF, i.e. the total
 * is the file's real line count; otherwise the caller must fall back to the
 * byte-capped counter (and print a lower bound).
 */
internal data class FileReadWindow(
    val lines: List<String>,
    val exactTotalLines: Int?,
)

/**
 * [T-fileread-read-cap] Bounded window over [file]:
 * - `head`: streams forward, skipping `offset - 1` lines and collecting until
 *   `requestedLines` or a ~[maxLength] char budget is reached;
 * - `tail`: streams the (at most [FILE_READ_MAX_SCAN_BYTES]-byte) tail into a
 *   ring of whole lines — the window is a suffix of the file, never the whole
 *   file;
 * - over-long lines are clipped per [BoundedLineReader], keeping the end for
 *   tail.
 */
internal fun readFileWindow(
    file: File,
    direction: String,
    offset: Int = 1,
    requestedLines: Int? = null,
    maxLength: Int = FileReadTool.DEFAULT_MAX_LENGTH,
): FileReadWindow {
    val limit = requestedLines?.coerceAtLeast(1)
    return if (direction == "tail") {
        readTailWindow(file, limit, maxLength)
    } else {
        readHeadWindow(file, offset.coerceAtLeast(1), limit, maxLength)
    }
}

private fun readHeadWindow(
    file: File,
    offset: Int,
    requestedLines: Int?,
    maxLength: Int,
): FileReadWindow {
    val out = ArrayList<String>()
    var seen = 0
    var contentChars = 0
    var scannedChars = 0
    var exact: Int? = null
    file.inputStream().use { stream ->
        val reader = BoundedLineReader(stream)
        while (true) {
            if (requestedLines != null && out.size >= requestedLines) break
            if (contentChars > maxLength) {
                // Budget crossed. Probe one more line so a read that happened to
                // end exactly on the last line still reports an exact total
                // (and no bogus next_offset).
                if (reader.readLine() == null) exact = seen
                break
            }
            // Hard read cap: a huge `offset` on a huge file must not walk it
            // from end to end.
            if (scannedChars > FILE_READ_MAX_SCAN_BYTES) break
            val line = reader.readLine()
            if (line == null) {
                exact = seen
                break
            }
            seen++
            scannedChars += line.length + 1
            if (seen < offset) continue
            out.add(line)
            contentChars = if (out.size == 1) line.length else contentChars + line.length + 1
        }
    }
    return FileReadWindow(out, exact)
}

private fun readTailWindow(
    file: File,
    requestedLines: Int?,
    maxLength: Int,
): FileReadWindow {
    val length = file.length()
    // Only a file that fits the cap is walked from byte 0, which is what makes
    // the total exact; past the cap the walk starts near EOF and the total
    // stays unknown.
    val start = if (length > FILE_READ_MAX_SCAN_BYTES) length - FILE_READ_MAX_SCAN_BYTES else 0L
    val ring = ArrayDeque<String>()
    var ringChars = 0
    var total = 0
    var exactTotal: Int? = null
    val from = openTailStart(file, start)
    from.stream.use { stream ->
        val reader = BoundedLineReader(stream, keepTail = true)
        // A seek that landed mid-line yields a partial first line: it is not a
        // line of the file, so it is neither returned nor counted.
        if (from.skipFirstLine) reader.readLine()
        while (true) {
            val line = reader.readLine() ?: break
            total++
            ring.addLast(line)
            ringChars += line.length + if (ring.size > 1) 1 else 0
            if (requestedLines != null) {
                while (ring.size > requestedLines) ringChars -= dropRingFront(ring)
            } else {
                // Char-bounded ring: keep the smallest suffix that still holds
                // at least maxLength chars, so execute's takeLast(maxLength)
                // starts on a whole line. The newest line is never dropped —
                // an over-long line is clipped to its end by the reader, and
                // dropping it would lose the file end entirely
                // [T-fileread-tail-longline].
                while (ring.size > 1 && ringChars - ring.first().length - 1 >= maxLength) {
                    ringChars -= dropRingFront(ring)
                }
            }
        }
        if (start == 0L) exactTotal = total
    }
    return FileReadWindow(ring.toList(), exactTotal)
}

private fun dropRingFront(ring: ArrayDeque<String>): Int {
    val removed = ring.removeFirst()
    return removed.length + if (ring.isEmpty()) 0 else 1
}

private class TailStart(val stream: InputStream, val skipFirstLine: Boolean)

/**
 * Opens [file] positioned at byte [start] and says whether the first line read
 * from there is a fragment of a longer line (and must therefore be dropped).
 */
private fun openTailStart(file: File, start: Long): TailStart {
    if (start <= 0L) return TailStart(file.inputStream(), false)
    var prev = -1
    var cur = -1
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(start - 1)
        prev = raf.read()
        cur = raf.read()
    }
    // A line starts at `start` unless we are in the middle of one; "\r\n" split
    // across the boundary counts as mid-terminator, not as a line start.
    val lineStart = prev == LF || (prev == CR && cur != LF)
    val stream = FileInputStream(file)
    var remaining = start
    while (remaining > 0) {
        val skipped = stream.skip(remaining)
        if (skipped <= 0) break
        remaining -= skipped
    }
    return TailStart(stream, !lineStart)
}

// ==== end bounded read primitives ====

internal fun identityFile(context: android.content.Context, path: String): java.io.File? {
    val name = when (path.trim()) {
        "/.arch" -> ".arch"
        "/.distro" -> ".distro"
        else -> return null
    }
    val root = com.openminis.app.sandbox.RootfsManager.getInstance(context).rootfsDir
    val file = java.io.File(root, name)
    return file.takeIf { it.isFile }
}

internal fun guestNamespaceHint(path: String): String {
    val p = path.trim()
    val guestOnly = p == "/" || p == "/proc" || p.startsWith("/proc/") ||
        p == "/sys" || p.startsWith("/sys/") ||
        p == "/dev" || p.startsWith("/dev/") ||
        p == "/etc" || p.startsWith("/etc/") ||
        p == "/.arch" || p == "/.distro"
    if (!guestOnly) return ""
    return ". App-process file tools cannot see this guest path. Use shell_execute, for example `cat $p`."
}
