package com.openminis.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [T-p1-delegate-task] 12 → 13 adds `parent_session_id` / `parent_tool_use_id`
 * to `sessions`; 13 → 12 is a no-op that keeps them. Mirrors
 * MessageAttributionMigrationTest. NOTE: connectedAndroidTest UNINSTALLS the
 * app under test — run only on a device whose data is disposable.
 */
@RunWith(AndroidJUnit4::class)
class ChildSessionMigrationTest {
    private val dbName = "child-session-migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun upgrade12to13_existingRowsReadNullParent() {
        helper.createDatabase(dbName, 12).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, title, model_id, created_at, updated_at, memory_enabled) " +
                    "VALUES ('s1', 'top', 'm', 1, 1, 1)"
            )
        }
        helper.runMigrationsAndValidate(dbName, 13, true, AppDatabase.MIGRATION_12_13).use { db ->
            db.query("SELECT parent_session_id, parent_tool_use_id FROM sessions WHERE id='s1'").use { c ->
                assertTrue(c.moveToFirst())
                assertNull(c.getString(0)); assertNull(c.getString(1))
            }
        }
    }

    /**
     * [T-b11-no-downgrade-migrations] 上游 1.14 的 13 → 12 降级迁移在本移植里
     * **不存在**，因此这条用例没有可跑的迁移对象：本分支把降级迁移从生产代码里
     * 全部移除（不声明、不注册），理由见 `DatabaseVersionGuardTest` 的同名用例与
     * `AppDatabase` 里那段说明——Room 处理的降级用的是**当前运行的旧构建**编译进去
     * 的表，放在源码树里的降级迁移永远不会执行；`DatabaseVersionGuard` 也会把
     * onDisk > CODE_DB_VERSION 的库直接送进引导页。
     *
     * 这里显式 Assume 跳过（而不是删掉断言、也不是让生产代码留一条永不执行的迁移），
     * 把「本移植不提供降级路径」这件事写成可执行的文档：一旦哪天真的重新引入降级
     * 迁移，这条用例会立刻以「假设不成立」的形式重新出现在报告里，而不是静默消失。
     *
     * 升级方向（12 → 13 加两列、旧行读 NULL）仍由上面的用例覆盖，未受影响的语义
     * 一个不少。
     */
    @Test
    fun downgrade13to12_keepsChildColumnsAndValues() {
        org.junit.Assume.assumeTrue(
            "本移植按 [T-b11-no-downgrade-migrations] 不提供任何降级迁移；" +
                "13 → 12 没有可验证的迁移对象（旧构建才需要它，且它永远不会被执行）",
            false,
        )
    }
}
