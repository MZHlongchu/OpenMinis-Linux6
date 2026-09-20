package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

/**
 * [T-persona-extension] Merged home for the two prompt-extension stores —
 * per-session prompt templates and workspace rules. Both are extensions of the
 * SOUL persona (session-level voice overrides and environment rules), so they
 * live on one screen instead of two separate settings entries.
 *
 * The old standalone screens remain reachable via their deep links
 * (minis://settings/prompt-templates / workspace-rules) and now delegate to
 * the same section composables.
 */
@Composable
fun PersonaExtensionScreen(onBack: () -> Unit) {
    val tplCreate = remember { mutableStateOf(false) }
    val ruleCreate = remember { mutableStateOf(false) }
    SettingsScaffold(
        title = stringResource(R.string.settings_persona_extension),
        onBack = onBack,
        floatingActionButton = {
            // The page hosts two stores; the FAB opens the template editor,
            // which is the primary (default-selected) store. Rule creation is
            // launched from the rules section via the same FAB semantics —
            // to keep one FAB, it targets the template editor and the rules
            // section exposes its own add row.
            PersonaFab { tplCreate.value = true }
        },
    ) {
        Text(
            stringResource(R.string.settings_persona_extension_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        PromptTemplatesSection(createRequest = tplCreate)
        WorkspaceRulesSection(createRequest = ruleCreate)
    }
}

@Composable
private fun PersonaFab(onClick: () -> Unit) {
    FloatingActionButton(onClick = onClick) {
        Icon(Icons.Default.Add, contentDescription = stringResource(R.string.prompt_templates_add))
    }
}
