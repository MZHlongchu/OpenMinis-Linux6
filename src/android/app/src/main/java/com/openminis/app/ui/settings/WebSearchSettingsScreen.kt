package com.openminis.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
    var customUrl by remember { mutableStateOf(WebSearchSettings.customUrl(context)) }
    var customKey by remember { mutableStateOf(WebSearchSettings.customKey(context)) }
    var customHeader by remember { mutableStateOf(WebSearchSettings.customKeyHeader(context)) }
    var fallback by remember { mutableStateOf(WebSearchSettings.fallbackEnabled(context)) }
    var detail by remember { mutableStateOf<WebSearchSettings.Engine?>(null) }

    BackHandler(enabled = detail != null) { detail = null }

    val title = when (detail) {
        null -> stringResource(R.string.settings_web_search)
        WebSearchSettings.Engine.DDG -> stringResource(R.string.web_search_engine_ddg)
        WebSearchSettings.Engine.SEARXNG -> stringResource(R.string.web_search_engine_searxng)
        WebSearchSettings.Engine.BING -> stringResource(R.string.web_search_engine_bing)
        WebSearchSettings.Engine.CUSTOM -> stringResource(R.string.web_search_engine_custom)
    }

    SettingsScaffold(
        title = title,
        onBack = { if (detail != null) detail = null else onBack() },
    ) {
        val current = detail
        if (current == null) {
            SettingsSection(
                header = stringResource(R.string.web_search_engine_header),
                footer = stringResource(R.string.web_search_engine_footer),
            ) {
                EngineNavRow(
                    title = stringResource(R.string.web_search_engine_ddg),
                    subtitle = stringResource(R.string.web_search_no_key_needed),
                    selected = engine == WebSearchSettings.Engine.DDG,
                    showDivider = true,
                    onClick = { detail = WebSearchSettings.Engine.DDG },
                )
                EngineNavRow(
                    title = stringResource(R.string.web_search_engine_searxng),
                    subtitle = if (searx.isBlank()) {
                        stringResource(R.string.web_search_not_configured)
                    } else {
                        searx
                    },
                    selected = engine == WebSearchSettings.Engine.SEARXNG,
                    showDivider = true,
                    onClick = { detail = WebSearchSettings.Engine.SEARXNG },
                )
                EngineNavRow(
                    title = stringResource(R.string.web_search_engine_bing),
                    subtitle = if (bing.isBlank()) {
                        stringResource(R.string.web_search_not_configured)
                    } else {
                        stringResource(R.string.web_search_key_saved)
                    },
                    selected = engine == WebSearchSettings.Engine.BING,
                    showDivider = true,
                    onClick = { detail = WebSearchSettings.Engine.BING },
                )
                EngineNavRow(
                    title = stringResource(R.string.web_search_engine_custom),
                    subtitle = if (customUrl.isBlank()) {
                        stringResource(R.string.web_search_not_configured)
                    } else {
                        customUrl
                    },
                    selected = engine == WebSearchSettings.Engine.CUSTOM,
                    showDivider = false,
                    onClick = { detail = WebSearchSettings.Engine.CUSTOM },
                )
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
        } else {
            SettingsSection(footer = engineDetailFooter(current)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.web_search_use_engine),
                    checked = engine == current,
                    onCheckedChange = { on ->
                        if (on) {
                            engine = current
                            WebSearchSettings.setEngine(context, current)
                        }
                    },
                    showDivider = current != WebSearchSettings.Engine.DDG,
                )
                when (current) {
                    WebSearchSettings.Engine.DDG -> { }
                    WebSearchSettings.Engine.SEARXNG -> CredentialField(
                        label = stringResource(R.string.web_search_searxng_url),
                        value = searx,
                        placeholder = "https://searx.example/search",
                    ) {
                        searx = it
                        WebSearchSettings.setSearxngUrl(context, it)
                    }
                    WebSearchSettings.Engine.BING -> CredentialField(
                        label = stringResource(R.string.web_search_bing_key),
                        value = bing,
                        placeholder = "Ocp-Apim-Subscription-Key",
                    ) {
                        bing = it
                        WebSearchSettings.setBingKey(context, it)
                    }
                    WebSearchSettings.Engine.CUSTOM -> {
                        CredentialField(
                            label = stringResource(R.string.web_search_custom_url),
                            value = customUrl,
                            placeholder = "https://example.com/search?q={query}",
                        ) {
                            customUrl = it
                            WebSearchSettings.setCustomUrl(context, it)
                        }
                        CredentialField(
                            label = stringResource(R.string.web_search_custom_key),
                            value = customKey,
                            placeholder = stringResource(R.string.web_search_custom_key_placeholder),
                        ) {
                            customKey = it
                            WebSearchSettings.setCustomKey(context, it)
                        }
                        CredentialField(
                            label = stringResource(R.string.web_search_custom_key_header),
                            value = customHeader,
                            placeholder = "Authorization",
                        ) {
                            customHeader = it
                            WebSearchSettings.setCustomKeyHeader(context, it)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun engineDetailFooter(engine: WebSearchSettings.Engine): String = when (engine) {
    WebSearchSettings.Engine.DDG -> stringResource(R.string.web_search_ddg_detail)
    WebSearchSettings.Engine.SEARXNG -> stringResource(R.string.web_search_searxng_detail)
    WebSearchSettings.Engine.BING -> stringResource(R.string.web_search_bing_detail)
    WebSearchSettings.Engine.CUSTOM -> stringResource(R.string.web_search_custom_detail)
}

@Composable
private fun EngineNavRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    showDivider: Boolean,
    onClick: () -> Unit,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        showChevron = true,
        showDivider = showDivider,
        onClick = onClick,
        trailing = {
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.web_search_in_use),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

@Composable
private fun CredentialField(
    label: String,
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
    }
}
