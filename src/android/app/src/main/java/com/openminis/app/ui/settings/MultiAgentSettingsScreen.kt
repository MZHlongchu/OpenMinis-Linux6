package com.openminis.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.Checkbox
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
    val liveSelectedIds = selectedIds.filter { it in candidateIds }
    val staleCount = selectedIds.size - liveSelectedIds.size
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
            } else {
                if (staleCount > 0) {
                    Text(
                        stringResource(R.string.settings_multi_agent_stale_pruned, staleCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                candidates.forEachIndexed { index, entry ->
                    val instance = instancesById[entry.providerInstanceId]
                    val selected = entry.id in liveSelectedIds
                    val atCap = !selected && liveSelectedIds.size >= maxConcurrent
                    val rowEnabled = enabled && (selected || !atCap)
                    val label = buildString {
                        append(entry.model.displayName)
                        instance?.label?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = rowEnabled) {
                                repo.toggleModelEntry(entry.id, candidateIds)
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            enabled = rowEnabled,
                        )
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                entry.model.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (index == candidates.lastIndex) {
                        // last row — no extra divider needed; SettingsSection cards handle it
                    }
                }
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
