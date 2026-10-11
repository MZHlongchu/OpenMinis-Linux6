package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-bodies-gc-backup-align] 备份/回收时间点对齐、线程归位与引用完整性的形状守卫。
 *
 * 本模块没有 Robolectric，真实的 Room 查询与 `Context` 都跑不起来；这几条结论的收益又
 * 全在"接线"上（谁先谁后、跑在哪个线程、和谁共用一把锁），所以与
 * [com.openminis.app.data.db.SessionSearchQueryShapeTest] 同口径，直接对源码断言 ——
 * 目标是把这些不变量钉成"读代码即可确认"，而不是靠人记着。
 *
 * 行为侧的证据在 [MessageBodyRefTest]（pin / 版本表 / 线程 / TOCTOU）与
 * `com.openminis.app.backup.BackupBodyExportTest`（快照与导出的对齐）。
 */
class BodyGcAlignmentShapeTest {

    private val repoSource =
        File("src/main/java/com/openminis/app/data/repository/ChatRepository.kt").readText()
    private val gcSource =
        File("src/main/java/com/openminis/app/data/repository/ChatBodyGc.kt").readText()
    private val storeSource =
        File("src/main/java/com/openminis/app/data/body/BodyStore.kt").readText()
    private val ledgerSource =
        File("src/main/java/com/openminis/app/data/body/BodyRefLedger.kt").readText()
    private val exporterSource =
        File("src/main/java/com/openminis/app/backup/BackupExporter.kt").readText()
    private val bodyExportSource =
        File("src/main/java/com/openminis/app/backup/BackupBodyExport.kt").readText()
    private val vmSource =
        File("src/main/java/com/openminis/app/ui/sessions/SessionListViewModel.kt").readText()
    private val versionDaoSource =
        File("src/main/java/com/openminis/app/data/db/MessageVersionDao.kt").readText()
    private val versionEntitySource =
        File("src/main/java/com/openminis/app/data/db/MessageVersionEntity.kt").readText()

    /**
     * 事故形态：先导出行、之后才读引用集合。期间删会话 → 正文被当孤儿跳过 → 包里留下
     * 引用它们的行却没有正文。所以快照必须在任何一行写出去之前冻结。
     */
    @Test
    fun `the body snapshot is frozen before any row is exported`() {
        val snapshotAt = exporterSource.indexOf("val bodies = BackupBodySnapshot.take(")
        val rowsAt = exporterSource.indexOf("exportChats(dataDir, trees, options.snapshotAtMillis, bodies)")
        val copyAt = exporterSource.indexOf("exportBodyFiles(File(context.filesDir, \"bodies\")")

        assertTrue("会话导出必须收到正文快照（否则又回到两个时间点）", snapshotAt > 0)
        assertTrue("会话导出必须收到正文快照（否则又回到两个时间点）", rowsAt > snapshotAt)
        assertTrue("正文拷贝在行之后是对的：集合已经冻结", copyAt > rowsAt)
        assertTrue(
            "包里的每一行都要把自己的 body_ref 并进快照",
            exporterSource.contains("bodies.recordWritten(msg.bodyRef)"),
        )
        assertTrue("导出结束必须解除 pin（否则孤儿正文再也不回收）", exporterSource.contains("bodies.release()"))
    }

    @Test
    fun `the export filter follows the frozen snapshot instead of the live database`() {
        assertTrue("快照必须建立 pin", bodyExportSource.contains("BodyGcPins.pin(refs)"))
        assertTrue("过滤只认快照", bodyExportSource.contains("snapshot.covers(body.name)"))
        assertFalse(
            "导出侧不许再看 mtime：时间点对齐靠集合，不靠文件时间",
            bodyExportSource.contains("lastModified"),
        )
        assertTrue(
            "读不出引用集合时按全部导出（fail-open：宁可多带，不能缺正文）",
            bodyExportSource.contains("return BackupBodySnapshot(null, null)"),
        )
        assertTrue(
            "本来就悬空的引用要计数上报，不许静默进包",
            bodyExportSource.contains("missingReferenced"),
        )
    }

    @Test
    fun `the reclaim runs its file layer on IO instead of the caller thread`() {
        assertTrue(
            "回收的 File 层必须在 Dispatchers.IO 上",
            gcSource.contains("withContext(Dispatchers.IO)"),
        )
        assertTrue(
            "ChatRepository 只做委托（调用方在 Main.immediate 上 await）",
            repoSource.contains("reclaimOrphanBodies(filesDir, dao, versionDao, candidates)"),
        )
        assertFalse(
            "回收的目录扫描/unlink 不许再留在棘轮文件里（也就不会跑在主线程上）",
            repoSource.contains("shouldReclaimBody"),
        )
        assertFalse("回收的目录扫描/unlink 不许再留在棘轮文件里", repoSource.contains("root.listFiles()"))
        assertTrue(
            "删分组时的工程目录删除同样不能在 Main.immediate 上做",
            vmSource.contains("withContext(Dispatchers.IO) { SessionWorkspace.deleteProject("),
        )
    }

    /**
     * lastModified→delete 的 TOCTOU：回收侧看完 mtime 之后、unlink 之前，`put` 完全可能刚把
     * 这个正文重新引用起来。同一把锁把「确认无人引用 + 删除」压成原子。
     */
    @Test
    fun `the reclaim and put share one lock so confirm-then-unlink is atomic`() {
        assertTrue("unlink 必须在 BodyStore 那把锁里", gcSource.contains("BodyStore.withCommitLock {"))
        assertTrue(
            "锁必须由 BodyStore 提供（put 的引用刷新用同一把）",
            storeSource.contains("internal fun <T> withCommitLock"),
        )
        assertEquals(
            "两个去重命中点都要在锁里刷新引用",
            2,
            Regex("refreshReference\\(dest, bytes\\)").findAll(storeSource).count(),
        )
        assertEquals(
            "touch 失败不许再被无声吞掉（裸语句调用点必须为 0）",
            0,
            Regex("(?m)^\\s*touch\\(dest\\)\\s*$").findAll(storeSource).count(),
        )
        assertTrue(
            "两条路都失败时至少留下 warning",
            storeSource.contains("AppLogger.warning("),
        )
        assertTrue(
            "pin 的登记项必须是身份相等的稳定 key（可变集合当 key 会让 pin 泄漏）",
            storeSource.contains("private val bodyGcPinRegistries"),
        )
    }

    /**
     * [T-bodies-gc-pin-lock] 「确认无人引用」必须**逐文件、在锁内**认实时登记表：删除循环
     * 之前取的两次快照都抓不到循环进行中才加进来的 ref（备份 `recordWritten`、fork 复制
     * 的 [com.openminis.app.data.body.BodyRefLedger.retain] 声明），只认快照就会在「确认完
     * 之后、unlink 之前」删掉活引用。
     */
    @Test
    fun `the reclaim confirms pins and declarations per file inside the lock`() {
        val lockAt = gcSource.indexOf("BodyStore.withCommitLock {")
        assertTrue("unlink 必须在 BodyStore 那把锁里", lockAt > 0)
        val pinnedAt = gcSource.indexOf("BodyGcPins.isPinned(file.name)", lockAt)
        val declaredAt = gcSource.indexOf("BodyRefLedger.isRecentlyReferenced(file.name)", lockAt)
        val decisionAt = gcSource.indexOf("shouldReclaimBody(file.name, late,", lockAt)

        assertTrue("锁内必须逐文件确认 pin（快照会漏掉循环中的 recordWritten）", pinnedAt > lockAt)
        assertTrue("锁内必须逐文件确认刚声明的引用（fork 复制）", declaredAt > lockAt)
        assertTrue(
            "确认必须排在删除判定之前",
            decisionAt > pinnedAt && decisionAt > declaredAt,
        )
        assertFalse(
            "删除循环不许再用循环前的 stale 快照短路（那样等于不确认）",
            gcSource.contains("if (file.name in heldLate) continue"),
        )
    }

    /**
     * [T-bodies-gc-fork-retain] fork 复制行原样搬 `body_ref`（不落盘、不刷 mtime）：它必须在
     * BodyStore 的提交锁内声明这次引用，否则回收侧在副本落库之前读到的引用集合里看不见它，
     * unlink 掉的就是活引用（副本永久退回 stub）。
     */
    @Test
    fun `a copied body reference is declared under the commit lock`() {
        assertTrue(
            "copyMessageInto 必须把搬过来的 ref 交给 BodyRefLedger.retain",
            repoSource.contains("BodyRefLedger.retain(ref)"),
        )
        assertTrue("登记入口由 BodyRefLedger 提供", ledgerSource.contains("fun retain(ref: String?"))
        val retainAt = ledgerSource.indexOf("fun retain(ref: String?")
        val lockAt = ledgerSource.indexOf("BodyStore.withCommitLock {", retainAt)
        assertTrue(
            "登记必须发生在提交锁内（否则 confirm-then-unlink 之间仍能插进来）",
            lockAt > retainAt,
        )
        assertTrue(
            "回收侧逐文件确认读的是同一张登记表",
            ledgerSource.contains("fun isRecentlyReferenced(ref: String") &&
                gcSource.contains("BodyRefLedger.isRecentlyReferenced(file.name)"),
        )
    }

    /**
     * `message_versions` 是「别处仍被引用就不能删」的另一半依据：两条查询缺一条，
     * 删会话就会把另一个会话仍在归档引用的正文删掉。
     */
    @Test
    fun `the version table is a first-class reference source`() {
        assertTrue(
            "全库归档引用查询（回收判定用）",
            versionDaoSource.contains("SELECT DISTINCT body_ref FROM message_versions WHERE body_ref IS NOT NULL"),
        )
        assertTrue(
            "按会话归档引用查询（删除候选集用）",
            versionDaoSource.contains(
                "SELECT DISTINCT body_ref FROM message_versions WHERE session_id = :sessionId AND body_ref IS NOT NULL"
            ),
        )
        assertTrue(
            "回收判定把版本表并进 live 集合",
            gcSource.contains("versionDao?.let { refs.addAll(it.allBodyRefs()) }"),
        )
        assertTrue(
            "删会话的候选集把版本表算进来",
            gcSource.contains("vdao.bodyRefsForSession(sessionId)"),
        )
        assertTrue(
            "备份快照同样读版本表",
            bodyExportSource.contains("versionDao?.let { live.addAll(it.allBodyRefs()) }"),
        )
    }

    /**
     * [T-bodies-gc-fork-retain] 归档行不是"永远不会被回收的旁路"：`message_versions.body_ref`
     * 是 live 集合的一半，归档行常常是那份正文的**唯一**存活依据。类注释曾宣称 BodyStore
     * 永不回收已提交正文（只清 tmp），与现在的 GC 语义直接矛盾。
     */
    @Test
    fun `the archive entity documents its part in the live reference set`() {
        assertFalse(
            "类注释不能再宣称 committed body 永不被回收",
            versionEntitySource.contains("never garbage-collects committed bodies"),
        )
        assertTrue(
            "归档行必须写明它参与回收的引用判定",
            versionEntitySource.contains("messages.body_ref ∪ message_versions.body_ref"),
        )
        assertTrue(
            "并且要指出回收实现是谁（读代码即可确认）",
            versionEntitySource.contains("collectOrphanBodies"),
        )
    }
}
