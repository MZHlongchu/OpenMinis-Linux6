package com.openminis.app.data.db

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-db-gate-probe-unavailable] 三态完整性探针：把「库真的坏了」与「这会儿打不开」
 * 分开。
 *
 * 原来的 [IntegrityChecker.quickCheck] 只有两态（null=好 / 非 null=坏），于是只读
 * 连接因 WAL 待 recovery、IO/权限抖动、文件忙而抛出的异常也被当成损坏证据，
 * `DatabaseHealthCheck` 据此把一个**健康库**整体 rename 进隔离目录，用户开机进空库。
 * 真正的损坏证据只有两条：`PRAGMA quick_check` 回出了非 ok 行，或 SQLite 明确报
 * 损坏（SQLITE_CORRUPT / SQLITE_NOTADB）。
 *
 * 本文件从 `DatabaseHealthCheck` 拆出（架构门 maxFileLines 棘轮：原文件已在基线上，
 * 只许缩减）。类型仍留在这个 package，`ensureHealthyOrQuarantined(checker = …)` 的
 * 默认值与全部调用点（含测试的 SAM lambda）不变。
 */
sealed class IntegrityProbeResult {
    /** The database file passed the integrity check. */
    data object Ok : IntegrityProbeResult()

    /** The check really reported damage — quarantine is warranted. */
    data class Corrupt(val detail: String) : IntegrityProbeResult()

    /**
     * The probe could not reach a verdict (open failed for a reason that does
     * not prove corruption). NEVER a reason to quarantine — the file stays put
     * and Room opens it as usual.
     */
    data class Unavailable(val reason: String) : IntegrityProbeResult()
}

/** Runs `PRAGMA quick_check`. Returns null when healthy, detail otherwise. */
fun interface IntegrityChecker {
    /** @return null when the file passes; a short detail string otherwise. */
    fun quickCheck(dbFile: File): String?

    /**
     * [T-db-gate-probe-unavailable] Three-state probe used by the gate.
     *
     * The default keeps every existing (test/custom) checker meaningful — a
     * non-null detail means corruption, which is what those checkers mean —
     * while [AndroidIntegrityChecker] overrides it to tell "the file is
     * damaged" apart from "the file could not be opened right now".
     */
    fun probe(dbFile: File): IntegrityProbeResult = when (val detail = quickCheck(dbFile)) {
        null -> IntegrityProbeResult.Ok
        else -> IntegrityProbeResult.Corrupt(detail)
    }
}

/** Production checker backed by the platform SQLite. */
class AndroidIntegrityChecker : IntegrityChecker {
    override fun quickCheck(dbFile: File): String? = when (val result = probe(dbFile)) {
        is IntegrityProbeResult.Ok -> null
        is IntegrityProbeResult.Corrupt -> result.detail
        is IntegrityProbeResult.Unavailable -> "probe unavailable: ${result.reason}"
    }

    /**
     * [T-db-gate-probe-unavailable] A read-only open that throws does NOT prove
     * corruption: a WAL database needing recovery, a transient IO/permission
     * failure or a busy file all surface as an exception here. Retry once with
     * a writable connection (which can complete WAL recovery) before giving up;
     * only rows that really are not `ok`, or a SQLite-level corruption error,
     * count as [IntegrityProbeResult.Corrupt].
     */
    override fun probe(dbFile: File): IntegrityProbeResult {
        val readOnly = probeOnce(dbFile, writable = false)
        if (readOnly !is IntegrityProbeResult.Unavailable) return readOnly
        val writable = probeOnce(dbFile, writable = true)
        return if (writable is IntegrityProbeResult.Unavailable) {
            IntegrityProbeResult.Unavailable("read-only: ${readOnly.reason}; writable: ${writable.reason}")
        } else {
            writable
        }
    }

    private fun probeOnce(dbFile: File, writable: Boolean): IntegrityProbeResult {
        var db: SQLiteDatabase? = null
        return try {
            val flags = (if (writable) SQLiteDatabase.OPEN_READWRITE else SQLiteDatabase.OPEN_READONLY) or
                SQLiteDatabase.NO_LOCALIZED_COLLATORS
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, flags)
            val rows = mutableListOf<String>()
            db.rawQuery("PRAGMA quick_check", null)?.use { c ->
                while (c.moveToNext() && rows.size < 8) {
                    rows.add(c.getString(0) ?: "")
                }
            }
            if (rows.isEmpty() || rows.all { it.equals("ok", ignoreCase = true) }) {
                IntegrityProbeResult.Ok
            } else {
                IntegrityProbeResult.Corrupt(rows.joinToString("; ").take(500))
            }
        } catch (e: Exception) {
            if (looksLikeCorruption(e)) {
                IntegrityProbeResult.Corrupt("${e.javaClass.simpleName}: ${e.message}".take(500))
            } else {
                IntegrityProbeResult.Unavailable(
                    "open/quick_check threw ${e.javaClass.simpleName}: ${e.message}".take(500),
                )
            }
        } finally {
            runCatching { db?.close() }
        }
    }

    /** SQLITE_CORRUPT / SQLITE_NOTADB surface as these shapes; anything else is "unknown". */
    private fun looksLikeCorruption(e: Exception): Boolean {
        if (e is SQLiteDatabaseCorruptException) return true
        val message = e.message?.lowercase() ?: return false
        return "malformed" in message || "not a database" in message ||
            "corrupt" in message || "encrypted" in message
    }
}

/**
 * [T-db-gate-probe-unavailable] 「探针判不出来」的现场记录：**不动文件**，只留一份
 * kind 可区分的诊断报告（见 [pendingQuarantineReport] 的使用方——UI 不会为它弹
 * 「损坏已隔离」对话框：弹窗文案会把这件事说成数据已被移出）。
 */
internal fun reportUnavailableProbe(filesDir: File, dbName: String, reason: String, occurredAtMs: Long) {
    runCatching {
        DatabaseHealthCheck.writeReport(
            filesDir,
            DatabaseHealthCheck.StartupFailureReport(
                kind = DatabaseHealthCheck.KIND_UNAVAILABLE,
                dbName = dbName,
                detail = reason,
                quarantineDir = null,
                movedFiles = emptyList(),
                occurredAtMs = occurredAtMs,
            ),
        )
    }.onFailure {
        AppLogger.error(TAG, "[T-db-gate] unavailable report NOT written: ${it.message}")
    }
}

private const val TAG = "DbHealth"

/**
 * [T-db-gate-probe-unavailable] 需要弹「损坏已隔离」对话框的报告。
 *
 * 与 [DatabaseHealthCheck.pendingReport] 的唯一差别：跳过 [DatabaseHealthCheck.KIND_UNAVAILABLE]
 * ——那种记录只是「探针这会儿判不出来」，一个字节都没被搬走、库照常打开，而弹窗文案
 * 写的是「损坏的文件已被移出，应用使用全新的空数据库启动」。诊断记录本身仍留在
 * 盘上（support 可读），只是不当作隔离事件展示给用户。
 */
fun pendingQuarantineReport(
    context: android.content.Context,
    nowMs: Long = System.currentTimeMillis(),
): DatabaseHealthCheck.StartupFailureReport? =
    DatabaseHealthCheck.pendingReport(context.filesDir, nowMs)
        ?.takeIf { it.kind != DatabaseHealthCheck.KIND_UNAVAILABLE }
