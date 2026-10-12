package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private val TITLE_MAX_ATTEMPTS = 3
// [T-android-auto-grouping-injection] Bounds on the group list folded into
// the title-generation prompt. 30 groups × ~140 chars keeps the segment
// well under a KB even in the worst case; the description cap matches
// FolderEntity.DESC_MAX_CHARS, which is enforced at creation time but not
// on rows written by older builds or by the AI-Suggest path.
private val GROUP_CONTEXT_MAX = 30
private val GROUP_NAME_MAX = 40
private val GROUP_DESC_MAX = 100

internal fun ChatViewModel.generateSessionTitleIfNeeded() {
    // [T-android-titlegen-diag-logging] Unified "TitleGen" trail across
    // every path of this function — XIN 40454 reported sessions silently
    // staying "New Chat" and the failure paths were under-logged.
    // Logging only; no logic change.
    AppLogger.info(
        "TitleGen",
        "enter session=${realSessionId.ifEmpty { sessionId }} attempts=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
            "inFlight=$titleGenerationInFlight currentTitle='${_sessionTitle.value.take(200)}'",
    )
    if (titleGenerationInFlight || titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
        AppLogger.info(
            "TitleGen",
            "skip guard=${if (titleGenerationInFlight) "inFlight" else "max-attempts ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS)"}",
        )
        return
    }
    // Skip if title already set (not "New Chat")
    if (_sessionTitle.value != "New Chat" && _sessionTitle.value.isNotEmpty()) {
        AppLogger.info("TitleGen", "skip guard=title-already-set title='${_sessionTitle.value.take(200)}'")
        return
    }
    // Prefer a dedicated sub-model (cheap, non-OAuth) — mirrors iOS resolveSubEntry.
    // Falls back to the primary provider if no sub-group is configured.
    // [T-title-gen-fallback-first-message-android] If no provider can be
    // resolved at all, the session would silently stay "New Chat". Log the
    // reason and fall back to the first user message as the title.
    //
    // [T-r2-titlegen-candidates] 上游 1.14 `ChatViewModel.kt:15920-15937` 在这里先
    // 组出**有序候选表**，再交给 walk 逐个尝试；合并时这块丢了，自动标题路径退回
    // 「只解析一个模型」（resolveTitleProvider），于是被限流的那一个标题模型会让会话
    // 一直停在 "New Chat"，而用户专门为这种情况排好序的标题子组其余成员一次都没被问
    // 到。按上游原文移植：标题子组（整组，按 agent loop 的回退顺序）→ 本会话自己的
    // 模型/组 → 默认主组 → 其余全部可用模型。只有「一个候选都建不出来」时才退到本
    // 会话当前的 provider（lastResortProvider）。
    val titleSid = realSessionId.ifEmpty { sessionId }
    val primarySource = _selectedGroupId.value
        ?.let { TitleCandidates.PrimarySource.Group(it, _activeEntryId.value) }
        ?: _activeEntryId.value?.let { TitleCandidates.PrimarySource.Entry(it) }
    val candidates = TitleCandidates.forSession(providerRepository, titleSid, primarySource, currentModel?.id)
    val lastResortProvider = if (candidates.isEmpty()) currentProvider else null
    AppLogger.info(
        "TitleGen",
        "candidates=${candidates.size} [${candidates.take(TitleCandidates.MAX_TRIES).joinToString { it.model.id }}]" +
            (if (lastResortProvider != null) " lastResort=${lastResortProvider.model.id}" else ""),
    )
    if (candidates.isEmpty() && lastResortProvider == null) {
        AppLogger.warning("TitleGen", "no provider available (no candidate + no current provider) — fallback-to-first-message path")
        viewModelScope.launch(Dispatchers.IO) {
            applyFallbackTitleFromFirstMessage("no provider available")
        }
        return
    }

    titleGenerationInFlight = true
    titleGenerationAttempts++

    // [T-titlegen-context-first-last-pair] Build the summary from the first
    // user + first assistant message, and — when the session has more than
    // one user turn — also the last user + last assistant message, each
    // truncated to 200 chars. This lets the title adapt when the topic
    // shifts later in a long session, instead of only seeing the opener.
    val msgs = _messages.value
    val userMessages = msgs.filter { it.role == "user" }
    val firstUser = userMessages.firstOrNull()
    if (firstUser == null) {
        AppLogger.warning("TitleGen", "skip guard=no-user-message (nothing to summarize)")
        titleGenerationInFlight = false
        return
    }
    val userText = firstUser.content.take(200)
    // First/last assistant *text* message — skip tool-only capsules whose
    // content is blank so the summary carries real assistant prose.
    val assistantTextMessages = msgs.filter { it.role == "assistant" && it.content.isNotBlank() }
    val firstAssistantText = assistantTextMessages.firstOrNull()?.content?.take(200) ?: ""
    // Only append the last pair when there is more than one user turn (i.e.
    // the first and last user messages differ) — avoids duplicating the
    // opener when the session is a single exchange.
    val hasMultipleUserTurns = userMessages.size > 1
    val lastUserText = if (hasMultipleUserTurns) userMessages.lastOrNull()?.content?.take(200) ?: "" else ""
    val lastAssistantText = if (hasMultipleUserTurns) assistantTextMessages.lastOrNull()?.content?.take(200) ?: "" else ""

    viewModelScope.launch(Dispatchers.IO) {
        try {
            // Mirror iOS callSubModelForTitle prompt shape: short cacheable system
            // prompt + user-message payload. Using the exact iOS strings keeps the
            // Anthropic prompt cache warm across title-gen calls.
            // [T-android-auto-grouping] Fold the group question into THIS
            // call — no second round-trip. With the toggle off, or with no
            // groups to offer, the prompt is byte-identical to the
            // title-only form (so the Anthropic prompt cache stays warm).
            // Port of iOS callSubModelForTitle's folderSegment.
            val autoGroupOn = com.openminis.app.ui.settings
                .autoGroupingEnabled(context)
            val groupContext: List<String> = if (!autoGroupOn) emptyList() else {
                // [T-android-auto-grouping-injection] Bound the group list.
                // Group names are free-form user input with no length or
                // content limit (only the description is capped, and only
                // at creation time), and they are interpolated into an
                // instruction — a name containing `" ].` can close the
                // bracket and inject directives. Unbounded COUNT is the
                // other half: 200 groups would add multiple KB to EVERY
                // title generation. Newest-first (listFolders is ORDER BY
                // updated_at DESC), so the cut drops the stalest groups.
                //
                // Dedup on the RENDERED name, not the raw one: sanitizing
                // and truncating to GROUP_NAME_MAX can map two distinct
                // folders onto the same string ("…Machine Learning Reading"
                // and "…Machine Learning Writing" share a 40-char prefix).
                // Offering that string twice would make the model's answer
                // unresolvable, so drop every name that is ambiguous after
                // rendering rather than filing the chat into a coin-flip
                // winner. Same reason the apply side refuses ambiguous
                // matches.
                val rendered = chatRepository.listFolders()
                    .map { f -> f to ChatViewModel.promptSafe(f.name, GROUP_NAME_MAX) }
                    .filter { (_, n) -> n.isNotEmpty() }
                val ambiguous = rendered
                    .groupingBy { (_, n) -> n.lowercase() }
                    .eachCount()
                    .filterValues { it > 1 }
                    .keys
                rendered
                    .filterNot { (_, n) -> n.lowercase() in ambiguous }
                    .take(GROUP_CONTEXT_MAX)
                    .map { (f, n) ->
                        // "name — one-sentence description" when the group
                        // has one; the description exists precisely to
                        // sharpen this membership judgment.
                        val d = f.description?.takeIf { it.isNotBlank() }
                            ?.let { ChatViewModel.promptSafe(it, GROUP_DESC_MAX) }
                            ?.takeIf { it.isNotEmpty() }
                        if (d != null) "\"$n\" — $d" else "\"$n\""
                    }
            }
            val prompt = buildString {
                append("Based on the following conversation, generate a short title (max 6 words) that captures the topic. ")
                append("Also pick a task category from: code, writing, research, analysis, creative, chat, math, translation, health, finance, travel, education, design, productivity, support, other.\n\n")
                if (groupContext.isNotEmpty()) {
                    // The null path is spelled out and given its own
                    // example: a closed option list pushes sub-models
                    // toward always picking something, and a wrongly-filed
                    // session costs far more than a wrong category (which
                    // only drives a row icon).
                    append("The user organizes chats into groups. Existing groups: [")
                    append(groupContext.joinToString("; "))
                    append("]. If this conversation clearly belongs to one of these groups, ")
                    append("set \"folder\" to that exact group name. ")
                    append("If it does not clearly match any group, or you are unsure, set \"folder\" to null. ")
                    append("Never invent a new group name.\n\n")
                }
                append("You MUST respond with valid JSON only. Example:\n")
                if (groupContext.isEmpty()) {
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\"}\n\n")
                } else {
                    append("{\"title\": \"Debug Login Page Issue\", \"category\": \"code\", \"folder\": null}\n\n")
                }
                append("Conversation:\n")
                append("User: $userText\n")
                if (firstAssistantText.isNotEmpty()) append("Assistant: $firstAssistantText\n")
                if (lastUserText.isNotEmpty()) append("User: $lastUserText\n")
                if (lastAssistantText.isNotEmpty()) append("Assistant: $lastAssistantText\n")
                append(titleLanguageDirective())
            }
            // [T-android-titlegen-systemprompt-unify] Shared with the manual
            // Regenerate path (SessionListViewModel.regenerateTitle) via the
            // single TITLE_GEN_SYSTEM_PROMPT constant so the two never drift.
            // Passed bare: for OAuth Anthropic instances,
            // AnthropicProvider.resolveSystemPrompt force-prepends the Claude
            // Code prefix block at the provider layer (and strips a
            // caller-supplied one), so no caller-side prepend is needed — the
            // previous manual prefix branch here was redundant.
            val effectiveSystemPrompt = TITLE_GEN_SYSTEM_PROMPT

            // 一个模型的一次标题请求；null = 没拿到可用标题（空响应 / 解析不出），
            // walk 把它当成这个模型的失败、继续问下一个候选。
            // [T-titlegen-retry-not-retrying] 上游 1.14 `ChatViewModel.kt:16059-16108`：
            // 请求本身抽成 ask(provider)，walk 与「最后一个模型」两条路共用它。
            suspend fun ask(provider: LLMProvider): TitleGenResult? {
                AppLogger.info(
                    "TitleGen",
                    "dispatch attempt=$titleGenerationAttempts provider=${provider.javaClass.simpleName} model=${provider.model.id}",
                )
                // [T-android-titlegen-reasoning] Match iOS callSubModelForTitle:
                // explicitly disable thinking (thinkingLevel = OFF). The provider
                // layer's injectThinkingParams honors OFF — e.g. DeepSeek V4 gets
                // an explicit {"thinking":{"type":"disabled"}}, o-series/gpt-5
                // omit reasoning_effort, Anthropic sends no thinking block — so a
                // reasoning sub-model doesn't burn the whole budget on hidden
                // thinking and return empty text. As a belt-and-suspenders for
                // models where OFF is still a no-op (e.g. Qwen3, which thinks by
                // default), keep the T334 budget bump so it can finish thinking
                // and still emit the JSON. Unified with regenerateTitle's ladder.
                val titleMaxTokens = if (provider.model.supportsReasoning == true) 2048 else 100
                val response = provider.sendMessage(
                    messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = prompt)),
                    systemPrompt = effectiveSystemPrompt,
                    maxTokens = titleMaxTokens,
                    temperature = null, // [T-android-titlegen-temperature] 上游 1.14：gpt-5.x 只接受 temperature=1，null 必须压过候选自身的 override（否则 400 会烧掉该候选）
                    thinkingLevel = ThinkingLevel.OFF,
                )

                AppLogger.info(
                    "TitleGen",
                    "response stopReason=${response.stopReason} textLen=${response.text.length} " +
                        "raw='${response.text.take(200).replace("\n", "\\n")}'",
                )
                val parsed = parseTitleResponse(response.text)
                if (parsed.title.isEmpty()) {
                    // [T-title-gen-fallback-first-message-android] 请求成功但没有
                    // 可用标题（空响应，或者推理模型把预算全花在思考上）。这是**这一个
                    // 模型**的失败：walk 继续下一个。
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=no-title model=${provider.model.id} (empty / unparseable response) " +
                            "stopReason=${response.stopReason} textLen=${response.text.length}",
                    )
                    return null
                }
                return parsed
            }
            val parsed: TitleGenResult? = if (lastResortProvider != null) {
                try {
                    ask(lastResortProvider)
                } catch (e: Exception) {
                    // [T-android-titlegen-stream-error-walk] provider 的流中错误是以
                    // LLMError 为 cause 的 CancellationException 送到的；只有**真的**
                    // 取消才结束标题生成。
                    if (e is CancellationException && TitleCandidates.isRealCancellation(e)) throw e
                    AppLogger.warning(
                        "TitleGen",
                        "outcome=exception model=${lastResortProvider.model.id} ${e.javaClass.simpleName}: ${e.message?.take(200)}",
                    )
                    null
                }
            } else {
                // [T-titlegen-retry-not-retrying] 按顺序试候选：某个模型 429 之后停顿
                // 一下换下一个，而不是让整次尝试作废。会话中途被改名（用户手改优先）
                // 就提前停。
                TitleCandidates.walk(
                    candidates,
                    origin = "auto",
                    pauseMs = TitleCandidates.AUTO_PAUSE_MS,
                    shouldStop = {
                        val t = chatRepository.getSession(titleSid)?.title?.trim()
                        !t.isNullOrEmpty() && t != ChatViewModel.UNTITLED_SESSION_TITLE
                    },
                ) { entry ->
                    val p = TitleCandidates.providerFor(providerRepository, context, entry, activeSessionId)
                        ?: return@walk null
                    ask(p)
                }
            }
            if (parsed != null) {
                val (title, category, folderName) = parsed
                val sid = realSessionId.ifEmpty { sessionId }
                chatRepository.updateSessionTitleAndCategory(sid, title, category)
                withContext(Dispatchers.Main) {
                    _sessionTitle.value = title
                    _sessionCategory.value = category
                }
                AppLogger.info("TitleGen", "outcome=set title='$title' category='$category'")
                // [T-android-auto-grouping] Group assignment is best-effort
                // and strictly SUBORDINATE to the title: a name that
                // resolves to nothing is silently dropped (never create a
                // group from a model's answer), and setFolderIfUnfiled
                // means a hand-filed session is never overridden by the
                // model's guess. Mirrors iOS.
                if (!folderName.isNullOrEmpty()) {
                    // findFolderByName rather than a local equals: it trims
                    // BOTH sides (models routinely echo "Work " with a
                    // trailing space, which an exact equals silently
                    // rejects — the feature then looks intermittently
                    // broken), and because listFolders is ORDER BY
                    // updated_at DESC its first hit is the most recently
                    // updated duplicate. That matters when two devices
                    // created a same-named group offline: an arbitrary pick
                    // files the chat into the group the user isn't looking
                    // at. Same resolver the AI-Suggest flow uses.
                    val match = chatRepository.findFolderByName(folderName)
                        // The prompt shows SANITIZED names, so a group whose
                        // real name contains stripped characters comes back
                        // in its sanitized form and won't match directly.
                        // Fall back to comparing sanitized-to-sanitized —
                        // but REFUSE an ambiguous hit rather than taking the
                        // first. Two folders can render to the same string
                        // (truncation at GROUP_NAME_MAX, or names differing
                        // only in stripped characters); filing into an
                        // arbitrary one is a silent wrong-group move the
                        // user gets no signal about. Leaving it ungrouped is
                        // the recoverable outcome.
                        ?: chatRepository.listFolders().filter {
                            ChatViewModel.promptSafe(it.name, GROUP_NAME_MAX)
                                .equals(folderName.trim(), ignoreCase = true)
                        }.singleOrNull()
                    if (match != null) {
                        val applied = chatRepository.setFolderIfUnfiled(match.id, sid)
                        AppLogger.info(
                            "TitleGen",
                            "auto-group '$folderName' -> ${match.id.take(8)} applied=$applied",
                        )
                    } else {
                        AppLogger.info(
                            "TitleGen",
                            "auto-group: model offered '$folderName' but no group matches — leaving ungrouped",
                        )
                    }
                }
            } else {
                // [T-titlegen-retry-not-retrying] Every candidate failed
                // (or none could be built). Only NOW the first-message
                // fallback — and no further automatic attempts: the walk
                // already was the retry. iOS sets titleGenAttempts = 3 here.
                // [T-r2-titlegen-candidates] 上游 1.14 `ChatViewModel.kt:16198-16206`：
                // 「整张候选表都失败」才落第一条用户消息兜底，并把尝试数打满，于是不会
                // 再有下一次自动尝试。
                titleGenerationAttempts = TITLE_MAX_ATTEMPTS
                AppLogger.warning("TitleGen", "outcome=gave-up (all title candidates failed) — applying first-message fallback title")
                applyFallbackTitleFromFirstMessage("all title candidates failed")
            }
        } catch (e: Exception) {
            // [T-title-gen-fallback-first-message-android] Request error /
            // timeout / provider failure. Log the concrete cause (was
            // already logged, kept) and fall back to the first user message
            // on the final attempt.
            AppLogger.warning(
                "TitleGen",
                "outcome=exception attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                    "${e.javaClass.simpleName}: ${e.message?.take(200)}",
            )
            if (titleGenerationAttempts >= TITLE_MAX_ATTEMPTS) {
                AppLogger.warning("TitleGen", "outcome=gave-up ($titleGenerationAttempts/$TITLE_MAX_ATTEMPTS) — applying first-message fallback title")
                applyFallbackTitleFromFirstMessage("request failed: ${e.message?.take(200)}")
            }
        } finally {
            titleGenerationInFlight = false
            // [GH#210] Cancellation used to be the one exit that produced
            // NOTHING — no title, no fallback, and no terminal log line, so
            // a dispatched attempt simply vanished. That silence is how
            // this bug stayed invisible: the logs showed 6 dispatches and
            // 5 outcomes with no failure in between.
            //
            // Now a cancelled attempt still lands the first-user-message
            // fallback, so leaving the chat mid-request can no longer strand
            // a session on "New Chat".
            //
            // NonCancellable is load-bearing: we are already in a cancelled
            // scope, so without it the very first suspension point inside
            // applyFallbackTitleFromFirstMessage (the DB write) would throw
            // CancellationException again and write nothing — a fallback
            // that silently never runs is worse than none, because the log
            // line would claim it did.
            if (!isActive) {
                AppLogger.warning(
                    "TitleGen",
                    "outcome=cancelled attempt=$titleGenerationAttempts/$TITLE_MAX_ATTEMPTS " +
                        "session=${(realSessionId.ifEmpty { sessionId }).take(8)} " +
                        "reason=scope-cancelled — applying first-message fallback",
                )
                withContext(NonCancellable) {
                    applyFallbackTitleFromFirstMessage("scope cancelled")
                }
            }
        }
    }
}

/**
 * [T-title-gen-fallback-first-message-android] Set the session title to a
 * cleaned-up truncation of the first user message when LLM title generation
 * fails (request error / timeout / empty / parse failure / model
 * unavailable). Strips the trailing `<user-attached-files>` XML block,
 * collapses whitespace/newlines to single spaces, and clamps to ~30 chars
 * with an ellipsis — matching the title norm (single-line, short). No-op
 * (logged) when there's no usable first-message text.
 *
 * [GH#210] Re-reads the session from the DB first and bails if it already
 * carries a real title, mirroring iOS `applyFallbackTitle` (76e4c07bc).
 * This is not defensive noise: the title request runs 22–51s against a real
 * provider, and every caller of this function is a FAILURE exit reached at
 * the end of that window. The user has had all that time to rename the
 * session by hand, and a manual rename must always win over a machine
 * fallback derived from the opening message.
 */
private suspend fun ChatViewModel.applyFallbackTitleFromFirstMessage(reason: String) {
    val sidForCheck = realSessionId.ifEmpty { sessionId }
    val existing = chatRepository.getSession(sidForCheck)?.title?.trim()
    if (!existing.isNullOrEmpty() && existing != "New Chat") {
        AppLogger.info(
            "TitleGen",
            "outcome=fallback-skipped session=${sidForCheck.take(8)} reason=already-titled",
        )
        return
    }
    val raw = _messages.value.firstOrNull { it.role == "user" }?.content
    var text = raw ?: ""
    // Drop the <user-attached-files> XML the composer appends so the title
    // reflects what the user actually typed, not the attachment manifest.
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx >= 0) {
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        text = if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }
    // Collapse all whitespace (incl. newlines) to single spaces, trim.
    val cleaned = text.replace(Regex("\\s+"), " ").trim()
    if (cleaned.isEmpty()) {
        AppLogger.warning(
            "TitleGen",
            "outcome=fallback-unavailable session=${sidForCheck.take(8)} " +
                "reason=first-user-message-empty-after-cleanup ($reason)",
        )
        return
    }
    val fallbackTitle = if (cleaned.length > 30) cleaned.take(30).trimEnd() + "…" else cleaned
    chatRepository.updateSessionTitle(sidForCheck, fallbackTitle)
    withContext(Dispatchers.Main) {
        _sessionTitle.value = fallbackTitle
    }
    // Length only — never the user's prompt text.
    AppLogger.info(
        "TitleGen",
        "outcome=fallback session=${sidForCheck.take(8)} titleLen=${fallbackTitle.length} reason=$reason",
    )
}

/**
 * [T-android-titlegen-temperature] The serving entry's own sampling override,
 * used by the agent loop's small side calls.
 *
 * [T-r2-titlegen-candidates] 上游 1.14 里「标题用哪个 provider」不再由这个文件里
 * 的单一解析器决定（那个 `resolveTitleProvider` 连同它上面这段 KDoc 一起删掉了）：
 * 候选表与建 provider 都归 [TitleCandidates.forSession] /
 * [TitleCandidates.providerFor]，自动标题与手动 Regenerate 共用同一套。
 */
internal fun ChatViewModel.samplingTemperature(entryId: String?): Double? {
    if (entryId.isNullOrBlank()) return null
    return providerRepository.config.value.modelEntries
        .firstOrNull { it.id == entryId }
        ?.overrides
        ?.temperature
}

/** Title sub-entry's own override. Falls back to the serving entry only when no sub-entry exists. */
private fun ChatViewModel.titleTemperature(): Double? {
    val sub = providerRepository.resolveTitleSubEntry()
    return if (sub != null) sub.overrides.temperature else samplingTemperature(_activeEntryId.value)
}

/** Parse LLM response for title/category JSON. Multiple fallback strategies. */
private fun ChatViewModel.parseTitleResponse(text: String): TitleGenResult {
    val cleaned = text.trim()
        .removePrefix("```json").removePrefix("```")
        .removeSuffix("```").trim()
    // Try JSON parse
    try {
        val json = JSONObject(cleaned)
        val title = json.optString("title", "").trim()
        val category = json.optString("category", "").trim().ifEmpty { null }
        // [T-android-auto-grouping] `folder` is absent whenever
        // auto-grouping is off, and JSON null when the model declined to
        // file the chat — optString maps both to "" → null here.
        val folder = json.optString("folder", "").trim()
            .takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
        if (title.isNotEmpty()) return TitleGenResult(title, category, folder)
    } catch (_: Exception) {}
    // Regex fallback: extract "title" value
    val titleMatch = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    val catMatch = Regex("\"category\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    val folderMatch = Regex("\"folder\"\\s*:\\s*\"([^\"]+)\"").find(cleaned)
    if (titleMatch != null) {
        return TitleGenResult(
            titleMatch.groupValues[1].trim(),
            catMatch?.groupValues?.getOrNull(1)?.trim(),
            folderMatch?.groupValues?.getOrNull(1)?.trim()
                ?.takeIf { !it.equals("null", ignoreCase = true) },
        )
    }
    // Plain text fallback: use first line
    val firstLine = cleaned.lines().firstOrNull()?.trim() ?: ""
    return TitleGenResult(firstLine.take(50), null, null)
}

/** Parsed title-generation payload. [folder] is non-null only when
 *  auto-grouping asked for it AND the model named an existing group. */
private data class TitleGenResult(
    val title: String,
    val category: String?,
    val folder: String?,
)
