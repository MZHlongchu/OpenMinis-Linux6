package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.openminis.app.ProductionSources
import org.junit.Test

/**
 * [T-android-opencode-session-header] Who receives `x-opencode-session`, and
 * with what value.
 *
 * OpenCode Go began rejecting requests without this header on 2026-09-06 —
 * `400 "Request is missing x-opencode-session and cannot be routed
 * efficiently"` (GH #327) — which took Minis out of service against it
 * (GH #304). The operator's rule is "one stable ID per conversation",
 * unchanged across retries and resumption.
 *
 * Two failure modes are pinned here because neither announces itself at
 * runtime: sending the id to a host that is not OpenCode (a privacy leak,
 * silent), and sending a draft placeholder that changes into a real UUID after
 * the first turn (cache never hits, no error).
 */
class OpenCodeSessionHeaderTest {

    private val SID = "3f2a1c7e-9b04-4d55-8e21-0a7c6f5b3d19"

    // ── Host gating ───────────────────────────────────────────────────────

    @Test
    fun `the documented endpoint is matched`() {
        assertTrue(OpenCodeSessionHeader.matches("https://opencode.ai/zen/go/v1"))
    }

    @Test
    fun `subdomains of the service are matched`() {
        assertTrue(OpenCodeSessionHeader.matches("https://api.opencode.ai/v1"))
        assertTrue(OpenCodeSessionHeader.matches("https://zen.opencode.ai/go/v1"))
    }

    /**
     * The reason this is host-matched rather than `contains("opencode.ai")`.
     * Both of these are registered by someone else, and a substring test hands
     * them a stable per-conversation identifier for every request the user
     * makes — the precise harm the host gate exists to prevent.
     */
    @Test
    fun `lookalike hosts are not matched`() {
        assertFalse(
            "a subdomain-prefixed lookalike must not match",
            OpenCodeSessionHeader.matches("https://opencode.ai.evil.example.com/v1"),
        )
        assertFalse(
            "a suffix lookalike must not match",
            OpenCodeSessionHeader.matches("https://not-opencode.ai/v1"),
        )
        assertFalse(
            "the domain appearing in a path must not match",
            OpenCodeSessionHeader.matches("https://relay.example.com/opencode.ai/v1"),
        )
    }

    @Test
    fun `ordinary providers are not matched`() {
        for (url in listOf(
            "https://api.openai.com/v1",
            "https://api.deepseek.com/v1",
            "https://generativelanguage.googleapis.com/v1beta",
            "http://192.168.1.10:11434/v1",
        )) {
            assertFalse("$url must not match", OpenCodeSessionHeader.matches(url))
        }
    }

    /**
     * `effectiveBaseURL` is null whenever no custom base URL is set (the
     * official-endpoint case), and an unparseable string tells us nothing
     * about where the request lands — neither may be treated as OpenCode.
     */
    @Test
    fun `absent or unparseable urls are not matched`() {
        assertFalse(OpenCodeSessionHeader.matches(null))
        assertFalse(OpenCodeSessionHeader.matches(""))
        assertFalse(OpenCodeSessionHeader.matches("   "))
        assertFalse(OpenCodeSessionHeader.matches("not a url"))
    }

    /**
     * The base-URL field only trims what the user typed, and the docs print the
     * endpoint without a scheme — so `opencode.ai/zen/go/v1` is a shape real
     * configs carry. Dropping the header for it would reproduce the very 400
     * this change fixes. iOS normalizes identically.
     */
    @Test
    fun `a scheme-less url is normalized before matching`() {
        assertTrue(OpenCodeSessionHeader.matches("opencode.ai/zen/go/v1"))
        assertTrue(OpenCodeSessionHeader.matches("api.opencode.ai/v1"))
        assertEquals(
            mapOf("x-opencode-session" to SID),
            OpenCodeSessionHeader.headersFor("opencode.ai/zen/go/v1", SID),
        )
    }

    /** Normalizing must not turn a lookalike into a match. */
    @Test
    fun `a scheme-less lookalike is still refused`() {
        assertFalse(OpenCodeSessionHeader.matches("not-opencode.ai/v1"))
        assertFalse(OpenCodeSessionHeader.matches("opencode.ai.evil.example.com/v1"))
    }

    /** Plain http (a local relay in front of the service) matches too. */
    @Test
    fun `http is matched as well as https`() {
        assertTrue(OpenCodeSessionHeader.matches("http://opencode.ai/zen/go/v1"))
    }

    /** Host comparison is case-insensitive, since a user may paste any casing. */
    @Test
    fun `host casing does not matter`() {
        assertTrue(OpenCodeSessionHeader.matches("https://OpenCode.AI/zen/go/v1"))
    }

    // ── Header emission ───────────────────────────────────────────────────

    @Test
    fun `a matched host with a real session id gets the header`() {
        assertEquals(
            mapOf("x-opencode-session" to SID),
            OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", SID),
        )
    }

    @Test
    fun `an unmatched host never gets the header`() {
        assertTrue(OpenCodeSessionHeader.headersFor("https://api.openai.com/v1", SID).isEmpty())
        assertTrue(OpenCodeSessionHeader.headersFor("https://not-opencode.ai/v1", SID).isEmpty())
    }

    // ── The draft guard ───────────────────────────────────────────────────

    /**
     * The silent bug this exists to stop. A new chat's id is a synthetic
     * `__new__<uuid>` key until `ensureSession()` persists the row on first
     * send (ChatViewModel.isDraft). Emitting it would put one value on turn 1
     * and a different UUID on turn 2 — the endpoint keeps answering, so
     * nothing surfaces, while the prompt cache the header exists to key never
     * hits and the traffic looks like a client churning session ids.
     */
    @Test
    fun `a draft session id is never sent`() {
        for (draft in listOf(
            "__new__3f2a1c7e-9b04-4d55-8e21-0a7c6f5b3d19",
            "__new__abc__fld__work",
            "__new__abc__grp__research",
        )) {
            assertTrue(
                "draft id $draft must not be sent",
                OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", draft).isEmpty(),
            )
        }
    }

    /**
     * The guard rejects the whole synthetic `__…` shape, not just the current
     * literal, so a future placeholder key cannot silently start leaking a
     * changing value. Costs at most a header on one turn if that ever happens.
     */
    @Test
    fun `any synthetic underscore key is refused`() {
        assertFalse(OpenCodeSessionHeader.isPersistedSessionId("__draft__x"))
        assertFalse(OpenCodeSessionHeader.isPersistedSessionId("__whatever"))
    }

    @Test
    fun `a missing or blank id sends nothing rather than a placeholder`() {
        val url = "https://opencode.ai/zen/go/v1"
        assertTrue(OpenCodeSessionHeader.headersFor(url, null).isEmpty())
        assertTrue(OpenCodeSessionHeader.headersFor(url, "").isEmpty())
        assertTrue(OpenCodeSessionHeader.headersFor(url, "   ").isEmpty())
    }

    @Test
    fun `a real uuid is recognised as persisted`() {
        assertTrue(OpenCodeSessionHeader.isPersistedSessionId(SID))
    }

    /**
     * Stability is the whole contract: the same conversation must produce the
     * same value on every call, including the retries and resumptions that
     * rebuild the provider (model switch, fallback, OAuth refresh).
     */
    @Test
    fun `the same session yields the same header every time`() {
        val url = "https://opencode.ai/zen/go/v1"
        val first = OpenCodeSessionHeader.headersFor(url, SID)
        repeat(5) { assertEquals(first, OpenCodeSessionHeader.headersFor(url, SID)) }
    }

    /** Distinct conversations must not share a value. */
    @Test
    fun `different sessions yield different headers`() {
        val url = "https://opencode.ai/zen/go/v1"
        val a = OpenCodeSessionHeader.headersFor(url, "aaaaaaaa-0000-0000-0000-000000000001")
        val b = OpenCodeSessionHeader.headersFor(url, "bbbbbbbb-0000-0000-0000-000000000002")
        assertTrue(a.isNotEmpty() && b.isNotEmpty())
        assertTrue("two conversations must not share an id", a != b)
    }

    /**
     * A surrounding-whitespace id is a real value, not a placeholder — trim it
     * rather than dropping the header, since HTTP would reject the raw form.
     */
    @Test
    fun `a padded id is trimmed rather than refused`() {
        assertEquals(
            mapOf("x-opencode-session" to SID),
            OpenCodeSessionHeader.headersFor("https://opencode.ai/zen/go/v1", "  $SID  "),
        )
    }

    /** The name is the operator's contract; a rename silently un-fixes the bug. */
    @Test
    fun `the header name is exactly what opencode requires`() {
        assertEquals("x-opencode-session", OpenCodeSessionHeader.HEADER)
    }

    /**
     * [T-android-opencode-subtask-session] Requests made on a chat's behalf
     * outside the chat screen must carry that chat's id too, or OpenCode Go
     * answers 400. The chat screen's own paths already pass activeSessionId.
     */
    @Test
    fun `regenerate title and minis-model-use pass the chat's session id`() {
        // [T-titlegen-group-order] Both title paths now build each candidate's
        // provider in TitleCandidates.providerFor, which tags it; the callers
        // hand it the chat's id.
        val list = ProductionSources.read("ui/sessions/SessionListViewModel.kt")
        assertTrue(list.contains("providerRepository, context, entry, sessionId = id,"))
        // [T-android-opencode-subtask-session] 本移植的自动标题那条路**不走**
        // TitleCandidates：ChatViewModel.generateSessionTitleIfNeeded() 自己用
        // resolveTitleProvider() 解析子系统条目（[T-android-regenerate-title-submodel]
        // 只共享 ProviderRepository.resolveTitleSubEntry 这一半），所以上游那句
        // `TitleCandidates.providerFor(providerRepository, context, entry, activeSessionId)`
        // （upstream ChatViewModel.kt:16138）在本移植里不存在。要守的东西一个字没变：
        // **替这个会话发出去的请求必须带上这个会话的 id** —— 断言因此落在
        // "那个函数把 activeSessionId 交给 ProviderFactory"上（ProviderFactory 是
        // provider.sessionId 的唯一来源，OpenCodeSessionHeader 再从它推导 header）。
        val vm = ProductionSources.readClassSurface("ui/chat/ChatViewModel.kt")
        val titleProvider = ProductionSources.functionWindow(vm, "resolveTitleProvider", 4_000).orEmpty()
        assertTrue("resolveTitleProvider window not found in the ChatViewModel surface", titleProvider.isNotEmpty())
        assertTrue(
            "the chat's own title request must carry the chat's id",
            titleProvider.contains(
                "ProviderFactory.create(instance, apiKey, entry.model, context, sessionId = activeSessionId)",
            ),
        )
        val titles = ProductionSources.read("ui/chat/TitleCandidates.kt")
        assertTrue(titles.contains("sessionId = sessionId, overrides = entry.overrides"))
        // [T-android-model-use-provider-pollution] minis-model-use 那条路的 provider 是
        // createEphemeral 单独构建、不进 memo 的（memo 那份正是 UI 聊天共用的对象，
        // 按调用改它会污染后续请求），所以 sessionId / 覆盖参数改成**等价的两行手工
        // 接线**，而不是 ProviderFactory.create 的命名参数。要守的两件事一件不少：
        // 会话 id 与 per-model overrides 都必须到达那个 provider。
        val modelUse = ProductionSources.read("sandbox/offload/ModelUseOffloadHandler.kt")
        assertTrue(modelUse.contains("openAI.sessionId = request.sessionId"))
        assertTrue(modelUse.contains("openAI.modelOverrides = entry.overrides"))
    }
}
