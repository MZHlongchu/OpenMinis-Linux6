package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.openminis.app.MinisApp
import com.openminis.app.R
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
    val subagentMaxTurns by repo.subagentMaxTurns.collectAsState()
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
                    Text(
                        maxConcurrent.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
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
                        stringResource(R.string.settings_multi_agent_turns),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.settings_multi_agent_turns_subtitle, subagentMaxTurns),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { repo.setSubagentMaxTurns(subagentMaxTurns - 1) },
                        enabled = enabled && subagentMaxTurns > MultiAgentSettings.MIN_SUBAGENT_TURNS,
                    ) {
                        Icon(Icons.Outlined.Remove, contentDescription = stringResource(R.string.settings_multi_agent_decrease))
                    }
                    Text(
                        subagentMaxTurns.toString(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    IconButton(
                        onClick = { repo.setSubagentMaxTurns(subagentMaxTurns + 1) },
                        enabled = enabled && subagentMaxTurns < MultiAgentSettings.MAX_SUBAGENT_TURNS,
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
