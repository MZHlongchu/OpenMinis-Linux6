package com.openminis.app.ui.chat

/**
 * Humanize a snake_case tool name into a Title-Case label for pill headers
 * while the model's own `tool_title` arg has not yet streamed in.
 */
internal fun friendlyToolTitle(toolName: String): String = when (toolName) {
    "shell_execute" -> "Execute Shell"
    "file_read" -> "Read File"
    "file_write" -> "Write File"
    "file_edit" -> "Edit File"
    "browser_use" -> "Browse Web"
    "read_image" -> "Read Image"
    "memory_write" -> "Write Memory"
    "memory_get" -> "Read Memory"
    "web_search" -> "Search Web"
    "search_sessions" -> "Search Sessions"
    "read_session" -> "Read Session"
    "run_subagent" -> "Sub-agent"
    "ask_user_question", "AskUserQuestion" -> "Ask User"
    else -> toolName
        .split('_')
        .filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }
}
