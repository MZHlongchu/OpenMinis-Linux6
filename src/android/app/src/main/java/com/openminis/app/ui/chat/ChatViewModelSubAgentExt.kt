package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.repository.MultiAgentSettings
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.ProviderFactory
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.tools.AgentTools
import com.openminis.app.tools.PlanDiscussionOrchestrator
import com.openminis.app.tools.SubAgentLane
import com.openminis.app.tools.SubAgentRunner
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

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
    ): ToolExecutionResult {
        if (!multiAgentSettings.enabled.value) {
            return ToolExecutionResult("Multi-agent dispatch is disabled in Settings → Multi-agent.", false)
        }
        if (subAgentDepth.get() > 0) {
            return ToolExecutionResult("Error: sub-agents cannot spawn further sub-agents.", false)
        }
        val spawn = parseSubAgentSpawn(argsJson, multiAgentSettings.subagentMaxTurns.value)
            ?: return ToolExecutionResult(
                if (argsJson.isBlank() || !argsJson.trim().startsWith("{"))
                    "Error: invalid run_subagent arguments"
                else
                    "Error: prompt is required for run_subagent",
                false,
            )
        val prompt = spawn.prompt
        val role = spawn.role
        val skills = spawn.skills
        val requested = spawn.requestedModel
        val title = spawn.title
        val kind = spawn.kind
        val writePaths = spawn.writePaths
        val maxTurns = spawn.maxTurns
        val config = providerRepository.config.value
        val pool = MultiAgentSettings.retainLive(
            multiAgentSettings.selectedModelEntryIds.value,
            config.modelEntries.map { it.id }.toSet(),
            multiAgentSettings.maxConcurrent.value,
        )
        val pickedId = MultiAgentSettings.pickModelId(pool, requested, subAgentRoundRobin.getAndIncrement())
        val entry = when {
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
        val provider = providerForModelEntry(entry)
            ?: return ToolExecutionResult("Failed to create provider for ${entry.model.displayName}", false)
        subAgentDepth.incrementAndGet()
        val parentSession = realSessionId.ifBlank { sessionId }.ifBlank { activeSessionId }
        val laneId = kotlin.coroutines.coroutineContext[SubAgentLane]?.id
            ?: SubAgentLane.idFor(parentSession, System.nanoTime())
        val trackerId = com.openminis.app.service.SubAgentActivityTracker.start(
            parentSessionId = parentSession,
            title = title.ifEmpty { "Sub-agent ($kind)" },
            role = role,
            model = entry.model.displayName,
        )
        return try {
            withContext(SubAgentLane(laneId)) {
            val liveLog = StringBuilder()
            var lastUiMs = 0L
            suspend fun onStep(line: String) {
                val clipped = line.trim()
                if (clipped.isEmpty()) return
                liveLog.append(clipped).append('\n')
                if (liveLog.length > 24_000) {
                    liveLog.delete(0, liveLog.length - 20_000)
                }
                com.openminis.app.service.SubAgentActivityTracker.updateStep(trackerId, clipped)
                val now = System.currentTimeMillis()
                val important = clipped.startsWith("▶") || clipped.startsWith("✓") || clipped.startsWith("✗") || clipped.startsWith("turn ")
                if (!important && now - lastUiMs < 250L) return
                lastUiMs = now
                publishRunSubagentLog(toolId, assistantId, currentText, toolBlocks, liveLog.toString())
            }
            val result = com.openminis.app.tools.WritePathGuard.withPaths(writePaths) {
                SubAgentRunner.run(
                    provider = provider,
                    modelDisplayName = entry.model.displayName,
                    userPrompt = prompt,
                    role = role,
                    skillsHint = skills,
                    tools = com.openminis.app.tools.SubAgentKind.filterTools(
                        kind,
                        AgentTools.makeAgentTools(
                            supportsImageInput = entry.model.hasImageInput,
                            visionGroupConfigured = com.openminis.app.tools.VisionGroupResolver.isConfigured(
                                providerRepository, context,
                            ),
                            memoryEnabled = false,
                            subAgentEnabled = false,
                        ),
                    ),
                    maxTokens = (entry.model.maxOutputTokens ?: 4096).coerceIn(256, 8192),
                    executeTool = { name, json ->
                        if (com.openminis.app.tools.SubAgentKind.blocks(kind, name)) {
                            ToolExecutionResult("Error: $kind sub-agent cannot use $name.", false)
                        } else {
                            executeTool(name, json, "", mutableListOf(), "", "")
                        }
                    },
                    onStep = { onStep(it) },
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
            publishRunSubagentLog(toolId, assistantId, currentText, toolBlocks, uiLog)
            com.openminis.app.service.SubAgentActivityTracker.finish(trackerId, result.success)
            result.copy(toolTitle = title.ifEmpty { "Sub-agent · ${entry.model.displayName}" })
            }
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
