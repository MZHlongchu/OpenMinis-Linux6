package com.openminis.app.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.openminis.app.R
import com.openminis.app.data.PromptTemplate
import com.openminis.app.data.PromptTemplateCodec
import com.openminis.app.data.PromptTemplateStore
import com.openminis.app.ui.components.DialogTextField

@Composable
fun PromptTemplatesSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val revision by PromptTemplateStore.revision.collectAsState()
    val state = remember(revision) { PromptTemplateStore.state() }
    var editing by remember { mutableStateOf<PromptTemplate?>(null) }
    var creating by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.prompt_templates_title),
        onBack = onBack,
        floatingActionButton = {
            FloatingActionButton(onClick = { creating = true }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.prompt_templates_add))
            }
        },
    ) {
        SettingsSection(
            header = stringResource(R.string.prompt_templates_header),
            footer = stringResource(R.string.prompt_templates_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.prompt_templates_none),
                subtitle = stringResource(R.string.prompt_templates_default),
                showChevron = false,
                showDivider = state.templates.isNotEmpty(),
                trailing = {
                    RadioButton(
                        selected = state.defaultTemplate.isBlank(),
                        onClick = { PromptTemplateStore.setDefault(PromptTemplateCodec.NONE) },
                    )
                },
                onClick = { PromptTemplateStore.setDefault(PromptTemplateCodec.NONE) },
            )
            if (state.templates.isEmpty()) {
                SettingsRow(
                    title = stringResource(R.string.prompt_templates_empty),
                    showChevron = false,
                    showDivider = false,
                )
            } else {
                state.templates.forEachIndexed { index, tpl ->
                    SettingsRow(
                        title = tpl.name,
                        subtitle = "${tpl.text.length} · ${tpl.id}",
                        showChevron = false,
                        showDivider = index < state.templates.lastIndex,
                        onClick = { editing = tpl },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = state.defaultTemplate == tpl.id,
                                    onClick = { PromptTemplateStore.setDefault(tpl.id) },
                                )
                                IconButton(onClick = { PromptTemplateStore.delete(tpl.id) }) {
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
        TemplateEditorDialog(
            initialName = "",
            initialText = "",
            onDismiss = { creating = false },
            onSave = { name, text ->
                when (val result = PromptTemplateStore.save(name, text)) {
                    is PromptTemplateStore.SaveResult.Ok -> creating = false
                    is PromptTemplateStore.SaveResult.Error -> {
                        val msg = when (result.code) {
                            "unsafe" -> context.getString(R.string.prompt_templates_rejected)
                            "too_long" -> context.getString(R.string.prompt_templates_too_long, PromptTemplateCodec.MAX_TEXT)
                            else -> context.getString(R.string.prompt_templates_rejected)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
    editing?.let { tpl ->
        TemplateEditorDialog(
            initialName = tpl.name,
            initialText = tpl.text,
            onDismiss = { editing = null },
            onSave = { name, text ->
                when (val result = PromptTemplateStore.save(name, text, tpl.order, tpl.id)) {
                    is PromptTemplateStore.SaveResult.Ok -> editing = null
                    is PromptTemplateStore.SaveResult.Error -> {
                        val msg = when (result.code) {
                            "unsafe" -> context.getString(R.string.prompt_templates_rejected)
                            "too_long" -> context.getString(R.string.prompt_templates_too_long, PromptTemplateCodec.MAX_TEXT)
                            else -> context.getString(R.string.prompt_templates_rejected)
                        }
                        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptTemplatePickerSheet(
    sessionId: String,
    onDismiss: () -> Unit,
) {
    val revision by PromptTemplateStore.revision.collectAsState()
    val state = remember(revision) { PromptTemplateStore.state() }
    val current = PromptTemplateCodec.templateForSession(state, sessionId)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.prompt_templates_picker_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            SettingsRow(
                title = stringResource(R.string.prompt_templates_none),
                showChevron = false,
                showDivider = state.templates.isNotEmpty(),
                trailing = {
                    RadioButton(
                        selected = current == null,
                        onClick = {
                            PromptTemplateStore.applyToSession(sessionId, PromptTemplateCodec.NONE)
                            onDismiss()
                        },
                    )
                },
                onClick = {
                    PromptTemplateStore.applyToSession(sessionId, PromptTemplateCodec.NONE)
                    onDismiss()
                },
            )
            state.templates.forEachIndexed { index, tpl ->
                SettingsRow(
                    title = tpl.name,
                    subtitle = if (current?.id == tpl.id) stringResource(R.string.prompt_templates_current) else null,
                    showChevron = false,
                    showDivider = index < state.templates.lastIndex,
                    trailing = {
                        RadioButton(
                            selected = current?.id == tpl.id,
                            onClick = {
                                PromptTemplateStore.applyToSession(sessionId, tpl.id)
                                onDismiss()
                            },
                        )
                    },
                    onClick = {
                        PromptTemplateStore.applyToSession(sessionId, tpl.id)
                        onDismiss()
                    },
                )
            }
            Text(
                stringResource(R.string.prompt_templates_picker_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun TemplateEditorDialog(
    initialName: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var text by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.prompt_templates_add)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.prompt_templates_name), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                DialogTextField(value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.prompt_templates_body), style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(6.dp))
                DialogTextField(
                    value = text,
                    onValueChange = { text = it.take(PromptTemplateCodec.MAX_TEXT) },
                    singleLine = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, text) }, enabled = name.isNotBlank() && text.isNotBlank()) {
                Text(stringResource(R.string.prompt_templates_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}
