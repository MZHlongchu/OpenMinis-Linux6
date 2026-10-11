package com.openminis.app.sandbox.offload

import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.util.IsoTime
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.PromptDecision
import com.openminis.app.sandbox.NativeOffloadHandler
import com.openminis.app.sandbox.NativeOffloadRequest
import com.openminis.app.sandbox.NativeOffloadResult
import com.openminis.app.sandbox.SessionAccessAudit
import com.openminis.app.sandbox.SessionAccessPolicy
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.atomic.AtomicBoolean

/**
 * T188 — `minis-sessions-cli` offload handler. Lets the in-shell agent
 * query historical chat sessions and messages without round-tripping
 * back through the LLM. Three subcommands:
 *
 *   list       List recent sessions, optionally filtered by id, keyword,
 *              and date range. Default if no subcommand is given.
 *   search     Search message content across sessions; --keywords
 *              required.
 *   messages   Read a paginated transcript of one session; --id required.
 *
 * Mirrors iOS canonical CLI surface: argv shape, JSON envelope, exit
 * codes, error messages all line up. The on-device db (Room) is the
 * same shape as iOS (sqlite tables `sessions`/`messages` with matching
 * column names), so query semantics translate one-to-one.
 *
 * Output flags `--compact` / `-q` / `--quiet` are honored uniformly via
 * [OffloadOutput.formatBody], same as every other android-* / minis-*
 * tool.
 */
class SessionsOffloadHandler(
    private val repo: ChatRepository,
) : NativeOffloadHandler {

    override fun handle(request: NativeOffloadRequest): NativeOffloadResult {
        // argv[0] is the program name ("minis-sessions-cli"); subcommand
        // and options follow. Drop argv[0] before parsing so positional[0]
        // is the subcommand name. `full` is declared boolean so
        // `--full <token>` never greedily consumes the next token as a value
        // (e.g. `--full` placed before the subcommand). Same for `--tools`.
        val args = OffloadArgs(request.argv.drop(1), booleanFlags = setOf("full", "tools"))
        if (args.hasFlag("h", "help")) {
            return NativeOffloadResult(0, OffloadOutput.formatBody(HELP_TEXT, args) + "\n")
        }

        val sub = args.positional.firstOrNull() ?: "list"
        // Cross-session reads are gated before any query runs: the boundary
        // has to hold for the CLI surface, not just the in-app tools.
        runBlocking { crossSessionDenial(request, sub, args) }?.let { return it }
        return try {
            when (sub) {
                "list" -> cmdList(args)
                "search" -> cmdSearch(args)
                "messages" -> cmdMessages(args)
                else -> {
                    val err = errorEnvelope(
                        sub,
                        "INVALID_ARGS",
                        "Unknown command '$sub'. Valid: list, search, messages. " +
                            "Use --help for details.",
                    )
                    NativeOffloadResult(
                        EXIT_INVALID_ARGS,
                        OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                    )
                }
            }
        } catch (e: Throwable) {
            // Last-ditch: any unexpected exception (sql parse error, OOM,
            // …) becomes an INTERNAL envelope rather than a crash so the
            // shell stays alive and the agent can read the failure.
            AppLogger.warning(TAG, "uncaught: ${e.message}")
            val err = errorEnvelope(sub, "INTERNAL", e.message ?: "unknown error")
            NativeOffloadResult(
                1,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }
    }

    /**
     * [T-android-session-read-boundary] Cross-session chat reads need an
     * explicit `session_read` grant.
     *
     * `list` stays ungated on purpose: it returns ids and titles only, and the
     * agent needs an id before it can ask to read anything. `search` and
     * `messages` return message bodies, which is the payload this boundary
     * exists to protect: a caller that does not own the target session must
     * hold the user's grant. Every attempt, allowed or not, is audited.
     *
     * [T-android-offload-hang] The grant check is bounded by
     * [OffloadPermissionManager.INTERACTIVE_BUDGET_MS], the same contract
     * [OffloadGate] applies: this runs inside `runBlocking` on an offload
     * worker thread where the ASK_ONCE prompt has no guaranteed UI, so an
     * unbounded wait parked that thread (and its suspended continuation) for
     * good — long after the caller's watchdog gave up. Expiry answers
     * `interaction_required` / exit 124, exactly like the gate.
     *
     * [T-d10-prompt-slot] 预算之外还要分清"提示到底有没有上过屏"。ASK_ONCE 提示槽是
     * **全局单槽**（`OffloadPromptQueue`：一张在屏、其余 FIFO 排队），而在屏那一张只由
     * **调用方自己的预算**让它退位。在屏工具（`SessionLookupTool` →
     * [SessionAccessPolicy.isCrossSessionGranted]）没有预算，它那张提示可以一直挂在
     * 屏幕上；此时本调用方排进 FIFO 只会把 15s 预算整段烧在队列里，然后报一句
     * "15s 内没人应答"——用户根本没看过这张提示，agent 会据此反复重试，而不是去处理
     * 眼前那一张。所以这里观测"我方提示是否上过屏"，把两种失败在文案上分开
     * （错误码不变：都是 `interaction_required` / 124，只有明确的 ALLOWED 才放行）。
     * 根因（在屏路径无预算）在 `SessionLookupTool` / `SessionAccessPolicy` 一侧，
     * 不在本文件；槽本身的"无人应答即退位"由 `OffloadPromptQueue` + 调用方预算共同
     * 保证（回归判据见 `SessionsOffloadSlotRetirementTest`）。
     */
    private suspend fun crossSessionDenial(
        request: NativeOffloadRequest,
        sub: String,
        args: OffloadArgs,
    ): NativeOffloadResult? {
        val caller = request.sessionId?.takeIf { it.isNotBlank() }
            ?: OffloadPermissionManager.OFFLOAD_GLOBAL_SESSION_ID
        val target: String = when (sub) {
            "messages" -> args.get("id")?.takeIf { it.isNotBlank() } ?: return null
            "search" -> {
                val ids = parseIds(args)
                if (ids != null && ids.isNotEmpty() &&
                    ids.all { !SessionAccessPolicy.isForeign(caller, it) }
                ) {
                    SessionAccessAudit.record(caller, sub, ids.joinToString(","), true, false)
                    return null
                }
                ""
            }
            else -> return null
        }
        if (target.isNotEmpty() && !SessionAccessPolicy.isForeign(caller, target)) {
            SessionAccessAudit.record(caller, sub, target, true, false)
            return null
        }
        // [T-d10-prompt-slot] 观测我方那张提示有没有真的上屏（见方法 KDoc）。watcher 是
        // 当前 runBlocking 事件循环上的子协程：不新起线程，父协程挂起（等提示）时它才
        // 被调度，因此能在提示在屏期间看到它。StateFlow 会合并中间值，所以这只是
        // "best-effort 观测"——判错时两边都还是 fail-closed 的 interaction_required，
        // 只是文案不同。
        val promptOnScreen = AtomicBoolean(false)
        val decision = coroutineScope {
            val watcher = launch {
                OffloadPermissionManager.pendingRequest.collect { pending ->
                    val shown = pending?.request ?: return@collect
                    if (shown.toolName == SessionAccessPolicy.GRANT && shown.sessionId == caller) {
                        promptOnScreen.set(true)
                    }
                }
            }
            try {
                withTimeoutOrNull(OffloadPermissionManager.INTERACTIVE_BUDGET_MS) {
                    OffloadPermissionManager.checkPermission(SessionAccessPolicy.GRANT, "Read other chats", caller)
                }
            } finally {
                watcher.cancel()
            }
        }
        val targetLabel = target.ifEmpty { "(all sessions)" }
        // [T-android-prompt-identity] 没人应答 ≠ 策略拒绝：null = 预算耗尽，
        // UNANSWERED = 提示被队列丢弃（排队超时 / 队列满，见 OffloadPromptQueue）。
        // 审计标记未回答，错误码走 interaction_required / 124，而不是 PERMISSION_DENIED / 126。
        if (decision == null || decision.isUnanswered) {
            SessionAccessAudit.record(caller, sub, targetLabel, false, false, promptUnanswered = true)
            return when (CrossSessionPromptSlot.outcome(decision, promptOnScreen.get())) {
                // [T-d10-prompt-slot] 我方的提示从来没上过屏（槽被另一张提示占着 / 队列满）：
                // 不能报"没人应答"，那是把槽的饥饿说成用户没点。
                CrossSessionPromptSlot.Outcome.DROPPED_WITHOUT_SCREEN -> slotBusyResult(sub, args)
                else -> interactionRequiredResult(sub, args)
            }
        }
        val granted = decision.isAllowed
        SessionAccessAudit.record(caller, sub, targetLabel, granted, granted)
        if (granted) return null
        val err = errorEnvelope(
            sub,
            "PERMISSION_DENIED",
            "This command reads message bodies from other chat sessions, which needs the " +
                "user to grant `session_read`. 'list' only returns ids and titles and stays " +
                "available. Ask the user to allow it, then retry.",
        )
        return NativeOffloadResult(
            EXIT_PERMISSION_DENIED,
            OffloadOutput.formatBody(err.toString(2), args) + "\n",
        )
    }
    private fun cmdList(args: OffloadArgs): NativeOffloadResult {
        val ids = parseIds(args)
        val kws = parseKeywords(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val metas = runBlocking {
            // keywordsMatchBody=false: list is ungated, so keyword matching
            // must not act as a yes/no oracle over foreign sessions' message
            // bodies (that would bypass the session_read boundary that
            // messages/search enforce).
            repo.querySessionsMeta(ids, kws, limit, startMs, endMs, keywordsMatchBody = false)
        }

        val sessions = JSONArray()
        for (m in metas) {
            val s = JSONObject()
                .put("session_id", m.id)
                .put("started_at", IsoTime.formatMinutes(m.startedAt))
                .put("last_active", IsoTime.formatMinutes(m.lastActive))
                .put("message_count", m.messageCount)
            // Optional fields — only emit when non-null so the JSON
            // matches iOS's `(optional)` shape rather than carrying
            // null literals through to the agent prompt.
            m.title?.let { if (it.isNotBlank()) s.put("title", it) }
            // preview is deliberately NOT emitted: list is ungated and its
            // documented contract is "id and title only" — a first-message
            // excerpt would leak foreign session content without a grant.
            m.source?.let { if (it.isNotBlank()) s.put("source", it) }
            sessions.put(s)
        }
        val data = JSONObject()
            .put("count", sessions.length())
            .put("sessions", sessions)
        return emit("list", data, args)
    }

    private fun cmdSearch(args: OffloadArgs): NativeOffloadResult {
        val kws = parseKeywords(args).orEmpty()
        if (kws.isEmpty()) {
            val err = errorEnvelope(
                "search",
                "INVALID_ARGS",
                "--keywords is required for search. " +
                    "Example: minis-sessions-cli search --keywords \"API error\"",
            )
            return NativeOffloadResult(
                EXIT_INVALID_ARGS,
                OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
            )
        }
        val ids = parseIds(args)
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        val limit = parseLimit(args)

        val matches = runBlocking { repo.searchMessages(ids, kws, limit, startMs, endMs) }

        val msgs = JSONArray()
        for (m in matches) {
            msgs.put(
                JSONObject()
                    .put("session_id", m.sessionId)
                    .put("message_id", m.messageId)
                    .put("role", m.role)
                    .put("created_at", IsoTime.formatMinutes(m.createdAt))
                    .put("snippet", m.snippet),
            )
        }
        val data = JSONObject()
            .put("count", msgs.length())
            .put("messages", msgs)
        return emit("search", data, args)
    }

    private fun cmdMessages(args: OffloadArgs): NativeOffloadResult {
        val sessionId = args.get("id")?.takeIf { it.isNotBlank() }
            ?: run {
                val err = errorEnvelope(
                    "messages",
                    "INVALID_ARGS",
                    "--id <session_id> is required. Use 'list' first to find session IDs.",
                )
                return NativeOffloadResult(
                    EXIT_INVALID_ARGS,
                    OffloadOutput.formatBody(err.toString(2), args) + "\n" + HELP_TEXT,
                )
            }
        val limit = parseLimit(args)
        val offset = (args.getInt("offset") ?: 0).coerceAtLeast(0)
        // [T-android-sessions-cli-full] --full lifts the per-message text cap
        // from the default 600 to 50000 chars (iOS parity). Previously the flag
        // was silently ignored — every message stayed truncated at 600 no
        // matter what the caller passed, gutting transcript exports.
        val full = args.hasFlag("full")
        val maxChars = if (full) ChatRepository.MESSAGE_TEXT_MAX_FULL else ChatRepository.MESSAGE_TEXT_MAX
        // [T-android-sessions-cli-messages-daterange] GH#200 (iOS 8f3189a73).
        // HELP_TEXT documents --start / --end and `list` / `search` honour
        // them, but `messages` parsed neither — the CLI accepted the flags and
        // silently returned the whole session, which reads as the filter
        // working and matching everything.
        val startMs = parseDate(args.get("start"))
        val endMs = parseEndDate(args.get("end"))
        // [T-android-sessions-cli-tool-calls] Opt-in, so the default output is
        // unchanged (iOS 7af22b59f).
        val tools = args.hasFlag("tools")

        val (page, total) = runBlocking {
            // Single coroutine block so the two reads see a consistent
            // snapshot — ordering matters less than ensuring we don't
            // surface a `total` that disagrees with the slice we
            // returned (e.g. another session interleaving inserts mid-
            // call). Room serializes via the suspending dispatcher so
            // these run sequentially in the same coroutine.
            // Both reads take the SAME date range on purpose: an unfiltered
            // count next to a filtered page would make `total` describe the
            // whole session while the slice covers only the matches, so
            // `hasMore` would lie. iOS 8f3189a73 calls this out explicitly.
            repo.loadMessagePage(sessionId, offset, limit, maxChars, startMs, endMs, includeTools = tools) to
                repo.messageCountInRange(sessionId, startMs, endMs)
        }

        val msgs = JSONArray()
        for (m in page) {
            val obj = JSONObject()
                .put("message_id", m.messageId)
                .put("role", m.role)
                .put("created_at", IsoTime.formatMinutes(m.createdAt))
                .put("text", m.text)
            // Only present when the stored text exceeded the cap, so normal
            // messages serialize byte-identically to before (iOS parity).
            if (m.truncated) obj.put("truncated", true)
            if (tools) {
                obj.put("tool_calls", toolCallsJson(m.toolUses, maxChars))
                obj.put("tool_results", toolResultsJson(m.toolResults, maxChars))
            }
            msgs.put(obj)
        }
        val data = JSONObject()
            .put("session_id", sessionId)
            .put("offset", offset)
            .put("limit", limit)
            .put("full", full)
            .put("max_chars", maxChars)
            .put("total", total)
            .put("count", msgs.length())
            .put("messages", msgs)
        // Only when on, so the default envelope keeps exactly its old keys.
        if (tools) data.put("tools", true)
        return emit("messages", data, args)
    }

    // ─── arg parsing helpers ──────────────────────────────────────────

    private fun parseIds(args: OffloadArgs): List<String>? {
        val raw = args.get("ids") ?: return null
        val parts = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseKeywords(args: OffloadArgs): List<String>? {
        val raw = args.get("keywords") ?: return null
        // Split on any whitespace (the user may quote multi-word keywords
        // at the shell, in which case we get one big token containing a
        // space — collapse runs of whitespace into a single delimiter).
        val parts = raw.split(Regex("\\s+")).filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() }
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        return IsoTime.parseFlexible(raw)
    }

    /**
     * Inclusive end-of-day: `--end 2025-03-31` includes everything up to
     * and including 2025-03-31 23:59:59.999. Adding (1 day - 1 ms) is
     * safer than (24h - 1ms) across DST transitions on devices that
     * don't run UTC. Mirrors iOS SessionsOffload.m L141-145.
     */
    private fun parseEndDate(raw: String?): Long? {
        val ms = parseDate(raw) ?: return null
        val cal = Calendar.getInstance().apply {
            timeInMillis = ms
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis - 1
    }

    private fun parseLimit(args: OffloadArgs): Int {
        val raw = args.getInt("limit") ?: return DEFAULT_LIMIT
        return raw.coerceIn(1, MAX_LIMIT)
    }

    // ─── envelope helpers ─────────────────────────────────────────────

    private fun emit(action: String, data: JSONObject, args: OffloadArgs): NativeOffloadResult {
        val envelope = JSONObject()
            .put("ok", true)
            .put("tool", TOOL_NAME)
            .put("action", action)
            .put("data", data)
        return NativeOffloadResult(
            0,
            OffloadOutput.formatBody(envelope.toString(2), args) + "\n",
        )
    }

    companion object {
        private const val TAG = "SessionsOffload"
        private const val TOOL_NAME = "minis-sessions-cli"
        private const val DEFAULT_LIMIT = 50
        private const val MAX_LIMIT = 100

        // iOS NOFF_EXIT_INVALID_ARGS — the shell convention is exit 2
        // for invalid CLI args, distinct from exit 1 for runtime errors.
        private const val EXIT_INVALID_ARGS = 2
        // Same code ConfigOffloadHandler uses for permission_denied, so a
        // caller can classify the failure without reading the message.
        private const val EXIT_PERMISSION_DENIED = 126
        // [T-android-offload-hang] The ASK_ONCE prompt went unanswered inside
        // INTERACTIVE_BUDGET_MS. 124 is the "timed out" code OffloadGate and
        // the offload watchdog already report, so a caller can tell "nobody
        // has answered yet" apart from "the user denied it" (126).
        private const val EXIT_INTERACTION_REQUIRED = 124

        /**
         * [T-android-offload-hang] Result for an expired cross-session prompt:
         * `interaction_required` + exit 124, matching [OffloadGate]'s timeout
         * branch so both gates read identically to the caller. `internal`
         * rather than private so the JVM unit test can pin the contract
         * without a Room-backed handler instance.
         *
         * [T-android-prompt-identity] 提示被队列丢弃（排队超时 / 队列满，
         * `PromptDecision.UNANSWERED`）也从这里出去 —— 两种情况下用户都还没
         * 看过那张提示，都不是"用户拒绝"。
         */
        internal fun interactionRequiredResult(action: String, args: OffloadArgs): NativeOffloadResult {
            val err = errorEnvelope(
                action,
                "interaction_required",
                "This command reads message bodies from other chat sessions, which needs the " +
                    "user to grant `session_read`. Nobody answered the in-app prompt within " +
                    "${OffloadPermissionManager.INTERACTIVE_BUDGET_MS / 1000}s, so no message " +
                    "bodies were read. Ask the user to approve it, then retry.",
            )
            return NativeOffloadResult(
                EXIT_INTERACTION_REQUIRED,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }

        /**
         * [T-d10-prompt-slot] 在屏槽被**另一张**提示占着、我方这张压根没上屏时的结果。
         * 错误码与 [interactionRequiredResult] 相同（124 / `interaction_required`，
         * 仍然 fail-closed，没读到任何消息体），区别只在文案：说清"这张提示没上过屏"，
         * 让 agent 去处理眼前那张提示（或去设置里直接授权）后重试，而不是以为用户
         * 看到了却没点。`internal` 供 JVM 单测钉住文案契约。
         */
        internal fun slotBusyResult(action: String, args: OffloadArgs): NativeOffloadResult {
            val err = errorEnvelope(
                action,
                "interaction_required",
                "This command reads message bodies from other chat sessions, which needs the " +
                    "user to grant `session_read`. The app is already showing another permission " +
                    "prompt, so this one was never put on screen and no message bodies were read. " +
                    "Ask the user to answer that prompt (or to grant `session_read` under " +
                    "Settings > Permissions), then retry.",
            )
            return NativeOffloadResult(
                EXIT_INTERACTION_REQUIRED,
                OffloadOutput.formatBody(err.toString(2), args) + "\n",
            )
        }

        private fun errorEnvelope(action: String, code: String, msg: String): JSONObject {
            return JSONObject()
                .put("ok", false)
                .put("tool", TOOL_NAME)
                .put("action", action)
                .put("data", JSONObject().put("error", code).put("message", msg))
        }

        /**
         * [T-android-sessions-cli-tool-calls] `tool_calls` for one message:
         * `{tool_use_id, name, input}`, input being the stored input JSON as a
         * string (so the field has one type even when cut mid-JSON), capped at
         * the same [maxChars] as `text` with `input_truncated` when cut.
         */
        internal fun toolCallsJson(uses: List<com.openminis.app.data.repository.OffloadToolUse>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tu in uses) {
                val call = JSONObject()
                    .put("tool_use_id", tu.toolUseId)
                    .put("name", tu.name)
                    .put("input", tu.input.take(maxChars))
                if (tu.input.length > maxChars) call.put("input_truncated", true)
                out.put(call)
            }
            return out
        }

        /** [T-android-sessions-cli-tool-calls] `tool_results`: `{tool_use_id, success, output}`, same cap. */
        internal fun toolResultsJson(results: List<com.openminis.app.data.repository.OffloadToolResult>, maxChars: Int): JSONArray {
            val out = JSONArray()
            for (tr in results) {
                val res = JSONObject()
                    .put("tool_use_id", tr.toolUseId)
                    .put("success", tr.success)
                    .put("output", tr.output.take(maxChars))
                if (tr.output.length > maxChars) res.put("output_truncated", true)
                out.put(res)
            }
            return out
        }

        private const val HELP_TEXT = """minis-sessions-cli - Query historical chat sessions and messages

USAGE:
  minis-sessions-cli <command> [options]

COMMANDS:
  list      List recent sessions (default: 50, max: 100)
  search    Search message content across sessions (requires --keywords)
  messages  Read messages from a specific session (requires --id)

OPTIONS:
  --keywords <words>    Space-separated keywords (AND logic, required for search)
  --ids <id1,id2,...>   Filter by comma-separated session IDs (list/search)
  --id <session_id>     Session ID to read messages from (messages)
  --full                (messages only) Return full message text up to 50000 chars
  --tools               (messages only) Also return each message's tool calls:
                        tool_calls [{tool_use_id, name, input}] with the full
                        input JSON (e.g. the exact shell command), and
                        tool_results [{tool_use_id, success, output}].
                        Same per-field cap as text (600, or 50000 with --full).
  --offset <n>          Skip first n messages, 0-based (default: 0)
  --start <YYYY-MM-DD>  Filter results after this date (inclusive)
  --end <YYYY-MM-DD>    Filter results before this date (inclusive, end of day)
  --limit <n>           Max results (default: 50, max: 100)
  --help, -h            Show this help message
  --compact             Minimize JSON output
  -q, --quiet           Output only data field

OUTPUT (list):
  Each session includes: session_id, title, source, started_at,
  last_active, message_count. No message content: bodies (and keyword
  matching against them) need a session_read grant via search/messages.

OUTPUT (search):
  Each message includes: session_id, message_id, role, created_at,
  snippet (up to 600 chars centered on keyword match).

OUTPUT (messages):
  Each message includes: message_id, role, created_at, text (up to 600 chars
  by default; up to 50000 with --full). Messages longer than the cap carry
  "truncated": true. Response also includes: session_id, offset, limit, full,
  max_chars, total (total message count). With --tools each message also
  has tool_calls / tool_results (input_truncated / output_truncated when cut)
  and the response carries "tools": true.

WORKFLOW:
  1. Use 'list' or 'list --keywords <topic>' to find relevant sessions
  2. Use 'search --keywords <terms>' to find specific messages
  3. Use 'messages --id <session_id>' to read a conversation
  4. Use --offset to paginate through long conversations

EXAMPLES:
  minis-sessions-cli list
  minis-sessions-cli list --limit 10
  minis-sessions-cli list --keywords python flask
  minis-sessions-cli list --start 2025-01-01 --end 2025-03-31
  minis-sessions-cli search --keywords "API error" --limit 20
  minis-sessions-cli search --keywords deploy --ids abc123,def456
  minis-sessions-cli messages --id <session_id>
  minis-sessions-cli messages --id <session_id> --full
  minis-sessions-cli messages --id <session_id> --tools --full
  minis-sessions-cli messages --id <session_id> --offset 20 --limit 10
"""
    }
}

/**
 * [T-d10-prompt-slot] `minis-sessions-cli` 跨会话门控"没拿到授权"时的两种失败形态，
 * 判据只有两件事：`checkPermission` 的返回值，以及**我方那张提示有没有真的上过屏**
 * （`OffloadPermissionManager.pendingRequest` 里出现过 `toolName == session_read` 且
 * `sessionId == 调用方` 的那一张，观测见 `crossSessionDenial`）。
 *
 * - [Outcome.DECIDED]：拿到了明确答复（ALLOWED / DENIED）——不是"没人应答"，文案由调用方按
 *   [PromptDecision.isAllowed] 决定；
 * - [Outcome.SHOWN_THEN_UNANSWERED]：提示上过屏，但预算内没人点 —— 文案说"没人应答"；
 * - [Outcome.DROPPED_WITHOUT_SCREEN]：提示压根没上屏（全局单槽被另一张提示占着 / 队列满 /
 *   排队超时）—— 文案必须说"没上屏"，否则 agent 会把槽的饥饿当成用户没点，反复重试。
 *
 * 三种都 fail-closed（只有 ALLOWED 放行）。抽成纯逻辑是为了让这条判据能被单测钉住
 * （见 `SessionsOffloadPromptSlotTest`）：调用它的 `crossSessionDenial` 要 Room 的
 * `ChatRepository` 才能实例化，本模块没有 Robolectric。
 */
internal object CrossSessionPromptSlot {

    enum class Outcome { DECIDED, SHOWN_THEN_UNANSWERED, DROPPED_WITHOUT_SCREEN }

    fun outcome(decision: PromptDecision?, sawOurPromptOnScreen: Boolean): Outcome = when {
        decision != null && !decision.isUnanswered -> Outcome.DECIDED
        sawOurPromptOnScreen -> Outcome.SHOWN_THEN_UNANSWERED
        else -> Outcome.DROPPED_WITHOUT_SCREEN
    }
}
