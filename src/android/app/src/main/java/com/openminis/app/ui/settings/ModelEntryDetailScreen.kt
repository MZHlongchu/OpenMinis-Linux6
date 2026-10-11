package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.applyUnrecognizedModelDefaults
import com.openminis.app.data.model.effectiveInputModalities
import com.openminis.app.data.model.effectiveOutputModalities
import com.openminis.app.data.model.normalizeModalityName
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.ui.components.RowLabel
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.R
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton

/**
 * Detail / edit screen for a single ModelEntry. T210: brought to iOS
 * parity with five inset-grouped sections — Identity, Capabilities,
 * Visibility, Input Modality, Output Modality (+ optional Reset). The
 * data layer (`ModelOverrides.contextWindow`, `supportsReasoning`,
 * `inputModalities`, `outputModalities`) was already in place; pre-T210
 * the UI only exposed displayName / maxOutputTokens / hidden, which
 * meant Android users had no way to override the things iOS lets them
 * tweak (context window, thinking flag, per-modality enablement).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEntryDetailScreen(
    instanceId: String,
    entryId: String,
    providerRepository: ProviderRepository,
    onBack: () -> Unit,
) {
    val config by providerRepository.config.collectAsState()
    val entry = config.modelEntries.find { it.id == entryId && it.providerInstanceId == instanceId }
    val instance = config.instances.find { it.id == instanceId }

    if (entry == null) {
        onBack()
        return
    }

    val baseModel = entry.baseModel
    val overrides = entry.overrides
    val defaulted = remember(baseModel) { applyUnrecognizedModelDefaults(baseModel) }

    var modelId by remember { mutableStateOf(baseModel.id) }
    var displayName by remember { mutableStateOf(overrides.displayName ?: baseModel.displayName) }
    var displayNameTouched by remember { mutableStateOf(false) }
    var maxOutputTokensText by remember { mutableStateOf(overrides.maxOutputTokens?.toString() ?: "") }
    var contextWindowText by remember { mutableStateOf(overrides.contextWindow?.toString() ?: "") }
    var thinkingEnabled by remember {
        mutableStateOf(overrides.supportsReasoning ?: defaulted.supportsReasoning ?: true)
    }
    // [T-model-override-silent-drop] "Did the user touch this control?" — the
    // only thing that distinguishes a deliberate choice from an untouched
    // default on a control with no empty state. See the save block below.
    var thinkingTouched by remember { mutableStateOf(false) }
    var isHidden by remember { mutableStateOf(entry.isHidden) }
    var showQuickTest by remember { mutableStateOf(false) }

    // Modality state — derive from override-or-base so the toggles reflect
    // what the model actually supports today, then let the user flip from
    // there. A null override means "inherit baseModel" (data-layer
    // contract); on save we only persist a non-null list when it diverges
    // from the inherited set.
    // Defensive normalization: pre-T213 SharedPreferences may hold suffixed strings
    // (`image_input`) saved before the parse-boundary fix, which would silently fail
    // the bare-name `"image" in effectiveInput` checks below.
    // [T-android-modality-provider-fallback] Fall back to the provider default
    // when the model declares nothing, so a model models.dev has not catalogued
    // yet (Fable 5.1 on its release day) shows its real capabilities instead of
    // every switch OFF. Precedence is unchanged above that: a user override
    // still wins, then the model's own list, and only then the provider table.
    val effectiveInput = (
        overrides.inputModalities
            ?: baseModel.effectiveInputModalities
            ?: emptyList()
        ).map { it.normalizeModalityName() }
    val effectiveOutput = (
        overrides.outputModalities
            ?: baseModel.effectiveOutputModalities
            ?: emptyList()
        ).map { it.normalizeModalityName() }
    var imageInput by remember { mutableStateOf("image" in effectiveInput) }
    var pdfInput by remember { mutableStateOf("pdf" in effectiveInput) }
    var audioInput by remember { mutableStateOf("audio" in effectiveInput) }
    var videoInput by remember { mutableStateOf("video" in effectiveInput) }
    var imageOutput by remember { mutableStateOf("image" in effectiveOutput) }
    var audioOutput by remember { mutableStateOf("audio" in effectiveOutput) }
    var videoOutput by remember { mutableStateOf("video" in effectiveOutput) }
    // One flag per dimension, not per switch: the override is a LIST covering
    // the whole dimension, so touching any one switch is a statement about the
    // whole set and the entire list must then be recorded. (upstream 1.14)
    var inputModalitiesTouched by remember { mutableStateOf(false) }
    var outputModalitiesTouched by remember { mutableStateOf(false) }
    // [T-android-model-tuning-per-entry] 我们这一侧的逐模型调参（自动压缩 /
    // 压缩阈值 / 重试次数 / 温度）。null 是“继承全局”的持久化标记，
    // 所以“跟随全局”开关单独用 *Inherited 记录，保存时绝不把全局现值写死进模型。
    val globalAutoCompactOn = com.openminis.app.data.AutoCompactPrefs.isEnabled()
    val globalCompactPercent = com.openminis.app.data.AutoCompactPrefs.thresholdPercent()
    var autoCompactInherited by remember { mutableStateOf(overrides.autoCompactEnabled == null) }
    var autoCompactOn by remember {
        mutableStateOf(overrides.autoCompactEnabled ?: globalAutoCompactOn)
    }
    var compactPercentInherited by remember { mutableStateOf(overrides.compactThresholdPercent == null) }
    var compactPercentText by remember {
        mutableStateOf(
            (overrides.compactThresholdPercent ?: globalCompactPercent).toString(),
        )
    }
    var maxRetriesText by remember {
        mutableStateOf(
            (overrides.maxRetries
                ?: com.openminis.app.harness.agent.HttpRetryAfter.DEFAULT_MAX_RETRIES).toString(),
        )
    }
    var temperatureText by remember {
        mutableStateOf(overrides.temperature?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty())
    }
    var temperatureError by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.model_entry_model_detail),
        // [T-android-modeldetail-savecancel-ios-parity] iOS modal pattern
        // (ProviderInstanceDetailView toolbar): Cancel is a plain leading
        // text action, the title is centered, and Save is the single
        // emphasized trailing action — the filled pill is the MD3 analog of
        // iOS's semibold Save. No back arrow: Cancel and system back both
        // discard + pop.
        onBack = null,
        navigation = {
            MinisTextButton(
                onClick = onBack,
                modifier = Modifier.padding(start = 8.dp),
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) { Text(stringResource(R.string.common_cancel)) }
        },
        actions = {
            MinisButton(
                onClick = {
                    // No baseline is computed here any more. It used to be
                    // `baseModel.effective*Modalities`, and [T-android-modality-provider-fallback]
                    // had already had to move it once — from the raw lists to the
                    // provider-fallback ones — because opening an un-catalogued model
                    // and pressing Save untouched compared {image,pdf} against {} and
                    // persisted a pointless override, freezing the entry so it no
                    // longer tracked the real modalities. The touched flag removes the
                    // comparison altogether, so that class of mismatch cannot recur:
                    // an untouched Save now writes nothing regardless of which
                    // baseline would have been chosen.
                    val newInputs = buildList {
                        if (imageInput) add("image")
                        if (pdfInput) add("pdf")
                        if (audioInput) add("audio")
                        if (videoInput) add("video")
                    }
                    val newOutputs = buildList {
                        if (imageOutput) add("image")
                        if (audioOutput) add("audio")
                        if (videoOutput) add("video")
                    }
                    // [T-android-sampling-input-gate] 温度非法时先挡住保存，
                    // 不给用户“已保存”的假象（NaN/Infinity/越界都不落库）。
                    val parsedTemperature = com.openminis.app.provider.SamplingPolicy.parseUserInput(temperatureText)
                    if (temperatureText.isNotBlank() && parsedTemperature == null) {
                        temperatureError = true
                        return@MinisButton
                    }
                    temperatureError = false
                    // 上游 1.14 的 touched 语义（未触碰的开关既不新增也不清空 override）
                    // + 我们这一侧的调参字段；两者合并进同一个 helper，避免出现
                    // “两条保存路径各写一半字段”的静默丢字段回归。
                    val newOverrides = buildModelOverrides(
                        existing = overrides,
                        displayNameText = displayName,
                        displayNameTouched = displayNameTouched,
                        maxOutputTokensText = maxOutputTokensText,
                        contextWindowText = contextWindowText,
                        thinkingEnabled = thinkingEnabled,
                        thinkingTouched = thinkingTouched,
                        inputModalities = newInputs,
                        inputModalitiesTouched = inputModalitiesTouched,
                        outputModalities = newOutputs,
                        outputModalitiesTouched = outputModalitiesTouched,
                        // 我们独有的四个旋钮：空/继承 → null（保持“跟随全局”），
                        // 越界值按契约收敛（阈值 50–95，重试 0–8）。
                        autoCompactEnabled = autoCompactOn.takeUnless { autoCompactInherited },
                        compactThresholdPercent = compactPercentText.trim().toIntOrNull()?.coerceIn(50, 95)
                            ?.takeUnless { compactPercentInherited },
                        maxRetries = maxRetriesText.trim().toIntOrNull()?.coerceIn(0, 8)
                            ?: com.openminis.app.harness.agent.HttpRetryAfter.DEFAULT_MAX_RETRIES,
                        temperature = parsedTemperature,
                    )
                    val updated = if (entry.isCustom) {
                        entry.copy(baseModel = baseModel.copy(id = modelId), overrides = newOverrides, isHidden = isHidden)
                    } else {
                        entry.copy(overrides = newOverrides, isHidden = isHidden)
                    }
                    providerRepository.updateEntry(updated)
                    onBack()
                },
                modifier = Modifier.padding(end = 8.dp),
            ) { Text(stringResource(R.string.common_save)) }
        },
    ) {
        // ── Identity ────────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.add_provider_identity),
            footer = if (entry.isCustom) stringResource(R.string.modeldetail_identity_footer_custom)
                     else stringResource(R.string.modeldetail_identity_footer_builtin),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.add_custom_model_model_id))
                SectionTextField(
                    value = modelId,
                    onValueChange = { if (entry.isCustom) modelId = it },
                    singleLine = true,
                    readOnly = !entry.isCustom,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_display_name))
                SectionTextField(
                    value = displayName,
                    onValueChange = { displayName = it; displayNameTouched = true },
                    singleLine = true,
                )
            }
            SettingsValueRow(
                title = stringResource(R.string.model_entry_provider),
                value = instance?.label ?: instanceId,
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.modeldetail_section_context_retry),
            footer = stringResource(R.string.modeldetail_context_retry_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_follow_global_auto_compact),
                subtitle = if (autoCompactInherited) {
                    stringResource(R.string.modeldetail_auto_compact_state_global)
                } else {
                    stringResource(R.string.modeldetail_auto_compact_state_specific)
                },
                checked = autoCompactInherited,
                onCheckedChange = { inherit ->
                    if (inherit) autoCompactOn = globalAutoCompactOn
                    autoCompactInherited = inherit
                },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_auto_compact),
                checked = autoCompactOn,
                enabled = !autoCompactInherited,
                onCheckedChange = { autoCompactOn = it },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_follow_global_compact_threshold),
                subtitle = if (compactPercentInherited) {
                    stringResource(R.string.modeldetail_compact_threshold_state_global)
                } else {
                    stringResource(R.string.modeldetail_compact_threshold_state_specific)
                },
                checked = compactPercentInherited,
                onCheckedChange = { inherit ->
                    if (inherit) compactPercentText = globalCompactPercent.toString()
                    compactPercentInherited = inherit
                },
            )
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.modeldetail_compact_threshold))
                SectionTextField(
                    value = compactPercentText,
                    onValueChange = { compactPercentText = it.filter { c -> c.isDigit() }.take(2) },
                    placeholder = globalCompactPercent.toString(),
                    singleLine = true,
                    readOnly = compactPercentInherited,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.modeldetail_max_retries))
                SectionTextField(
                    value = maxRetriesText,
                    onValueChange = { maxRetriesText = it.filter { c -> c.isDigit() }.take(1) },
                    placeholder = "5",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.modeldetail_temperature))
                SectionTextField(
                    value = temperatureText,
                    onValueChange = {
                        temperatureText = it.filter { c -> c.isDigit() || c == '.' }.take(6)
                        temperatureError = false
                    },
                    placeholder = stringResource(R.string.modeldetail_temperature_placeholder),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (temperatureError) {
                    Text(
                        text = stringResource(R.string.modeldetail_temperature_invalid),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        // ── Capabilities ────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_capabilities),
            footer = stringResource(R.string.modeldetail_capabilities_footer),
        ) {
            SettingsCardBlock {
                RowLabel(text = stringResource(R.string.modeldetail_context_window))
                SectionTextField(
                    value = contextWindowText,
                    onValueChange = { contextWindowText = it.filter { c -> c.isDigit() } },
                    placeholder = defaulted.contextWindow?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.height(12.dp))
                RowLabel(text = stringResource(R.string.model_entry_max_output_tokens))
                SectionTextField(
                    value = maxOutputTokensText,
                    onValueChange = { maxOutputTokensText = it.filter { c -> c.isDigit() } },
                    placeholder = defaulted.maxOutputTokens?.toString()
                        ?: stringResource(R.string.modeldetail_provider_default),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                // Inline warning when the value exceeds the provider-reported
                // limit. The save still goes through so power users can probe
                // a higher cap; we just flag it.
                val enteredTokens = maxOutputTokensText.trim().toIntOrNull()
                val baseLimit = baseModel.maxOutputTokens
                if (enteredTokens != null && baseLimit != null && enteredTokens > baseLimit) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.modeldetail_max_tokens_exceeds_warning, baseLimit),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                    )
                }
            }
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_thinking),
                checked = thinkingEnabled,
                onCheckedChange = { thinkingEnabled = it; thinkingTouched = true },
                showDivider = false,
            )
        }

        // ── Visibility ──────────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.model_entry_visibility),
            footer = stringResource(R.string.model_entry_hidden_models_won_t_appear_in_the_model_),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.model_entry_hidden),
                checked = isHidden,
                onCheckedChange = { isHidden = it },
                showDivider = false,
            )
        }

        // ── Input Modality ──────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_input_modality),
            footer = stringResource(R.string.modeldetail_input_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_input),
                checked = imageInput,
                onCheckedChange = { imageInput = it; inputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_pdf_input),
                checked = pdfInput,
                onCheckedChange = { pdfInput = it; inputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_input),
                checked = audioInput,
                onCheckedChange = { audioInput = it; inputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_video_input),
                checked = videoInput,
                onCheckedChange = { videoInput = it; inputModalitiesTouched = true },
                showDivider = false,
            )
        }

        // ── Output Modality ─────────────────────────────────────────────
        SettingsSection(
            header = stringResource(R.string.modeldetail_section_output_modality),
            footer = stringResource(R.string.modeldetail_output_modality_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_image_output),
                checked = imageOutput,
                onCheckedChange = { imageOutput = it; outputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_audio_output),
                checked = audioOutput,
                onCheckedChange = { audioOutput = it; outputModalitiesTouched = true },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.modeldetail_video_output),
                checked = videoOutput,
                // 视频输出是我们这一侧新增的维度，同样遵循上游的 touched 语义。
                onCheckedChange = { videoOutput = it; outputModalitiesTouched = true },
                showDivider = false,
            )
        }

        // ── Quick Test ──
        SettingsSection(
            footer = stringResource(R.string.quicktest_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.quicktest_button),
                icon = Icons.Outlined.Bolt,
                showChevron = false,
                showDivider = false,
                onClick = { showQuickTest = true },
            )
        }

        // ── Reset (only for built-in models that have been customized) ──
        if (!entry.isCustom && entry.isUserModified) {
            SettingsSection(
                footer = stringResource(R.string.model_entry_restores_the_provider_reported_display_n),
            ) {
                SettingsRow(
                    title = stringResource(R.string.model_entry_reset_to_default),
                    titleColor = MaterialTheme.colorScheme.error,
                    showChevron = false,
                    showDivider = false,
                    onClick = {
                        providerRepository.updateEntry(entry.copy(overrides = ModelOverrides(), isHidden = false))
                        onBack()
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
    }

    if (showQuickTest) {
        com.openminis.app.ui.components.QuickTestSheet(
            entry = entry,
            providerRepository = providerRepository,
            onDismiss = { showQuickTest = false },
        )
    }
}

/**
 * Decides which override fields the model detail screen persists on Save.
 *
 * [T-model-override-silent-drop] Record an override when the USER EXPRESSED AN
 * INTENT, not when the value happens to differ from today's API value. iOS fixed
 * the same defect in `ProviderInstanceDetailView` (4ef8a48d7).
 *
 * The old rule compared each field against `baseModel` while the controls LOAD
 * from override-or-base. That asymmetry closes a loop which drops intent two ways:
 *
 *  1. Setting a value equal to today's auto value records no override at all, so
 *     the next vendor bump shows straight through. This is the reported case: a
 *     context window typed as 400000 while the vendor also said 400000, which
 *     later read 1048576 after a refresh.
 *  2. An EXISTING override is cleared the moment the vendor catches up to it,
 *     because a no-op re-save then compares equal — and a later vendor flip
 *     loses the user's choice entirely.
 *
 * Two different mechanisms express intent, because the controls differ:
 *
 *  - `contextWindow` / `maxOutputTokens` are text fields, which HAVE an empty
 *    state, so "filled in" is expressible directly: any parsed value is recorded
 *    and empty means back to auto-detection, exactly as the footer promises.
 *    These were already correct and are deliberately unchanged — iOS adopted
 *    this behaviour from Android rather than the other way round.
 *  - Switches and modality lists have no empty state, so intent is carried by an
 *    explicit `touched` flag set from their change callbacks.
 *
 * An untouched control keeps whatever override already existed. It neither
 * invents one — so a never-touched field still inherits vendor updates, which is
 * what keeps [ModelOverrides.isEmpty] meaningful and preserves the fast path in
 * `ProviderConfig.model` — nor clears one, which is failure mode 2 above.
 *
 * This is marginally stronger than the iOS rule, which has no touched flag and
 * so still drops one case: toggling a switch away and back, ending on a value
 * equal to base with no prior override, records nothing there but is recorded
 * here. Every other case agrees across the two platforms. No serialized field
 * name or type changes, so the cross-platform wire format is untouched.
 */
internal fun buildModelOverrides(
    existing: ModelOverrides,
    displayNameText: String,
    displayNameTouched: Boolean,
    maxOutputTokensText: String,
    contextWindowText: String,
    thinkingEnabled: Boolean,
    thinkingTouched: Boolean,
    inputModalities: List<String>,
    inputModalitiesTouched: Boolean,
    outputModalities: List<String>,
    outputModalitiesTouched: Boolean,
    // ── 我们这一侧（Android 本地增量）的逐模型调参字段 [T-android-model-tuning-per-entry] ──
    // 默认值 = 沿用 existing：没有这几个编辑器的调用点（例如
    // ModelOverrideSaveGateTest 的“未触碰不得抹掉已有调参”用例）漏传时，
    // 行为与上游一致，不会因为多出来的可选参数反而清空备份导入的调参。
    autoCompactEnabled: Boolean? = existing.autoCompactEnabled,
    compactThresholdPercent: Int? = existing.compactThresholdPercent,
    maxRetries: Int? = existing.maxRetries,
    temperature: Double? = existing.temperature,
): ModelOverrides = ModelOverrides(
    displayName = if (displayNameTouched) {
        displayNameText.trim().takeIf { it.isNotEmpty() }
    } else {
        existing.displayName
    },
    maxOutputTokens = maxOutputTokensText.trim().toIntOrNull()?.takeIf { it > 0 },
    contextWindow = contextWindowText.trim().toIntOrNull()?.takeIf { it > 0 },
    supportsReasoning = if (thinkingTouched) thinkingEnabled else existing.supportsReasoning,
    inputModalities = if (inputModalitiesTouched) inputModalities else existing.inputModalities,
    outputModalities = if (outputModalitiesTouched) outputModalities else existing.outputModalities,
    // Carried through verbatim: this screen has no editor for any of them, so
    // every one is by definition untouched and the rule above applies. The
    // previous inline construction simply omitted them, which let them default
    // to null — a save from this screen silently wiped per-model tuning that
    // had arrived via backup import or iCloud sync (ProviderRepository reads
    // and writes all five). Naming them explicitly also means adding a sixth
    // field to ModelOverrides fails to compile here rather than quietly
    // resurrecting that wipe.
    maxThinkingLevel = existing.maxThinkingLevel,
    // 这四个本屏有编辑器，因此由调用方显式给出（见上面的默认值说明）。
    autoCompactEnabled = autoCompactEnabled,
    compactThresholdPercent = compactThresholdPercent,
    maxRetries = maxRetries,
    temperature = temperature,
    topP = existing.topP,
    customHeaders = existing.customHeaders,
    extraBodyParams = existing.extraBodyParams,
)
