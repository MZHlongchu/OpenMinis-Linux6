package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.tools.WebSearchSettings
import com.openminis.app.ui.components.DialogTextField

@Composable
fun WebSearchSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var engine by remember { mutableStateOf(WebSearchSettings.engine(context)) }
    var searx by remember { mutableStateOf(WebSearchSettings.searxngUrl(context)) }
    var bing by remember { mutableStateOf(WebSearchSettings.bingKey(context)) }
    var fallback by remember { mutableStateOf(WebSearchSettings.fallbackEnabled(context)) }

    SettingsScaffold(title = stringResource(R.string.settings_web_search), onBack = onBack) {
        SettingsSection(
            header = stringResource(R.string.web_search_engine_header),
            footer = stringResource(R.string.web_search_engine_footer),
        ) {
            EngineRow(
                title = stringResource(R.string.web_search_engine_ddg),
                selected = engine == WebSearchSettings.Engine.DDG,
                showDivider = true,
            ) {
                engine = WebSearchSettings.Engine.DDG
                WebSearchSettings.setEngine(context, engine)
            }
            EngineRow(
                title = stringResource(R.string.web_search_engine_searxng),
                selected = engine == WebSearchSettings.Engine.SEARXNG,
                showDivider = true,
            ) {
                engine = WebSearchSettings.Engine.SEARXNG
                WebSearchSettings.setEngine(context, engine)
            }
            EngineRow(
                title = stringResource(R.string.web_search_engine_bing),
                selected = engine == WebSearchSettings.Engine.BING,
                showDivider = false,
            ) {
                engine = WebSearchSettings.Engine.BING
                WebSearchSettings.setEngine(context, engine)
            }
        }

        SettingsSection(header = stringResource(R.string.web_search_credentials_header)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    stringResource(R.string.web_search_searxng_url),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogTextField(
                    value = searx,
                    onValueChange = {
                        searx = it
                        WebSearchSettings.setSearxngUrl(context, it)
                    },
                    placeholder = "https://searx.example/search",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 12.dp),
                )
                Text(
                    stringResource(R.string.web_search_bing_key),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DialogTextField(
                    value = bing,
                    onValueChange = {
                        bing = it
                        WebSearchSettings.setBingKey(context, it)
                    },
                    placeholder = "Ocp-Apim-Subscription-Key",
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }

        SettingsSection(footer = stringResource(R.string.web_search_fallback_footer)) {
            SettingsSwitchRow(
                title = stringResource(R.string.web_search_fallback),
                subtitle = stringResource(R.string.web_search_fallback_sub),
                checked = fallback,
                onCheckedChange = {
                    fallback = it
                    WebSearchSettings.setFallbackEnabled(context, it)
                },
                showDivider = false,
            )
        }
    }
}

@Composable
private fun EngineRow(
    title: String,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        showChevron = false,
        showDivider = showDivider,
        onClick = onClick,
        trailing = { RadioButton(selected = selected, onClick = onClick) },
    )
}
