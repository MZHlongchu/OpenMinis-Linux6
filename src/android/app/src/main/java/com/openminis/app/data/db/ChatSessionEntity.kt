package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-android-session-grouping] The `folder_id` index is declared here so the
 * entity and MIGRATION_10_11 agree — Room validates the live schema against
 * the entity on open, and an index present in one but not the other aborts
 * startup with an IllegalStateException.
 *
 * Non-unique on purpose: many sessions share one group.
 *
 * [T-android-moveto-perf] `updated_at` is indexed for the same
 * entity-must-match-migration reason. Every session listing orders by it
 * (`ORDER BY updated_at DESC`), which without an index degrades to a full scan
 * plus a sort as the table grows — the "Move to" sheet's visible loading pause.
 *
 * The index lands in MIGRATION_15_16 on this branch. Upstream 1.14 ships the
 * identical index as its own 13 → 14; the two are the same statement, and this
 * lineage's chain (13 → 14 creates kanban_tasks, 15 → 16 adds the index) is
 * the one the committed schema json and DatabaseVersionGuard agree with, so the
 * upstream numbering is deliberately not adopted here.
 *
 * Ascending, not DESC: Room's Index annotation emits a plain
 * `CREATE INDEX … ON sessions(updated_at)` and validates the live schema
 * against exactly that string. Declaring DESC in the migration would not match
 * what Room expects and would abort startup. SQLite walks a B-tree index in
 * either direction, so an ascending index serves ORDER BY … DESC fine.
 */
@Entity(
    tableName = "sessions",
    // [T-android-sessions-updated-at-index] Backs the session-list query
    // (SELECT * FROM sessions ORDER BY updated_at DESC); without it the list
    // is a full scan + sort on every recomposition-triggered requery.
    // Keep in lockstep with MIGRATION_15_16 (same index name).
    indices = [
        androidx.room.Index(value = ["folder_id"], name = "index_sessions_folder_id"),
        androidx.room.Index(value = ["updated_at"], name = "index_sessions_updated_at"),
    ],
)
data class ChatSessionEntity(
    @PrimaryKey val id: String,
    val title: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "updated_at") val updatedAt: Long,        // milliseconds
    val category: String? = null,
    @ColumnInfo(name = "last_message") val lastMessage: String? = null,
    @ColumnInfo(name = "model_binding") val modelBinding: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "source") val source: String? = null,             // e.g. "shortcut", "share"
    @ColumnInfo(name = "memory_enabled") val memoryEnabled: Int = 1,     // 1=on, 0=off
    @ColumnInfo(name = "pinned_at") val pinnedAt: Long? = null,          // milliseconds, null=not pinned
    @ColumnInfo(name = "edit_count") val editCount: Int = 0,             // message edit counter
    // T239: per-session thinking-mode override. null = unset (use the
    // current model/group default — i.e. existing pre-T239 behaviour, which
    // is OFF on Android today). Non-null is one of ThinkingLevel.name
    // ("OFF"/"LOW"/"MEDIUM"/"HIGH"/"XHIGH") and represents an explicit user
    // choice that survives cold-start.
    @ColumnInfo(name = "thinking_override") val thinkingOverride: String? = null,
    /**
     * [T-android-session-grouping] Group membership. NULL = ungrouped.
     *
     * Deliberately NOT a declared @ForeignKey. A folder_id pointing at a group
     * that does not exist locally is a legitimate transient state, not
     * corruption: a future sync could deliver the session before its group, and
     * a group dissolved on another device leaves references behind until that
     * change arrives. Such orphans render as ungrouped (see
     * SessionListViewModel's grouping pass) instead of failing a constraint or
     * making the session vanish. Same rule as iOS (ChatStore.swift:610).
     *
     * NOTE for anyone adding list diffing: this field MUST participate in
     * equality. Moving a session between groups changes nothing else — not even
     * `updatedAt`, by design — so a differ that ignores it keeps drawing the row
     * in its old section.
     */
    @ColumnInfo(name = "folder_id") val folderId: String? = null,
    /**
     * [T-p1-delegate-task] Child-session ownership (design v4 §3.1). Non-null
     * ⇒ this is a hidden child of that session: it never appears in the home
     * list and is reached only through the parent's helper capsule / tool
     * block. Nullable, no DEFAULT, no FK (same reasoning as [folderId]: a
     * parent that is not here yet is a transient state, not corruption — and
     * cascade delete is explicit in ChatRepository.deleteSession, not a
     * constraint). Field names are the iOS wire names verbatim.
     *
     * Created by MIGRATION_12_13 on this branch: upstream 1.14 introduces the
     * two columns in its own 12 → 13, and our 12 → 13 (code graph) was folded
     * into the same step so a v12 database reaches v22 with both sets present.
     */
    @ColumnInfo(name = "parent_session_id") val parentSessionId: String? = null,
    /** The parent's `delegate_task` tool_use id that spawned this child, or
     *  null for a child created by `minis-scheduled --target child-of-current`. */
    @ColumnInfo(name = "parent_tool_use_id") val parentToolUseId: String? = null,
    /**
     * Session tool gate: ASK (审批) or ALLOW_ALL (YOYO). Default ASK.
     * Other enum names are accepted on read but the UI only writes these two.
     *
     * Added by MIGRATION_16_17, which also back-fills every pre-existing row to
     * `ASK` so an upgraded chat never inherits the old build-wide ALLOW_ALL /
     * READ_ONLY preference.
     */
    @ColumnInfo(name = "permission_mode") val permissionMode: String? = "ASK",
) {
    val isChild: Boolean get() = parentSessionId != null
}
