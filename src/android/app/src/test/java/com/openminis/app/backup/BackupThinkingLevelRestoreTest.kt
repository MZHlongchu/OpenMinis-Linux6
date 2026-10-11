package com.openminis.app.backup

import com.openminis.app.ProductionSources
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ThinkingLevel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test

/**
 * [T-android-restore-thinking-level] A group restored from a backup kept its
 * members but lost its thinking level, and so did per-model ceilings and sub
 * agent overrides.
 *
 * Root cause: both platforms define the same seven levels, but iOS writes them
 * as its `String` raw values (`"high"`) while Android's enum serialized by
 * NAME (`"HIGH"`). `BackupFormat.json` runs with `coerceInputValues = true`,
 * which turns an enum string it does not recognize on a nullable property into
 * `null` — silently, with no exception and no log. Every iOS-written level
 * therefore restored as "no level".
 */
class BackupThinkingLevelRestoreTest {

    /** provider_config.json as iOS writes it: lowercase raw values. */
    private val iosProviderConfig = """
        {
          "instances": [],
          "modelEntries": [{
            "providerInstanceId": "p1",
            "model": {"id": "gemini-3-pro", "displayName": "Gemini 3 Pro", "provider": "antigravity"},
            "overrides": {"maxThinkingLevel": "max"},
            "uuid": "e1"
          }],
          "modelGroups": [{
            "id": "g1", "name": "Group", "memberEntryIds": ["e1"],
            "strategy": "fallback", "fallbackStrategy": "default",
            "defaultThinkingLevel": "high"
          }],
          "subAgents": [{
            "id": "a1", "name": "researcher", "description": "d",
            "thinkingLevelOverride": "low"
          }]
        }
    """.trimIndent()

    @Test
    fun iosLowercaseLevels_surviveProviderConfigRestore() {
        val config = BackupImporter.parseProviderConfigLeniently(iosProviderConfig).config
        assertEquals(ThinkingLevel.HIGH, config.modelGroups.single().defaultThinkingLevel)
        assertEquals(ThinkingLevel.MAX, config.modelEntries.single().overrides.maxThinkingLevel)
        assertEquals(ThinkingLevel.LOW, config.subAgents.single().thinkingLevelOverride)
    }

    @Test
    fun androidBackup_roundTripsItsOwnLevels() {
        val group = ModelGroup(id = "g1", name = "G", defaultThinkingLevel = ThinkingLevel.ULTRA)
        val text = BackupFormat.json.encodeToString(
            ProviderConfig.serializer(), ProviderConfig(modelGroups = mutableListOf(group)),
        )
        val back = BackupImporter.parseProviderConfigLeniently(text).config
        assertEquals(ThinkingLevel.ULTRA, back.modelGroups.single().defaultThinkingLevel)
    }

    @Test
    fun androidEncoding_isUnchanged() {
        // The level is also persisted in Room blobs (ModelOverrides,
        // SubAgentDefinition) and the ProviderConfig JSON mirror. Changing what
        // is WRITTEN would make an older build (a downgrade) misread them, so
        // the fix is read-side only: the written form stays the enum name.
        val text = BackupFormat.json.encodeToString(
            ModelGroup.serializer(),
            ModelGroup(id = "g1", name = "G", defaultThinkingLevel = ThinkingLevel.HIGH),
        )
        assertTrue(text, text.contains("\"defaultThinkingLevel\":\"HIGH\""))
    }

    @Test
    fun absentLevel_staysNull() {
        val wire = """{"id":"g1","name":"G","memberEntryIds":[]}"""
        assertNull(BackupFormat.json.decodeFromString(ModelGroup.serializer(), wire).defaultThinkingLevel)
    }

    /**
     * Every raw value of the Swift enum must decode to the level of the same
     * name, so either platform adding or respelling a level breaks here rather
     * than silently in a user's restore.
     *
     * [T-android-ios-tree-removed] 这条用例的期望来源是 iOS 的
     * `src/ios/Providers/LLMTypes.swift`；本移植是 Linux/Android-only，整棵
     * src/ios 在移植时被删除，所以「Android 与 iOS 必须定义同一组 level」这半边
     * 在这里**没有可核对的源码**——显式 Assume 跳过，并在跳过原因里写明缺哪份文件，
     * 而不是把这条断言悄悄删掉或放宽成「只看 Android」。
     *
     * 不依赖 iOS 源码的那半边照旧执行（而且它是这条用例真正防回归的部分）：
     * iOS 写的是小写 raw value，所以每个 level 的小写拼写都必须能解回同名 level。
     */
    @Test
    fun everyIosRawValue_decodesToTheSameLevel() {
        val androidCases = ThinkingLevel.entries.map { it.name.lowercase() }
        for (raw in androidCases) {
            val group = BackupFormat.json.decodeFromString(
                ModelGroup.serializer(),
                """{"id":"g","name":"G","memberEntryIds":[],"defaultThinkingLevel":"$raw"}""",
            )
            assertEquals(raw, raw, group.defaultThinkingLevel?.name?.lowercase())
            assertEquals(raw, group.defaultThinkingLevel, ThinkingLevel.parseOrNull(raw))
        }

        val iosCases = iosThinkingLevelCasesOrNull()
        Assume.assumeTrue(
            "[T-android-ios-tree-removed] src/ios 已随移植删除（本移植只支持 Android/Linux），" +
                "跳过与 iOS 枚举的一致性核对：期望来源 src/ios/Providers/LLMTypes.swift 不存在。",
            iosCases != null,
        )
        iosCases ?: return
        assertEquals(
            "iOS and Android must define the same levels",
            androidCases,
            iosCases,
        )
        for (raw in iosCases) {
            val group = BackupFormat.json.decodeFromString(
                ModelGroup.serializer(),
                """{"id":"g","name":"G","memberEntryIds":[],"defaultThinkingLevel":"$raw"}""",
            )
            assertEquals(raw, raw, group.defaultThinkingLevel?.name?.lowercase())
            assertEquals(raw, group.defaultThinkingLevel, ThinkingLevel.parseOrNull(raw))
        }
    }

    /**
     * iOS 的 raw value 列表，源码不在时返回 null（由调用方 Assume 跳过并说明原因）。
     * 以前这里用 requireNotNull 抛 IllegalArgumentException——那会把「文件不在」
     * 报成一条看不懂的失败，而不是「这条核对在本移植上不适用」。
     */
    private fun iosThinkingLevelCasesOrNull(): List<String>? {
        var dir: File? = ProductionSources.mainRoot()
        while (dir != null && !File(dir, "src/ios").isDirectory) dir = dir.parentFile
        val root = dir ?: return null
        val source = File(root, "src/ios/Providers/LLMTypes.swift")
        if (!source.isFile) return null
        val text = source.readText()
        val start = text.indexOf("enum ThinkingLevel: String")
        require(start >= 0) { "enum ThinkingLevel not found in LLMTypes.swift" }
        val body = text.substring(text.indexOf('{', start) + 1, text.indexOf("static func", start))
        return Regex("""^\s*case (\w+)\s*$""", RegexOption.MULTILINE)
            .findAll(body).map { it.groupValues[1] }.toList()
    }
}
