package com.openminis.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.openminis.app.logging.AppLogger

/**
 * [T-android-downgrade-compat] Detects a database written by a NEWER build
 * than the one now running, before Room ever opens it.
 *
 * ## The failure this prevents
 *
 * Room resolves a downgrade by calling `onUpgrade(from, to)` and looking for a
 * migration path. Finding none it consults `isMigrationRequired`, which either
 * throws `IllegalStateException` or — with a destructive fallback enabled —
 * calls `dropAllTables`. We enable no fallback (deliberately: dropping would
 * destroy the user's entire chat history), so an unmatched downgrade means the
 * app throws at first database access and simply will not start.
 *
 * [T-b11-no-downgrade-migrations] There used to be a `MIGRATION_12_11` and a
 * whole chain of registered downgrade migrations, on the theory that
 * registering a no-op path lets an older build open a newer database. It does
 * not, and never could: the process that resolves a downgrade is the OLDER
 * build, using the migration table compiled into IT — which was released
 * before the newer migration existed. Every downgrade migration this build
 * registers is therefore unreachable, and the guard's default deny below is
 * what actually decides. They were removed (see AppDatabase's
 * [T-b11-no-downgrade-migrations] block); this guard is the downgrade story,
 * and it costs the user nothing but a screen: the file is never touched.
 *
 * ## Why a pre-check instead of catching Room's exception
 *
 * `Room.databaseBuilder(...).build()` is lazy: nothing opens until the first
 * DAO call, so the exception surfaces from an arbitrary coroutine somewhere
 * deep in the app. Every one of those call sites would need a catch, and one
 * miss is still a crash. Reading the version first is deterministic and
 * happens once, in one place.
 *
 * Crucially, this runs BEFORE Room is constructed — so at the moment we
 * decide, no migration and no `dropAllTables` can possibly have executed. The
 * database file is guaranteed untouched.
 */
object DatabaseVersionGuard {

    private const val TAG = "DbVersionGuard"

    /**
     * The schema version this build understands. MUST equal the `version` in
     * [AppDatabase]'s `@Database` annotation.
     *
     * Kept as a separate constant because the annotation value is not readable
     * at runtime without reflection. [DatabaseVersionGuardTest] asserts the two
     * agree, so they cannot drift apart silently — a stale copy here would
     * either disable the guard or trip it on every launch.
     *
     * 1.31 bumped [AppDatabase] 12 → 14 (code graph + kanban) but left this at
     * 12. After Room migrated on-disk `user_version` to 14, the next launch
     * treated the user's own database as "from a newer build" and blocked
     * opening. Keep this equal to `@Database(version=...)`.
     */
    const val CODE_DB_VERSION = 23 // upstream 1.14: sessions.parent_session_id / parent_tool_use_id 22 -> 23

    /** Filename must match the one passed to `Room.databaseBuilder`. */
    private const val DB_NAME = "minis.db"

    /**
     * Read `user_version` without going through Room.
     *
     * Opened READ-ONLY on purpose: it makes it impossible for the probe itself
     * to modify, migrate or corrupt the file. Returns null when the database
     * does not exist yet (a fresh install) or cannot be read — in both cases
     * the caller should just proceed and let Room do its normal thing.
     */
    fun readOnDiskVersion(context: Context): Int? {
        val file = context.getDatabasePath(DB_NAME)
        if (!file.exists()) return null
        return runCatching {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
                .use { it.version }
        }.getOrElse {
            // A file we cannot even open read-only is not a downgrade signal;
            // let Room report whatever the real problem is.
            AppLogger.warning(TAG, "could not probe db version: ${it.javaClass.simpleName}")
            null
        }
    }

    /**
     * True when the on-disk database is newer than this build supports, i.e.
     * the user has downgraded and Room may be unable to open it.
     *
     * Every such database is refused: no downgrade migration can ever be
     * honored (see the class doc), so there is nothing to check for an
     * exception here.
     */
    fun isFromNewerBuild(context: Context): Boolean {
        val onDisk = readOnDiskVersion(context) ?: return false
        val newer = onDisk > CODE_DB_VERSION
        if (newer) {
            AppLogger.warning(
                TAG,
                "database is from a newer build: onDisk=$onDisk code=$CODE_DB_VERSION",
            )
        }
        return newer
    }

    // [T-merge-1.14] 上游 1.14 在这里还提供过 `isHandledDowngrade(onDiskVersion)`，
    // 用来放行「有已验证降级迁移」的版本跳变。本分支不采纳，理由见上面的
    // [T-b11-no-downgrade-migrations]：降级迁移已全部删除，本构建的
    // CODE_DB_VERSION 恒大于表里写死的 11/12 两个门槛，该函数对任何输入都返回
    // false，且全仓库没有任何调用点——保留它只会让「降级可以被放行」这件事看起来
    // 还有实现。真正生效的仍是下面 evaluate() 的默认拒绝。
    /** Result of the launch-time check. */
    enum class Decision {
        /** Normal path — open the database. */
        PROCEED,

        /**
         * Database is from a newer build with no downgrade path. Show guidance
         * and do NOT touch the file; upgrading restores everything.
         */
        SHOW_NEWER_DB_GUIDANCE,
    }

    fun evaluate(context: Context): Decision {
        val onDisk = readOnDiskVersion(context) ?: return Decision.PROCEED
        if (onDisk <= CODE_DB_VERSION) return Decision.PROCEED
        return Decision.SHOW_NEWER_DB_GUIDANCE
    }
}
