package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun RootPassThroughScreen(onBack: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(RootPassThroughSettings.isEnabled()) }
    var auditLog by remember { mutableStateOf("") }

    SettingsScaffold(
        title = stringResource(R.string.root_passthrough_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsSection(
                header = stringResource(R.string.root_passthrough_risk_header),
                footer = stringResource(R.string.root_passthrough_risk_footer),
            ) {
                SettingsSwitchRow(
                    icon = Icons.Filled.Lock,
                    iconColor = MaterialTheme.colorScheme.error,
                    title = stringResource(R.string.root_passthrough_enable),
                    subtitle = stringResource(R.string.root_passthrough_enable_subtitle),
                    checked = enabled,
                    onCheckedChange = { new ->
                        enabled = new
                        scope.launch {
                            RootPassThroughSettings.setEnabled(context, new)
                        }
                    },
                )
            }

            SettingsSection(
                header = stringResource(R.string.root_passthrough_audit_header),
                footer = stringResource(R.string.root_passthrough_audit_desc),
            ) {
                OutlinedButton(
                    onClick = {
                        val auditDir = java.io.File(context.filesDir, "audit")
                        val files = auditDir.listFiles { f -> f.name.startsWith("root-") && f.name.endsWith(".log") }
                        auditLog = if (files != null && files.isNotEmpty()) {
                            files.sortedByDescending { it.lastModified() }.take(5)
                                .joinToString("\n\n") { f ->
                                    "=== ${f.name} ===\n" + f.readLines().takeLast(10).joinToString("\n")
                                }
                        } else {
                            context.getString(R.string.root_passthrough_no_audit)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.root_passthrough_view_audit))
                }
                Spacer(modifier = Modifier.height(8.dp))
                if (auditLog.isNotEmpty()) {
                    Text(
                        text = auditLog,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(
                    onClick = {
                        val auditDir = java.io.File(context.filesDir, "audit")
                        auditDir.listFiles { f -> f.name.startsWith("root-") && f.name.endsWith(".log") }
                            ?.forEach { it.delete() }
                        auditLog = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.root_passthrough_clear_audit))
                }
            }
        }
    }
}
