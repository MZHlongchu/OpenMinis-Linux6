package com.openminis.app.tools

import com.openminis.app.browser.BrowserAction
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.model.SubAgentDefinition

/**
 * Central registry of all agent tool definitions.
 * Returns provider-agnostic AgentToolDefinition list used by the agent loop.
 * Tool definitions aligned with iOS AIChatViewModel.makeAgentTools().
 */
object AgentTools {

    /**
     * [T-a11y-skill] System-prompt guidance for the recorded-skill loop.
     * Assembled by ChatViewModelPromptExt into the "Available tools" section —
     * the fragment lives here (next to the tool registration it describes) so
     * the prompt text cannot drift from the schema.
     */
    const val UI_SKILL_PROMPT_FRAGMENT: String =
        "- ui_skill: Repetitive device operations (固定的打开→搜索→点击流程) — first ui_skill match/replay a previously recorded skill; fall back to ui_read + ui_action only when replay fails. Record new flows via Settings → Accessibility → 录制场景 then ui_skill save."

    fun makeAgentTools(
        supportsImageInput: Boolean = true,
        // [T-android-vision-group / GH#182] When the main model can't natively
        // see images but the user has bound a Vision Group, still expose
        // read_image: ReadImageTool routes the image through a vision-capable
        // group member and returns a text description. Mirrors iOS makeAgentTools
        // visionGroupConfigured. Neither native vision nor a Vision Group → tool
        // stays absent (current behaviour).
        visionGroupConfigured: Boolean = false,
        // [T-memory-toggle-gates-injection-and-tools-android] When the
        // user has turned memory off (via /memory or
        // Settings/SessionMemorySheet), drop both memory_write and
        // memory_get from the schema entirely so the model can't even
        // attempt those calls. Mirrors the iOS gate at
        // AIChatViewModel.makeAgentTools(memoryEnabled:).
        memoryEnabled: Boolean = true,
        // [T-p1-delegate-task] True for a helper (child) vm. Depth = 1: a
        // helper never sees delegate_task; it also loses memory_write (design
        // §4.3 — helpers have no long-term memory). memory_get stays so a
        // helper can still LOOK things up.
        isHelper: Boolean = false,
        // [T-p2-agent-settings] Settings › Agents can remove delegation globally.
        delegateEnabled: Boolean = true,
        // [T-tools-granular-switches] Settings › Agent Tools can remove
        // browser_use globally. Separate from delegateEnabled because the two
        // are independent user choices; mirrors iOS AgentToolSwitch.browser.
        browserEnabled: Boolean = true,
        // [T-sub-agents-v1] The names of the enabled sub agents, in roster
        // order. These become `subagent_task.agent`'s enum, and the caller
        // rebuilds them every turn: the schema is not cached, so a rename takes
        // effect on the next request and the model cannot emit a name that
        // would fail to resolve. Empty falls back to the built-in's name so the
        // enum is never an empty list (which some providers reject).
        rosterNames: List<String> = listOf(SubAgentDefinition.BUILT_IN_NAME),
        // [T-merge-1.14] 我们这一侧的协作型子代理波次（spawn_agent +
        // check_agent + wolfpack/dispatch_agents，见 SubAgentKind）与上游 1.14
        // 统一的 subagent_task 是两套并行能力：闸门互不覆盖，任一开启即暴露
        // 对应工具集；主会话同时开启时模型两种委派路径都可用（工具名不冲突，
        // 见 makeAgentTools 末尾的 alwaysOn 说明）。
        subAgentEnabled: Boolean = false,
        codeGraphEnabled: Boolean = false,
        // [T-mcp-native] Expose the in-process `mcp` tool when an MCPRepository
        // is wired into the session (main chats only; sub-agents/group chats
        // keep the minis-mcp-cli shell route).
        mcpNativeEnabled: Boolean = false,
        /** When false, expose only the core set plus [find_tools]. */
        includeLongTail: Boolean = true,
        /** Long-tail tools enabled by a prior find_tools call. */
        enabledToolNames: Set<String> = emptySet(),
    ): List<AgentToolDefinition> {
        val all = buildList {
        add(shellExecuteDefinition())
        add(FileReadTool.definition())
        add(FileWriteTool.definition())
        add(FileEditTool.definition())
        add(MultiEditTool.definition())
        add(ListDirTool.definition())
        add(GrepTool.definition())
        add(GlobTool.definition())
        add(UiReadTool.definition())
        add(UiActionTool.definition())
        add(UiSkillTool.definition())
        if (supportsImageInput || visionGroupConfigured) {
            add(ReadImageTool.definition())
        }
        // [T-tools-granular-switches] Upstream 1.14 gates browser_use behind
        // Settings › Agent Tools; adopted here (default on, so behaviour is
        // unchanged until the user flips it).
        if (browserEnabled) add(browserUseDefinition())
        add(WebSearchTool.definition())
        add(OcrTool.definition())
        add(ScreenTimeTool.definition())
        add(WebFetchTool.definition())
        add(shellExecAliasDefinition())
        add(suExecDefinition())
        add(envExecDefinition())
        add(DispatchAgentsTool.definition())
        add(WolfpackTool.definition())
        add(AgentPlanTool.definition())
        if (mcpNativeEnabled) {
            add(McpNativeTool.definition())
        }
        add(GoalTool.definition())
        add(ExecuteCodeTool.definition())
        add(InvokeSkillTool.definition())
        add(SkillManageTool.definition())
        // Agent-facing surface for the evolution engine (status/list/decide).
        add(EvolutionTool.definition())
        add(AskReasoningTool.definition())
        add(ProductMediaTools.generateImageDefinition())
        add(ProductMediaTools.generateVideoDefinition())
        add(ProductMediaTools.describeImageDefinition())
        add(ProductMediaTools.transcribeAudioDefinition())
        add(ProductMediaTools.translateTextDefinition())
        add(SessionLookupTool.searchDefinition())
        add(SessionLookupTool.readDefinition())
        add(AskUserQuestion.definition())
        add(CronJobTool.definition())
        // [T-ssh-backend] Remote SSH exec/SFTP. Unconditional — the backend
        // needs no repository wiring (hosts live in the encrypted
        // SshConfigStore; empty state self-documents via list_hosts). Kept
        // OUT of alwaysOn: long-tail, discovered via find_tools("ssh").
        add(SshTool.definition())
        if (memoryEnabled) {
            if (!isHelper) add(memoryWriteDefinition())
            add(memoryGetDefinition())
        }
        if (codeGraphEnabled) {
            add(CodeGraphTool.definition())
        }
        if (subAgentEnabled) {
            add(runSubAgentDefinition())
            // [T-subagent-background] Collection surface for detached waves.
            add(CheckAgentTool.definition())
        }
        // [T-sub-agents-v1 / T-merge-1.14] 上游 1.14 的统一委派工具：一次调用
        // 内含 delegate / status / steer / cancel / resume 五个动作，取代
        // delegate_task + agent_status。helper（子代理，深度=1）永远看不到它，
        // 用户关掉 Settings › Agents 时同样从 schema 中移除。
        if (!isHelper && delegateEnabled) {
            add(subAgentTaskDefinition(rosterNames))
        }
        }
        // [T-find-tools] With long-tail loading, the main session schema starts
        // with the core set plus find_tools; everything else is enabled on
        // demand. Gated tools (memory / code_graph / sub-agent dispatch) stay
        // present whenever their gate is on so existing injected prompts still
        // resolve. Full mode (sub-agents / group chat) keeps the historical
        // all-tools schema unchanged.
        if (includeLongTail) return all
        val alwaysOn = buildSet {
            addAll(
                listOf(
                    "shell_execute", "file_read", "file_write", "file_edit", "multi_edit",
                    "list_dir", "grep", "glob", "browser_use", "web_search", "web_fetch",
                    "ui_read", "ui_action", "read_image", "ask_user_question", "update_goal",
                    // [T-plan-board-always-on] 任务列表工具必须常驻：多步任务的
                    // 进度看板（PlanBoard）只渲染模型经此工具写入的条目——被
                    // long-tail 门控挡住时模型看不到工具、不会建任务列表，
                    // 看板永远为空（实测「没有任务列表」的根因）。
                    AgentPlanTool.NAME,
                ),
            )
            if (memoryEnabled) {
                add(memoryWriteDefinition().name)
                add(memoryGetDefinition().name)
            }
            if (codeGraphEnabled) add(CodeGraphTool.NAME)
            // [T-mcp-native] The prompt fragment announces MCP servers, so the
            // native tool must never be long-tail-gated away when enabled.
            if (mcpNativeEnabled) add(McpNativeTool.NAME)
            if (subAgentEnabled) {
                add(SubAgentKind.SPAWN_AGENT)
                add(SubAgentKind.RUN_SUBAGENT)
                add(WolfpackTool.NAME)
                add(DispatchAgentsTool.NAME)
                // A background dispatch hands the model a dispatch_id and tells
                // it to call check_agent; gating that tool behind find_tools
                // would strand the wave it was supposed to collect.
                add(CheckAgentTool.NAME)
            }
            // [T-sub-agents-v1 / T-merge-1.14] The agents switch is an explicit
            // user choice, so subagent_task must survive long-tail filtering:
            // the prompt announces it, and hiding it behind find_tools would
            // strand the delegation path the user just enabled (same reasoning
            // as the check_agent entry above).
            if (!isHelper && delegateEnabled) add(SubAgentDefinition.TOOL_NAME)
        }
        return (all.filter { it.name in alwaysOn || it.name in enabledToolNames } + FindTools.definition())
            .distinctBy { it.name }
    }

    /**
     * Extra tools exposed ONLY to sub-agents (see ChatViewModelSubAgentExt).
     * The main session already has shell_execute for grep; sub-agents get a
     * dedicated grep_source so a source lookup is one turn instead of a
     * file_read paging loop (see SubAgentRunner history-budget pressure).
     */
    fun makeSubAgentExtraTools(): List<AgentToolDefinition> = buildList {
        add(GrepSourceTool.definition())
        add(GrepTool.definition())
        add(GlobTool.definition())
        add(ListDirTool.definition())
        add(WebFetchTool.definition())
        add(CodeGraphTool.definition())
        add(ExecuteCodeTool.definition())
    }

    private fun runSubAgentDefinition(): AgentToolDefinition {
        val taskItem = AgentToolParam(
            type = "object",
            description = "One teammate. Independent tasks in this array run concurrently; failures are isolated.",
            properties = mapOf(
                "prompt" to AgentToolParam(
                    "string",
                    "Self-contained brief with ## Task / ## Expected result / ## Constraints / ## Workflow / ## Collaboration. Sub-agents cannot see this conversation and must not call spawn_agent.",
                ),
                "kind" to AgentToolParam(
                    "string",
                    "explore (read-only recon), plan (read-only design), worker (writes), or general-purpose (fallback).",
                    enumValues = listOf("explore", "plan", "worker", "general-purpose"),
                ),
                "write_paths" to AgentToolParam(
                    "string",
                    "Comma-separated Linux path prefixes this worker may file_write/file_edit. Required when two or more workers run in the same wave. Use none if the task must not write.",
                ),
                "max_turns" to AgentToolParam(
                    "integer",
                    "Omit to auto-size: simple ≈ 10, complex 40–60, clamped to Settings → Multi-agent.",
                ),
                "token_budget" to AgentToolParam(
                    "integer",
                    "OPTIONAL shared token cap for this dispatch (weighted: output=1.0, prefill=0.1). Omit for NO token cap — lanes run to their max_turns. When set, the strictest cap among tasks wins; lanes stop with a partial report when the pool is drained.",
                ),
                "thinking_level" to AgentToolParam(
                    "string",
                    "Per-dispatch reasoning depth. Defaults to ULTRA; the provider clamps it to the selected model ceiling.",
                    enumValues = listOf("OFF", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX", "ULTRA"),
                ),
                "role" to AgentToolParam("string", "Catalog role (秘书助理, 产品经理, 架构师, 工程师, 前端设计师, 测试工程师, 侦察兵, 拆解工, 分析员) injects focus / do-not / when-silent / who-to-ask and a tool whitelist. Other strings are labels only."),
                "skills" to AgentToolParam("string", "Comma-separated skill ids the worker should read first."),
                "model" to AgentToolParam("string", "Optional model-entry id from the configured sub-agent pool. Omit to round-robin."),
                "tool_title" to AgentToolParam("string", "Short live-status title, e.g. 'Review RootfsManager'."),
            ),
            required = listOf("prompt"),
        )
        return AgentToolDefinition(
            name = "spawn_agent",
            description = """Dispatch teammate sub-agents. You are the session coordinator: decompose, dispatch, accept, summarize — do not do all the work yourself.

Prefer ONE spawn_agent call with a tasks[] array. You choose how many tasks the work needs; they run concurrently up to the configured cap, and one failure does not cancel siblings. Nested spawn_agent/run_subagent is blocked.

kind: explore (read-only recon, shell inspection allowed), plan (read-only design), worker (can write), general-purpose (fallback when the slice does not fit the others). Parallel workers MUST set non-overlapping write_paths, or write_paths=none if they must not write.

Each task prompt MUST be self-contained with ## Task / ## Expected result / ## Constraints / ## Workflow / ## Collaboration. Omit max_turns to auto-size (simple ≈ 10, complex 40–60). token_budget is optional: omit it for no token cap. Dependent phases: wait, verify Expected result, then dispatch the next wave. A single-task call may still pass prompt at the top level.

background=true detaches the wave: this call returns a dispatch_id at once instead of blocking, the lanes keep running, and you collect them later with check_agent. Use it for long waves you do not need before your next step — then actually collect them, an uncollected wave is wasted work. Every lane is filed on the agent_plan board automatically, so progress survives an interruption.""",
            parameters = mapOf(
                "tasks" to AgentToolParam(
                    type = "array",
                    description = "Teammates to run in parallel. Size is your call based on complexity. Independent slices only.",
                    items = taskItem,
                ),
                "tool_title" to AgentToolParam("string", "Short live-status title when not using tasks[]."),
                "prompt" to AgentToolParam("string", "Legacy single-task brief. Ignored when tasks[] is non-empty."),
                "role" to AgentToolParam("string", "Catalog role (秘书助理, 产品经理, 架构师, 工程师, 前端设计师, 测试工程师, 侦察兵, 拆解工, 分析员) injects focus / do-not / when-silent / who-to-ask and a tool whitelist. Other strings are labels only."),
                "skills" to AgentToolParam("string", "Comma-separated skill ids the worker should read first."),
                "model" to AgentToolParam("string", "Optional model-entry id from the configured sub-agent pool. Omit to round-robin."),
                "kind" to AgentToolParam(
                    "string",
                    "explore | plan | worker | general-purpose. Default worker.",
                    enumValues = listOf("explore", "plan", "worker", "general-purpose"),
                ),
                "write_paths" to AgentToolParam("string", "Comma-separated Linux path prefixes this worker may modify, or none. Required for parallel workers."),
                "max_turns" to AgentToolParam("integer", "Omit to auto-size: simple ≈ 10, complex 40–60."),
                "thinking_level" to AgentToolParam("string", "Default ULTRA; clamped to model ceiling.", enumValues = listOf("OFF", "LOW", "MEDIUM", "HIGH", "XHIGH", "MAX", "ULTRA")),
                "background" to AgentToolParam(
                    "boolean",
                    "true = return a dispatch_id immediately and let the lanes run detached; collect " +
                        "them later with check_agent. Default false: this call blocks until every lane " +
                        "finishes and returns their reports inline.",
                ),
            ),
            required = emptyList(),
            propertyOrdering = listOf(
                "tasks", "tool_title", "prompt", "kind", "write_paths", "role", "skills", "model", "max_turns", "thinking_level", "background",
            ),
        )
    }

    /**
     * [T-sub-agents-v1] The one sub agent tool: delegate, and inspect or stop
     * what you delegated.
     *
     * Replaces `delegate_task` + `agent_status`. The separate status tool is
     * folded in as an `action`, so the model sees ONE tool — the shape iOS
     * settled on. Description text is byte-identical to iOS
     * `AIChatViewModel+ToolDefinitions.swift`; the two clients present one
     * contract to the same models, so these strings are copied, never
     * paraphrased.
     *
     * [rosterNames] are the enabled sub agents' names, rebuilt every turn
     * (the schema is not cached), so a rename takes effect on the next request
     * and the model cannot invent a name that would fail to resolve.
     *
     * `task` is deliberately NOT in [required]: it is required for
     * action=delegate and meaningless for status/cancel, which JSON Schema
     * cannot express here. The dispatcher rejects a delegate call with no task.
     */
    private fun subAgentTaskDefinition(rosterNames: List<String>): AgentToolDefinition =
        AgentToolDefinition(
            name = SubAgentDefinition.TOOL_NAME,
            description = "Delegate a self-contained task to a sub agent — its own isolated context and tool loop, in a hidden child session running concurrently with you — and inspect or stop the ones you started. `action` defaults to `delegate`.\n\nDELEGATE work needing many rounds of exploration (reading lots of files or pages, trial-and-error), producing bulk output you only need a conclusion from, or splitting into independent sub-problems you can run in parallel (several calls in one turn). DO NOT delegate what you can finish in one or two tool calls, what needs the user's confirmation mid-way, or work depending on nuances of this conversation you cannot restate. A sub agent cannot see this conversation and has no memory: write `task` as a complete brief for a capable colleague who just walked in — goal, constraints, where things are, what exactly to return. It costs a full model run, so nothing trivial. Only 3 run at once, but delegate everything you need anyway: extras return status=queued and start as slots free, so never re-delegate a queued task or wait for a slot.\n\nwait=false (default) returns at once with status=running and a job_id; the result arrives later as a NEW MESSAGE prefixed [Background task finished …] (also on cancel/timeout/failure). End your turn when you have nothing else to do — never poll in a loop, never promise to report back. You do NOT need action=status to receive results.",
            parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user on the block in the tool bar and in the transcript (e.g. 'Survey repo test layout', 'Check on the research agent'). Use the same language as the user."),
            "action" to AgentToolParam("string", "\"delegate\" (default): start a sub agent on `task`. \"status\": report this conversation's sub agents — state (queued/running/done/cancelled/failed/interrupted), current tool, elapsed, model, finished results; `job_id` for one, omit for all. \"steer\": course-correct a RUNNING one without stopping it (see `message`). \"cancel\": stop the one named by `job_id`; its partial result is still posted back. \"resume\": restart runs the app lost when it was killed (they report `interrupted`); `job_id`/`child_session_id` for one, omit both for all.", enumValues = listOf("delegate", "status", "steer", "cancel", "resume")),
            "task" to AgentToolParam("string", "action=delegate only, required. The complete, self-contained brief: goal, success criteria, relevant paths/URLs, constraints, and exactly what to return. The sub agent sees nothing else."),
            "agent" to AgentToolParam("string", "action=delegate only. Which sub agent runs this task. Pick the one whose description matches the work; omit it to use the general one.", enumValues = rosterNames),
            "model_choice" to AgentToolParam("string", "action=delegate only, and only when the chosen sub agent is set to Auto — one the user pinned to a group ignores it. DEFAULT TO \"same_as_me\". The user picked the model this conversation runs on, and that choice covers the work you delegate from it: a sub agent on a different model can cost far more, or be far weaker, than what they chose, and they never see it happen. Only depart from it when the task itself gives you a specific reason, judged by what the task demands and not by how long it will take. \"same_as_me\" (default): this conversation's model — anything continuing the work at hand, and every case where you are unsure. \"default_model\": the user's strongest group — only when this task clearly needs more capability than the current model, e.g. multi-step reasoning, design judgment, ambiguous requirements where a wrong answer is expensive. \"sub_model\": the user's light group — only when the task is clearly mechanical and well-bounded, verifiable at a glance (collecting files against a list, format conversion, fixed commands, lookups).", enumValues = listOf("same_as_me", "default_model", "sub_model")),
            "context" to AgentToolParam("string", "action=delegate only. Optional raw material to hand over verbatim (file excerpts, error output, a list of paths). Appended to the task."),
            "max_minutes" to AgentToolParam("integer", "action=delegate only. Wall-clock budget in minutes (default 10, maximum 60). The sub agent is stopped when it runs out and whatever it produced so far is returned with status=timeout."),
            "wait" to AgentToolParam("boolean", "action=delegate only. false (default): return at once with status=running; the result is posted here as a new message when done. true: block until it finishes and return the result here — only when the next step cannot proceed without it. If the user sends a message while you wait, the run moves to the background and the call returns status=running."),
            "progress_report" to AgentToolParam("string", "action=delegate only. Mid-run [Background task progress …] messages (status, current tool, elapsed, latest message). \"none\" (default): final result only. \"frequent\": every 15s when something changed. \"moderate\": once a minute. Each costs you a turn — leave at none unless the user asked to follow along or you must react mid-way. Answer one with at most a short sentence, or just carry on; never re-delegate or poll because of one. Ignored when wait=true.", enumValues = listOf("none", "frequent", "moderate")),
            "job_id" to AgentToolParam("string", "action=status/steer/cancel. The job_id this tool returned when it started the sub agent (a prefix is accepted). Required for steer and cancel; omit on status to list every sub agent of this conversation."),
            "message" to AgentToolParam("string", "action=steer only, required. The correction, phrased as an instruction to the running sub agent (e.g. 'focus on pricing, skip the migration notes'). Use when new information changes what it should do — it keeps the work already done, unlike cancelling and re-delegating. Read at its next turn, so a running tool call is not interrupted; if the run finishes first the result reports the steer as missed."),
            "child_session_id" to AgentToolParam("string", "action=resume only, optional. The child_session_id of one interrupted sub agent to restart. Omit to resume every interrupted sub agent in this conversation."),
            ),
            required = listOf("tool_title"),
            propertyOrdering = listOf(
                "tool_title", "action", "task", "agent", "model_choice", "context",
                "max_minutes", "wait", "progress_report", "job_id", "message",
                "child_session_id",
            ),
        )

    // Aligned with iOS AIChatViewModel.swift:4982-4993
    private fun shellExecuteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "shell_execute",
        description = "Execute a command in an isolated Linux process (Ubuntu 24.04 arm64 via PRoot). " +
            "The command runs in GNU bash (/bin/bash), not BusyBox ash. Heredocs and bash syntax work. stdout and stderr are merged. " +
            "Each invocation spawns a fresh process — there is no shared terminal session. " +
            // [T-android-parity-fixes] Same reason as the `timeout` parameter below:
            // the schema must state THIS port's real default (the user's Caps →
            // Shell timeout, 600 s out of the box), not upstream's fixed 900.
            "Default timeout is 10 minutes (the Caps → Shell timeout setting).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Install Python data analysis packages', 'List files in home directory'). Use the same language as the user."),
            "command" to AgentToolParam("string", "The shell command to execute. Supports multi-line commands directly — no special escaping needed. Keep under 1000 chars; for longer scripts, write to a file with file_write first, then run it."),
            // [T-android-parity-fixes] 上游 1.14 的整改意图：上限必须写出来，
            // 旧文案只说"可以用更大的值"，而超过上限的值会被静默截断。
            // 上游写死 3600；本移植把上限做成用户可调（设置页 Caps → Shell
            // timeout），2026-10-12 起 MAX_SHELL_TIMEOUT_SEC 也抬到 3600 小时整
            // ——与上游、iOS 计划任务允许的范围（PrefilledToolCall 1..3600）一致。
            // 因此这里的默认值/上限必须写成**本移植的真实值**，不能再照抄上游的
            // 900/3600：默认是这个设置本身（出厂 600），上限是同一个设置（≤3600）。
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default: 600, maximum: 3600 — larger values are capped at the Caps → Shell timeout setting). Use a larger value for long-running commands like package installs."),
            // [T-resource-class-threaded] 我们这一侧的重/轻资源分级（上游没有该
            // 参数）：重任务共享进程级内存预算，包装进 shell 命令的调用链。
            "resource_class" to AgentToolParam("string", "Use heavy for memory-intensive work, including scripts that run decompilers, compilers, JVMs or large data processing. Heavy tasks share one process-wide memory budget. Omit or use auto for other commands."),
            "delay" to AgentToolParam("integer", "Delay in seconds before execution begins. The tool blocks the agent flow during this wait WITHOUT occupying the shell, so other concurrent tasks can use it. Use this instead of sleep commands to avoid resource contention."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout", "delay", "resource_class"),
    )

    // Aligned with iOS AIChatViewModel.swift browser_use definition
    private fun browserUseDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "browser_use",
        // [T-android-browser-tab-ownership] "up to 3" stopped being true once
        // the ceiling became dynamic (3 alone, +2 per active agent, capped at
        // 6), and an agent sees only its own tabs anyway — so the honest thing
        // to tell it is which tabs it may use, not a number that is now wrong.
        description = "Control a web browser with a few tabs (list_tabs shows the ones you may use). " +
            "Do NOT use this tool for minis:// action URLs (open_terminal, views, settings) — those are app deep links, use Markdown links in chat instead. " +
            "The browser supports both web URLs and minis:// resource URLs. Use minis:// URLs to preview session files (e.g. navigate to minis://workspace/index.html). " +
            "Sub-resources (JS, CSS, images, fonts) referenced via minis:// absolute paths or relative paths within HTML pages resolve correctly. " +
            "Use navigate to open URLs, screenshot to see the page (returns an image), " +
            "click/type to interact with elements, get_text/get_readable to extract content, " +
            "scroll to navigate long pages, scroll_and_collect to scroll through infinite-scroll/virtual-rendered pages (like Twitter/X timelines) and accumulate unique content items across scroll positions in a single call, " +
            "find_elements to discover interactive elements, " +
            "get_page_info for page metadata, get_backbone to get a structural overview of the page DOM as a simplified tree, " +
            "fetch to download files/resources using the page's session (returns metadata and a minis:// URL), " +
            "new_tab to open an additional tab, close_tab to close a tab, and list_tabs to see all open tabs. " +
            "Use set_viewport with viewport_width + viewport_height to override the viewport for the current session (e.g. before screenshotting a 1920×1080 HTML composition that would otherwise be cropped to the phone viewport); pass reset=true to drop the session override and fall back to the global browser setting. " +
            "Use get_cookies to retrieve cookies for the current page URL / current site root domain only (including HttpOnly cookies). get_cookies supports optional 'keywords' (filter by cookie name) and 'fuzzy' (true=contains match, false=exact match, default true). It returns only a summary and an offload env file path — raw cookie values are NOT included in the tool response. To reuse cookies in shell commands: `. /var/minis/offloads/env_cookies_xxx.sh && command`. You may define alias variables when needed. " +
            "Use set_cookies to write cookies into the current page's cookie store via the native cookie store (so even HttpOnly cookies, which JS cannot set, land). Pass a 'cookies' array of objects, each with name + value (required) and optional domain (defaults to the current page host), path (defaults to '/'), secure, http_only, and expires (Unix timestamp in seconds; omit for a session cookie). " +
            "Use wait_for_dom_stable to wait until the page DOM stops changing (useful after navigation or interactions that trigger async data loading — polls every 0.5s, resolves when mutation rate gradient is stable for 3+ intervals, default timeout 10s). " +
            "Use tab_id to target a specific tab (defaults to the most recently used tab).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Open Wikipedia homepage', 'Take screenshot of current page'). Use the same language as the user."),
            "action" to AgentToolParam("string", "The browser action to perform",
                enumValues = BrowserAction.allValues),
            "url" to AgentToolParam("string", "URL to navigate to (for navigate action) or resource to download (for fetch action)"),
            "selector" to AgentToolParam("string", "CSS selector for targeting elements (click, type, get_text, scroll, hover, find_elements). For scroll: specify a scrollable container to scroll (e.g. 'div.timeline'); if omitted, auto-detects the best scrollable element."),
            "text" to AgentToolParam("string", "Text to type (for type action)"),
            "coordinate_x" to AgentToolParam("integer", "X coordinate for click (alternative to selector)"),
            "coordinate_y" to AgentToolParam("integer", "Y coordinate for click (alternative to selector)"),
            "direction" to AgentToolParam("string", "Scroll direction", enumValues = listOf("up", "down")),
            "amount" to AgentToolParam("integer", "Scroll amount in pixels (default: 500)"),
            "script" to AgentToolParam("string", "JavaScript code to execute (for execute_js action). The script runs inside an async function wrapper — `await` and top-level `return` are both supported (e.g. `var r = await fetch(url); return await r.json()`)."),
            "user_agent" to AgentToolParam("string", "User agent profile to switch to", enumValues = listOf("desktop_chrome", "mobile_chrome")),
            "max_depth" to AgentToolParam("integer", "Maximum tree depth for get_backbone (default: 5)"),
            "scroll_count" to AgentToolParam("integer", "Number of scroll steps for scroll_and_collect (default: 10, max: 20). Each step scrolls by 'amount' pixels and waits for new content."),
            "item_selector" to AgentToolParam("string", "CSS selector for individual content items in scroll_and_collect (e.g. 'article', '[data-testid=\"tweet\"]'). If omitted, auto-detects repeated elements."),
            "tab_id" to AgentToolParam("integer", "Target tab ID (optional, defaults to your most recently used tab). Use list_tabs to see the tabs you may use; ids you did not receive from list_tabs/new_tab are rejected."),
            "keywords" to AgentToolParam("string", "Filter cookies by name (for get_cookies). A space-separated string or array of strings. With fuzzy=true (default), ALL keywords must appear in the cookie name (case-insensitive). With fuzzy=false, cookie name must exactly equal any one of the provided keywords (case-insensitive). Omit to return all cookies for the current site."),
            "fuzzy" to AgentToolParam("boolean", "Whether keyword matching is fuzzy (contains-all) or exact-any (for get_cookies, default: true)."),
            "cookies" to AgentToolParam("string", "For set_cookies: a JSON array of cookie objects to write. Pass it as a JSON array (a JSON-encoded string of the array is also accepted). Each object: {\"name\": str (required), \"value\": str (required), \"domain\": str (optional, defaults to current page host), \"path\": str (optional, defaults to \"/\"), \"secure\": bool (optional), \"http_only\": bool (optional — sets an HttpOnly cookie that JS cannot read/set), \"expires\": int (optional, Unix timestamp in seconds; omit for a session cookie)}. Field-name variants from common cookie exports are accepted: httpOnly (=http_only), expirationDate (=expires), sameSite, and case/camel variants — so you can paste cookies verbatim from browser extensions (EditThisCookie / Cookie-Editor) or Playwright/Puppeteer storage."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds for wait_for_dom_stable (default: 10). The action polls every 0.5s and resolves when DOM mutation rate stabilizes."),
            "viewport_width" to AgentToolParam("integer", "Viewport width in CSS pixels for set_viewport (e.g. 1920). Required together with viewport_height unless reset=true."),
            "viewport_height" to AgentToolParam("integer", "Viewport height in CSS pixels for set_viewport (e.g. 1080). Required together with viewport_width unless reset=true."),
            "reset" to AgentToolParam("boolean", "For set_viewport: when true, clear the session-level viewport override and fall back to the global browser setting."),
            // [T-android-browser-full-page-schema] The capability was already
            // implemented end to end (BrowserActionInput parses `full_page`,
            // BrowserUseManager.screenshot stretches the WebView and caps at
            // MAX_FULL_PAGE_HEIGHT_PX) — it was simply never declared, so the
            // model had no way to ask for it. Wording adapted from iOS: the
            // mechanism differs (Android stretches the WebView's viewport
            // rather than resizing a WKWebView), the cap and the truncation
            // reporting are identical.
            "full_page" to AgentToolParam("boolean", "For screenshot: capture the entire scrollable page by temporarily stretching the browser viewport to document.documentElement.scrollHeight. Default false captures the visible viewport only. Capped at 32768px tall; when capped, the result text includes 'Truncated: true' and the original height, so you can scroll and capture the remainder separately."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "tab_id", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "scroll_count", "item_selector", "script", "user_agent", "max_depth", "keywords", "fuzzy", "cookies", "timeout", "viewport_width", "viewport_height", "reset", "full_page"),
    )

    // Aligned with iOS AIChatViewModel.swift:5059-5067
    private fun memoryWriteDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_write",
        description = "Write a memory entry to today's daily log (YYYY-MM-DD.md). Memories persist across all sessions. " +
            "Each entry is prepended with a timestamp. " +
            "Save: user preferences, recurring patterns, key facts, project conventions, reusable knowledge. " +
            "Avoid saving passwords, API keys, tokens, or secrets unless the user explicitly confirms after being warned. " +
            "Keep entries concise and general-purpose. GLOBAL.md is read-only (user-maintained via Settings).",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Save user preference for Python', 'Note today's project context'). Use the same language as the user."),
            "content" to AgentToolParam("string", "The memory content to write. Use concise Markdown with a short heading (## Topic) and context about what was done/learned."),
            "revision" to AgentToolParam("string", "Optional. Pass the current log revision (reported at the end of memory_get) to make the write conflict-safe; the write fails with MEMORY_CONFLICT instead of overwriting a concurrent update."),
        ),
        required = listOf("tool_title", "content"),
        propertyOrdering = listOf("tool_title", "content", "revision"),
    )

    // Aligned with iOS AIChatViewModel.swift:5069-5078
    private fun memoryGetDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "memory_get",
        description = "Retrieve memories from persistent storage. Supports keyword-based fuzzy search across memory files. " +
            "Returns matching lines with surrounding context. Use this to recall previous knowledge, user preferences, or past notes.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this tool call does, shown to the user (e.g. 'Recall user preferences', 'Search past notes'). Use the same language as the user."),
            "scope" to AgentToolParam("string", "Memory scope to search: 'daily' for daily logs only, 'all' for daily logs + GLOBAL.md.", enumValues = listOf("daily", "all")),
            "keywords" to AgentToolParam("string", "Space-separated keywords for fuzzy matching (e.g. 'python preference' or 'API key setup'). All keywords must appear in a line or its surrounding context for a match. Leave empty to return full memory files."),
        ),
        required = listOf("tool_title"),
        propertyOrdering = listOf("tool_title", "scope", "keywords"),
    )

    private fun shellExecAliasDefinition(): AgentToolDefinition =
        shellExecuteDefinition().copy(
            name = "shell_exec",
            description = "Alias of shell_execute. Isolated Ubuntu 24.04 PRoot. Prefer this name if you come from XINCODE.",
        )

    private fun envExecDefinition(): AgentToolDefinition =
        shellExecuteDefinition().copy(
            name = "env_exec",
            description = "Run a command in the Ubuntu guest (same as shell_execute). The guest already is the Linux environment.",
        )

    private fun suExecDefinition(): AgentToolDefinition = AgentToolDefinition(
        name = "su_exec",
        description = "Privileged host command via android-su / Magisk KernelSU. Equivalent to android-su -c <command>. Not PRoot fake-root.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "command" to AgentToolParam("string", "Host command to run as root."),
            "timeout" to AgentToolParam("integer", "Timeout in seconds (default 120)."),
        ),
        required = listOf("tool_title", "command"),
        propertyOrdering = listOf("tool_title", "command", "timeout"),
    )
}
