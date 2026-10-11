package com.openminis.app.data.body

import com.openminis.app.logging.AppLogger
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.UUID

/**
 * Durable body files. A killed write leaves only a temp file, which the next
 * open deletes. The SQLite row is updated only after the rename succeeds.
 *
 * File layout: magic OMB1, uncompressed size (int), compressed size (int),
 * codec byte, payload. Declared uncompressed size above the cap is refused
 * before inflate.
 */
open class BodyStore(private val root: File) {
    data class Put(val ok: Boolean, val ref: String? = null, val sha: String? = null, val error: String? = null)

    fun put(bytes: ByteArray): Put {
        if (bytes.size > ResourceLimits.MAX_DECLARED_UNCOMPRESSED) {
            return Put(ok = false, error = "declared size ${bytes.size} exceeds cap")
        }
        if (!root.mkdirs() && !root.isDirectory) {
            return Put(ok = false, error = "body directory unavailable")
        }
        // Compression is staged to disk, so the only body-sized allocation is
        // the caller-owned input; reserve that input for cross-operation limits.
        val reservation = bytes.size.toLong()
        if (!Admission.tryAdmit(reservation)) {
            return Put(ok = false, error = "admission refused")
        }
        try {
            val sha = sha256(bytes)
            val dest = File(root, sha)
            // [T-bodies-gc-toctou] 去重命中的「判定 + 引用刷新」与回收侧的
            // 「重看 mtime + unlink」必须原子（回收侧见 [withCommitLock]）：分开做时，
            // 回收侧能在我们读到文件之后、刷新引用之前把它 unlink 掉，而 put 已经对外
            // 宣称正文在盘上 —— 调用方随后落库的 body_ref 从此悬空。
            val alreadyStored = synchronized(finalizeLock) {
                if (dest.isFile && dest.length() > HEADER) {
                    // [T-bodies-gc-dedupe-touch] 去重命中也要刷新 mtime：见 refreshReference。
                    refreshReference(dest, bytes)
                    true
                } else {
                    false
                }
            }
            if (alreadyStored) return Put(ok = true, ref = sha, sha = sha)
            // A unique temp per invocation; a concurrent put for identical bytes
            // can never truncate or unlink another writer's in-flight payload.
            val tmp = File(root, "$sha.${UUID.randomUUID()}.tmp")
            return try {
                writeFramed(tmp, bytes)

                synchronized(finalizeLock) {
                    // Another writer of the same content may have won while we
                    // compressed. Never replace its complete object with an
                    // in-progress copy.
                    if (dest.isFile && dest.length() > HEADER) {
                        // [T-bodies-gc-dedupe-touch] 我们这次 put 的正文就在这个文件
                        // 里，指针马上会落库 —— 同样要刷新 mtime。
                        refreshReference(dest, bytes)
                        tmp.delete()
                    } else {
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                    }
                }
                Put(ok = true, ref = sha, sha = sha)
            } catch (e: Exception) {
                tmp.delete()
                Put(ok = false, error = e.javaClass.simpleName)
            }
        } finally {
            Admission.release(reservation)
        }
    }

    /**
     * [T-bodies-gc-dedupe-touch] 把「这个正文最后一次被引用」推到 [nowMillis]。
     *
     * `put` 在内容已存在时直接返回：不重写文件、也不动 mtime。而回收侧
     * （`ChatRepository.collectOrphanBodies`）的宽限期**只认** `file.lastModified`，
     * 于是「盘上放了很久、这次却被重新引用的正文」在回收侧看来就是孤儿：另一个 GC
     * 在这条新行的 `body_ref` 落库**之前**读完 live 集合，就会把它 unlink —— 新行
     * 指针悬空，气泡与模型上下文永久退回 stub（`body stored (N bytes)`）。
     *
     * 去重命中代表"这个内容此刻正被引用"，所以把 mtime 推到当前时刻，让宽限期从本次
     * 引用重新起算。
     */
    private fun touch(dest: File, nowMillis: Long = System.currentTimeMillis()): Boolean =
        dest.isFile && runCatching { dest.setLastModified(nowMillis) }.getOrDefault(false)

    /**
     * [T-bodies-gc-touch-fallback] 刷新"这个正文此刻被引用"的盘上痕迹，**失败不许静默**。
     *
     * mtime 是回收侧唯一的宽限期依据（见 [touch]），刷新失败就等于这次引用在回收侧
     * 不可见：盘上很旧的文件会被当成孤儿删掉，而指向它的新行还活着。所以 mtime 刷不动
     * 时退化为原子重写 —— `rename` 出来的是新 inode，mtime 必然落在当下（同一条规律也是
     * 首次写入不需要额外 touch 的原因）。两条路都不行（只读挂载 / 目录被占）时留下
     * warning：这是"引用刷新失败"，不是可以吞掉的枝节。
     *
     * 调用方必须持有 [finalizeLock]：重写本身会覆盖 [dest]，不锁就又会和回收侧的 unlink
     * 抢同一个路径。
     */
    private fun refreshReference(dest: File, bytes: ByteArray): Boolean {
        if (touch(dest)) return true
        val tmp = File(root, "${dest.name}.${UUID.randomUUID()}.tmp")
        val landed = runCatching {
            writeFramed(tmp, bytes)
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
            }
            true
        }.getOrDefault(false)
        if (!landed) {
            runCatching { tmp.delete() }
            AppLogger.warning(
                TAG,
                "[T-bodies-gc] 正文引用刷新失败（mtime 与重写均不可用），${dest.name} 可能被当成孤儿回收",
            )
        } else if (tmp.exists()) {
            tmp.delete()
        }
        return landed
    }

    fun read(ref: String, maxBytes: Int = ResourceLimits.SQL_CELL_BYTES): ByteArray? {
        if (ref.length != 64 || ref.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val file = File(root, ref)
        if (!file.isFile) return null
        val length = file.length()
        if (length < HEADER || length > HEADER + ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()) return null
        // Read framing without copying the stored payload. Reserve output plus a
        // bounded scratch chunk; the caller receives at most maxBytes.
        val inputLength = length - HEADER
        val header = ByteArray(HEADER)
        val stream = try { file.inputStream() } catch (_: Exception) { return null }
        val parsed = try {
            DataInputStream(stream).use { input ->
                input.readFully(header)
                val magicOk = header[0] == 'O'.code.toByte() && header[1] == 'M'.code.toByte() &&
                    header[2] == 'B'.code.toByte() && header[3] == '1'.code.toByte()
                if (!magicOk) return null
                Triple(intAt(header, 4), intAt(header, 8), header[12].toInt() and 0xff)
            }
        } catch (_: Exception) { return null }
        val (uncompressed, stored, codec) = parsed
        if (uncompressed < 0 || uncompressed > ResourceLimits.MAX_DECLARED_UNCOMPRESSED ||
            stored < 0 || stored.toLong() != inputLength) return null
        if (codec == CODEC_RAW && uncompressed != stored) return null
        if (codec == CODEC_DEFLATE && (stored == 0 ||
                uncompressed > stored.toLong() * ResourceLimits.MAX_EXPANSION_RATIO)) return null
        if (codec != CODEC_RAW && codec != CODEC_DEFLATE) return null
        val bounded = maxBytes.coerceIn(0, uncompressed)
        val reservation = bounded.toLong() + IO_CHUNK_BYTES
        if (!Admission.tryAdmit(reservation)) return null
        try {
            val bodyPrefix = ByteArray(bounded)
            val digest = MessageDigest.getInstance("SHA-256")
            val payloadStream = try {
                FileInputStream(file).also { input ->
                    var skipped = 0L
                    while (skipped < HEADER) {
                        val n = input.skip(HEADER - skipped)
                        if (n <= 0) { input.close(); return null }
                        skipped += n
                    }
                }
            } catch (_: Exception) { return null }
            val decoded = if (codec == CODEC_RAW) payloadStream else InflaterInputStream(payloadStream)
            decoded.use { input ->
                val chunk = ByteArray(IO_CHUNK_BYTES)
                var total = 0
                var prefixWritten = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > uncompressed) return null
                    digest.update(chunk, 0, n)
                    val keep = minOf(n, bounded - prefixWritten)
                    if (keep > 0) {
                        System.arraycopy(chunk, 0, bodyPrefix, prefixWritten, keep)
                        prefixWritten += keep
                    }
                }
                if (total != uncompressed || prefixWritten != bounded) return null
            }
            if (!digest.digest().joinToString("") { "%02x".format(it) }.equals(ref, ignoreCase = false)) return null
            return bodyPrefix
        } catch (_: Exception) {
            return null
        } finally {
            Admission.release(reservation)
        }
    }

    private fun writeFramed(tmp: File, bytes: ByteArray) {
        // Keep the overridable atomic writer as the failure-injection seam used
        // by storage tests and production disk-full handling.
        writeAtomic(tmp, MAGIC + intBytes(bytes.size) + intBytes(bytes.size) +
            byteArrayOf(CODEC_RAW.toByte()) + bytes)
    }

    internal open fun writeAtomic(tmp: File, bytes: ByteArray) {
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    /** Remove only abandoned temps old enough that no current put can own them. */
    fun discardTemps(maxAgeMillis: Long = TEMP_MAX_AGE_MILLIS, nowMillis: Long = System.currentTimeMillis()) {
        root.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { file ->
            val modified = file.lastModified()
            if (modified > 0 && nowMillis - modified >= maxAgeMillis) file.delete()
        }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_SPEED)).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun inflateCapped(payload: ByteArray, declared: Int): ByteArray? {
        val out = ByteArray(declared)
        return try {
            InflaterInputStream(ByteArrayInputStream(payload)).use { input ->
                var off = 0
                while (off < declared) {
                    val n = input.read(out, off, declared - off)
                    if (n < 0) return null
                    off += n
                }
                if (input.read() != -1) return null
            }
            out
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun readBounded(file: File): ByteArray? {
        val len = file.length()
        if (len < HEADER || len > HEADER + ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()) return null
        val out = ByteArray(len.toInt())
        file.inputStream().use { input ->
            var off = 0
            while (off < out.size) {
                val n = input.read(out, off, out.size - off)
                if (n < 0) return null
                off += n
            }
            if (input.read() != -1) return null
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun intAt(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xff) shl 24) or
            ((raw[offset + 1].toInt() and 0xff) shl 16) or
            ((raw[offset + 2].toInt() and 0xff) shl 8) or
            (raw[offset + 3].toInt() and 0xff)

    companion object {
        private const val TAG = "BodyStore"
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        private const val CODEC_RAW = 1
        private const val CODEC_DEFLATE = 2
        private const val HEADER = 13
        private const val IO_CHUNK_BYTES = 64 * 1024
        private const val TEMP_MAX_AGE_MILLIS = 15L * 60L * 1000L
        private val finalizeLock = Any()

        /**
         * [T-bodies-gc-toctou] 回收侧（`ChatBodyGc.reclaimOrphanBodies`）unlink 之前的
         * 最后一次「重看 mtime」必须与 [put] 的引用刷新/落盘互斥。
         *
         * 只靠 mtime 判定就有 TOCTOU：回收侧读完 mtime 之后、unlink 之前，[put] 完全可能
         * 刚把这个正文重新引用起来（去重命中 → refreshReference），于是删除抹掉了一个活
         * 引用，新行的 body_ref 悬空。同一把锁把「确认无人引用 + 删除」压成原子：谁先拿到
         * 锁结果都安全 —— 先 put 则 mtime 已刷新，回收侧看得见宽限期；先 unlink 则 put
         * 发现文件不在了，走首次写入路径把它原子重建出来。
         *
         * 持锁期间只做 stat / unlink（极少数情况下加一次重写），不回调 [put]，也不跨挂起
         * 点，所以短且不会死锁。
         */
        internal fun <T> withCommitLock(block: () -> T): T = synchronized(finalizeLock) { block() }
    }
}

/**
 * [T-bodies-gc-pin] 进程内「这些正文正在被使用，回收侧不许动」登记表。
 *
 * 备份导出是唯一需要它的地方：导出必须先把引用集合冻结成一个时间点
 * （`BackupBodySnapshot`），再逐行写 `messages.jsonl`，最后才把正文文件拷进包。中间隔着
 * 任意长的时间，而用户在此期间删掉一个会话就会触发一轮回收 —— 回收只看"此刻的库引用"，
 * 看不见备份手上那份快照，于是包里的行还在、正文文件已经没了：恢复后 body_ref 悬空，那条
 * 消息永久退回 stub。
 *
 * 登记的是**集合对象本身**而不是拷贝：导出过程中新写入的行引用（`recordWritten`）经由同
 * 一个集合对回收侧可见，所以调用方必须传并发安全的集合。回收侧先用 [snapshot] 并进 live
 * 集合做候选早筛（[T-bodies-gc-pin]），删除前再在提交锁内逐文件 [isPinned] 确认
 * （[T-bodies-gc-pin-lock]）—— 只看快照会漏掉循环进行中才加进来的 ref。于是「被导出按住的
 * 正文」永远不会被当成孤儿。
 */
object BodyGcPins {
    /** 按住 [refs]；返回的 [Handle] 必须在导出结束后 close（`finally`）。 */
    fun pin(refs: MutableSet<String>): Handle {
        val entry = Entry(refs)
        bodyGcPinRegistries.add(entry)
        return Handle(entry)
    }

    fun isPinned(ref: String): Boolean = bodyGcPinRegistries.any { ref in it.refs }

    /** 当前被按住的全部 ref。 */
    fun snapshot(): Set<String> = bodyGcPinRegistries.flatMapTo(HashSet()) { it.refs }

    /** 测试与异常路径的兜底。 */
    internal fun clearForTests() {
        bodyGcPinRegistries.clear()
    }

    /**
     * 登记项。**故意不是 data class**：[refs] 在导出过程中会被 `recordWritten` 改变内容，
     * 它的 `hashCode` 也会跟着变。直接拿可变集合当 map 的 key，close 时的 remove 会落到
     * 错误的桶里 —— pin 再也解除不掉，那些正文从此不会被回收（JVM 探针实测复现）。
     * 包装成身份相等的对象，key 就永远稳定。
     */
    internal class Entry(val refs: MutableSet<String>)

    class Handle internal constructor(private val entry: Entry) : AutoCloseable {
        override fun close() {
            bodyGcPinRegistries.remove(entry)
        }
    }
}

/** 并发集合（文件级私有）：pin 登记的是集合对象本身，读写可能来自不同线程。 */
private val bodyGcPinRegistries: MutableSet<BodyGcPins.Entry> =
    java.util.concurrent.ConcurrentHashMap.newKeySet()
