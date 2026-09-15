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
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.sandbox.SandboxProfile
import com.openminis.app.sandbox.SandboxSettings

@Composable
fun ToolchainScreen(onBack: () -> Unit) {
    val profile = SandboxSettings.currentProfile()
    if (profile !is SandboxProfile.Devstack) {
        SettingsScaffold(
            title = stringResource(R.string.toolchain_title),
            onBack = onBack,
        ) {
            SettingsSection(
                header = stringResource(R.string.toolchain_devstack_only_header),
                footer = stringResource(R.string.toolchain_devstack_only_footer),
            ) {
                SettingsRow(
                    title = stringResource(R.string.toolchain_devstack_only_header),
                    subtitle = stringResource(R.string.toolchain_devstack_only_footer),
                    icon = Icons.Outlined.Info,
                    iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showChevron = false,
                )
            }
        }
        return
    }

    var installing by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.toolchain_title),
        onBack = onBack,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                header = stringResource(R.string.toolchain_section_header),
                footer = stringResource(R.string.toolchain_section_footer),
            ) {
                SettingsRow(
                    title = stringResource(R.string.toolchain_install_all),
                    subtitle = stringResource(R.string.toolchain_install_all_desc),
                    icon = Icons.Outlined.Build,
                    iconColor = MaterialTheme.colorScheme.primary,
                    showChevron = false,
                    trailing = {
                        OutlinedButton(
                            onClick = { installing = true },
                            enabled = !installing,
                        ) {
                            Text(if (installing) stringResource(R.string.toolchain_installing) else stringResource(R.string.toolchain_install))
                        }
                    },
                )
            }

            SettingsSection(
                header = stringResource(R.string.toolchain_check_header),
            ) {
                SettingsRow(
                    title = stringResource(R.string.toolchain_check_label),
                    subtitle = stringResource(R.string.toolchain_check_desc),
                    showChevron = false,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.toolchain_check_all))
                }
            }
        }
    }
}
