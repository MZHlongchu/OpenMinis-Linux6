package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-modality-provider-fallback] Provider-level default modalities.
 *
 * Android read modality straight off the two stored lists, so a model that
 * models.dev has not catalogued yet had both null and reported "supports
 * nothing" — the model detail screen showed image / PDF / audio / video input
 * all OFF for Claude Fable 5.1 on its release day, while iOS showed image + PDF
 * correctly because its `capabilities` falls back to a per-provider table.
 *
 * The risk in adding that table is the opposite failure: granting a modality to
 * something that does not have it. So these tests pin BOTH directions —
 * the fallback fires where it should, and stays out of the way everywhere else.
 */
class ProviderModalityFallbackTest {

    private fun model(
        id: String = "m",
        provider: String,
        inputs: List<String>? = null,
        outputs: List<String>? = null,
    ) = LLMModel(
        id = id,
        displayName = id,
        provider = provider,
        inputModalities = inputs,
        outputModalities = outputs,
    )

    // ── The reported bug ─────────────────────────────────────────────────

    @Test
    fun `an uncatalogued Anthropic model falls back to image and pdf input`() {
        // Fable 5.1 on release day: catalogued in LLMModel with no modality
        // literal and not yet in models.dev, so both lists are null.
        val fable51 = LLMModel.claudeFable51
        assertNull("precondition: no declared modality", fable51.inputModalities)
        assertTrue(fable51.hasImageInput)
        assertTrue("pdf must come through too", fable51.effectiveInputModalities!!.contains("pdf"))
    }

    @Test
    fun `the Anthropic fallback grants vision but NOT audio or video`() {
        // iOS's `.vision` is text+image+pdf. Over-granting here would light up
        // switches the model cannot honour.
        val m = model(provider = "Anthropic")
        assertTrue(m.hasImageInput)
        assertFalse(m.hasAudioInput)
        assertFalse(m.hasAudioOutput)
        assertFalse(m.effectiveInputModalities!!.contains("video"))
    }

    @Test
    fun `Google falls back to full multimodal input`() {
        // iOS maps Google to `.fullMultimodal` = text+image+pdf+audio+video.
        val m = model(provider = "Google")
        val ins = m.effectiveInputModalities!!
        assertTrue(ins.containsAll(listOf("text", "image", "pdf", "audio", "video")))
        assertTrue(m.hasAudioInput)
        // Input-multimodal only — iOS aliases fullMultimodal to
        // fullMultimodalInput, so audio OUT is not implied.
        assertFalse(m.hasAudioOutput)
    }

    @Test
    fun `OpenAI and OpenRouter fall back to vision, matching iOS`() {
        for (p in listOf("OpenAI", "OpenRouter")) {
            val m = model(provider = p)
            assertTrue("$p should default to vision", m.hasImageInput)
            assertFalse("$p must not default to audio", m.hasAudioInput)
        }
    }

    // ── Declared data always wins ────────────────────────────────────────

    @Test
    fun `a declared modality list is never overwritten by the fallback`() {
        // models.dev enrichment (or a hand-written literal) saying "text only"
        // must stay text-only even for a provider whose default is vision.
        val m = model(provider = "Anthropic", inputs = listOf("text"))
        assertEquals(listOf("text"), m.effectiveInputModalities)
        assertFalse(m.hasImageInput)
    }

    @Test
    fun `a declared list that EXCEEDS the provider default is preserved`() {
        val m = model(provider = "Anthropic", inputs = listOf("text", "image", "audio"))
        assertTrue(m.hasAudioInput)
        assertEquals(listOf("text", "image", "audio"), m.effectiveInputModalities)
    }

    @Test
    fun `an already-catalogued Anthropic model is unaffected`() {
        // The spec's control case: a model WITH models.dev data must behave
        // exactly as before the fallback existed.
        val opus5 = model(id = "claude-opus-5", provider = "Anthropic", inputs = listOf("text", "image"))
        assertEquals(listOf("text", "image"), opus5.effectiveInputModalities)
        assertTrue(opus5.hasImageInput)
        assertFalse(opus5.hasAudioInput)
    }

    // ── Third-party / unlisted providers must not change ─────────────────

    @Test
    fun `an uncatalogued Grok, Kimi or Copilot model falls back to vision, matching iOS`() {
        // [T-provider-default-modality-key] iOS 97577e81d. A Grok released after
        // the last models.dev snapshot declares no modalities; with no row it
        // reported no image input at all.
        for (p in listOf("xAI", "xAI (Grok)", "Kimi", "Kimi Code", "GitHub Copilot")) {
            val m = model(provider = p)
            assertEquals("$p gets the vision default", listOf("text", "image", "pdf"), m.effectiveInputModalities)
            assertTrue("$p must accept images", m.hasImageInput)
            assertFalse("$p must not gain audio", m.hasAudioInput)
            assertEquals(listOf("text"), m.effectiveOutputModalities)
        }
    }

    @Test
    fun `a Grok or Kimi model's own list still wins over the default`() {
        // The default only fills a null: a model whose API declared text only
        // stays text only.
        val textOnly = model(provider = "xAI", inputs = listOf("text"))
        assertFalse(textOnly.hasImageInput)
        val declaredVision = model(provider = "Kimi", inputs = listOf("text", "image"))
        assertEquals(listOf("text", "image"), declaredVision.effectiveInputModalities)
    }

    @Test
    fun `unknown and third-party provider names get no fallback at all`() {
        // "Custom" is how OpenAIModelsApi labels a relay of unknown capability:
        // the regression this table must never cause is a text-only relay
        // advertising image input.
        for (p in listOf("Custom", "DeepSeek", "Ollama", "vLLM", "LiteLLM", "test", "")) {
            val m = model(provider = p)
            assertNull("$p must have no synthetic modality", m.effectiveInputModalities)
            assertFalse("$p must not report image input", m.hasImageInput)
        }
    }

    @Test
    fun `provider matching is exact, not fuzzy`() {
        // A relay named "Anthropic (proxy)" is a different provider entry and
        // must not inherit Anthropic's defaults by substring luck.
        assertNull(model(provider = "Anthropic (proxy)").effectiveInputModalities)
        assertNull(model(provider = "anthropic").effectiveInputModalities)
    }

    // ── ModelEntry.model — the property the whole app actually reads ─────
    //
    // 1be751fdc added the fallback but wired it only into
    // ModelEntryDetailScreen, so the detail screen showed corrected switches
    // while chat gating, Vision Group membership and every other consumer —
    // all of which read `entry.model` — still saw the raw nulls. That is why
    // reinstalling and refreshing models appeared to change nothing.

    private fun entry(
        base: LLMModel,
        overrides: ModelOverrides = ModelOverrides(),
    ) = ModelEntry(providerInstanceId = "inst", baseModel = base, overrides = overrides)

    @Test
    fun `entry model applies the provider fallback when there is no override`() {
        // The reported bug, at the layer that matters.
        val e = entry(LLMModel.claudeFable51)
        assertTrue(e.overrides.isEmpty)
        val ins = e.model.inputModalities!!
        assertTrue(ins.contains("image"))
        assertTrue(ins.contains("pdf"))
        assertTrue(e.model.hasImageInput)
    }

    @Test
    fun `entry model no longer short-circuits past the fallback on empty overrides`() {
        // The specific defect: `if (overrides.isEmpty) return baseModel` never
        // called copy(), so no fallback could ever be applied in the common
        // case. Pin that the returned model is NOT the untouched baseModel.
        val base = LLMModel.claudeFable51
        val e = entry(base)
        assertNull("precondition", base.inputModalities)
        assertTrue("must not hand back the raw baseModel", e.model.inputModalities != null)
    }

    @Test
    fun `entry model keeps a declared list untouched`() {
        val base = model(id = "claude-opus-5", provider = "Anthropic", inputs = listOf("text", "image"))
        val e = entry(base)
        // The declared INPUT list survives verbatim — the fallback never
        // widens or reorders a list the model actually stated.
        assertEquals(listOf("text", "image"), e.model.inputModalities)
        assertTrue(e.model.hasImageInput)
        // The OUTPUT side was still null, so the Anthropic default fills it in.
        // That is correct and worth pinning: a model may be half-declared, and
        // each side resolves independently.
        assertEquals(listOf("text"), e.model.outputModalities)
    }

    @Test
    fun `a fully-declared entry allocates nothing`() {
        // Both sides stated and no override. 上游这条钉的是「无物可应用 → 直接交回
        // 同一个实例」（assertSame），即这条修复对已编目的模型零成本。
        //
        // [T-u14-merge-entry-hole-fill] 本移植的 ModelEntry.model 末端还有
        // `applyUnrecognizedModelDefaults(healed)`（[U14-merge] 的三段链；HEAD 里就已经
        // 是 `return applyUnrecognizedModelDefaults(healed)` 收尾，早于 1.14 合并）：
        // 只要 catalog 没给 contextWindow / maxOutputTokens / supportsReasoning，它就会
        // 补保守默认值，因此 true identity 在这种入口上不可能成立——那份补洞行为由
        // ModelEntryDefaultsTest 钉住，是有意为之，不是回退。
        //
        // 名字沿用上游便于与失败清单对齐，断言改钉原意里真正被依赖的那一半：兜底不得
        // 改写、增删或重排任何**已经声明**的值。
        val base = model(
            id = "claude-opus-5", provider = "Anthropic",
            inputs = listOf("text", "image"), outputs = listOf("text"),
        )
        val e = entry(base)
        assertEquals("declared input list survives verbatim", base.inputModalities, e.model.inputModalities)
        assertEquals("declared output list survives verbatim", base.outputModalities, e.model.outputModalities)
        assertEquals(base.id, e.model.id)
        assertEquals(base.displayName, e.model.displayName)
        assertEquals(base.provider, e.model.provider)
    }

    @Test
    fun `a user override still wins over the provider fallback`() {
        // Precedence: override > declared > provider default. The user turned
        // image OFF on an Anthropic model; the fallback must not turn it back on.
        val e = entry(
            LLMModel.claudeFable51,
            ModelOverrides(inputModalities = listOf("text")),
        )
        assertEquals(listOf("text"), e.model.inputModalities)
        assertFalse(e.model.hasImageInput)
    }

    @Test
    fun `a third-party entry is unchanged by the fallback`() {
        // A relay ("Custom") is outside the table: an entry with no override must
        // not end up advertising a capability /v1/models never gave it.
        //
        // [T-u14-merge-entry-hole-fill] 上游写的是 `assertNull(inputModalities)` +
        // `assertSame(baseModel, model)`：它的 getter 只落 provider 表，表里没有
        // "Custom" 就原样返回 baseModel。本移植的 getter 末端还有
        // applyUnrecognizedModelDefaults（见 ModelEntryDefaultsTest），会给目录没给值的
        // 字段补保守默认值，于是既会出现一份 copy，input 侧也会是 ["text"]。这条用例的
        // 原意是「第三方 provider 不得因此多出能力」，这里就把它钉在能力上。
        val declaredNothing = entry(model(provider = "Custom"))
        assertFalse("no vision may be invented for a relay", declaredNothing.model.hasImageInput)
        assertFalse("nor audio in", declaredNothing.model.hasAudioInput)
        assertFalse("nor audio out", declaredNothing.model.hasAudioOutput)
        val filledInput = declaredNothing.model.inputModalities
        assertTrue(
            "the relay's input side may only be absent or the text-only hole-fill: $filledInput",
            filledInput == null || filledInput == listOf("text"),
        )

        val declaredVision = entry(model(provider = "Custom", inputs = listOf("text", "image")))
        assertEquals(listOf("text", "image"), declaredVision.model.inputModalities)
    }

    @Test
    fun `the fallback does not disturb the other override fields`() {
        // Only modality resolves through effective*; displayName / tokens /
        // context / reasoning keep plain "null means inherit" semantics.
        val base = LLMModel(
            id = "m", displayName = "Base", provider = "Anthropic",
            contextWindow = 111, maxOutputTokens = 222, supportsReasoning = true,
        )
        val e = entry(base, ModelOverrides(displayName = "Renamed"))
        val m = e.model
        assertEquals("Renamed", m.displayName)
        assertEquals(111, m.contextWindow)
        assertEquals(222, m.maxOutputTokens)
        assertEquals(true, m.supportsReasoning)
        // …and the modality fallback still applied alongside them.
        assertTrue(m.hasImageInput)
    }

    @Test
    fun `a voice template seed entry keeps its exact shape through entry model`() {
        // Seeds declare one audio side and leave the other null. The shape
        // discriminator must keep matching the seed through `entry.model`, or
        // launch cleanup stops recognising retired seeds.
        //
        // [T-u14-merge-entry-hole-fill] 上游断言 `assertNull(e.model.inputModalities)`：
        // 它的 getter 只应用 provider 表，声明为 null 的一侧就保持 null。本移植的 getter
        // 末端还会跑 applyUnrecognizedModelDefaults（ModelEntryDefaultsTest 钉住），凡是
        // 目录没给值的字段都会补保守默认值，所以那侧的 null 一定会变成 ["text"] —— 有意
        // 为之，不是回退。
        //
        // 那"种子形状"的守卫放在哪？谓词 isVoiceTemplateSeedShape 读的是 **raw** 字段
        // （它的注释写明刻意避开会被兜底改写的 effective 列表），而唯一的读者
        // ProviderRepository 的退役种子清理读的也是 `baseModel`。所以这里按本移植的分层
        // 钉三件事：raw 形状原样活着、声明过的 audio 侧原样带过来、兜底只允许补 text。
        val seed = model(provider = "elevenlabs", inputs = null, outputs = listOf("audio"))
        val e = entry(seed)
        assertTrue("the shape the cleanup reads must survive on the raw model", e.baseModel.isVoiceTemplateSeedShape)
        assertEquals("the declared audio side comes through verbatim", listOf("audio"), e.model.outputModalities)
        assertTrue("audio out survives the fallback", e.model.hasAudioOutput)
        assertFalse("the hole-fill must not invent vision for a TTS seed", e.model.hasImageInput)
        val filledInput = e.model.inputModalities
        assertTrue(
            "the input side may only be absent or the text-only hole-fill: $filledInput",
            filledInput == null || filledInput == listOf("text"),
        )
    }

    // ── The voice shape predicates must keep seeing RAW nulls ────────────

    @Test
    fun `the ASR template seed shape still matches under the fallback`() {
        // [T-voice-seed-shape-exact] inputs == ["audio"] && outputs == null.
        // If the seed predicate read the effective lists, the null output side
        // would become ["text"] for a table provider and this could never match
        // — stranding retired seeds that launch cleanup removes by this shape.
        val seed = model(provider = "Anthropic", inputs = listOf("audio"), outputs = null)
        assertTrue(seed.isVoiceTemplateSeedShape)
    }

    @Test
    fun `the TTS template seed shape still matches under the fallback`() {
        val seed = model(provider = "Google", inputs = null, outputs = listOf("audio"))
        assertTrue(seed.isVoiceTemplateSeedShape)
    }

    @Test
    fun `a modality-less model is NOT mistaken for a template seed`() {
        // The fallback gives it text+image+pdf for capability purposes, but the
        // shape predicate reads raw nulls and correctly says "not a seed".
        val m = model(provider = "Anthropic")
        assertFalse(m.isVoiceTemplateSeedShape)
        assertTrue("capability side still works", m.hasImageInput)
    }

    // ── [M08] The table is keyed by ProviderType.displayName ─────────────
    //
    // The near-miss that motivated this section: the table shipped with a
    // "Google" row, but `LLMModel.provider` carries `ProviderType.displayName`
    // and the Gemini type's display name is "Google Gemini". The row therefore
    // matched no real model and the fallback silently no-op'd for every
    // un-catalogued Gemini model — a lookup that fails by returning null, with
    // no type error and no log line (6fff8231b / 728bf68d8 / 1be751fdc).
    //
    // The fix added the second key rather than replacing the first, so both
    // spellings resolve. Below, the property is stated over the WHOLE enum
    // instead of the two names that happened to be reported: for every provider
    // type, the answer the table gives for its real display name must be the
    // answer that type is supposed to have. A type added later either appears in
    // the table under its display name or is deliberately text-only — and either
    // way it cannot silently have an entry nothing can reach.

    /** Types iOS's knownCapabilities grants a non-text-only default to. */
    private val expectedVisionTypes = setOf(
        ProviderType.anthropic,
        ProviderType.openAI,
        ProviderType.openRouter,
        // [T-provider-default-modality-key] iOS 97577e81d.
        ProviderType.xAI,
        ProviderType.kimiCode,
        ProviderType.githubCopilot,
    )
    private val expectedMultimodalTypes = setOf(ProviderType.gemini)

    @Test
    fun `every provider type resolves the table under its real display name`() {
        for (type in ProviderType.entries) {
            // Exactly how a real model is constructed: provider = displayName.
            val m = model(provider = type.displayName)
            val ins = m.effectiveInputModalities
            when (type) {
                in expectedMultimodalTypes -> {
                    assertEquals(
                        "${type.name} (\"${type.displayName}\") must get the full " +
                            "multimodal default — a key mismatch here is the reported bug",
                        listOf("text", "image", "pdf", "audio", "video"),
                        ins,
                    )
                    assertTrue(m.hasAudioInput)
                }
                in expectedVisionTypes -> {
                    assertEquals(
                        "${type.name} (\"${type.displayName}\") must get the vision default",
                        listOf("text", "image", "pdf"),
                        ins,
                    )
                    assertTrue(m.hasImageInput)
                    assertFalse("${type.name} must not gain audio", m.hasAudioInput)
                }
                else -> {
                    // Responses API, Antigravity, Unsupported and the rest:
                    // absent from iOS's table on purpose, so they keep deriving
                    // modality from what their own /v1/models returned.
                    assertNull(
                        "${type.name} (\"${type.displayName}\") must have NO synthetic " +
                            "modality — inventing one would make a text-only relay " +
                            "advertise image input",
                        ins,
                    )
                    assertFalse(m.hasImageInput)
                }
            }
        }
    }

    @Test
    fun `the Gemini type resolves, which is the exact reported miss`() {
        // Named on its own because it is the case that shipped broken: the row
        // existed, the type existed, and they did not meet.
        assertEquals("Google Gemini", ProviderType.gemini.displayName)
        val gemini = model(provider = ProviderType.gemini.displayName)
        assertTrue("an un-catalogued Gemini model must see images", gemini.hasImageInput)
        assertTrue(gemini.hasAudioInput)
    }

    @Test
    fun `the legacy bare Google key is kept as well, not replaced`() {
        // Removing it would break any model already persisted with provider
        // "Google" — the built-in Gemini catalog entries are authored that way
        // (LLMModel.gemini25Pro et al), so both spellings must resolve.
        assertEquals(
            listOf("text", "image", "pdf", "audio", "video"),
            model(provider = "Google").effectiveInputModalities,
        )
        assertEquals("Google", LLMModel.gemini25Pro.provider)
        assertTrue("the built-in catalog uses the bare key", LLMModel.gemini25Pro.hasImageInput)
    }

    @Test
    fun `every built-in catalog model resolves through the table it belongs to`() {
        // End-to-end over the real catalog rather than synthetic providers: an
        // Anthropic or Gemini built-in that declares no modality must still
        // report vision, and no built-in may report audio it does not have.
        for (m in LLMModel.allAnthropic) {
            assertTrue("${m.id} must see images", m.hasImageInput)
            assertFalse("${m.id} must not claim audio in", m.hasAudioInput)
            assertFalse("${m.id} must not claim audio out", m.hasAudioOutput)
        }
        for (m in LLMModel.allGemini) {
            assertTrue("${m.id} must see images", m.hasImageInput)
            assertTrue("${m.id} must accept audio in", m.hasAudioInput)
            assertFalse("${m.id} must not claim audio out", m.hasAudioOutput)
        }
        // [T-provider-default-modality-key] xAI is in the table now (iOS
        // 97577e81d), and like iOS's catalog (xAIModelsAPI.swift) the built-ins
        // declare no modality, so they read vision until models.dev enrichment
        // (XAIModelsApi.fetchModelsOAuth) gives a catalogued model its real list.
        for (m in LLMModel.allXAI) {
            assertTrue("${m.id} gets the xAI vision default", m.hasImageInput)
            assertFalse("${m.id} must not claim audio in", m.hasAudioInput)
        }
    }

    @Test
    fun `no table key is unreachable`() {
        // The generalized form of the bug: a key that matches neither a
        // ProviderType.displayName nor a provider string the catalog actually
        // writes is dead code that LOOKS like coverage. "Google" is reachable via
        // the built-in Gemini entries, every other key must be a display name.
        val src = com.openminis.app.ProductionSources.read("data/model/LLMModel.kt")
        val table = src.substringAfter("PROVIDER_DEFAULT_INPUT_MODALITIES: Map<String, List<String>> = mapOf(")
            .substringBefore(")\n")
        val keys = Regex("""^\s*"([^"]+)" to """, RegexOption.MULTILINE)
            .findAll(table).map { it.groupValues[1] }.toList()
        assertTrue("expected to parse the table keys, got $keys", keys.size >= 4)

        val displayNames = ProviderType.entries.map { it.displayName }.toSet()
        val catalogProviders = LLMModel.allModels.map { it.provider }.toSet()
        for (key in keys) {
            assertTrue(
                "table key \"$key\" matches no ProviderType.displayName and no " +
                    "provider string in the built-in catalog — it can never fire " +
                    "(this is the \"Google\" vs \"Google Gemini\" failure)",
                key in displayNames || key in catalogProviders,
            )
        }
    }

    @Test
    fun `the input and output tables cover the same providers`() {
        // A provider present on one side only would resolve half its modality
        // and leave the other null, which then falls through to "no answer" —
        // half-applied is harder to diagnose than not applied.
        val src = com.openminis.app.ProductionSources.read("data/model/LLMModel.kt")
        fun keysOf(marker: String) = src.substringAfter(marker)
            .substringBefore(")\n")
            .let { body ->
                Regex("""^\s*"([^"]+)" to """, RegexOption.MULTILINE)
                    .findAll(body).map { it.groupValues[1] }.toSet()
            }
        assertEquals(
            keysOf("PROVIDER_DEFAULT_INPUT_MODALITIES: Map<String, List<String>> = mapOf("),
            keysOf("PROVIDER_DEFAULT_OUTPUT_MODALITIES: Map<String, List<String>> = mapOf("),
        )
    }

    // ── [M08] The fallback applies where the REQUEST is built ─────────────

    @Test
    fun `the request-time image gate reads the effective list`() {
        // 1be751fdc wired the fallback into the detail screen only, so the
        // switches looked right while chat gating still saw raw nulls — which is
        // why reinstalling and refreshing appeared to change nothing. The
        // preflight that decides whether an image may be attached is the
        // request-side consumer, and it must read `effectiveInputModalities`.
        val src = com.openminis.app.ProductionSources.read("ui/chat/ImageInputPreflight.kt")
        assertTrue(
            "the preflight must import the effective list, not the raw field",
            src.contains("effectiveInputModalities"),
        )
    }

    @Test
    fun `ModelEntry_model is the single place the fallback is applied`() {
        // Every consumer reads `entry.model`, so the fallback belongs there and
        // nowhere else — a second copy in a screen is how the first fix ended up
        // covering only one surface.
        val src = com.openminis.app.ProductionSources.read("data/model/ProviderConfig.kt")
        val prop = src.substringAfter("val model: LLMModel").substringBefore("\n}")
        assertTrue(prop.contains("baseModel.effectiveInputModalities"))
        assertTrue(prop.contains("baseModel.effectiveOutputModalities"))
        // [T-u14-merge-fallback-gate] 上游这条是 `prop.contains("fallbackPending")`：
        // 它的 getter 有 `if (overrides.isEmpty && !fallbackPending) return baseModel`，
        // 断言那个 early return 被"两侧都无物可应用"把住。本移植的 [U14-merge] 三段链
        // 没有 early return（它总是先解析出 resolved/healed/fallback 再返回），所以
        // "回退不得被短路"这条性质在这里是**无条件成立**的；断言改钉它，且对
        // "将来又有人加回 early return"保持敏感：真加了，就必须带上那个门。
        assertTrue(
            "the provider table must supply BOTH sides whenever they are still unresolved",
            prop.contains("?: effIn") && prop.contains("?: effOut"),
        )
        val earlyReturn = Regex("return baseModel\\b").containsMatchIn(prop)
        assertTrue(
            "an early return may only exist when it is gated on 'nothing to apply from " +
                "EITHER source' (upstream's `fallbackPending`); this getter has none, which " +
                "is the stronger form of the same rule",
            !earlyReturn || prop.contains("fallbackPending"),
        )
    }

    @Test
    fun `voice inference still fires for a modality-less dedicated ASR model`() {
        // withInferredVoiceModality gates on the RAW fields being null; the
        // fallback is a read-side concept and must not make it think the model
        // already declared something.
        val whisper = model(id = "whisper-large-v3", provider = "OpenAI")
        val inferred = whisper.withInferredVoiceModality()
        assertEquals(listOf("audio"), inferred.inputModalities)
        assertEquals(listOf("text"), inferred.outputModalities)
    }

    @Test
    fun `fetched Grok and Kimi models are labelled as the provider, not as a relay`() {
        // The OpenAI-compatible fetcher labels any non-OpenAI base "Custom".
        // Without the relabel a live-fetched Grok would miss the rows above,
        // which is exactly how a brand-new Grok arrives.
        val repo = java.io.File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt").readText()
        assertTrue(repo.contains(".map { it.copy(provider = \"xAI\") }"))
        assertTrue(repo.contains(".map { it.copy(provider = \"Kimi\") }"))
    }

    // [T-android-copilot-textonly-explicit] Copilot reports vision per model;
    // "no" must reach the table as text-only, never as null, or the Copilot
    // provider default (vision) fills it in.
    @Test
    fun `a Copilot model reported as text-only stays text-only`() {
        val textOnly = model(provider = "GitHub Copilot", inputs = listOf("text"))
        assertEquals(listOf("text"), textOnly.effectiveInputModalities)
        val src = com.openminis.app.ProductionSources.read("data/repository/ProviderRepository.kt")
        assertTrue(
            "the Copilot fetch writes text-only for supportsVision=false",
            src.contains("inputModalities = if (m.supportsVision) {\n                                            listOf(\"text\", \"image\")\n                                        } else listOf(\"text\"),"),
        )
    }
}
