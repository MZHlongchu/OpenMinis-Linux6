package com.openminis.app.ui.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.util.IsoTime

// [T-android-split-chat] Pure tool-label / duration / timestamp formatting
// helpers extracted verbatim from ChatScreen.kt. `internal` so the rest of the
// chat package (still in ChatScreen.kt) can call them across the file boundary.
// No logic change — code moved as-is.

internal fun formatStepTimestamp(epochMs: Long): String =
    IsoTime.formatHms(epochMs)

// [T-step-timestamp v2 aa8b1128] Short "elapsed-or-final duration"
// label for the tool detail header. Cross-platform format contract:
//   < 60s   → "3s"
//   < 1h    → "2m30s" (drops the seconds suffix when seconds == 0)
//   ≥ 1h    → "1h12m"
// When `stillRunning` is true the result is suffixed "…" so the
// header reads "12s…" while the tool is in flight.
// Negative / non-positive values clamp to 0.
internal fun formatStepDuration(seconds: Long, stillRunning: Boolean): String {
    val safe = seconds.coerceAtLeast(0L)
    val base = when {
        safe < 60L -> "${safe}s"
        safe < 3600L -> {
            val m = safe / 60L
            val s = safe % 60L
            if (s == 0L) "${m}m" else "${m}m${s}s"
        }
        else -> {
            val h = safe / 3600L
            val m = (safe % 3600L) / 60L
            if (m == 0L) "${h}h" else "${h}h${m}m"
        }
    }
    return if (stillRunning) "$base…" else base
}

// Helper: tool accent color
internal fun toolAccentColor(toolName: String): Color = when (toolName) {
    "shell_execute" -> Color(0xFF34C759)
    "file_read" -> Color(0xFF32ADE6)
    "file_write" -> Color(0xFF007AFF)
    "file_edit" -> Color(0xFFFF9500)
    "browser_use" -> Color(0xFF007AFF)
    "read_image" -> Color(0xFFAF52DE)
    "memory_write", "memory_get" -> Color(0xFFFF2D55)
    "web_search" -> Color(0xFF32ADE6)    // iOS: .cyan for search
    // [T-sub-agents-v1] Current name + the pre-rename one still in shipped transcripts.
    "subagent_task", "delegate_task", "agent_status" -> HelperAccentStatic  // iOS: HelperAccent.color (electric violet)
    // [T-linux-tool-vocab] OpenMinis/Linux-only tools (session lookup, cron, online
    // plugins) — upstream has no entry for them, so keep the port's accents.
    "search_sessions", "read_session" -> Color(0xFF64D2FF)
    "spawn_agent", "run_subagent" -> Color(0xFF5856D6)
    "cronjob" -> Color(0xFFFF9500)
    else -> if (toolName.startsWith("online_")) Color(0xFF007AFF) else Color(0xFF8E8E93)
}

// Helper: tool icon (iOS: distinct SF Symbols per tool type)
internal fun toolIconFor(toolName: String) = when (toolName) {
    "shell_execute" -> Icons.Default.Terminal
    "file_read" -> Icons.Default.Description         // iOS: doc.text
    "file_write" -> Icons.AutoMirrored.Filled.NoteAdd   // iOS: doc.text.fill (filled variant)
    "file_edit" -> Icons.Default.EditNote             // iOS: square.and.pencil
    "browser_use" -> Icons.Default.Language            // iOS: globe
    "read_image" -> Icons.Default.Image                // iOS: photo
    "memory_write", "memory_get" -> Icons.Default.Psychology // iOS: brain.head.profile
    "web_search" -> Icons.Default.Search               // iOS: magnifyingglass
    "subagent_task", "delegate_task", "agent_status" -> Icons.Default.Groups  // iOS: person.2.wave.2
    "search_sessions", "read_session" -> Icons.Default.Search
    "spawn_agent", "run_subagent" -> Icons.Default.Groups
    "cronjob" -> Icons.Default.Alarm
    else -> if (toolName.startsWith("online_")) Icons.Default.Language else Icons.Default.Build
}

// [T-android-overlay-two-row] View-layer twin of [toolIconFor], for callers
// that build a classic View hierarchy instead of Compose (the floating-window
// capsule in ToolOverlayController). An ImageVector cannot be handed to an
// ImageView, so the same vocabulary has to exist a second time as drawable
// resources — but it lives HERE, beside the Compose map, so the two are edited
// together and cannot silently diverge. Adding a tool means adding it to both.
@androidx.annotation.DrawableRes
internal fun toolIconResFor(toolName: String?): Int = when (toolName) {
    "shell_execute" -> R.drawable.ic_tool_terminal
    "file_read" -> R.drawable.ic_tool_description
    "file_write" -> R.drawable.ic_tool_note_add
    "file_edit" -> R.drawable.ic_tool_edit_note
    "browser_use" -> R.drawable.ic_tool_globe
    "read_image" -> R.drawable.ic_tool_image
    "memory_write", "memory_get" -> R.drawable.ic_tool_psychology
    "web_search" -> R.drawable.ic_tool_search
    "subagent_task", "delegate_task", "agent_status" -> R.drawable.ic_tool_groups
    // [T-linux-tool-vocab] Port-only tool vocabulary, mirroring [toolIconFor].
    // (cronjob has no alarm drawable in the merged resource set, so it keeps the
    // generic ic_tool_build fallback here while the Compose map uses Alarm.)
    "search_sessions", "read_session" -> R.drawable.ic_tool_search
    else -> if (toolName?.startsWith("online_") == true) R.drawable.ic_tool_globe else R.drawable.ic_tool_build
}

// [T-android-overlay-two-row] View-layer twin of [toolAccentColor]. Same
// literals, as a packed ARGB Int rather than a Compose Color.
@androidx.annotation.ColorInt
internal fun toolAccentColorInt(toolName: String?): Int = when (toolName) {
    "shell_execute" -> 0xFF34C759.toInt()
    "file_read" -> 0xFF32ADE6.toInt()
    "file_write" -> 0xFF007AFF.toInt()
    "file_edit" -> 0xFFFF9500.toInt()
    "browser_use" -> 0xFF007AFF.toInt()
    "read_image" -> 0xFFAF52DE.toInt()
    "memory_write", "memory_get" -> 0xFFFF2D55.toInt()
    "web_search" -> 0xFF32ADE6.toInt()
    "subagent_task", "delegate_task", "agent_status" -> 0xFFAF52DE.toInt()
    // [T-linux-tool-vocab] Port-only tool vocabulary, mirroring [toolAccentColor].
    "search_sessions", "read_session" -> 0xFF64D2FF.toInt()
    "spawn_agent", "run_subagent" -> 0xFF5856D6.toInt()
    "cronjob" -> 0xFFFF9500.toInt()
    else -> if (toolName?.startsWith("online_") == true) 0xFF007AFF.toInt() else 0xFF8E8E93.toInt()
}

/**
 * [T-android-overlay-two-row] Humanized fallback title for a tool call whose
 * model-supplied `tool_title` has not arrived (or was never sent).
 *
 * This mirrors ChatViewModel.friendlyToolTitle, which is what the chat stream's
 * tool pill shows in the same situation. Kept as a separate copy rather than
 * hoisting that private member, because ChatViewModel is a very large file that
 * several sessions edit concurrently; the vocabulary here is small and stable.
 */
internal fun friendlyToolTitleFor(toolName: String?): String = when (toolName) {
    null, "" -> "Minis"
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "memory_write" -> "Write Memory"
    "memory_get" -> "Read Memory"
    "web_search" -> "Search Web"
    else -> toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}

// Helper: tool display name for "Minis is using X"
internal fun toolDisplayName(toolName: String): String = when (toolName) {
    "shell_execute" -> "terminal"
    "file_read" -> "file reader"
    "file_write" -> "file writer"
    "file_edit" -> "file editor"
    "browser_use" -> "browser"
    "read_image" -> "image viewer"
    "memory_write" -> "memory"
    "memory_get" -> "memory"
    "web_search" -> "search"
    "subagent_task", "delegate_task", "agent_status" -> "agent"
    "search_sessions" -> "session search"
    "read_session" -> "session reader"
    "spawn_agent", "run_subagent" -> "sub-agent"
    "cronjob" -> "cron"
    else -> if (toolName.startsWith("online_")) "online plugin" else toolName
}

/**
 * Full "Minis is …" label shown in the tool detail sheet's bottom bar.
 * Mirrors iOS ToolLiveSheet.toolTitle so the wording matches per tool.
 */
internal fun toolTitleLabel(toolName: String): String = when (toolName) {
    "shell_execute" -> "Minis is using Shell"
    "file_read" -> "Minis is reading File"
    "file_write" -> "Minis is using Editor"
    "file_edit" -> "Minis is editing File"
    "browser_use" -> "Minis is using Browser"
    "read_image" -> "Minis is reading Image"
    "memory_write", "memory_get" -> "Minis is using Memory"
    "web_search" -> "Minis is using Search"
    "subagent_task", "delegate_task", "agent_status" -> "Minis is using an Agent"
    "search_sessions" -> "Minis is searching Sessions"
    "read_session" -> "Minis is reading a Session"
    "spawn_agent", "run_subagent" -> "Minis is coordinating a sub-agent"
    "cronjob" -> "Minis is scheduling a task"
    else -> "Minis is using ${toolDisplayName(toolName)}"
}

// Helper: format duration (iOS: < 1s → "0.1s", < 60s → "45s", >= 60s → "2m 10s")
internal fun formatToolDuration(ms: Long): String {
    val seconds = ms / 1000.0
    return when {
        seconds < 1 -> String.format("%.1fs", seconds)
        seconds < 60 -> String.format("%.0fs", seconds)
        else -> {
            val m = (seconds / 60).toInt()
            val s = (seconds % 60).toInt()
            "${m}m ${s}s"
        }
    }
}

/**
 * [T-agent-ui-design-system] Status icon with a same-color halo ring.
 *
 * Adapted from the agent-ui design system spec 6.2 (tool row five states):
 * a 9px status dot wrapped in a 3px halo of the same color at ~12% alpha
 * (`box-shadow: 0 0 0 3px <color>1f`). Here the per-tool icon plays the
 * dot, and the halo is an 18dp circle under the 12dp icon — same intent
 * (state reads at a glance) at mobile density.
 *
 * `halo = false` renders the bare icon: neutral rows (pending, or muted
 * rows with no viewable output) carry no state ring, per spec.
 */
@Composable
internal fun ToolStatusHaloIcon(
    icon: ImageVector,
    tint: Color,
    halo: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(18.dp),
    ) {
        if (halo) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(color = tint.copy(alpha = 0.12f), shape = CircleShape),
            )
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * [T-process-run-card] The turn-error row rendered at the bottom of an
 * expanded process card — a failed run reads as one process, not a card
 * plus a detached banner. Lives here (not in the card file) to keep the
 * card file under the architecture ratchet.
 */
@Composable
internal fun ProcessRunErrorRow(errorText: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = ToolErrorColor,
            modifier = Modifier.size(13.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.chat_process_card_error_row) + "\n" + errorText,
            fontSize = 12.sp,
            color = ToolErrorColor,
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
}
