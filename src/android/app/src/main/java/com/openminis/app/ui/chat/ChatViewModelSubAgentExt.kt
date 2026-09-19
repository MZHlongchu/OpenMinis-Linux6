package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.PlanDiscussionOrchestrator
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.SubAgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.random.Random

/** Per-retry backoff (seconds). Index 0 = wait before attempt 2, etc. */
private val SUBAGENT_BACKOFF_S = intArrayOf(2, 5)

internal suspend fun ChatViewModel.runPlanDiscussion(provider: LLMProvider): String {
        val assistantId = java.util.UUID.randomUUID().toString()
        withContext(Dispatchers.Main) {
            _messages.value = _messages.value + ChatMessage(
                id = assistantId,
                role = "assistant",
                content = "",
                isStreaming = true,
                isAwaitingModelResponse = true,
            )
        }
        val userText = _messages.value.lastOrNull { it.role == "user" && !it.isQueued }?.content.orEmpty()
        val excerpt = _messages.value.takeLast(16).joinToString("\n") {
            "${it.role}: ${it.content.take(500)}"
        }
        val config = providerRepository.config.value
        val mainEntry = _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        val mainName = mainEntry?.model?.displayName ?: currentModel?.displayName ?: "main"
        val mainMax = (mainEntry?.model?.maxOutputTokens ?: currentModel?.maxOutputTokens ?: 4096).coerceIn(256, 8192)
        val mainMember = PlanDiscussionOrchestrator.Member(mainName, "facilitator", provider, mainMax)
        val stances = listOf("architect", "skeptic", "implementer", "operator")
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        val members = mutableListOf<PlanDiscussionOrchestrator.Member>()
        if (pool.isNotEmpty()) {
            pool.forEachIndexed { i, id ->
                val entry = config.modelEntries.find { it.id == id } ?: return@forEachIndexed
                val p = providerForModelEntry(entry) ?: return@forEachIndexed
                members += PlanDiscussionOrchestrator.Member(
                    displayName = entry.model.displayName,
                    stance = stances[i % stances.size],
                    provider = p,
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                )
            }
        }
        if (members.isEmpty()) {
            members += mainMember.copy(stance = "architect")
            members += mainMember.copy(displayName = "$mainName · critic", stance = "skeptic")
            members += mainMember.copy(displayName = "$mainName · implementer", stance = "implementer")
        }
        val tools = AgentTools.makeAgentTools(
            supportsImageInput = currentModel?.hasImageInput == true,
            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                providerRepository, context,
            ),
            memoryEnabled = false,
            subAgentEnabled = false,
        )
        val result = try {
            PlanDiscussionOrchestrator.run(
                userText = userText,
                conversationExcerpt = excerpt,
                main = mainMember,
                members = members,
                tools = tools,
                executeTool = { name, json ->
                    executeTool(name, json, "", mutableListOf(), assistantId, "")
                },
                onProgress = { msg ->
                    withContext(Dispatchers.Main) {
                        val overlay = msg.lineSequence()
                            .firstOrNull { it.startsWith("**状态：**") }
                            ?.removePrefix("**状态：**")
                            ?.trim()
                            ?: "计划讨论"
                        SessionActivityTracker.updateToolStatus(overlay, "plan_discussion", true, overlay)
                        _messages.value = _messages.value.map {
                            if (it.id == assistantId) {
                                it.copy(content = msg, isAwaitingModelResponse = true, isStreaming = true)
                            } else it
                        }
                    }
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = "计划讨论失败: ${e.message ?: e.javaClass.simpleName}"
            withContext(Dispatchers.Main) {
                _messages.value = _messages.value.map {
                    if (it.id == assistantId) {
                        it.copy(content = err, isStreaming = false, isAwaitingModelResponse = false)
                    } else it
                }
            }
            return ""
        }
        val partsJson = "[{\"type\":\"text\",\"value\":" + escapeJson(result.markdown) + "}]"
        val persisted = chatRepository.appendMessage(activeSessionId, "assistant", partsJson)
        withContext(Dispatchers.Main) {
            SessionActivityTracker.updateToolStatus("", null, false)
            _messages.value = _messages.value.map {
                if (it.id == assistantId) it.copy(
                    id = persisted.id,
                    content = result.markdown,
                    isStreaming = false,
                    isAwaitingModelResponse = false,
                ) else it
            }
        }
        return result.markdown
    }

internal suspend fun ChatViewModel.publishRunSubagentLog(
        toolId: String,
        assistantId: String,
        currentText: String,
        toolBlocks: MutableList<AssistantBlock>?,
        log: String,
    ) {
        if (toolId.isEmpty() || assistantId.isEmpty() || toolBlocks == null) return
        synchronized(toolBlocks) {
            val i = toolBlocks.indexOfFirst { it.id == toolId }
            if (i >= 0) {
                toolBlocks[i] = toolBlocks[i].copy(content = log)
            }
        }
        withContext(Dispatchers.Main) {
            updateAssistantMessage(assistantId, currentText, true, toolBlocks.toList())
        }
    }

internal suspend fun ChatViewModel.executeRunSubAgent(
        argsJson: String,
        toolId: String = "",
        toolBlocks: MutableList<AssistantBlock>? = null,
        assistantId: String = "",
        currentText: String = "",
        limiter: Semaphore? = null,
        parallelWriters: Int = 1,
        waveIndex: Int = 0,
        waveSize: Int = 1,
    ): ToolExecutionResult {
        if (!multiAgentSettings.enabled.value) {
            return ToolExecutionResult("Multi-agent dispatch is disabled in Settings → Multi-agent.", false)
        }
        if (subAgentDepth.get() > 0) {
            return ToolExecutionResult("Error: sub-agents cannot spawn further sub-agents.", false)
        }
        val spawns = parseSubAgentBatch(argsJson, SubAgentRunner.ABSOLUTE_MAX_TURNS)
        if (spawns.isEmpty()) {
            return ToolExecutionResult(
                if (argsJson.isBlank() || !argsJson.trim().startsWith("{"))
                    "Error: invalid spawn_agent arguments"
                else
                    "Error: spawn_agent requires a non-empty tasks array or prompt",
                false,
            )
        }
        val writersHere = spawns.count { SubAgentKind.canWrite(it.kind) }
        val writerTotal = maxOf(parallelWriters, writersHere)
        val sem = limiter ?: Semaphore(
            MultiAgentSettings.clampConcurrent(multiAgentSettings.maxConcurrent.value),
        )
        if (spawns.size == 1) {
            val total = waveSize.coerceAtLeast(1)
            val index = if (total > 1) waveIndex + 1 else 1
            return sem.withPermit {
                runOneSubAgent(
                    spawn = spawns[0],
                    toolId = toolId,
                    toolBlocks = toolBlocks,
                    assistantId = assistantId,
                    currentText = currentText,
                    parallelWriters = writerTotal,
                    index = index,
                    total = total,
                )
            }
        }
        val results = supervisorScope {
            spawns.mapIndexed { i, spawn ->
                async {
                    sem.withPermit {
                        runOneSubAgent(
                            spawn = spawn,
                            toolId = toolId,
                            toolBlocks = toolBlocks,
                            assistantId = assistantId,
                            currentText = currentText,
                            parallelWriters = writerTotal,
                            index = i + 1,
                            total = spawns.size,
                        )
                    }
                }
            }.awaitAll()
        }
        val ok = results.count { it.success }
        val body = buildString {
            append("Dispatched ${results.size} sub-agents: $ok ok, ${results.size - ok} failed.\n")
            results.forEachIndexed { i, r ->
                append("\n## 子代理 ${i + 1}/${results.size} (${spawns[i].kind}, ")
                append(if (r.success) "ok" else "fail")
                append(")\n")
                append(r.output.trim())
                append('\n')
            }
        }
        publishRunSubagentLog(toolId, assistantId, currentText, toolBlocks, body)
        return ToolExecutionResult(
            output = body,
            success = results.any { it.success },
            toolTitle = "子代理 ${spawns.size}",
        )
    }

private suspend fun ChatViewModel.runOneSubAgent(
        spawn: ChatSubAgentSpawn,
        toolId: String,
        toolBlocks: MutableList<AssistantBlock>?,
        assistantId: String,
        currentText: String,
        parallelWriters: Int,
        index: Int,
        total: Int,
    ): ToolExecutionResult {
        val prompt = spawn.prompt
        val role = spawn.role
        val skills = spawn.skills
        val requested = spawn.requestedModel
        val kind = spawn.kind
        val writePaths = spawn.writePaths
        val maxTurns = spawn.maxTurns
        val title = spawn.title.ifEmpty { "子代理 $index/$total" }
        if (SubAgentKind.requiresWritePaths(kind, parallelWriters) && writePaths.isEmpty()) {
            return ToolExecutionResult(
                "Error: parallel workers must declare non-overlapping write_paths so file_write/file_edit stay isolated.",
                false,
            )
        }
        val config = providerRepository.config.value
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        // Resolve the initial entry once. Retries may swap to another pool entry
        // (see the attempt loop below) so a rate-limited / truncated endpoint is
        // skipped instead of hammered. The base pick honours the coordinator's
        // explicit `model` request and the round-robin slot assignment.
        val pickedId = MultiAgentSettings.pickModelId(pool, requested, subAgentRoundRobin.getAndIncrement())
        val baseEntry = when {
            pickedId != null -> config.modelEntries.find {
                it.id == pickedId ||
                    it.model.id.equals(pickedId, ignoreCase = true) ||
                    it.model.displayName.equals(pickedId, ignoreCase = true)
            }
            else -> _activeEntryId.value?.let { id -> config.modelEntries.find { it.id == id } }
        } ?: return ToolExecutionResult(
            "No model available for sub-agent. Select models under Settings → Multi-agent, or keep the main session model selected.",
            false,
        )
        subAgentDepth.incrementAndGet()
        val parentSession = realSessionId.ifBlank { sessionId }.ifBlank { activeSessionId }
        val laneId = kotlin.coroutines.coroutineContext[SubAgentLane]?.id
            ?: SubAgentLane.idFor(parentSession, System.nanoTime())
        val trackerId = com.openminis.app.service.SubAgentActivityTracker.start(
            parentSessionId = parentSession,
            title = title,
            role = role,
            model = baseEntry.model.displayName,
            index = index,
            total = total,
            kind = kind,
            turnCap = maxTurns,
        )
        return try {
            // Bounded retry with pool-endpoint rotation. Sub-agent failures are
            // mostly transient upstream errors (429 / truncated stream); the main
            // session already retries those, sub-agents used to die on first blip.
            // Attempts budget is user-tunable (Settings → Multi-agent, 1 = no retry).
            val maxAttempts = multiAgentSettings.subagentMaxAttempts.value
            var attempt = 0
            var lastError: Exception? = null
            var lastFailedProviderId: String? = null
            while (attempt < maxAttempts) {
                attempt++
                val entry = if (attempt == 1) baseEntry else pickRetryEntry(
                    entries = config.modelEntries,
                    pool = pool,
                    excludeProviderId = lastFailedProviderId,
                    seed = subAgentRoundRobin.getAndIncrement(),
                ) ?: baseEntry
                val provider = providerForModelEntry(entry)
                if (provider == null) {
                    if (attempt >= maxAttempts) {
                        return ToolExecutionResult(
                            "Failed to create provider for ${entry.model.displayName} (attempt $attempt/$maxAttempts)",
                            false,
                        )
                    }
                    lastFailedProviderId = entry.providerInstanceId
                    continue
                }
                if (attempt > 1) {
                    Log.i(ChatViewModel.TAG, "Sub-agent retry lane=$laneId attempt=$attempt/$maxAttempts model=${entry.model.displayName} prev=${lastError?.message}")
                    runCatching {
                        com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                            trackerId, 0, maxTurns, "retry ${attempt - 1} → ${entry.model.displayName}",
                        )
                    }
                }
                val attemptResult: ToolExecutionResult? = try {
            withContext(SubAgentLane(laneId)) {
            val liveLog = StringBuilder()
            var lastUiMs = 0L
            val publishLive = total == 1
            suspend fun onStep(turn: Int, toolName: String) {
                com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                    trackerId, turn, maxTurns, toolName,
                )
                val clipped = buildString {
                    append("turn $turn/$maxTurns")
                    if (toolName.isNotBlank()) append(" · $toolName")
                }
                liveLog.append(clipped).append('\n')
                if (liveLog.length > 24_000) {
                    liveLog.delete(0, liveLog.length - 20_000)
                }
                val now = System.currentTimeMillis()
                val important = toolName.isNotBlank()
                if (!important && now - lastUiMs < 250L) return
                lastUiMs = now
                if (publishLive) {
                    publishRunSubagentLog(toolId, assistantId, currentText, toolBlocks, liveLog.toString())
                }
            }
            val result = com.openminis.app.tools.WritePathGuard.withPaths(writePaths) {
                SubAgentRunner.run(
                    provider = provider,
                    modelDisplayName = entry.model.displayName,
                    userPrompt = prompt,
                    role = role,
                    skillsHint = skills,
                    tools = SubAgentKind.filterTools(
                        kind,
                        AgentTools.makeAgentTools(
                            supportsImageInput = entry.model.hasImageInput,
                            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                                providerRepository, context,
                            ),
                            memoryEnabled = false,
                            subAgentEnabled = false,
                        ) + AgentTools.makeSubAgentExtraTools(),
                        role,
                    ),
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                    executeTool = { name, json ->
                        if (SubAgentKind.blocks(kind, name)) {
                            ToolExecutionResult("Error: $kind sub-agent cannot use $name.", false)
                        } else if (com.openminis.app.tools.CollabRoles.toolsFor(role)?.let { name !in it } == true) {
                            ToolExecutionResult("Error: role $role cannot use $name.", false)
                        } else if (name == com.openminis.app.tools.GrepSourceTool.NAME) {
                            com.openminis.app.tools.GrepSourceTool.execute(json, sessionId, context)
                        } else {
                            executeTool(name, json, "", mutableListOf(), "", "")
                        }
                    },
                    onStep = { turn, toolName -> onStep(turn, toolName) },
                    kind = kind,
                    writePaths = writePaths,
                    maxTurns = maxTurns,
                )
            }
            val uiLog = if (liveLog.isNotEmpty()) {
                liveLog.toString().trimEnd() + "\n---\n" + result.output
            } else {
                result.output
            }
            if (publishLive) {
                publishRunSubagentLog(toolId, assistantId, currentText, toolBlocks, uiLog)
            }
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, result.success)
            result.copy(toolTitle = title.ifEmpty { "Sub-agent · ${entry.model.displayName}" })
            }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                lastFailedProviderId = entry.providerInstanceId
                val retryable = (e as? LLMError)?.isRetryable ?: isUpstreamTruncation(e)
                Log.w(ChatViewModel.TAG, "Sub-agent lane=$laneId attempt=$attempt/$maxAttempts retryable=$retryable err=${e.message}")
                if (retryable && attempt < maxAttempts) {
                    val backoffS = SUBAGENT_BACKOFF_S[(attempt - 1).coerceAtMost(SUBAGENT_BACKOFF_S.size - 1)]
                    val retryAfter = (e as? LLMError.RateLimited)?.retryAfterSeconds ?: 0
                    val waitS = maxOf(backoffS, retryAfter)
                    runCatching {
                        com.openminis.app.service.SubAgentActivityTracker.updateProgress(
                            trackerId, 0, maxTurns, "retry in ${waitS}s (${e.javaClass.simpleName})",
                        )
                    }
                    delay(waitS * 1000L + Random.nextLong(0, 800))
                    null
                } else {
                    // Final failure: RETURN, do not throw — an exception escaping
                    // this lane would cancel its fan-out siblings via awaitAll.
                    com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
                    return ToolExecutionResult(
                        "Sub-agent failed after $attempt attempt(s): ${e.message ?: e.javaClass.simpleName}",
                        false,
                    )
                }
            }
            if (attemptResult != null) {
                return if (attempt > 1) attemptResult.copy(
                    output = "(recovered on attempt $attempt/$maxAttempts via ${entry.model.displayName})\n" + attemptResult.output,
                ) else attemptResult
            }
            }
            ToolExecutionResult(
                "Sub-agent failed after $maxAttempts attempt(s): ${lastError?.message ?: "unknown error"}",
                false,
            )
        } catch (e: Exception) {
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, false, e.message)
            throw e
        } finally {
            subAgentDepth.decrementAndGet()
            withContext(NonCancellable) {
                runCatching { ExecutionCoordinator.sessionDidTerminate(laneId) }
            }
        }
    }

private fun isUpstreamTruncation(e: Exception): Boolean {
        val m = (e.message ?: "").lowercase()
        return "empty response" in m || "connection dropped" in m || "upstream" in m ||
            "eof" in m || "truncat" in m
    }

/**
 * Re-pick a pool entry on a DIFFERENT provider instance than the one that just
 * failed, so a rate-limited / truncated relay is skipped on retry. Returns null
 * when the pool has no entry on another instance (caller retries the base entry).
 */
private fun pickRetryEntry(
        entries: List<ModelEntry>,
        pool: List<String>,
        excludeProviderId: String?,
        seed: Int,
    ): ModelEntry? {
        val pooled = pool.filter { it.isNotBlank() }.mapNotNull { id -> entries.find { it.id == id } }
        val rotated = pooled.filter { it.providerInstanceId != excludeProviderId }
        return if (rotated.isNotEmpty()) rotated[Math.floorMod(seed, rotated.size)] else null
    }

internal fun ChatViewModel.providerForModelEntry(entry: ModelEntry): LLMProvider? {
        val instance = providerRepository.instance(entry.providerInstanceId) ?: return null
        var apiKey = providerRepository.usableApiKey(instance) ?: return null
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.oauth) {
            try {
                val manager = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val freshToken = kotlinx.coroutines.runBlocking { manager?.validAccessToken() }
                if (freshToken != null && freshToken != apiKey) {
                    providerRepository.saveApiKey(instance.id, freshToken)
                    apiKey = freshToken
                }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "Sub-agent OAuth refresh failed: ${e.message}")
            }
        }
        return ProviderFactory.create(instance, apiKey, entry.model, context)
    }
