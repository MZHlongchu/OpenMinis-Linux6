package com.openminis.app.backup

import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-model-custom-params][T-provider-export-thinking-ceiling][T-android-overrides-wire-codec]
 * provider「分享 / 导出 → 导入」的 overrides 往返 —— 这一次打的是**生产代码**
 * ([ProviderOverridesWire])，不是照抄一份算法再 grep 源码：上游 1.14 的导出/导入
 * 是两处逐字段手写，所以 1.14 新增的 4 个键（maxThinkingLevel / topP /
 * customHeaders / extraBodyParams）在合并里整段消失时，没有任何测试会红。
 *
 * 往返用「文本走一圈」（encode → toString → JSONObject(...) → decode）而不是直接传
 * 对象引用，因为真正的线格式就是 provider share 的 JSON 文本：只有这样才验证数字
 * 精度、嵌套结构与缺省键在序列化边界上的行为。
 */
class ProviderOverridesWireRoundTripTest {

    /** 线格式往返：encode → JSON 文本 → decode（与 importInstanceJSON 同一条路）。 */
    private fun roundTrip(overrides: ModelOverrides): ModelOverrides =
        ProviderOverridesWire.decode(JSONObject(ProviderOverridesWire.encode(overrides).toString()))

    private val full = ModelOverrides(
        displayName = "Tuned Relay Model",
        maxOutputTokens = 32_000,
        contextWindow = 262_144,
        supportsReasoning = true,
        inputModalities = listOf("text", "image", "pdf"),
        outputModalities = listOf("text"),
        maxThinkingLevel = ThinkingLevel.XHIGH,
        temperature = 0.35,
        topP = 0.9,
        customHeaders = mapOf("X-Tenant" to "acme", "X-Trace" to "abc123"),
        extraBodyParams = JsonObject(
            mapOf(
                "reasoning_effort" to JsonPrimitive("high"),
                "seed" to JsonPrimitive(42),
            ),
        ),
    )

    /**
     * 工单的验收点：1.14 的 4 个键必须真的写在线格式里。
     * 少一个 = 用户手改过的模型在分享/备份往返后静默回退默认值。
     */
    @Test
    fun `the four 1_14 keys are written to the share wire`() {
        val wire = ProviderOverridesWire.encode(full)
        for (key in listOf("maxThinkingLevel", "topP", "customHeaders", "extraBodyParams")) {
            assertTrue("share 线格式必须写出 $key", wire.has(key))
        }
        // 解析回来的文本同样带这 4 个键（toString 之后才是真正发出去的字节）。
        val text = JSONObject(wire.toString())
        assertEquals("xhigh", text.getString("maxThinkingLevel"))
        assertEquals(0.9, text.getDouble("topP"), 1e-9)
        assertEquals("acme", text.getJSONObject("customHeaders").getString("X-Tenant"))
        assertEquals(
            "high",
            text.getJSONObject("extraBodyParams").getString("reasoning_effort"),
        )
    }

    /** 整层往返：线格式上的 11 个字段一个都不能少。 */
    @Test
    fun `the whole overrides layer survives the round trip`() {
        val back = roundTrip(full)

        assertEquals("Tuned Relay Model", back.displayName)
        assertEquals(32_000, back.maxOutputTokens)
        assertEquals(262_144, back.contextWindow)
        assertEquals(true, back.supportsReasoning)
        assertEquals(listOf("text", "image", "pdf"), back.inputModalities)
        assertEquals(listOf("text"), back.outputModalities)
        assertEquals(ThinkingLevel.XHIGH, back.maxThinkingLevel)
        assertEquals(0.35, back.temperature!!, 1e-9)
        assertEquals(0.9, back.topP!!, 1e-9)
        assertEquals(mapOf("X-Tenant" to "acme", "X-Trace" to "abc123"), back.customHeaders)
        assertEquals("high", (back.extraBodyParams!!["reasoning_effort"] as JsonPrimitive).content)
        assertEquals("42", (back.extraBodyParams!!["seed"] as JsonPrimitive).content)
    }

    /** 二次往返幂等：导入的结果再导出一次，不能有侵蚀（尤其是数值）。 */
    @Test
    fun `a second round trip changes nothing`() {
        val once = roundTrip(full)
        assertEquals(once, roundTrip(once))
    }

    /**
     * 前向兼容：1.14 之前写的导出（或 iOS 只写了部分键）没有这些键 —— 既不能抛异常，
     * 也不能把「缺省」合成 0 / 空表 / false，否则一次导入就把模型钉死在错误的参数上。
     */
    @Test
    fun `a pre-1_14 export restores with nulls, not zeros`() {
        val legacy = JSONObject(
            """{"displayName":"Renamed","maxOutputTokens":4096,"supportsReasoning":false}""",
        )
        val back = ProviderOverridesWire.decode(legacy)

        assertEquals("Renamed", back.displayName)
        assertEquals(4096, back.maxOutputTokens)
        assertEquals(false, back.supportsReasoning)
        assertNull("absent contextWindow must stay null", back.contextWindow)
        assertNull("absent temperature must not become 0.0", back.temperature)
        assertNull(back.topP)
        assertNull(back.customHeaders)
        assertNull(back.extraBodyParams)
        assertNull("absent maxThinkingLevel must stay null", back.maxThinkingLevel)
    }

    /** 未知键（更新的构建 / 别的平台加的旋钮）不得让导入失败，也不影响已知键。 */
    @Test
    fun `an unknown key is ignored instead of failing the import`() {
        val future = JSONObject(
            """{"topP":0.5,"futureKnob":{"nested":1},"another":["a"]}""",
        )
        val back = ProviderOverridesWire.decode(future)
        assertEquals(0.5, back.topP!!, 1e-9)
    }

    // ── maxThinkingLevel ──────────────────────────────────────────────────

    /** 线值是小写 level id（对齐 iOS rawValue），两种大小写都能读回来。 */
    @Test
    fun `the thinking ceiling round-trips and accepts either casing`() {
        val wire = ProviderOverridesWire.encode(ModelOverrides(maxThinkingLevel = ThinkingLevel.MAX))
        assertEquals("max", wire.getString("maxThinkingLevel"))
        assertEquals(ThinkingLevel.MAX, ProviderOverridesWire.decode(wire).maxThinkingLevel)

        for (token in listOf("xhigh", "XHIGH", "XHigh", " xhigh ")) {
            val back = ProviderOverridesWire.decode(
                JSONObject("""{"maxThinkingLevel":"$token"}"""),
            )
            assertEquals("token \"$token\"", ThinkingLevel.XHIGH, back.maxThinkingLevel)
        }
        // OFF 是真实上限（「这个模型永不思考」），不是「没设过」。
        assertEquals(
            ThinkingLevel.OFF,
            ProviderOverridesWire.decode(JSONObject("""{"maxThinkingLevel":"off"}""")).maxThinkingLevel,
        )
    }

    /**
     * 更新构建发明的 level 必须读成 null（= 沿用目录规则），绝不能走
     * ThinkingLevel.decoded —— 那会把未知 token 夹到 XHIGH，等于替发送方凭空设了一个
     * 它从没设过的上限。
     */
    @Test
    fun `an unknown thinking level imports as null rather than clamped`() {
        assertNull(ProviderOverridesWire.decode(JSONObject("""{"maxThinkingLevel":"hyper"}""")).maxThinkingLevel)
        assertNull(ProviderOverridesWire.decode(JSONObject("""{"maxThinkingLevel":7}""")).maxThinkingLevel)
    }

    // ── topP / temperature ────────────────────────────────────────────────

    /** 类型不符（字符串 / 布尔 / null）→ NaN → null，而不是抛异常。 */
    @Test
    fun `a mistyped topP or temperature imports as null`() {
        for (bad in listOf("\"\"", "\"abc\"", "true", "null", "{}")) {
            val back = ProviderOverridesWire.decode(
                JSONObject("""{"topP":$bad,"temperature":$bad}"""),
            )
            assertNull("topP=$bad", back.topP)
            assertNull("temperature=$bad", back.temperature)
        }
    }

    /** 0.0 是合法温度（完全确定性），必须与「没写过」区分开。 */
    @Test
    fun `a zero temperature survives and does not read as absent`() {
        val back = roundTrip(ModelOverrides(temperature = 0.0))
        assertEquals(0.0, back.temperature!!, 1e-9)
        assertFalse(back.isEmpty)
    }

    /** 越界温度读成 null（本移植的模型层登记了 0.0-2.0 值域，比上游严）。 */
    @Test
    fun `an out-of-range temperature is dropped`() {
        assertNull(ProviderOverridesWire.decode(JSONObject("""{"temperature":3.5}""")).temperature)
        assertNull(ProviderOverridesWire.decode(JSONObject("""{"temperature":-1}""")).temperature)
        assertEquals(
            2.0,
            ProviderOverridesWire.decode(JSONObject("""{"temperature":2.0}""")).temperature!!,
            1e-9,
        )
    }

    // ── customHeaders / extraBodyParams ───────────────────────────────────

    /** 空表不写（读回来是 null = 没有自定义头），且不因此把整层当成空。 */
    @Test
    fun `an empty header map round-trips to null`() {
        assertNull(roundTrip(ModelOverrides(customHeaders = emptyMap())).customHeaders)
        assertFalse(ModelOverrides(customHeaders = emptyMap()).isEmpty)
    }

    /** header 值统一成字符串（org.json 的宽松转换），不会因为值是数字就丢掉整个头。 */
    @Test
    fun `non-string header values are coerced instead of dropped`() {
        val back = ProviderOverridesWire.decode(
            JSONObject("""{"customHeaders":{"X-Num":123,"X-Bool":true}}"""),
        )
        assertEquals("123", back.customHeaders!!["X-Num"])
        assertEquals("true", back.customHeaders!!["X-Bool"])
    }

    /** 嵌套结构必须原样活着（不能被压成一行字符串，也不能被截断）。 */
    @Test
    fun `nested extra body params keep their structure`() {
        val nested = Json.parseToJsonElement(
            """{"thinking":{"type":"enabled","budget_tokens":8192},"tags":["a","b"]}""",
        ) as JsonObject
        val back = roundTrip(ModelOverrides(extraBodyParams = nested))
        val thinking = requireNotNull(back.extraBodyParams)["thinking"] as JsonObject
        assertEquals("enabled", (thinking["type"] as JsonPrimitive).content)
        assertEquals("8192", (thinking["budget_tokens"] as JsonPrimitive).content)
        assertEquals(2, (back.extraBodyParams!!["tags"] as JsonArray).size)
    }

    /** 只有 JSON OBJECT 才收：数组/标量进不了 body 合并逻辑（否则请求体会被撑坏）。 */
    @Test
    fun `a non-object extra body payload is rejected`() {
        assertNull(
            ProviderOverridesWire.decode(JSONObject("""{"extraBodyParams":[1,2,3]}"""))
                .extraBodyParams,
        )
        assertNull(
            ProviderOverridesWire.decode(JSONObject("""{"extraBodyParams":"raw"}"""))
                .extraBodyParams,
        )
    }

    // ── modality 位域（iOS 互操作）────────────────────────────────────────

    /** 导出同时写原生列表与位域；导入列表优先、位域兜底。 */
    @Test
    fun `the modality bitfield is written and used as the fallback`() {
        val entry = ModelOverrides(
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text"),
        )
        val wire = ProviderOverridesWire.encode(entry)
        assertTrue(wire.has("inputModalities"))
        // textIn(1) | imgIn(4) | textOut(2)
        assertEquals(1 or 4 or 2, wire.getInt("modalityOverride"))

        // iOS 形状：只有位域。
        val ios = ProviderOverridesWire.decode(JSONObject("""{"modalityOverride":${1 or 4 or 2}}"""))
        assertEquals(listOf("text", "image"), ios.inputModalities)
        assertEquals(listOf("text"), ios.outputModalities)

        // 位域为 0 → 没有任何能力声明，不是「空列表」。
        val none = ProviderOverridesWire.decode(JSONObject("""{"modalityOverride":0}"""))
        assertNull(none.inputModalities)
        assertNull(none.outputModalities)
    }
}
