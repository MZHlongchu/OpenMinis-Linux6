package com.openminis.app.backup

import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup record -> Room entity, and the merge rule that decides whether a
 * restored row may replace the local one.
 *
 * Moved out of [BackupImporter]'s DAO loops unchanged so the rules can be
 * tested directly. They are the error-prone part of a restore — a wrong
 * default or merge comparison silently drops or overwrites user data and a
 * restore still reports success — and while they lived inline next to Room
 * the only available tests pinned source text or kept their own copies.
 * The importer keeps everything that needs the database: looking up the
 * existing row, the parent-session check, transactions and the report.
 */
internal object BackupRecordMapper {

    // -- Merge ----------------------------------------------------------------

    /**
     * §8.2: newer `updatedAt` wins. An equal stamp keeps the local row, so
     * re-running the same restore is a no-op and an older backup never undoes
     * work the user did after taking it.
     */
    fun incomingWins(existingUpdatedAt: Long?, incomingUpdatedAt: Long): Boolean =
        existingUpdatedAt == null || incomingUpdatedAt > existingUpdatedAt

    // -- Sessions -------------------------------------------------------------

    sealed class Decoded<out T> {
        /** No id: the record cannot be placed at all. */
        object Unreadable : Decoded<Nothing>()
        /** Readable, but the local row is as new or newer; keep it. */
        data class Stale(val id: String) : Decoded<Nothing>()
        data class Apply<T>(val id: String, val entity: T, val isNew: Boolean) : Decoded<T>()
    }

    /**
     * A `SessionV2` payload, in either shape: iOS nests the session under
     * `"session"` with wrapper fields beside it; Android wrote it flat before
     * 1.14 and nested since. The two levels are merged, inner winning.
     */
    fun session(payload: JsonObject, existing: ChatSessionEntity?): Decoded<ChatSessionEntity> {
        val s = payload.unwrapNested("session")
        val id = s.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = s.millis("updatedAt") ?: 0
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            ChatSessionEntity(
                id = id,
                title = s.str("title"),
                modelId = s.str("modelId") ?: existing?.modelId ?: "",
                createdAt = s.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
                category = s.str("category"),
                lastMessage = s.str("lastMessage"),
                modelBinding = s.str("modelBinding"),
                source = s.str("source"),
                memoryEnabled = if (s.bool("memoryEnabled") != false) 1 else 0,
                pinnedAt = s.millis("pinnedAt"),
                editCount = s.int("editCount") ?: 0,
                thinkingOverride = s.str("thinkingOverride"),
                folderId = s.str("folderId"),
                parentSessionId = s.str("parentSessionId"),
                parentToolUseId = s.str("parentToolUseId"),
            ),
            isNew = existing == null,
        )
    }

    // -- Folders --------------------------------------------------------------

    fun folder(f: JsonObject, existing: FolderEntity?): Decoded<FolderEntity> {
        val id = f.str("id") ?: return Decoded.Unreadable
        val incomingUpdated = f.millis("updatedAt") ?: 0
        // Merge by the same rule as sessions: an older backup must not undo a
        // rename the user made after taking it.
        if (!incomingWins(existing?.updatedAt, incomingUpdated)) return Decoded.Stale(id)
        return Decoded.Apply(
            id,
            FolderEntity(
                id = id,
                name = f.str("name") ?: "",
                icon = f.str("icon"),
                color = f.str("color"),
                origin = f.str("origin") ?: FolderEntity.ORIGIN_MANUAL,
                sortIndex = f.int("sortIndex") ?: 0,
                pinnedAt = f.millis("pinnedAt"),
                // iOS names this field `desc` (ChatStore.Folder.desc); Android
                // wrote `description` (and since 1.14 writes both). Accept
                // either, or a folder restored from an iPhone silently loses
                // its one-line description.
                description = f.str("description") ?: f.str("desc"),
                createdAt = f.millis("createdAt") ?: incomingUpdated,
                updatedAt = incomingUpdated,
            ),
            isNew = existing == null,
        )
    }

    // -- Messages ---------------------------------------------------------------

    /** Null when id or sessionId is missing. The parent check is the caller's. */
    fun message(m: JsonObject): MessageEntity? {
        val id = m.str("id") ?: return null
        val sessionId = m.str("sessionId") ?: return null
        val createdAt = m.millis("createdAt") ?: 0
        return MessageEntity(
            id = id,
            sessionId = sessionId,
            role = m.str("role") ?: "user",
            // Re-serialised from the parsed element, so any part type this
            // build doesn't model is preserved verbatim.
            partsJson = (m["parts"]?.toString()) ?: "[]",
            createdAt = createdAt,
            tokenUsage = m["tokenUsage"]?.takeIf { it.toString() != "null" }?.toString(),
            sortOrder = m.int("sortOrder") ?: 0,
            reasoningContent = m.str("reasoningContent"),
            streamInterruptCount = m.int("streamInterruptCount") ?: 0,
            updatedAt = createdAt,
            // errorInfo is device-local (§0.2) and is never restored.
            errorInfo = null,
            // [T-token-attribution-snapshot] Absent in packages written before
            // this existed (and in any category the other platform hasn't
            // updated yet) — null then, which is exactly the "estimated" state
            // the Usage page renders.
            modelId = m.str("modelId"),
            modelDisplayName = m.str("modelDisplayName"),
            providerType = m.str("providerType"),
            providerInstanceId = m.str("providerInstanceId"),
        )
    }

    // -- Compact markers --------------------------------------------------------

    /** Null when id or sessionId is missing. The parent check is the caller's. */
    fun compactMarker(c: JsonObject): CompactMarkerEntity? {
        val id = c.str("id") ?: return null
        val sessionId = c.str("sessionId") ?: return null
        return CompactMarkerEntity(
            id = id,
            sessionId = sessionId,
            summary = c.str("summary") ?: "",
            firstKeptSortOrder = c.int("firstKeptSortOrder") ?: 0,
            compactedCount = c.int("compactedCount") ?: 0,
            createdAt = c.millis("createdAt") ?: 0,
            uiBoundarySortOrder = c.int("uiBoundarySortOrder"),
            boundaryMessageId = c.str("boundaryMessageId"),
            firstKeptMessageId = c.str("firstKeptMessageId"),
            lastCompactedMessageId = c.str("lastCompactedMessageId"),
            // [T-backup-marker-version] Without this a v2 marker restored as v1 and
            // resolved through the legacy firstKeptSortOrder chain (v2 writes
            // Int.MAX_VALUE there). Older packages carry no key: they are v1.
            version = c.int("version") ?: 1,
        )
    }

    // -- JSON helpers -------------------------------------------------------------

    /**
     * [T-android-restore-ios-session-nesting] Flatten `{outer…, key:{inner…}}`
     * into one object, inner winning.
     *
     * iOS wraps some records — a session arrives as
     * `{"memoryEnabled":true,"session":{"id":…,"title":…}}`. Merging instead
     * of choosing means one reader handles both shapes, and the wrapper's own
     * fields (`memoryEnabled`) stay reachable by their plain names.
     *
     * Returns `this` unchanged when [key] is absent or is not an object, so a
     * flat record costs nothing.
     */
    fun JsonObject.unwrapNested(key: String): JsonObject {
        val inner = (this[key] as? JsonObject) ?: return this
        return JsonObject(this.filterKeys { it != key } + inner)
    }

    fun JsonObject.str(key: String): String? =
        this[key]?.takeIf { it.toString() != "null" }?.runCatching { jsonPrimitive.content }
            ?.getOrNull()

    fun JsonObject.int(key: String): Int? =
        this[key]?.runCatching { jsonPrimitive.content.toInt() }?.getOrNull()

    fun JsonObject.bool(key: String): Boolean? =
        this[key]?.runCatching { jsonPrimitive.content.toBooleanStrict() }?.getOrNull()

    /**
     * Parse an ISO-8601 instant into epoch millis.
     *
     * iOS writes dates as ISO-8601 strings, but a package written by a future
     * build (or by a tool) could carry a numeric epoch, so both are accepted —
     * §2.2's tolerance rule applied to a value, not just a key.
     */
    fun JsonObject.millis(key: String): Long? {
        val raw = str(key) ?: return null
        raw.toLongOrNull()?.let { return it }
        for (pattern in ISO_PATTERNS) {
            runCatching {
                val f = SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                return f.parse(raw)?.time
            }
        }
        return null
    }

    private val ISO_PATTERNS = listOf(
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
    )
}

/**
 * [T-android-overrides-wire-codec] provider「分享 / 导出 → 导入」线格式里 `overrides`
 * 对象的编解码：纯 org.json 进、org.json 出，不碰 Context / Room。
 *
 * 落点：上游 1.14 逐字段内联在 ProviderRepository 的导出/导入两处；本移植的 share 线格式已挪进
 * ProviderBackupCoordinator（那份文件顶在架构棘轮基线上，只许下调），所以这段与数据库无关的纯
 * 映射落在本文件：协调器腾空间，单测也能直接驱动它。
 *
 * 键名与 iOS 共享，一个都不能改。键都可选 + 增量：旧包缺键 → null（= 沿用上一层），不把缺省合成
 * 0/空表，0.0 的 temperature 也靠 has() 与「没写过」区分；1.14 新增的 maxThinkingLevel / topP /
 * customHeaders / extraBodyParams 漏掉任意一个，都会让用户手改过的模型在分享后静默回退默认值。
 */
internal object ProviderOverridesWire {
    // Modality bit layout — must match src/ios/Providers/LLMTypes.swift ModelModality
    // OptionSet rawValue exactly: iOS decodes this Int, Android uses the string lists.
    private const val MODALITY_BIT_TEXT_IN = 1 shl 0
    private const val MODALITY_BIT_TEXT_OUT = 1 shl 1
    private const val MODALITY_BIT_IMG_IN = 1 shl 2
    private const val MODALITY_BIT_PDF_IN = 1 shl 3
    private const val MODALITY_BIT_AUD_IN = 1 shl 4
    private const val MODALITY_BIT_VID_IN = 1 shl 5
    private const val MODALITY_BIT_IMG_OUT = 1 shl 6
    private const val MODALITY_BIT_AUD_OUT = 1 shl 7
    private const val MODALITY_BIT_VID_OUT = 1 shl 8

    /**
     * [T-provider-export-model-overrides] 写出整个 overrides 层（空层由调用方跳过）。
     * iOS 只认 `modalityOverride` 位域，所以列表 + 位域都写：Android↔Android 无损，
     * iOS 读位域、忽略不认识的列表键。
     */
    fun encode(overrides: ModelOverrides): JSONObject {
        val o = JSONObject()
        overrides.displayName?.let { o.put("displayName", it) }
        overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
        overrides.contextWindow?.let { o.put("contextWindow", it) }
        overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
        overrides.inputModalities?.let { o.put("inputModalities", JSONArray(it)) }
        overrides.outputModalities?.let { o.put("outputModalities", JSONArray(it)) }
        // [T-provider-export-thinking-ceiling] 线值用小写 level id，与 iOS
        // ThinkingLevel.rawValue 的拼写对齐（读侧两种大小写都收）。
        overrides.maxThinkingLevel?.let { o.put("maxThinkingLevel", it.name.lowercase()) }
        overrides.temperature?.let { o.put("temperature", it) }
        overrides.topP?.let { o.put("topP", it) }
        overrides.customHeaders?.takeIf { it.isNotEmpty() }?.let { headers ->
            // 空表不写：读回来就是 null（= 没有自定义头），不必多一个 {} 状态。
            val h = JSONObject()
            for ((k, v) in headers) h.put(k, v)
            o.put("customHeaders", h)
        }
        overrides.extraBodyParams?.let { extra ->
            // JsonObject → org.json 走序列化文本，嵌套结构才不会退化成 toString()。
            runCatching { JSONObject(extra.toString()) }.getOrNull()?.let { o.put("extraBodyParams", it) }
        }
        val bitfield = bitfieldFromLists(overrides.inputModalities, overrides.outputModalities)
        if (bitfield != 0) o.put("modalityOverride", bitfield)
        return o
    }

    /**
     * [T-android-model-custom-params] 读回 overrides 层：逐键先 has() 再读，缺键 → null
     * （= 沿用上一层），类型不符（字符串塞进数值键）→ optDouble 得 NaN → null；0 和空表
     * 都不是「缺省」的同义词。校验口径与上游 1.14 一致，宁缺勿造。
     */
    fun decode(overridesObj: JSONObject): ModelOverrides {
        val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
        return ModelOverrides(
            displayName = overridesObj.optString("displayName", "").ifEmpty { null },
            maxOutputTokens = if (overridesObj.has("maxOutputTokens")) {
                overridesObj.optInt("maxOutputTokens").takeIf { it > 0 }
            } else null,
            contextWindow = if (overridesObj.has("contextWindow")) {
                overridesObj.optInt("contextWindow").takeIf { it > 0 }
            } else null,
            supportsReasoning = if (overridesObj.has("supportsReasoning")) {
                overridesObj.optBoolean("supportsReasoning")
            } else null,
            inputModalities = ovIn,
            outputModalities = ovOut,
            // [T-provider-export-thinking-ceiling] 未知 token → null（沿用目录规则），大小写
            // 不敏感：模型层 ThinkingLevel.parseOrNull 与上游私有 thinkingLevelFromWire 同义。
            // 绝不能用 ThinkingLevel.decoded —— 它把未知 token 夹到 XHIGH，等于替发送方凭空
            // 设了一个它从没设过的上限。
            maxThinkingLevel = if (overridesObj.has("maxThinkingLevel")) {
                ThinkingLevel.parseOrNull(overridesObj.optString("maxThinkingLevel", ""))
            } else null,
            // 温度值域在本移植的模型注释里已登记（0.0-2.0，NaN/Infinity 不入库），所以这处
            // 比上游严：越界/NaN 读成 null，而不是带着脏值去发请求。
            temperature = if (overridesObj.has("temperature")) {
                overridesObj.optDouble("temperature").takeUnless { it.isNaN() }
                    ?.takeIf { it in 0.0..2.0 }
            } else null,
            // topP 没登记过值域，只做与上游一致的类型/NaN 校验，不擅自设 0..1 的界把对方
            // 写过的值丢掉。
            topP = if (overridesObj.has("topP")) {
                overridesObj.optDouble("topP").takeUnless { it.isNaN() }
            } else null,
            // header 值只能是字符串（非字符串走 optString 宽松转换），空表读成 null。
            customHeaders = overridesObj.optJSONObject("customHeaders")?.let { h ->
                buildMap { for (k in h.keys()) put(k, h.optString(k, "")) }
                    .takeIf { it.isNotEmpty() }
            },
            // 只有真能解析成 JSON OBJECT 才收（数组/标量 → null），否则这个键会被当成
            // 结构化 body 片段合并进请求。
            extraBodyParams = overridesObj.optJSONObject("extraBodyParams")?.let { ex ->
                runCatching { Json.parseToJsonElement(ex.toString()) as? JsonObject }.getOrNull()
            },
        )
    }

    // -- Modality interop with iOS ----------------------------------------
    // iOS 用 Int 位域（OptionSet rawValue），Android 用两个字符串列表：导出都写，
    // 导入列表优先、位域兜底，哪个方向都不丢能力信息。
    fun bitfieldFromLists(
        inputs: List<String>?,
        outputs: List<String>?,
    ): Int {
        var bits = 0
        inputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_IN
                "image" -> bits = bits or MODALITY_BIT_IMG_IN
                "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
                "audio" -> bits = bits or MODALITY_BIT_AUD_IN
                "video" -> bits = bits or MODALITY_BIT_VID_IN
            }
        }
        outputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
                "image" -> bits = bits or MODALITY_BIT_IMG_OUT
                "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
                "video" -> bits = bits or MODALITY_BIT_VID_OUT
            }
        }
        return bits
    }

    fun listsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
        if (bits == 0) return null to null
        val inputs = buildList {
            if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
            if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
            if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
            if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
            if (bits and MODALITY_BIT_VID_IN != 0) add("video")
        }
        val outputs = buildList {
            if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
            if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
            if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
            if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
        }
        return inputs.ifEmpty { null } to outputs.ifEmpty { null }
    }

    /** 原生列表键优先（Android 自己写的导出无损）；否则用 iOS 的 `modalityOverride` 位域。 */
    fun readModalitiesWithBitfieldFallback(
        obj: JSONObject,
    ): Pair<List<String>?, List<String>?> {
        fun list(key: String): List<String>? = obj.optJSONArray(key)?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).ifEmpty { null } }.ifEmpty { null }
        }
        val nativeIn = list("inputModalities")
        val nativeOut = list("outputModalities")
        if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
        if (!obj.has("modalityOverride")) return null to null
        val bits = obj.optInt("modalityOverride", 0)
        return listsFromBitfield(bits)
    }
}
