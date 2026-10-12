package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.launch
import org.json.JSONObject

// ─── [T-u12-merge] 子代理卡片上的 Start / Resume ─────────────────────────────
//
// 上游 1.14 的 HelperToolBlock 会给两种块各配一个按钮：委派停在内存队列里且重启后
// 已经查不到（HelperPhase.NeverStarted）的给 Start，被中断的 child（status=interrupted）
// 给 Resume。上游把这两个入口接在它自己的委派执行器（executeDelegateTask）与
// AgentJobRegistry 的委派队列上；本移植的委派走自己的 executeRunSubAgent 批次执行器
// （ChatViewModelSubAgentExt.kt），合并时上游那两个实现没跟过来，而 ChatScreen /
// HelperUi（上游侧）仍然渲染这两个按钮 → 主源码编译不过（ChatScreen.kt:4730/4745）。
//
// 这里保持上游的**契约**不变——三态 CardResume、控制调用不重启、双击与「模型自己也
// 在 resume」的竞态护栏、名额满则停进共享队列等槽位——执行改接我们现有的执行器与队列。
//
// 拆文件带来的唯一形态差异：上游这些是 class 成员（`_messages` 等私有状态随手可及），
// 本移植里它们是 ChatViewModel 的扩展函数，`cardDelegationsInFlight` 因此提到文件级。

/**
 * 卡片触发的委派正在飞的 tool_use id。
 *
 * 上游不需要它：它一按下就把块写成 RUNNING，卡片自己就不再渲染按钮了。
 * 我们的执行器不写带 `job_id` 的载荷，而 OrphanedRunningToolGate 会把「没有活 job
 * 撑着」的 RUNNING 覆盖降级成 TIMEOUT——写了也是假状态。所以双击护栏放在这里：
 * 同一张卡同一时刻只允许一次卡片触发的委派。
 */
private val cardDelegationsInFlight = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

/** Queue key of a parked resume; see [enqueueResume]. */
private fun resumeQueueKey(childId: String) = "resume:$childId"

/**
 * [T-android-neverstarted-not-running] Run a delegation that was still on
 * the in-memory queue when the process died.
 *
 * 队列不跨进程：重载后这种块只能报 "Never started"（HelperUi 靠
 * com.openminis.app.agent.jobs.HelperRunner.isQueuedLive 判定），此前是条死路——
 * 卡片给一个「停」按钮，却没有任何办法让它真的跑起来。块上什么都还在：`toolArgs`
 * 在加载时从落库的工具输入恢复，任务文本、agent 与模型选择都完整，所以从这里重入
 * 和队列自己 drain 出来的那次没有区别。
 *
 * 参数缺了、或被停掉的是一次控制调用（status/steer/cancel/resume——句柄本身没有
 * 任务可跑，`cancel` 更是重放不得），一律不启动并返回 false，让卡片维持原样，
 * 而不是报一次没发生的启动。
 */
internal fun ChatViewModel.startNeverStartedDelegation(block: AssistantBlock): Boolean {
    val args = block.toolArgs
    if (args.isBlank()) return false
    if (com.openminis.app.agent.jobs.HelperRunner.isControlOnly(args, block.content.ifEmpty { null })) {
        return false
    }
    return launchCardDelegation(args, block.id)
}

/**
 * [T-android-subagent-card-resume] The Resume button on an interrupted
 * sub agent's card. Port of iOS HelperBlockView.resume(), which restarts the
 * same run the model's `resume` control call restarts.
 *
 * 与模型那次 resume 完全同一个决策，顺序也一样：已经排在队列里的先回报 QUEUED
 * （同一次点击不排两遍）；不是本会话里「应用丢下、载荷还写着 running 却没有活 job」
 * 的 child 直接 REFUSED；然后才看名额——有空位立刻续跑，名额满则把这次续跑停进队列、
 * 由子任务结束时那次 drain 自己开跑，积压也满才是 REFUSED。超过上限直接开跑正是
 * 「五个丢掉的 agent 一起起来」那类事故。
 *
 * [T-u12-merge] 与上游的差异（本移植没有上游那条执行路径，只能如此）：上游的
 * [resumeInterruptedChild] 用 child 行 + 父块重建整条 HelperRunner 运行
 * （executeDelegateTask），续跑在 child 自己的会话里接着跑、带上 `resumeNotice()`，
 * 工具结果都还在；我们的 executeRunSubAgent 不能指定既有子会话，所以任务从父块上
 * 持久化的参数重新跑起来——任务回来了、卡片重新有进度，但旧 child 的转录不会被续写。
 */
internal fun ChatViewModel.resumeInterruptedFromCard(block: AssistantBlock): ChatViewModel.CardResume {
    val childId = com.openminis.app.agent.jobs.HelperRunner.childSessionIdFrom(block.content)
        ?: return ChatViewModel.CardResume.REFUSED
    if (com.openminis.app.agent.jobs.HelperRunner.isQueuedLive(resumeQueueKey(childId))) {
        return ChatViewModel.CardResume.QUEUED
    }
    if (childId !in interruptedChildIds(activeSessionId)) {
        AppLogger.info(ChatViewModel.TAG, "[subagent_task] card resume: ${childId.take(8)} is not interrupted")
        return ChatViewModel.CardResume.REFUSED
    }
    val outcome = when {
        com.openminis.app.agent.jobs.AgentJobRegistry.canStartChildJob ->
            if (resumeInterruptedChild(childId)) ChatViewModel.CardResume.STARTED else ChatViewModel.CardResume.REFUSED
        enqueueResume(childId) -> ChatViewModel.CardResume.QUEUED
        else -> ChatViewModel.CardResume.REFUSED
    }
    AppLogger.info(ChatViewModel.TAG, "[subagent_task] card resume child=${childId.take(8)} -> $outcome")
    return outcome
}

/**
 * [T-sub-agents-resume] The child sessions of this conversation the app lost.
 *
 * 从转录数起而不是查 job 注册表，这正是要点：注册表活在内存里，进程一被杀就不剩
 * 任何 job，最该被报出来的那些 run 恰好是它再也看不见的。块里还写着 running 的载荷、
 * 带着 child session id，背后却没有活 job —— 那就是被丢下的。
 */
private fun ChatViewModel.interruptedChildIds(parentSessionId: String): List<String> {
    if (parentSessionId.isEmpty()) return emptyList()
    val registry = com.openminis.app.agent.jobs.AgentJobRegistry
    val out = mutableListOf<String>()
    for (m in _messages.value) {
        if (m.role != "assistant") continue
        for (b in m.toolBlocks) {
            if (b.kind != "tool_use") continue
            if (!com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(b.toolName)) continue
            val o = runCatching { JSONObject(b.content) }.getOrNull() ?: continue
            if (o.optString("status") != "running") continue
            val childId = o.optString("child_session_id", "")
            if (childId.isEmpty() || childId in out) continue
            if (registry.jobForSession(childId) != null) continue
            out.add(childId)
        }
    }
    return out
}

/**
 * [T-u12-merge] Restart ONE interrupted child.
 *
 * 上游按「child 行 + 父块是唯一来源」重建：child 已经不在进程里，没有注册表可查，
 * 预算也从零开始（原来的 deadline 早在应用死的时候就过了，照抄会当场把它杀掉）。
 * 这里同样只有这两样东西可用——先从父块上把这次委派的参数恢复出来
 * （[delegateBlockForChild]），再交给我们的执行器重开一次：与卡片上的 Start、
 * 以及与队列 drain 出来的是同一个入口。
 */
private fun ChatViewModel.resumeInterruptedChild(childId: String): Boolean {
    val block = delegateBlockForChild(childId)
    if (block == null) {
        AppLogger.warning(ChatViewModel.TAG, "[subagent_task] resume: no parent block for child ${childId.take(8)}")
        return false
    }
    val args = block.toolArgs
    if (args.isBlank()) return false
    if (com.openminis.app.agent.jobs.HelperRunner.isControlOnly(args, block.content.ifEmpty { null })) {
        return false
    }
    return launchCardDelegation(args, block.id)
}

/**
 * [T-u12-merge] The delegate block that owns [childId], or null.
 *
 * 块按 tool_use id 寻址，而 child 的归属写在载荷的 `child_session_id` 里；
 * 消息与块的顺序在下一次重载后会变，所以每次现查，不缓存。
 */
private fun ChatViewModel.delegateBlockForChild(childId: String): AssistantBlock? {
    for (m in _messages.value) {
        if (m.role != "assistant") continue
        for (b in m.toolBlocks) {
            if (b.kind != "tool_use") continue
            if (!com.openminis.app.agent.jobs.HelperRunner.isSubAgentToolName(b.toolName)) continue
            val owner = com.openminis.app.agent.jobs.HelperRunner.childSessionIdFrom(b.content) ?: continue
            if (owner == childId) return b
        }
    }
    return null
}

/**
 * [T-u12-merge] 内存委派队列唯一的 drain 入口：把停着的委派重新跑起来。
 *
 * 上游同名函数（`startQueuedDelegation`）走 executeDelegateTask，这里走我们的
 * executeRunSubAgent；两条入队路径（全新委派 / 续跑）在队列里只靠
 * com.openminis.app.agent.jobs.HelperRunner.QUEUED_RESUME_CHILD_KEY 区分，
 * 与上游同一个约定。续跑那次仍从块上恢复参数，所以这里转回
 * [resumeInterruptedChild]，与模型自己的 resume 走同一条路。
 */
internal fun ChatViewModel.startQueuedDelegation(argsJson: String, toolUseId: String): Boolean {
    if (activeSessionId.isEmpty()) return false
    val resumeChildId = runCatching {
        JSONObject(argsJson)
            .optString(com.openminis.app.agent.jobs.HelperRunner.QUEUED_RESUME_CHILD_KEY, "")
    }.getOrDefault("").ifEmpty { null }
    if (resumeChildId != null) {
        AppLogger.info(ChatViewModel.TAG, "[subagent_task] queued resume draining child=${resumeChildId.take(8)}")
        return resumeInterruptedChild(resumeChildId)
    }
    return launchCardDelegation(argsJson, toolUseId)
}

/**
 * [T-android-subagent-prompt-parity] Park a resume in the shared delegation
 * queue so it starts by itself when a slot frees.
 *
 * 队列项只存参数不存半个 job，重入时才重新解析定义/模型组/上限——所以现在必须把
 * starter 登记上，否则子任务跑完时那次 drain 找不到能重入队列项的活 view model，
 * 排进去的续跑会一直躺着；interrupted 计数器同理，它喂的是队列自愈与兄弟状态
 * 那两句文案。
 *
 * 返回 false 只有一种情况：积压也满了，那是唯一需要模型自己处理的失败。
 */
private fun ChatViewModel.enqueueResume(childId: String): Boolean {
    val registry = com.openminis.app.agent.jobs.AgentJobRegistry
    val parentSid = activeSessionId
    if (parentSid.isEmpty()) return false
    val args = delegateBlockForChild(childId)?.toolArgs ?: return false
    if (args.isBlank()) return false
    registry.registerQueuedStarter(parentSid) { item ->
        startQueuedDelegation(item.argsJson, item.toolUseId)
    }
    registry.registerInterruptedCounter(parentSid) { interruptedChildIds(parentSid).size }
    val queuedArgs = runCatching {
        JSONObject(args)
            .put(com.openminis.app.agent.jobs.HelperRunner.QUEUED_RESUME_CHILD_KEY, childId)
            .put(com.openminis.app.agent.jobs.HelperRunner.QUEUED_REENTRY_KEY, true)
            .toString()
    }.getOrNull() ?: return false
    return registry.enqueueDelegation(
        com.openminis.app.agent.jobs.AgentJobRegistry.QueuedDelegation(
            parentSessionId = parentSid,
            argsJson = queuedArgs,
            // 一个 child 只排一次；用真 tool_use id 会和原委派块抢同一个键。
            toolUseId = resumeQueueKey(childId),
        ),
    )
}

/**
 * [T-u12-merge] 把一次卡片触发的委派交给我们的执行器跑起来。
 *
 * 不在模型回合里，所以整条链都挂在 viewModelScope 上：`executeRunSubAgent` 是挂起
 * 函数，跑完才返回（不传 background，本次调用就是「等这批跑完」的那一个），结果照旧
 * 由子代理的活动条 / 批次台账呈现。assistantId 留空是有意的——transcript 卡片的发布
 * 入口对空 id 直接返回，卡片本身由上面的 in-flight 集合与 HelperUi 自己的状态推导
 * 负责，这里不编造块状态。
 */
private fun ChatViewModel.launchCardDelegation(argsJson: String, toolUseId: String): Boolean {
    if (!multiAgentSettings.enabled.value) return false
    val spawnArgs = subAgentTaskToSpawnArgs(argsJson) ?: return false
    if (cardDelegationsInFlight.putIfAbsent(toolUseId, true) != null) return false
    // 显式取一次接收者：`launch` 的隐式接收者是 CoroutineScope，扩展函数里也没有
    // `this@ChatViewModel` 这种标签（那不是 lambda 标签），会把调用解析到错误的接收者。
    val vm = this
    viewModelScope.launch {
        try {
            vm.executeRunSubAgent(argsJson = spawnArgs, toolId = toolUseId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLogger.warning(ChatViewModel.TAG, "[subagent_task] card delegation failed: ${e.message}")
        } finally {
            cardDelegationsInFlight.remove(toolUseId)
        }
    }
    return true
}

/**
 * [T-u12-merge] `subagent_task` 的参数形状 → 我们执行器的 spawn 批次形状。
 *
 * 不手写映射：把这次委派原样翻译成 dispatch_agents 的形状，再交给
 * com.openminis.app.tools.DispatchAgentsTool.toSpawnArgs —— 它会按 agent 名解析出
 * 子代理类型（种类、系统提示、技能白名单），这正是 executeRunSubAgent 需要的。
 * `max_minutes` 在这里没有对应物（我们按轮次上限约束子代理），`model_choice` 也不
 * 映射：类型自己钉住的模型组优先，否则沿用本会话的模型，与 `same_as_me` 这个默认
 * 方向一致。
 */
private fun ChatViewModel.subAgentTaskToSpawnArgs(argsJson: String): String? {
    val src = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
    val task = src.optString("task", "").trim()
    if (task.isEmpty()) return null
    val contextBlock = src.optString("context", "").trim()
    val agent = src.optString("agent", "").trim()
    val title = src.optString("tool_title", "").trim().ifEmpty {
        com.openminis.app.agent.jobs.HelperRunner.TOOL_NAME
    }
    val assignment = JSONObject()
        .put("agent", agent)
        .put("task", if (contextBlock.isEmpty()) task else "$task\n\n$contextBlock")
    val dispatchArgs = JSONObject()
        .put("tool_title", title)
        .put("assignments", org.json.JSONArray().put(assignment))
    return runCatching {
        com.openminis.app.tools.DispatchAgentsTool.toSpawnArgs(dispatchArgs.toString(), context)
    }.getOrNull()
}
