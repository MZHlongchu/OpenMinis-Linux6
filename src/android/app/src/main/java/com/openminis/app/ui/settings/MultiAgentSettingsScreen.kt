package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.data.ToolLimitPrefs
import com.openminis.app.data.PlanDiscussionPrefs
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.MultiAgentSettings

@Composable
fun MultiAgentSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as MinisApp
    val repo = app.multiAgentSettingsRepository
    val providerRepo = app.providerRepository

    val enabled by repo.enabled.collectAsState()
    val maxConcurrent by repo.maxConcurrent.collectAsState()
    // Turn budget is coordinator-assigned (1.27 unclamp); only the retry-attempts
    // stepper still reads a repo StateFlow here.
    val subagentMaxAttempts by repo.subagentMaxAttempts.collectAsState()
    val selectedIds by repo.selectedModelEntryIds.collectAsState()
    val config by providerRepo.config.collectAsState()
    val configLoaded by providerRepo.configLoaded.collectAsState()

    val instancesById = config.instances.associateBy { it.id }
    val candidates = config.modelEntries.filter { entry ->
        !entry.isHidden && instancesById[entry.providerInstanceId]?.isEnabled == true
    }
    val candidateIds = remember(candidates) { candidates.map { it.id }.toSet() }
    val slots = remember(selectedIds, maxConcurrent) {
        MultiAgentSettings.resizeSlots(selectedIds, maxConcurrent)
    }
    val staleCount = selectedIds.count { it.isNotBlank() && it !in candidateIds }
    var discussionMode by remember { mutableStateOf(PlanDiscussionPrefs.mode()) }

    LaunchedEffect(configLoaded, candidateIds) {
        if (configLoaded) repo.retainLiveEntries(candidateIds)
    }

    SettingsScaffold(
        title = stringResource(R.string.settings_multi_agent),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.settings_multi_agent_section_dispatch),
            footer = stringResource(R.string.settings_multi_agent_footer_dispatch),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.settings_multi_agent_enable),
                subtitle = stringResource(R.string.settings_multi_agent_enable_subtitle),
                checked = enabled,
                onCheckedChange = { repo.setEnabled(it) },
                icon = Icons.Outlined.Groups,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_multi_agent_max),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_multi_agent_max_subtitle, maxConcurrent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { repo.setMaxConcurrent(maxConcurrent - 1) },
                        enabled = enabled && maxConcurrent > MultiAgentSettings.MIN_CONCURRENT,
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.settings_multi_agent_decrease))
                    }
                    EditableStepperValue(
                        value = maxConcurrent,
                        min = MultiAgentSettings.MIN_CONCURRENT,
                        max = MultiAgentSettings.MAX_CONCURRENT,
                        enabled = enabled,
                        onValueChange = { repo.setMaxConcurrent(it) },
                    )
                    IconButton(
                        onClick = { repo.setMaxConcurrent(maxConcurrent + 1) },
                        enabled = enabled && maxConcurrent < MultiAgentSettings.MAX_CONCURRENT,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.settings_multi_agent_increase))
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        stringResource(R.string.settings_multi_agent_attempts),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_multi_agent_attempts_subtitle, subagentMaxAttempts),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { repo.setSubagentMaxAttempts(subagentMaxAttempts - 1) },
                        enabled = enabled && subagentMaxAttempts > MultiAgentSettings.MIN_SUBAGENT_ATTEMPTS,
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.settings_multi_agent_decrease))
                    }
                    EditableStepperValue(
                        value = subagentMaxAttempts,
                        min = MultiAgentSettings.MIN_SUBAGENT_ATTEMPTS,
                        max = MultiAgentSettings.MAX_SUBAGENT_ATTEMPTS,
                        enabled = enabled,
                        onValueChange = { repo.setSubagentMaxAttempts(it) },
                    )
                    IconButton(
                        onClick = { repo.setSubagentMaxAttempts(subagentMaxAttempts + 1) },
                        enabled = enabled && subagentMaxAttempts < MultiAgentSettings.MAX_SUBAGENT_ATTEMPTS,
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.settings_multi_agent_increase))
                    }
                }
            }
        }

        SettingsSection(
            header = stringResource(R.string.settings_multi_agent_section_models),
            footer = stringResource(R.string.settings_multi_agent_footer_models, maxConcurrent),
        ) {
            if (candidates.isEmpty()) {
                Text(
                    stringResource(R.string.settings_multi_agent_no_models),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            if (staleCount > 0) {
                Text(
                    stringResource(R.string.settings_multi_agent_stale_pruned, staleCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            slots.forEachIndexed { index, slotId ->
                SubAgentSlotRow(
                    index = index,
                    selectedId = slotId,
                    candidates = candidates,
                    instancesById = instancesById,
                    enabled = enabled && candidates.isNotEmpty(),
                    showDivider = index < slots.lastIndex,
                    onSelect = { repo.setSlotModel(index, it) },
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.settings_plan_discussion_section),
            footer = stringResource(R.string.settings_plan_discussion_footer),
        ) {
            val modes = listOf(
                PlanDiscussionPrefs.Mode.OFF to R.string.settings_plan_discussion_off,
                PlanDiscussionPrefs.Mode.AUTO to R.string.settings_plan_discussion_auto,
                PlanDiscussionPrefs.Mode.ALWAYS to R.string.settings_plan_discussion_always,
            )
            modes.forEachIndexed { index, (mode, titleRes) ->
                SettingsChoiceRow(
                    title = stringResource(titleRes),
                    selected = discussionMode == mode,
                    onSelect = {
                        discussionMode = mode
                        PlanDiscussionPrefs.setMode(context, mode)
                    },
                    showDivider = index < modes.lastIndex,
                )
            }
        }

        SettingsSection(
            header = stringResource(R.string.settings_collab_roles_section),
            footer = stringResource(R.string.settings_collab_roles_footer),
        ) {
            var editingRole by remember { mutableStateOf<com.openminis.app.tools.CollabRoles.Role?>(null) }
            var showAddDialog by remember { mutableStateOf(false) }
            val roles = com.openminis.app.tools.CollabRoles.allWithCustom(context)
            roles.forEachIndexed { index, role ->
                SettingsRow(
                    title = role.name + if (role.builtin) "" else " *",
                    subtitle = role.description +
                        " · 工具: " + role.tools.sorted().take(6).joinToString(),
                    showChevron = !role.builtin,
                    onClick = if (role.builtin) null else ({ editingRole = role }),
                    showDivider = index < roles.lastIndex,
                )
            }
            SettingsRow(
                title = "+ 新增角色",
                subtitle = "自定义名字/描述/提示词/工具白名单, 同名覆盖内置",
                showChevron = false,
                onClick = { showAddDialog = true },
                showDivider = false,
            )
            if (editingRole != null || showAddDialog) {
                CollabRoleEditDialog(
                    initial = editingRole,
                    onDismiss = { editingRole = null; showAddDialog = false },
                    onSave = { saved ->
                        val customs = com.openminis.app.tools.CollabRoles
                            .loadCustom(context).filter { it.name != saved.name }
                        com.openminis.app.tools.CollabRoles.saveCustom(context, customs + saved)
                        editingRole = null
                        showAddDialog = false
                    },
                    onDelete = if (editingRole?.builtin == false) ({
                        val target = editingRole!!
                        val customs = com.openminis.app.tools.CollabRoles
                            .loadCustom(context).filter { it.name != target.name }
                        com.openminis.app.tools.CollabRoles.saveCustom(context, customs)
                        editingRole = null
                    }) else null,
                )
            }
        }

        // [T-tool-limits-merge] Tool limits moved here from the removed
        // standalone settings entry (shell timeout / file_read caps /
        // sub-agent turns). ToolLimitPrefs is SharedPreferences-backed;
        // revision drives recomposition after +/- taps.
        val toolLimitRevision by ToolLimitPrefs.revision.collectAsState()
        SettingsSection(
            header = stringResource(R.string.tool_limits_header),
            footer = stringResource(R.string.tool_limits_footer),
        ) {
            val shell = remember(toolLimitRevision) { ToolLimitPrefs.shellTimeoutSec() }
            val chars = remember(toolLimitRevision) { ToolLimitPrefs.fileReadMaxChars() }
            val lines = remember(toolLimitRevision) { ToolLimitPrefs.fileReadMaxLines() }
            val turns = remember(toolLimitRevision) { ToolLimitPrefs.subagentMaxTurns() }
            LimitStepper(
                title = stringResource(R.string.tool_limits_shell),
                valueLabel = stringResource(R.string.tool_limits_shell_value, shell),
                value = shell,
                min = ToolLimitPrefs.MIN_SHELL_TIMEOUT_SEC,
                max = ToolLimitPrefs.MAX_SHELL_TIMEOUT_SEC,
                step = ToolLimitPrefs.SHELL_STEP_SEC,
                onChange = { ToolLimitPrefs.setShellTimeoutSec(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_file_chars),
                valueLabel = chars.toString(),
                value = chars,
                min = ToolLimitPrefs.MIN_FILE_READ_MAX_CHARS,
                max = ToolLimitPrefs.MAX_FILE_READ_MAX_CHARS,
                step = ToolLimitPrefs.FILE_CHARS_STEP,
                onChange = { ToolLimitPrefs.setFileReadMaxChars(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_file_lines),
                valueLabel = if (lines == 0) {
                    stringResource(R.string.tool_limits_file_lines_unlimited)
                } else {
                    lines.toString()
                },
                value = lines,
                min = 0,
                max = ToolLimitPrefs.MAX_FILE_READ_MAX_LINES,
                step = ToolLimitPrefs.FILE_LINES_STEP,
                onChange = { ToolLimitPrefs.setFileReadMaxLines(it) },
                showDivider = true,
            )
            LimitStepper(
                title = stringResource(R.string.tool_limits_subagent),
                valueLabel = turns.toString(),
                value = turns,
                min = ToolLimitPrefs.MIN_SUBAGENT_MAX_TURNS,
                max = ToolLimitPrefs.MAX_SUBAGENT_MAX_TURNS,
                step = ToolLimitPrefs.TURNS_STEP,
                onChange = { ToolLimitPrefs.setSubagentMaxTurns(it) },
                showDivider = false,
            )
        }

        val gate = com.openminis.app.security.SecurityGateHolder.gate
        var permMode by remember { mutableStateOf(gate.getPermissionMode()) }
        SettingsSection(
            header = "权限模式",
            footer = "ASK 默认询问写操作与高风险命令；ALLOW_ALL 自动放行；READ_ONLY / PLAN 只读；DENY_ALL 全拒。",
        ) {
            val modes = com.openminis.app.security.PermissionMode.entries
            modes.forEachIndexed { index, mode ->
                SettingsChoiceRow(
                    title = mode.name,
                    selected = permMode == mode,
                    onSelect = {
                        permMode = mode
                        com.openminis.app.security.SecurityGateHolder.setMode(context, mode)
                    },
                    showDivider = index < modes.lastIndex,
                )
            }
        }

        SettingsSection(
            header = "子代理类型",
            footer = "dispatch_agents 按类型名派发。内置探索者 / 审查员 / 编码员 / 研究员，各自有独立工具白名单。",
        ) {
            val types = com.openminis.app.tools.SubAgentTypeStore.load(context)
            types.forEachIndexed { index, type ->
                SettingsRow(
                    title = type.name,
                    subtitle = type.description.take(80) + " · " + type.toolNames.take(6).joinToString(),
                    showChevron = false,
                    showDivider = index < types.lastIndex,
                )
            }
        }
    }
}

@Composable
private fun SubAgentSlotRow(
    index: Int,
    selectedId: String,
    candidates: List<ModelEntry>,
    instancesById: Map<String, ProviderInstance>,
    enabled: Boolean,
    showDivider: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedEntry = candidates.find { it.id == selectedId }
    val value = selectedEntry?.let { slotModelLabel(it, instancesById) }
        ?: stringResource(R.string.settings_multi_agent_slot_main)
    Box(modifier = Modifier.fillMaxWidth()) {
        SettingsValueRow(
            title = stringResource(R.string.settings_multi_agent_slot, index + 1),
            value = value,
            onClick = if (enabled) ({ expanded = true }) else null,
            showDivider = showDivider,
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_multi_agent_slot_main)) },
                onClick = {
                    onSelect("")
                    expanded = false
                },
            )
            candidates.forEach { entry ->
                DropdownMenuItem(
                    text = { Text(slotModelLabel(entry, instancesById)) },
                    onClick = {
                        onSelect(entry.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun slotModelLabel(
    entry: ModelEntry,
    instancesById: Map<String, ProviderInstance>,
): String = buildString {
    append(entry.model.displayName)
    instancesById[entry.providerInstanceId]?.label?.takeIf { it.isNotBlank() }?.let {
        append(" · ").append(it)
    }
}

/**
 * A numeric stepper value that is ALSO directly editable: tapping the number
 * opens a small dialog with a numeric field. Fixes the "only +/- works, cannot
 * type a value" complaint. Out-of-range / non-numeric input disables Confirm.
 */
@Composable
private fun EditableStepperValue(
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    Text(
        value.toString(),
        style = MaterialTheme.typography.titleMedium,
        color = if (enabled) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .clickable(enabled = enabled) { editing = true },
    )
    if (editing) {
        var text by remember { mutableStateOf(value.toString()) }
        val parsed = text.trim().toIntOrNull()
        val valid = parsed != null && parsed in min..max
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.settings_multi_agent_enter_value)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.settings_multi_agent_value_range, min, max)) },
                    isError = text.isNotEmpty() && !valid,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        parsed?.let { onValueChange(it) }
                        editing = false
                    },
                    enabled = valid,
                ) { Text(stringResource(R.string.settings_multi_agent_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) {
                    Text(stringResource(R.string.settings_multi_agent_cancel))
                }
            },
        )
    }
}

/**
 * [T-tool-limits-merge] +/- stepper for a numeric tool limit. Moved from the
 * removed ToolLimitsSettingsScreen.
 */
@Composable
private fun LimitStepper(
    title: String,
    valueLabel: String,
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    onChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    SettingsRow(
        title = title,
        subtitle = valueLabel,
        showChevron = false,
        showDivider = showDivider,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onChange((value - step).coerceAtLeast(min)) },
                    enabled = value > min,
                ) {
                    Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.tool_limits_decrease))
                }
                IconButton(
                    onClick = { onChange((value + step).coerceAtMost(max)) },
                    enabled = value < max,
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.tool_limits_increase))
                }
            }
        },
    )

@Composable
private fun CollabRoleEditDialog(
    initial: com.openminis.app.tools.CollabRoles.Role?,
    onDismiss: () -> Unit,
    onSave: (com.openminis.app.tools.CollabRoles.Role) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val builtin = initial?.builtin == true
    // builtin roles are copied into a custom draft on tap; customs edit in place
    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var description by remember(initial) { mutableStateOf(initial?.description ?: "") }
    var prompt by remember(initial) { mutableStateOf(initial?.prompt ?: "") }
    var toolsCsv by remember(initial) { mutableStateOf(initial?.tools?.sorted()?.joinToString() ?: "") }
    val valid = name.isNotBlank() && prompt.isNotBlank()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (builtin) "内置角色 (保存为自定义副本)" else if (initial == null) "新增协作角色" else "编辑角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("角色名 (spawn_agent 的 role)") },
                    singleLine = true,
                    readOnly = builtin,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("一句话描述") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text("角色提示词 (盯着/不管/该找谁/闭嘴)") },
                    minLines = 4,
                    maxLines = 8,
                )
                OutlinedTextField(
                    value = toolsCsv,
                    onValueChange = { toolsCsv = it },
                    label = { Text("工具白名单 (逗号分隔, 空=不限)") },
                    minLines = 2,
                    maxLines = 3,
                )
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text("删除此角色", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val toolSet = toolsCsv.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                onSave(
                    com.openminis.app.tools.CollabRoles.Role(
                        name = name.trim(),
                        description = description.trim(),
                        prompt = prompt.trim(),
                        tools = if (toolSet.isEmpty()) com.openminis.app.tools.CollabRoles.ALL.first().tools else toolSet,
                        builtin = false,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
}
