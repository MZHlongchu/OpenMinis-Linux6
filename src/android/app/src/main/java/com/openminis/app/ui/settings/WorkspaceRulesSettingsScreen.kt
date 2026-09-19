package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.agent.WorkspaceRule
import com.openminis.app.agent.WorkspaceRulesStore
import com.openminis.app.ui.components.DialogTextField

@Composable
fun WorkspaceRulesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision by WorkspaceRulesStore.revision.collectAsState()
    val rules = remember(revision) { WorkspaceRulesStore.list() }
    val active = remember(revision) { WorkspaceRulesStore.activeIds() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<WorkspaceRule?>(null) }

    SettingsScaffold(
        title = stringResource(R.string.workspace_rules_title),
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.workspace_rules_add))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.workspace_rules_header),
            footer = stringResource(R.string.workspace_rules_footer),
        ) {
            if (rules.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.workspace_rules_empty),
                    showChevron = false,
                    showDivider = false,
                )
            } else {
                rules.forEachIndexed { index, rule ->
                    val checked = rule.id in active
                    SettingsRow(
                        title = rule.name,
                        subtitle = rule.body.take(80).ifBlank { rule.id },
                        showChevron = false,
                        showDivider = index < rules.lastIndex,
                        onClick = { editing = rule },
                        trailing = {
                            androidx.compose.foundation.layout.Row(
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                            ) {
                                androidx.compose.material3.Switch(
                                    checked = checked,
                                    onCheckedChange = { WorkspaceRulesStore.setActive(rule.id, it) },
                                )
                                IconButton(onClick = { WorkspaceRulesStore.delete(rule.id) }) {
                                    Icon(
                                        Icons.Outlined.Delete,
                                        contentDescription = stringResource(R.string.prompt_templates_delete),
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (creating) {
        RuleEditorDialog(
            initialName = "",
            initialBody = "",
            onDismiss = { creating = false },
            onSave = { name, body ->
                when (val result = WorkspaceRulesStore.save(name, body)) {
                    is WorkspaceRulesStore.SaveResult.Ok -> {
                        WorkspaceRulesStore.setActive(result.rule.id, true)
                        creating = false
                    }
                    is WorkspaceRulesStore.SaveResult.Error -> toastError(context, result.code)
                }
            },
        )
    }
    editing?.let { rule ->
        RuleEditorDialog(
            initialName = rule.name,
            initialBody = rule.body,
            onDismiss = { editing = null },
            onSave = { name, body ->
                when (val result = WorkspaceRulesStore.save(name, body, rule.id)) {
                    is WorkspaceRulesStore.SaveResult.Ok -> editing = null
                    is WorkspaceRulesStore.SaveResult.Error -> toastError(context, result.code)
                }
            },
        )
    }
}

private fun toastError(context: android.content.Context, code: String) {
    val msg = when (code) {
        "unsafe" -> context.getString(R.string.workspace_rules_rejected)
        "too_long" -> context.getString(R.string.prompt_templates_too_long, WorkspaceRulesStore.MAX_BODY)
        else -> context.getString(R.string.workspace_rules_rejected)
    }
    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
}

@Composable
private fun RuleEditorDialog(
    initialName: String,
    initialBody: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var body by remember { mutableStateOf(initialBody) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.workspace_rules_add)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.workspace_rules_name), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                DialogTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.workspace_rules_body), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                DialogTextField(
                    value = body,
                    onValueChange = { body = it.take(WorkspaceRulesStore.MAX_BODY) },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, body) }, enabled = name.isNotBlank() && body.isNotBlank()) {
                Text(stringResource(R.string.prompt_templates_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
