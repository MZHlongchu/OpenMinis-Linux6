package com.openminis.app.config

import com.openminis.app.config.collections.ModelsCollection
import com.openminis.app.config.fields.ReadOnlyField
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * [T-config-path-dotted-id] OpenMinis#390 — config paths address an entry by
 * its RAW id, dots included. Android half of iOS cef38f13b
 * (MinisTests/Standalone/ConfigPathSplitTests.swift); the fixture table and
 * the expected message texts are the same, and the message templates are
 * compared against the iOS source so neither side can drift alone.
 *
 * [T-a1-ios-source-absent] 本移植（OpenMinis-Linux）不再携带 iOS 源码树
 * （`src/ios` 目录整体删除）。这里的两类检查必须分开处理，否则 iOS 源码
 * 缺席会把 Android 侧的检查一起打死（整类 7 个失败都报
 * "repo root with src/ios not found"）：
 *   - 只扫 Android 源码的检查：改用仍然在场的 Android 源码树定位仓库根，
 *     照常运行，断言一条不动；
 *   - "与 iOS 源码逐字对齐"的检查：没有比对对象，显式 Assume 跳过（断言
 *     保留，`src/ios` 回到仓库后自动重新生效）。
 */
class ConfigPathSplitTest {

    private val u = "05B474B3-C297-48C3-A239-688569B3F872"

    // -- Split: the fixture table shared with iOS ---------------------------------

    @Test
    fun `split fixture table (shared with iOS)`() {
        val table: List<Pair<String, Triple<String, String, String>?>> = listOf(
            "models.$u/mimo-v2.6-pro.contextWindow" to Triple("models", "$u/mimo-v2.6-pro", "contextWindow"),
            "models.$u/kimi-k3.contextWindow" to Triple("models", "$u/kimi-k3", "contextWindow"),
            "models.$u/glm-5.1.isHidden" to Triple("models", "$u/glm-5.1", "isHidden"),
            "models.$u/01-ai/yi-large.displayName" to Triple("models", "$u/01-ai/yi-large", "displayName"),
            "models.$u/my.model.v2.displayName" to Triple("models", "$u/my.model.v2", "displayName"),
            "subagents.builtin.general.instructions" to Triple("subagents", "builtin.general", "instructions"),
            "models.$u/mimo-v2~d6-pro.contextWindow" to Triple("models", "$u/mimo-v2~d6-pro", "contextWindow"),
            "models" to null,
            "models.$u" to null,
            "models..x" to null,
            "models.$u." to null,
            ".x.y" to null,
        )
        for ((path, want) in table) {
            assertEquals(path, want, ConfigRegistry.splitCollectionPath(path))
        }
    }

    @Test
    fun `the old splitter cut the reported id at its first dot (the bug)`() {
        val old = "models.$u/mimo-v2.6-pro.contextWindow".split('.', limit = 3)
        assertEquals("$u/mimo-v2", old[1])
        assertEquals("6-pro.contextWindow", old[2])
    }

    // -- End to end through a real registry ---------------------------------------

    /**
     * Behaves like ModelsCollection: canonical paths carry the RAW id, and a
     * path id resolves raw first, then by the legacy `~d` decode (the REAL
     * ModelsCollection.lookupIn rule).
     */
    private class FakeModels(private val ids: List<String>) : ConfigCollection {
        override val basePath = "models"
        override val displayName = "LLM models"
        override val description = "test"
        override fun childIds() = ids
        override fun fields(forId: String): List<ConfigField> {
            val id = ModelsCollection.lookupIn(forId) { c -> c.takeIf { it in ids } } ?: return emptyList()
            return LEAVES.map { leaf ->
                ReadOnlyField(
                    path = "models.$id.$leaf", displayName = leaf, description = leaf,
                    valueSchema = ConfigSchema.Str(), reader = { ConfigValue.Str(id) },
                )
            }
        }
        override fun add(payload: ConfigValue) = error("unused")
        override fun remove(id: String) = error("unused")

        companion object {
            val LEAVES = listOf(
                "providerInstanceId", "modelId", "isCustom", "displayName", "maxOutputTokens",
                "isHidden", "modalities", "modalitiesOverride", "contextWindow",
                "contextWindowOverride", "supportsTools", "supportsVision",
            )
        }
    }

    private val registry = ConfigRegistry().apply {
        register(FakeModels(listOf("$u/mimo-v2.6-pro", "$u/glm-5.1", "$u/kimi-k3", "$u/lit~deral")))
        register(
            ReadOnlyField(
                path = "appearance.theme", displayName = "Theme", description = "",
                valueSchema = ConfigSchema.Str(), reader = { ConfigValue.Str("dark") },
            ),
        )
    }

    private fun resolved(path: String) = registry.resolveField(path)?.path

    @Test
    fun `the reported dotted id resolves (the #390 acceptance case)`() {
        assertEquals(
            "models.$u/mimo-v2.6-pro.contextWindow",
            resolved("models.$u/mimo-v2.6-pro.contextWindow"),
        )
        assertEquals("models.$u/glm-5.1.isHidden", resolved("models.$u/glm-5.1.isHidden"))
    }

    @Test
    fun `the dot-free control still resolves`() {
        assertNotNull(resolved("models.$u/kimi-k3.contextWindow"))
    }

    @Test
    fun `the legacy ~d spelling resolves to the canonical raw path`() {
        assertEquals(
            "models.$u/mimo-v2.6-pro.contextWindow",
            resolved("models.$u/mimo-v2~d6-pro.contextWindow"),
        )
    }

    @Test
    fun `an id that literally contains ~d is found raw, not decoded`() {
        assertEquals("models.$u/lit~deral.displayName", resolved("models.$u/lit~deral.displayName"))
    }

    @Test
    fun `unknown leaf and two-part paths do not resolve`() {
        assertNull(resolved("models.$u/glm-5.1.nope"))
        assertNull(resolved("models.$u/glm-5.1"))
    }

    @Test
    fun `flat fields still win, exactly as before`() {
        assertEquals("appearance.theme", resolved("appearance.theme"))
    }

    @Test
    fun `whole-path matching would still miss a legacy path (why leaf matching is needed)`() {
        val legacy = "models.$u/mimo-v2~d6-pro.contextWindow"
        val (_, id, _) = ConfigRegistry.splitCollectionPath(legacy)!!
        val children = FakeModels(listOf("$u/mimo-v2.6-pro")).fields(id)
        assertTrue(children.isNotEmpty())
        assertTrue(children.none { it.path == legacy })
    }

    // -- Guided unknown_path reasons (same texts as iOS) --------------------------

    private fun noEntry(base: String, id: String) =
        "No entry '$id' under '$base'. Run `minis-config get $base` and use an entry_id verbatim — ids may contain dots and slashes, no escaping needed: $base.<entry_id>.<field>."

    @Test
    fun `two-part path says it names an entry and gives an example`() {
        val e = registry.explainUnknownPath("models.$u/mimo-v2.6-pro")
        assertEquals(
            "'models.$u/mimo-v2.6-pro' names an entry, not a field. Append a field, e.g. " +
                "models.$u/mimo-v2.6-pro.providerInstanceId. Fields: ${FakeModels.LEAVES.joinToString(", ")}.",
            e,
        )
    }

    @Test
    fun `unknown field names the entry and lists fields`() {
        assertEquals(
            "Unknown field 'nope' for models entry '$u/glm-5.1'. Fields: ${FakeModels.LEAVES.joinToString(", ")}.",
            registry.explainUnknownPath("models.$u/glm-5.1.nope"),
        )
    }

    @Test
    fun `unknown entry points at get models and says no escaping`() {
        assertEquals(
            noEntry("models", "$u/nosuch-1.0"),
            registry.explainUnknownPath("models.$u/nosuch-1.0.contextWindow"),
        )
        assertEquals(noEntry("models", "nosuch"), registry.explainUnknownPath("models.nosuch"))
    }

    @Test
    fun `unknown topic points at list-topics`() {
        assertEquals("No topic 'modles'. Run `minis-config list-topics`.", registry.explainUnknownPath("modles.x.y"))
    }

    @Test
    fun `an unknown field on a flat topic keeps the plain reason`() {
        assertEquals("No registered field at 'appearance.nope'.", registry.explainUnknownPath("appearance.nope"))
    }

    // -- Cross-platform parity with the iOS source --------------------------------

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !hasRepoMarker(dir)) dir = dir.parentFile
        requireNotNull(dir) { "repo root with $ANDROID_SOURCE_MARKER not found" }
    }

    /**
     * [T-a1-ios-source-absent] 仓库根不再只认 `src/ios`：本移植删除了 iOS
     * 源码树，旧探针会在这里 `require` 失败，连带打死只扫 Android 源码的
     * 检查。现在任一在场标记即可，`src/ios` 仍是候选之一，所以带 iOS 的
     * 分支/上游仓库上定位结果不变。
     */
    private fun hasRepoMarker(dir: File): Boolean =
        File(dir, ANDROID_SOURCE_MARKER).isDirectory || File(dir, "src/ios").isDirectory

    /**
     * [T-a1-ios-source-absent] 与 iOS 源码逐字对齐的检查在 `src/ios` 缺席时
     * 显式跳过：断言一条没删，iOS 源码回到仓库即自动恢复；跳过原因会出现在
     * 测试报告里，不会被误读成"已通过"。
     */
    private fun requireIosSource() {
        Assume.assumeTrue(
            "本移植已删除 iOS 源码树 src/ios（iOS 目标下线），跳过与 iOS 源码逐字对齐的校验：" +
                "断言与意图保留，src/ios 回到仓库后自动生效",
            File(repoRoot, "src/ios").isDirectory,
        )
    }

    private fun ios(rel: String): String {
        requireIosSource()
        return File(repoRoot, "src/ios/$rel").readText()
    }

    private fun android(rel: String) = File(repoRoot, "$ANDROID_SOURCE_MARKER/$rel").readText()

    /**
     * A source line with every string interpolation replaced by `{}`. Done on
     * the whole line BEFORE literals are cut out, because interpolations carry
     * quotes of their own (`joined(separator: ", ")`, `joinToString(", ")`).
     */
    private fun skeleton(line: String): String = line
        .replace(Regex("""\\\((?:[^()]|\([^()]*\))*\)"""), "{}") // Swift \(…), one nested (…)
        .replace(Regex("""\$\{[^}]*\}"""), "{}")                 // Kotlin ${…}
        .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), "{}")     // Kotlin $name

    /** Every double-quoted literal, interpolations as `{}`, on lines containing [marker]. */
    private fun literalsWith(source: String, marker: String): Set<String> =
        source.lines().filter { it.contains(marker) }
            .flatMap { line ->
                Regex(""""((?:[^"\\]|\\.)*)"""").findAll(skeleton(line)).map { it.groupValues[1] }.toList()
            }
            .filter { it.contains(marker) }
            .toSet()

    @Test
    fun `explainUnknownPath texts match iOS byte for byte, modulo interpolation`() {
        val markers = listOf(
            "No topic '", "names an entry, not a field. Append a field, e.g. ",
            "Unknown field '", "No entry '", "No registered field at '",
        )
        // [T-a1-ios-source-absent] Android 侧的存在性先查，它不需要 iOS 源码：
        // 文案被删/被改，即使 src/ios 缺席也必须报错，不能被跳过掩盖。
        val andSrc = android("config/ConfigRegistry.kt")
        for (marker in markers) {
            assertTrue("Android has '$marker'", literalsWith(andSrc, marker).isNotEmpty())
        }
        // 逐字比对需要 iOS 侧的同一批文案；src/ios 缺席时显式跳过。
        requireIosSource()
        val iosSrc = ios("Shared/Config/ConfigRegistry.swift")
        for (marker in markers) {
            assertEquals(
                "'$marker' text differs from iOS",
                literalsWith(iosSrc, marker),
                literalsWith(andSrc, marker),
            )
        }
    }

    @Test
    fun `--help PATHS section matches iOS`() {
        val lines = listOf(
            "PATHS:\\n",
            "  <topic>.<field> or <topic>.<entry_id>.<field>. Use entry_id exactly as\\n",
            "  `get <topic>` prints it — dots and slashes are fine, no escaping, e.g.\\n",
            "    get models.<uuid>/glm-5.1.contextWindow\\n",
        )
        // [T-a1-ios-source-absent] Android 帮助文案先查（同上：不依赖 src/ios）。
        val andHelp = android("sandbox/offload/ConfigOffloadHandler.kt")
        for (line in lines) {
            assertTrue("Android help has: $line", andHelp.contains("\"$line\""))
        }
        requireIosSource()
        val iosHelp = ios("NativeOffloads/ConfigOffload.m")
        for (line in lines) {
            assertTrue("iOS help has: $line", iosHelp.contains("\"$line\""))
        }
    }

    @Test
    fun `topic-help path_note matches iOS`() {
        val marker = "Child paths are "
        val a = literalsWith(android("config/ConfigBridge.kt"), marker)
        assertTrue(a.isNotEmpty())
        assertTrue(
            "topic-help emits it",
            android("sandbox/offload/ConfigOffloadHandler.kt").contains("ConfigBridge.pathNoteForTopic(topic)?.let { put(\"path_note\", it) }"),
        )
        // [T-a1-ios-source-absent] 同样先跑 Android 侧两条断言，再跳过逐字比对。
        requireIosSource()
        val i = literalsWith(ios("NativeOffloads/ConfigOffloadBridge.swift"), marker)
        assertEquals(i, a)
    }

    // -- Source guards ---------------------------------------------------------------

    @Test
    fun `resolveField splits once and matches by leaf`() {
        val src = android("config/ConfigRegistry.kt")
        val body = src.substringAfter("fun resolveField(path: String)").substringBefore("fun explainUnknownPath(")
        assertTrue("the three-way split is gone", !body.contains("limit = 3"))
        assertTrue(body.contains("splitCollectionPath(path)"))
        assertTrue(body.contains("coll.fields(forId = id).firstOrNull { leafOf(it.path) == leaf }"))
    }

    @Test
    fun `both bridge unknown_path exits use the explanation`() {
        val bridge = android("config/ConfigBridge.kt")
        assertEquals(2, bridge.split("ConfigRegistry.get().explainUnknownPath(").size - 1)
        assertTrue(!bridge.contains("\"No registered field at '"))
    }

    @Test
    fun `ModelsCollection publishes raw ids and looks up raw first`() {
        val src = android("config/collections/ModelsCollection.kt")
        assertEquals(12, src.split("path = \"models.\$id.").size - 1)
        assertTrue(!src.contains("pathSegment"))
        assertTrue(src.contains("repo.config.value.modelEntries.map { it.id }"))
        assertTrue(src.contains("// Used verbatim as a path id ([T-config-path-dotted-id]).\n        return entry.id"))
        val fields = src.substringAfter("override fun fields(forId: String)").substringBefore("override fun add(")
        assertTrue(fields.contains("lookup(forId)"))
        val remove = src.substringAfter("override fun remove(id: String)").substringBefore("private fun entry(")
        assertTrue(remove.contains("lookup(id)") && remove.contains("repo.removeEntry(entry.id)"))
    }

    // -- The invariant the new split relies on ---------------------------------------

    @Test
    fun `every collection path template has a single-segment leaf`() {
        val template = Regex("""path = "([A-Za-z]+)\.\$\{?[A-Za-z]+\}?\.([^"]*)"""")
        val dir = File(repoRoot, "$ANDROID_SOURCE_MARKER/config")
        val bad = mutableListOf<String>()
        var count = 0
        dir.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            template.findAll(f.readText()).forEach { m ->
                count++
                if (m.groupValues[2].contains('.')) bad += "${f.name}: ${m.value}"
            }
        }
        assertTrue("collection path templates found ($count)", count >= 30)
        assertTrue("a leaf contains a dot: $bad", bad.isEmpty())
    }

    private companion object {
        /**
         * [T-a1-ios-source-absent] 本移植仍然在场的仓库标记：Android 源码树根。
         * 用它（而不是已删除的 `src/ios`）定位仓库根，只用 Android 源码的
         * 检查才能在 iOS 缺席时照常运行。
         */
        const val ANDROID_SOURCE_MARKER = "src/android/app/src/main/java/com/openminis/app"
    }
}
