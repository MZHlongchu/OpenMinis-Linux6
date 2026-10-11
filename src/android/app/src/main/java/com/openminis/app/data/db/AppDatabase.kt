package com.openminis.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.openminis.app.data.db.CodeIndexDao
import com.openminis.app.data.db.CodeSymbolEntity
import com.openminis.app.data.db.CodeEdgeEntity
// [T-b1-db-gate-off-main] 冷启动完整性闸门的后台预热/消费。
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking

@Database(
    entities = [
        ChatSessionEntity::class,
        MessageEntity::class,
        CompactMarkerEntity::class,
        WebAppShortcutEntity::class,
        FolderEntity::class,
        CodeSymbolEntity::class,
        CodeEdgeEntity::class,
        SessionGoalEntity::class,
        MessageVersionEntity::class,
    ],
    version = 23, // keep DatabaseVersionGuard.CODE_DB_VERSION in lockstep
    // Kept ON so MigrationTestHelper and CI can validate every migration
    // against the committed schema json. Without it the upgrade chain has no
    // automated check at all and only a real device install can catch a break.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun webAppShortcutDao(): WebAppShortcutDao
    abstract fun codeIndexDao(): CodeIndexDao
    abstract fun goalDao(): GoalDao
    abstract fun messageVersionDao(): MessageVersionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /**
         * [T-b1-db-gate-off-main] 冷启动完整性闸门的后台预热句柄。
         *
         * `DatabaseHealthCheck.ensureHealthyOrQuarantined` 会跑一次
         * `PRAGMA quick_check` —— 遍历整库 b-tree 的冷页读，耗时随库体积线性
         * 增长。它原先在 [getInstance] 里同步执行，而 [getInstance] 是
         * `Application.onCreate` 主线程调用的，于是整段扫描都计在首帧之前。
         *
         * 现在 `MinisApp` 在 onCreate 早期用 [warmStartupGate] 把闸门丢到
         * `Dispatchers.IO`，与其余启动初始化并行；[getInstance] 只消费这个
         * 结果。闸门仍然严格早于 Room 建库（`build()` 之前），进程内只跑一次，
         * 失败语义（QuarantineFailed → 抛异常）与顺序都不变。
         */
        @Volatile
        private var startupGate: Deferred<DatabaseHealthCheck.Outcome>? = null

        /**
         * 把完整性闸门提前调度到 [scope] 上（幂等，进程级一次）。预热未完成时
         * [getInstance] 等的是同一个 job，不会重复扫描。
         */
        fun warmStartupGate(context: Context, scope: CoroutineScope) {
            if (startupGate != null) return
            synchronized(this) {
                if (startupGate != null) return
                val app = context.applicationContext
                startupGate = scope.async(Dispatchers.IO) {
                    DatabaseHealthCheck.ensureHealthyOrQuarantined(app, "minis.db")
                }
            }
        }

        /**
         * 闸门结论：消费 [warmStartupGate] 的结果（正常路径下它早已跑完，等待为
         * 零）。没有预热过（非 MinisApp 入口 / 测试）时在这里补建同一个 job ——
         * 监视器保证只建一次，并发调用不会重复扫描，闸门也始终不跑在调用线程上。
         */
        private fun consumeStartupGate(context: Context): DatabaseHealthCheck.Outcome {
            val warmed = startupGate ?: synchronized(this) {
                startupGate ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
                    DatabaseHealthCheck.ensureHealthyOrQuarantined(context.applicationContext, "minis.db")
                }.also { startupGate = it }
            }
            return runBlocking { warmed.await() }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("sessions", "last_message", "TEXT")
                db.addColumnIfMissing("sessions", "model_binding", "TEXT")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("messages", "reasoning_content", "TEXT")
            }
        }

        /**
         * compact_markers: add Phase-A id-first boundary columns. The legacy
         * sort_order columns stay for backfill; when both are present the
         * id-first fields win on lookup (see ChatDao.latestCompactMarker).
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("compact_markers", "first_kept_message_id", "TEXT")
                db.addColumnIfMissing("compact_markers", "last_compacted_message_id", "TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_first_kept_message_id ON compact_markers(first_kept_message_id)")
            }
        }

        /**
         * T239: per-session thinking-mode override. Nullable so existing
         * sessions transparently keep "unset" semantics; only sessions where
         * the user explicitly chooses a level start storing a non-null value.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("sessions", "thinking_override", "TEXT")
            }
        }

        /**
         * T-pwa-1: pwa_shortcuts table backs the home-screen PWA pinning
         * flow. Pure additive migration — no existing entity is modified
         * and no data is rewritten.
         *
         * Superseded by MIGRATION_8_9 below (Pwa → WebApp rename); kept
         * here so users who already migrated from <=6 land on a
         * consistent state before the rename runs.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pwa_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * compact_markers: add `version` column for marker schema versioning.
         * Mirrors iOS Phase v2 — version=1 = legacy multi-field model,
         * version=2 = simplified id-only anchor model. Existing rows default
         * to 1 so legacy resolution code keeps running for them.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("compact_markers", "version", "INTEGER NOT NULL DEFAULT 1")
            }
        }

        /**
         * Pwa → WebApp rename: copy every row from `pwa_shortcuts` into a
         * new `webapp_shortcuts` table with identical schema, then drop
         * the old table. Row contents (UUIDs, html paths, icon refs) are
         * preserved verbatim — only the table name changes — so existing
         * in-app shortcut lists keep showing the same entries.
         *
         * Note: pinned launcher icons created before this rename still
         * carry the old `ACTION_OPEN_PWA` intent action and will be dead
         * after the upgrade (manifest no longer registers it). The user
         * has to re-pin from inside the app. Per
         * `feedback_no_destructive_git` we do NOT silently delete data —
         * the DB row stays, only the launcher-side icon dies.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS webapp_shortcuts (
                        id TEXT NOT NULL PRIMARY KEY,
                        html_path TEXT NOT NULL,
                        path_scope TEXT NOT NULL,
                        scope_context TEXT,
                        title TEXT NOT NULL,
                        icon_ref TEXT NOT NULL,
                        icon_cache_path TEXT,
                        created_at INTEGER NOT NULL,
                        source_session_id TEXT
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO webapp_shortcuts (
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    )
                    SELECT
                        id, html_path, path_scope, scope_context, title,
                        icon_ref, icon_cache_path, created_at, source_session_id
                    FROM pwa_shortcuts
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS pwa_shortcuts")
            }
        }

        /**
         * [T-error-persist-android] messages.error_info — persist the terminal
         * error sticker on an assistant turn so the inline error survives a
         * session reload (mirrors iOS messages.error_info). Pure additive,
         * nullable column; existing rows read back NULL (= no error). No data
         * rewrite.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("messages", "error_info", "TEXT")
            }
        }

        /**
         * [T-android-session-grouping] Session groups. Adds the `folders` table
         * and `sessions.folder_id`.
         *
         * Purely additive: existing sessions read back `folder_id = NULL`
         * (= ungrouped), which is exactly the pre-migration behaviour, so no
         * data is rewritten and a downgrade loses only the grouping.
         *
         * `folder_id` carries NO foreign key on purpose — an id pointing at a
         * group that is not present locally must render as ungrouped rather
         * than fail a constraint (see ChatSessionEntity.folderId). The index is
         * plain and non-unique: many sessions share one group.
         */
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        icon TEXT,
                        color TEXT,
                        origin TEXT NOT NULL DEFAULT 'manual',
                        sort_index INTEGER NOT NULL DEFAULT 0,
                        pinned_at INTEGER,
                        description TEXT,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.addColumnIfMissing("sessions", "folder_id", "TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_folder_id ON sessions(folder_id)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // sessions: add iOS-parity columns
                db.addColumnIfMissing("sessions", "source", "TEXT")
                db.addColumnIfMissing("sessions", "memory_enabled", "INTEGER NOT NULL DEFAULT 1")
                db.addColumnIfMissing("sessions", "pinned_at", "INTEGER")
                db.addColumnIfMissing("sessions", "edit_count", "INTEGER NOT NULL DEFAULT 0")

                // messages: add iOS-parity columns
                db.addColumnIfMissing("messages", "stream_interrupt_count", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("messages", "updated_at", "INTEGER")

                // compact_markers: new table mirroring iOS
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS compact_markers (
                        id TEXT NOT NULL PRIMARY KEY,
                        session_id TEXT NOT NULL,
                        summary TEXT NOT NULL,
                        first_kept_sort_order INTEGER NOT NULL,
                        compacted_count INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        ui_boundary_sort_order INTEGER,
                        boundary_message_id TEXT,
                        FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_compact_markers_session_id ON compact_markers(session_id)")
            }
        }

        /**
         * [T-token-attribution-snapshot] Per-message model attribution.
         *
         * Four nullable columns, no DEFAULT. `ADD COLUMN` is an O(1) metadata
         * change in SQLite — existing rows are untouched and simply read NULL,
         * which is the signal the Usage page uses to mark a row "estimated"
         * rather than "measured". A `NOT NULL DEFAULT ''` would make old rows
         * indistinguishable from new ones that genuinely have no model.
         */
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("messages", "model_id", "TEXT")
                db.addColumnIfMissing("messages", "model_display_name", "TEXT")
                db.addColumnIfMissing("messages", "provider_type", "TEXT")
                db.addColumnIfMissing("messages", "provider_instance_id", "TEXT")
            }
        }

        /**
         * [T-b11-no-downgrade-migrations] 降级不受支持——本文件**不要**再声明
         * `Migration(N+1, N)` 这类降级迁移。这是 schema 变更清单里的一条硬规则。
         *
         * ## 为什么注册降级迁移是无效代码
         *
         * Room 解析降级用的是**正在运行的那个更旧 APK** 里注册的迁移表：更旧的构建
         * 打开本构建的库时，需要的是它自己那份注册表里的 N+1 → N，而它早已发布，
         * 不可能包含本构建此刻新增的迁移。本构建里注册的降级迁移只有「更旧的构建」
         * 用得上——那些构建不存在。再加上 [com.openminis.app.data.db.DatabaseVersionGuard]
         * 对 onDisk > CODE_DB_VERSION 一律走 guidance 屏（默认拒绝，不碰文件），
         * 所以 12→11 … 22→21 这批降级迁移从诞生起就不可达。其中 22→21 更糟：
         * 它会 `DROP TABLE message_versions`，是唯一会删用户数据的降级迁移——一旦
         * 版本探针打不开库（`readOnDiskVersion` 返回 null 就 PROCEED）而 Room 能打开，
         * 它就会静默删掉整个消息归档。
         *
         * ## 现在的降级语义
         *
         * 降级 = 只显示 guidance 屏：数据一个字节都不动，装回新版本即恢复。这比
         * 「注册一条自以为安全的迁移」更诚实，也是守卫默认拒绝的方向（代价是要用户
         * 升回去，而不是静默损坏或丢数据）。
         *
         * ## 加列 / 加表 / 加索引仍然保持向后可忽略形态
         *
         * Room 的 DAO 按列名绑定、schema 校验只要求实体用到的列存在，多余的列与表
         * 会被旧构建忽略——前提是新列 **nullable 且无 DEFAULT**（旧 INSERT 不带它）。
         * 重命名 / 删除 / 改类型 / 改语义没有安全降级，只能靠上面的守卫拦下来。
         */
        /**
         * [T-code-graph] Adds the code_symbols and code_edges tables for the
         * lightweight code index used by CodeGraphTool. Pure additive — no
         * existing entity is modified and no data is rewritten.
         *
         * [T-merge-1.14] Also absorbs upstream 1.14's child-session columns
         * (`parent_session_id` + `parent_tool_use_id`, design v4 §3.1), which
         * upstream ships as its own 12 → 13. Both sides bump 12 → 13 to
         * different content and only one Migration(12, 13) may exist, so the
         * two are folded into this single step: a v12 database that walks this
         * branch's chain to v22 must end up with BOTH the code-graph tables and
         * the two columns, or Room's open-time entity validation aborts.
         *
         * They stay nullable TEXT with no DEFAULT and no index — children are
         * only ever enumerated on parent delete, and the sessions table is
         * small. Existing rows read NULL = "top-level session", which is
         * exactly what they are.
         */
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // [T-p1-delegate-task] Child-session ownership. Written with
                // addColumnIfMissing rather than a bare ALTER so a partial
                // upgrade that already added a column (and rolled the version
                // back) does not fail with "duplicate column name".
                db.addColumnIfMissing("sessions", "parent_session_id", "TEXT")
                db.addColumnIfMissing("sessions", "parent_tool_use_id", "TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS code_symbols (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        root TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        name TEXT NOT NULL,
                        qualifiedName TEXT NOT NULL DEFAULT '',
                        kind TEXT NOT NULL DEFAULT '',
                        startLine INTEGER NOT NULL DEFAULT 0,
                        endLine INTEGER NOT NULL DEFAULT 0,
                        signature TEXT NOT NULL DEFAULT '',
                        indexedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_root ON code_symbols(root)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_name ON code_symbols(name)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_symbols_filePath ON code_symbols(filePath)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS code_edges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        root TEXT NOT NULL,
                        filePath TEXT NOT NULL,
                        kind TEXT NOT NULL DEFAULT '',
                        fromName TEXT NOT NULL DEFAULT '',
                        toName TEXT NOT NULL DEFAULT '',
                        line INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_root ON code_edges(root)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_fromName ON code_edges(fromName)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_code_edges_toName ON code_edges(toName)")
            }
        }

        // [T-b11-no-downgrade-migrations] 上游 1.14 在这里还带了一整套「降级迁移」
        // （13 → 12 的空操作、12 → 11，以及 14 → 13 的 DROP INDEX），本分支一律
        // **不声明、不注册**，见 DatabaseVersionGuardTest 的同名用例：
        //
        //   * Room 用**当前运行的（旧）构建**编译进来的表来处理降级，所以放在本
        //     源码树里的降级迁移永远不可能执行——[DatabaseVersionGuard] 会把
        //     onDisk > CODE_DB_VERSION 的库直接送进引导页，而真需要它的旧构建早
        //     于它存在；
        //   * 它们还曾经是一条静默数据丢失通道（最后一条降级会丢掉
        //     `message_versions`）；
        //   * 声明本身就是缺陷：哪怕不可达也有误导性。
        //
        // 上游 1.14 的 `ChildSessionMigrationTest` 有一半用例（「降级保留子会话
        // 两列」）依赖其中一个常量，本移植无法提供，那个用例已改为显式 Assume
        // 跳过并写明原因，而不是让生产代码保留一条永远不会跑的迁移。
        //
        // 语义上确实没有损失：子会话那两列是可空 TEXT，降级后旧构建按列名绑定、
        // 忽略它们（见 ChatSessionEntity 的 [parentSessionId] 说明）。

        /**
         * [T-kanban] Adds the kanban_tasks table for the task board.
         * Pure additive — no existing entity is modified and no data is rewritten.
         */
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS kanban_tasks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT,
                        status TEXT NOT NULL DEFAULT 'todo',
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * [T-kanban-removal] Drops the kanban_tasks table (feature removed:
         * no entry point, no readers). Pure destructive on a table nothing
         * references anymore; chat/code-index data is untouched.
         */
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS kanban_tasks")
            }
        }

        /**
         * [T-android-sessions-updated-at-index] 15 -> 16: the session-list
         * query (`SELECT * FROM sessions ORDER BY updated_at DESC`) ran as a
         * full scan + sort; the index lets SQLite walk it in order.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_sessions_updated_at` ON `sessions` (`updated_at`)",
                )
            }
        }

        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // [T-compact-chunk-pool] Additive only: per-compaction summary
                // chunks for retrieval. Legacy rows stay NULL and keep
                // reading through `summary`.
                db.addColumnIfMissing("compact_markers", "summary_chunks", "TEXT")
            }
        }

        /**
         * Adds body metadata only. Does not rewrite parts_json. length(CAST AS BLOB)
         * is the UTF-8 byte count and stays inside SQLite. Preview for oversized
         * rows is a short JSON text part built with json_quote, so the hot path
         * can show the session without pulling the cell into Java.
         */
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("messages", "body_bytes", "INTEGER NOT NULL DEFAULT 0")
                db.addColumnIfMissing("messages", "body_ref", "TEXT")
                db.addColumnIfMissing("messages", "body_sha", "TEXT")
                db.addColumnIfMissing("messages", "preview", "TEXT")
                db.addColumnIfMissing("compact_markers", "summary_ref", "TEXT")
                db.execSQL("UPDATE messages SET body_bytes = length(CAST(parts_json AS BLOB))")
                db.execSQL(
                    "UPDATE messages SET preview = CASE " +
                        "WHEN body_bytes <= 2048 THEN parts_json " +
                        "ELSE '[{\"type\":\"text\",\"value\":' || json_quote(substr(parts_json, 1, 2000)) || '}]' " +
                        "END",
                )
            }
        }

        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS session_goals (
                        session_id TEXT NOT NULL PRIMARY KEY,
                        objective TEXT NOT NULL,
                        status TEXT NOT NULL DEFAULT 'active',
                        token_budget INTEGER,
                        tokens_used INTEGER NOT NULL DEFAULT 0,
                        elapsed_ms INTEGER NOT NULL DEFAULT 0,
                        blocked_condition TEXT,
                        blocked_count INTEGER NOT NULL DEFAULT 0,
                        paused_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        /** Re-number legacy duplicate cursors deterministically before enforcing uniqueness. */
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.beginTransaction()
                try {
                    db.execSQL("DROP INDEX IF EXISTS `index_messages_session_id_sort_order`")
                    db.execSQL("""
                        UPDATE messages
                        SET sort_order = (
                            SELECT COUNT(*) - 1
                            FROM messages AS prior
                            WHERE prior.session_id = messages.session_id
                              AND (prior.created_at < messages.created_at OR
                                   (prior.created_at = messages.created_at AND prior.id <= messages.id))
                        )
                    """.trimIndent())
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_messages_session_id_sort_order` " +
                            "ON `messages` (`session_id`, `sort_order`)"
                    )
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }

        /** [T-msg-version-archive] message_versions — see MessageVersionEntity. */
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS `message_versions` (
                        `id` TEXT NOT NULL,
                        `message_id` TEXT NOT NULL,
                        `session_id` TEXT NOT NULL,
                        `version_index` INTEGER NOT NULL,
                        `role` TEXT NOT NULL,
                        `parts_json` TEXT,
                        `body_ref` TEXT,
                        `body_sha` TEXT,
                        `reasoning_content` TEXT,
                        `error_info` TEXT,
                        `model_id` TEXT,
                        `model_display_name` TEXT,
                        `source_created_at` INTEGER NOT NULL,
                        `archived_at` INTEGER NOT NULL,
                        PRIMARY KEY(`id`),
                        FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )""".trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_message_versions_message_id_version_index` " +
                        "ON `message_versions` (`message_id`, `version_index`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_message_versions_session_id` " +
                        "ON `message_versions` (`session_id`)"
                )
            }
        }

        /**
         * [T-upstream-1-14-session-parent] 上游 1.14 的 12→13 增量（子会话委派）：
         * 我们的谱系在 12→13 建的是 `code_symbols`（版本号同名、DDL 不同），所以
         * 上游那两列在本移植里必须由**本迁移**补上——照搬上游的 `Migration(12, 13)`
         * 永远不会执行（我们的库早已越过 12）。`addColumnIfMissing` 是幂等的，
         * 因此从上游 1.13 直升与本移植旧版升级两条来源都安全。
         *
         * sessions 上的 `index_sessions_updated_at` 我们在 15→16 已建（同名同定义），
         * 上游 1.14 的 13→14 也只加了它，故此处不重复。
         */
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("sessions", "parent_session_id", "TEXT")
                db.addColumnIfMissing("sessions", "parent_tool_use_id", "TEXT")
            }
        }

        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.addColumnIfMissing("sessions", "permission_mode", "TEXT")
                // Pre-2.0.4 rows have NULL. Force ASK so upgraded chats
                // do not inherit the old global ALLOW_ALL / READ_ONLY prefs.
                db.execSQL("UPDATE sessions SET permission_mode = 'ASK' WHERE permission_mode IS NULL OR TRIM(permission_mode) = ''")
            }
        }

        // [T-merge-1.14] 上游 1.14 在这里另外声明了四个迁移对象，出处与去向：
        //   * 两个「给 sessions 加子会话列」的语句：合并进上面的 12 → 13（见那里的
        //     说明），否则 v12 库沿本分支链条升到 v22 时 sessions 永远不会有这两列，
        //     而 Room 开库时按实体校验列集合，会直接 IllegalStateException。
        //   * 一个「给 sessions 加 index_sessions_updated_at」的 13 → 14：该索引早已
        //     由本分支的 15 → 16 建立，上游那条在这里是重复语句，且会把 v14 的 schema
        //     快照（14.json 里没有该索引）对不上，故不重复声明。
        //   * 其余四条**降级**迁移（版本号递减 / DROP INDEX）：不采纳，理由与写法见
        //     上面 [T-b11-no-downgrade-migrations] 那段——本文件只允许出现升序迁移。

        fun getInstance(context: Context): AppDatabase {
            INSTANCE?.let { return it }
            // [T-b1-db-gate-off-main] 闸门等待放在监视器之外：预热 job 只碰文件
            // 与日志，等待期间不持有本对象的锁，避免与 IO 线程形成锁环。
            val gate = consumeStartupGate(context)
            return synchronized(this) {
                INSTANCE ?: run {
                // [T-db-startup-integrity-gate] quick_check BEFORE Room opens
                // the file. A corrupt database is quarantined (renamed aside,
                // never opened, never silently erased) and the app starts on
                // a fresh empty database; MainActivity surfaces the pending
                // failure report so the user learns where their data copy is.
                when (gate) {
                    is DatabaseHealthCheck.Outcome.QuarantineFailed ->
                        throw IllegalStateException(
                            "minis.db failed integrity check and could not be " +
                                "quarantined: ${gate.reason}"
                        )
                    // [T-db-gate-probe-unavailable] 探针没判出结论（打不开≠损坏）：
                    // 文件原封不动，按常规路径交给 Room——隔离健康库才是要避免的。
                    is DatabaseHealthCheck.Outcome.Unavailable -> Unit
                    else -> Unit
                }
                Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "minis.db"
                )
                    // 只注册升级迁移（N-1 → N）。降级迁移在这里注册也永远不会被
                    // 执行，见 [T-b11-no-downgrade-migrations]：解析降级的是运行中
                    // 的那个更旧 APK，它不可能含有本构建新增的迁移。
                    .addMigrations(
                        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                        MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11,
                        MIGRATION_11_12, MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                        MIGRATION_20_21,
                        MIGRATION_21_22,
                        MIGRATION_22_23,
                    )
                    .build()
                    .also { INSTANCE = it }
                }
            }
        }
    }
}
