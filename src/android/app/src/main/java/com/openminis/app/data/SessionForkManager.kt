package com.openminis.app.data

import android.content.Context
import com.openminis.app.data.body.Admission
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File

/**
 * Clone a session (or its skill/memory artifacts) into a fresh local session.
 * Mirrors iOS `SessionForkManager`. Android MVP only implements the **local**
 * branch of the iOS API — `forkSession` (from iCloud-mirrored remote devices)
 * is deferred until we ship a cross-device sync layer; see spec §7.8.
 *
 * The returned session carries "(Copy)" suffix and entirely new message ids,
 * so the duplicate and its source are independent. Compact markers ARE cloned
 * (T-session-duplicate-compact-marker-android, iOS parity e8ac8b82) with their
 * message-id references remapped onto the duplicated messages, so the compact
 * divider/shadow is preserved on the copy.
 */
class SessionForkManager(
    private val chatRepository: ChatRepository,
    private val skillRepository: SkillRepository? = null,
    private val filesDir: File,
) {
    companion object {
        private const val TAG = "SessionForkManager"
    }

    /**
     * Deep-copy a local session: creates a new session with the same model id
     * and copies every message's payload. Returns the new session's id or
     * null if [sessionId] doesn't exist.
     *
     * Carries over user-meaningful session metadata so the duplicate looks
     * and behaves like the source:
     *   - `category` — drives the session-list icon + colour swatch (see
     *     SessionListScreen.categoryStyle). The bug that motivated this fix:
     *     a "Coding" session duplicated as a generic uncategorised chat with
     *     a default icon. iOS duplicateSession copies this too.
     *   - `modelBinding` — user-pinned model override; if the source session
     *     was pinned to a specific model, the copy should keep it pinned.
     *   - `memoryEnabled` — user-toggled per-session; expectation is that
     *     the copy inherits the source's memory setting rather than
     *     defaulting to on.
     *
     * Deliberately NOT copied (the duplicate is a new session at creation
     * time, not a clone of the source's lifecycle):
     *   - `pinnedAt` (the copy starts unpinned)
     *   - `source` (origin tag like "shortcut" — the copy was made via the
     *     duplicate button, not via the original entry point)
     *   - `editCount` (starts at 0)
     *   - `lastMessage` as an inheritable *field* — but see [T-fork-copy-preview]
     *     below: it is seeded from the source as a FALLBACK and then refreshed by
     *     every copied row that yields a preview. Not copying it at all left a
     *     duplicate whose last row is a big/offloaded body showing "No messages
     *     yet" in the list, even though its conversation is identical.
     *   - `id` / `createdAt` / `updatedAt` (new identity, current timestamps)
     */
    suspend fun duplicateSession(sessionId: String): String? {
        val source = chatRepository.getSession(sessionId) ?: run {
            AppLogger.warning(TAG, "duplicateSession: $sessionId not found")
            return null
        }
        val messageTotal = chatRepository.messageCount(sessionId)
        val dupTitle = "${source.title ?: "Chat"} (Copy)"
        val new = chatRepository.createSession(
            modelId = source.modelId,
            title = dupTitle,
        )
        // Mirror iOS: carry over the source's category onto the copy so the
        // session-list icon + colour stay consistent with the original.
        if (source.category != null) {
            chatRepository.updateSessionTitleAndCategory(new.id, dupTitle, source.category)
        }
        // Carry over the user's per-session model binding (the "pinned model"
        // override) so duplicating a coding session pinned to GPT-5 stays
        // pinned to GPT-5 in the copy.
        if (source.modelBinding != null) {
            chatRepository.updateSessionBinding(new.id, source.modelBinding, source.modelId)
        }
        // Carry over the per-session memory toggle. Sessions default
        // memoryEnabled=1, so we only need to push the disabled state through;
        // the matching state is already in place from createSession.
        if (source.memoryEnabled == 0) {
            chatRepository.dao.updateMemoryEnabled(new.id, 0)
        }
        // T239: carry over the per-session thinking-mode override so a
        // duplicate of a "tuned to MEDIUM" session opens at MEDIUM rather
        // than reverting to the default (parity with memoryEnabled).
        source.thinkingOverride?.let { override ->
            chatRepository.dao.updateThinkingOverride(new.id, override)
        }
        val forkedMode = com.openminis.app.security.PermissionMode.sessionDefault(source.permissionMode)
        chatRepository.dao.updatePermissionMode(
            new.id,
            if (forkedMode.isYoyo()) "YOYO" else "ASK",
        )
        // [T-session-duplicate-compact-marker-android] Track old→new message id
        // and old→new sort_order so copied compact markers (which reference DB
        // message ids + legacy sort_orders) can be remapped onto the freshly
        // minted duplicate messages. copyMessageInto returns the new MessageEntity
        // (id + sortOrder), so no re-query is needed. Aligns iOS e8ac8b82.
        //
        // [T-fork-copy-preview] 先继承源会话的列表预览：副本与源会话消息完全相同，
        // 而逐行复制时只有"能解析出文本"的行才会写预览（大正文的 offload 行只能
        // 从 preview 列解析，解析不出来就不写）。没有这一步，最后一条是长回答/
        // 工具结果的副本会在会话列表里显示成「暂无消息」。后续行写出的真预览会
        // 覆盖它，故只是兜底。
        source.lastMessage?.let {
            chatRepository.dao.updateLastMessage(new.id, it, System.currentTimeMillis())
        }
        val oldToNewId = HashMap<String, String>()
        val oldToNewSort = HashMap<String, Int>()
        var offset = 0
        var copiedMessages = 0
        try {
            while (offset < messageTotal) {
                val page = chatRepository.dao.loadMessagesPage(sessionId, offset, 50)
                if (page.isEmpty()) break
                // [T-fork-admission-not-empty] 预算是进程级并发提示，不是「没有消息」。
                // 以前 tryAdmit 失败直接 break：副本被静默截断，收尾日志却按源会话
                // 总数宣称完整（同型缺陷见 ChatRepository.loadBoundedMessageRange 的
                // 注释）。现在先等一小会儿，仍然挤不下就照常做这次**有界**复制。
                val pageBytes = page.sumOf { (it.preview?.length ?: it.partsJson.length).toLong() }
                val admitted = Admission.awaitAdmit(pageBytes)
                if (!admitted) {
                    AppLogger.warning(
                        TAG,
                        "duplicateSession: admission busy, copying the page without a reservation",
                    )
                }
                try {
                    for (msg in page) {
                        // [T-fork-copy-body-ref] 逐条搬引用而不是搬体积：源行可能是
                        // offload 后的展示 stub（`body stored (N bytes)`），走
                        // appendMessage 会把 stub 当正文重新落库，副本里所有 >2KB 的
                        // 正文永久丢失。copyMessageInto 原样搬 body_ref/body_sha/
                        // body_bytes/preview，BodyStore 内容寻址，共享同一 sha 安全。
                        val newMsg = chatRepository.copyMessageInto(
                            targetSessionId = new.id,
                            source = msg,
                            tokenUsage = msg.tokenUsage,
                            reasoningContent = msg.reasoningContent,
                        )
                        oldToNewId[msg.id] = newMsg.id
                        oldToNewSort[msg.id] = newMsg.sortOrder
                    }
                    offset += page.size
                    copiedMessages += page.size
                } finally {
                    if (admitted) Admission.release(pageBytes)
                }
            }
        } catch (t: Throwable) {
            // 半截副本比没有副本更糟：列表里多一个残缺会话，用户还以为复制成功。
            // 回滚刚建的新会话再抛，与 AppSessionForkPort.createForkedSession 的
            // 失败处置一致（删除会话顺带回收它独占的正文引用）。
            runCatching { chatRepository.deleteSession(new.id) }
            throw t
        }

        // [T-session-duplicate-compact-marker-android] Copy compact markers
        // AFTER all messages are appended (so the new sort_orders exist). A
        // session with zero markers makes this a clean no-op — the normal
        // duplicate path is untouched. Each marker is remapped independently so
        // multi-compact sessions copy all dividers, in created_at order
        // (listCompactMarkers returns ASC by created_at).
        val markers = chatRepository.dao.listCompactMarkers(sessionId)
        if (markers.isNotEmpty()) {
            // Remap a referenced message id through the copy map. null → null.
            fun remapId(id: String?): String? = id?.let { oldToNewId[it] }
            // Resolve a legacy sort_order fallback to the NEW message's
            // sort_order via the referenced id; keep the original when the id
            // is null or can't be resolved (legacy markers without ids).
            fun remapSort(messageId: String?, original: Int): Int =
                messageId?.let { oldToNewSort[it] } ?: original
            var copied = 0
            for (marker in markers) {
                // All source messages are copied, so every non-null id field
                // MUST resolve. If one doesn't, the source marker is already
                // dangling — skip it rather than write a dangling copy.
                val firstKeptNew = remapId(marker.firstKeptMessageId)
                val lastCompactedNew = remapId(marker.lastCompactedMessageId)
                val boundaryNew = remapId(marker.boundaryMessageId)
                if ((marker.firstKeptMessageId != null && firstKeptNew == null) ||
                    (marker.lastCompactedMessageId != null && lastCompactedNew == null) ||
                    (marker.boundaryMessageId != null && boundaryNew == null)
                ) {
                    AppLogger.warning(
                        TAG,
                        "duplicateSession: skipping compact marker ${marker.id} — a referenced message id is not in the copy map (dangling source marker)",
                    )
                    continue
                }
                val copy = marker.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    sessionId = new.id,
                    firstKeptSortOrder = remapSort(marker.firstKeptMessageId, marker.firstKeptSortOrder),
                    uiBoundarySortOrder = marker.uiBoundarySortOrder?.let {
                        remapSort(marker.boundaryMessageId, it)
                    },
                    boundaryMessageId = boundaryNew,
                    firstKeptMessageId = firstKeptNew,
                    lastCompactedMessageId = lastCompactedNew,
                    // summary / compactedCount / createdAt / version verbatim.
                )
                chatRepository.dao.insertCompactMarker(copy)
                copied++
            }
            AppLogger.info(TAG, "duplicateSession: copied $copied/${markers.size} compact marker(s) to ${new.id}")
        }

        runCatching {
            copyEffectiveSessionFiles(filesDir, sessionId, source.folderId, new.id)
        }.onFailure {
            AppLogger.warning(TAG, "duplicateSession: workspace copy failed: ${it.message}")
        }

        if (copiedMessages < messageTotal) {
            AppLogger.warning(
                TAG,
                "duplicateSession: copied $copiedMessages/$messageTotal messages — the source changed while copying",
            )
        }
        AppLogger.info(
            TAG,
            "duplicated session $sessionId → ${new.id} ($copiedMessages/$messageTotal msgs, " +
                "category=${source.category}, modelBinding=${source.modelBinding}, " +
                "memoryEnabled=${source.memoryEnabled})",
        )
        return new.id
    }

    /**
     * Clone a skill into the local library. When [skillRepository] is wired,
     * reuses `importFromContent` so the skill lands in DB + `SKILL.md` just
     * like a fresh import. Returns whether the import succeeded.
     */
    fun copySkill(content: String, source: SkillRepository.ImportSource = SkillRepository.ImportSource.FILE): Boolean {
        val repo = skillRepository ?: run {
            AppLogger.warning(TAG, "copySkill: SkillRepository not injected")
            return false
        }
        return repo.importFromContent(content, source) != null
    }

    /**
     * Persist a memory note into [sessionId]'s workspace memory dir.
     * Overwrites if the file already exists.
     */
    fun copyMemory(sessionId: String, fileName: String, content: String): Boolean {
        if (fileName.contains("/") || fileName.contains("..") ||
            sessionId.contains("/") || sessionId.contains("\\") || sessionId.contains("..")
        ) {
            AppLogger.warning(TAG, "copyMemory: rejecting unsafe path '$sessionId'/'$fileName'")
            return false
        }
        val dir = SessionWorkspace.memoryDir(filesDir, sessionId).apply { mkdirs() }
        return try {
            File(dir, fileName).writeText(content)
            true
        } catch (e: Exception) {
            AppLogger.warning(TAG, "copyMemory: write failed: ${e.message}")
            false
        }
    }
}

/**
 * Copy the workspace the source session actually mounts, into the new session's
 * private tree. A filed session's `/var/minis/workspace` lives in the project
 * dir; copying only `minis-sessions/<id>/` would leave the duplicate empty.
 * The copy stays ungrouped so it does not share the project tree with the
 * original — "拷走" is an independent copy, not a second mount of the same files.
 */
internal fun copyEffectiveSessionFiles(
    filesDir: File,
    sourceId: String,
    folderId: String?,
    newId: String,
) {
    if (!SessionWorkspace.isSafeId(sourceId) || !SessionWorkspace.isSafeId(newId)) return
    if (sourceId == newId) return
    val dstRoot = SessionWorkspace.base(filesDir, newId)
    copySessionDir(
        File(SessionWorkspace.base(filesDir, sourceId), "memory"),
        File(dstRoot, "memory"),
    )
    for (sub in SessionWorkspace.SHARED_SUBDIRS) {
        copySessionDir(
            effectiveSharedSource(filesDir, sourceId, folderId, sub),
            File(dstRoot, sub),
        )
    }
}

private fun effectiveSharedSource(
    filesDir: File,
    sourceId: String,
    folderId: String?,
    sub: String,
): File {
    if (!folderId.isNullOrBlank() && SessionWorkspace.isSafeId(folderId)) {
        val shared = File(SessionWorkspace.projectBase(filesDir, folderId), sub)
        if (shared.isDirectory && !shared.listFiles().isNullOrEmpty()) return shared
    }
    return File(SessionWorkspace.base(filesDir, sourceId), sub)
}

private fun copySessionDir(src: File, dst: File) {
    if (!src.isDirectory || src.listFiles().isNullOrEmpty()) return
    if (src.absolutePath == dst.absolutePath) return
    dst.parentFile?.mkdirs()
    if (!src.copyRecursively(dst, overwrite = true)) {
        error("copy failed: ${src.path} -> ${dst.path}")
    }
}

/** Convenience: default-wire against application context. */
fun SessionForkManager(
    context: Context,
    chatRepository: ChatRepository,
    skillRepository: SkillRepository? = null,
): SessionForkManager = SessionForkManager(
    chatRepository = chatRepository,
    skillRepository = skillRepository,
    filesDir = context.filesDir,
)
