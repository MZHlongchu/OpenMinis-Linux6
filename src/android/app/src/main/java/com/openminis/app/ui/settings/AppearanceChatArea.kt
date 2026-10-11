package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubble
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.CompareArrows
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import kotlin.math.roundToInt

/**
 * [T-android-chat-customization] Chat Area section: conversation-column gutter
 * (the shared rail the message list and composer sit on) plus user-bubble /
 * chat-background / task-board color overrides.
 *
 * [T-color-rgb-mode-split] 每个颜色槽存储为**浅色/深色两个键**（ARGB Long）；
 * 读取按主题的深浅解析结果选槽，模式槽缺失回退旧单一键，旧键也缺失才跟随
 * 主题。色板对话框支持 HEX/RGB 输入（#RRGGBB / #AARRGGBB / R,G,B，实时预览）
 * + 预设网格（按模式切换色板组）+ 跟随主题重置。
 *
 * Self-contained: state, prefs writes and the picker dialog all live here, so
 * AppearanceScreen only needs a single call.
 */
@Composable
internal fun ChatAreaSection(prefs: android.content.SharedPreferences) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark = com.openminis.app.ui.theme.ChatColors.isDark
    var chatGutter by remember { mutableIntStateOf(chatGutterDp(context)) }
    var revision by remember { mutableIntStateOf(0) }
    fun readColor(slot: ColorSlot): Long = colorOverride(context, slot, isDark)
    var userBubbleColor by remember(revision) { mutableLongStateOf(readColor(ColorSlot.USER_BUBBLE)) }
    var chatBgColor by remember(revision) { mutableLongStateOf(readColor(ColorSlot.CHAT_BG)) }
    var planBoardColor by remember(revision) { mutableLongStateOf(readColor(ColorSlot.PLAN_BOARD)) }
    var colorPickerTarget by remember { mutableStateOf<ColorSlot?>(null) }

    val tileTeal = Color(0xFF5AC8FA)
    val tileBlue = Color(0xFF007AFF)
    val tilePurple = Color(0xFF5856D6)

    SettingsSection(
        header = stringResource(R.string.appearance_section_chat_area),
        footer = stringResource(R.string.appearance_chat_area_footer),
    ) {
        SettingsRow(
            icon = Icons.Outlined.CompareArrows,
            iconColor = tileTeal,
            title = stringResource(R.string.appearance_chat_gutter_title),
            subtitle = stringResource(R.string.appearance_chat_gutter_subtitle),
            onClick = null,
            showChevron = false,
            showDivider = true,
        )
        GutterSliderRow(
            level = chatGutter,
            onLevelChange = {
                chatGutter = it
                prefs.edit().putInt(KEY_CHAT_GUTTER_DP, it).apply()
            },
            showDivider = true,
        )
        SettingsRow(
            icon = Icons.Outlined.ChatBubble,
            iconColor = tileBlue,
            title = stringResource(R.string.appearance_chat_user_bubble_color),
            subtitle = null,
            onClick = { colorPickerTarget = ColorSlot.USER_BUBBLE },
            showDivider = true,
            trailing = {
                ColorSwatch(userBubbleColor.takeOrNullIfNoOverride())
            },
        )
        SettingsRow(
            icon = Icons.Outlined.Wallpaper,
            iconColor = tilePurple,
            title = stringResource(R.string.appearance_chat_bg_color),
            subtitle = null,
            onClick = { colorPickerTarget = ColorSlot.CHAT_BG },
            showDivider = true,
            trailing = {
                ColorSwatch(chatBgColor.takeOrNullIfNoOverride())
            },
        )
        // [T-plan-board-color] 任务看板背景色：与聊天背景/气泡同一套自定义
        //（模式分离 + HEX/RGB 输入 + 预设 + 跟随主题）。
        SettingsRow(
            icon = Icons.Outlined.Checklist,
            iconColor = tileTeal,
            title = stringResource(R.string.appearance_plan_board_color),
            subtitle = null,
            onClick = { colorPickerTarget = ColorSlot.PLAN_BOARD },
            showDivider = false,
            trailing = {
                ColorSwatch(planBoardColor.takeOrNullIfNoOverride())
            },
        )
    }

    colorPickerTarget?.let { target ->
        val current = when (target) {
            ColorSlot.USER_BUBBLE -> userBubbleColor
            ColorSlot.CHAT_BG -> chatBgColor
            ColorSlot.PLAN_BOARD -> planBoardColor
        }
        ChatColorPickerDialog(
            title = stringResource(
                when (target) {
                    ColorSlot.USER_BUBBLE -> R.string.appearance_chat_user_bubble_color
                    ColorSlot.CHAT_BG -> R.string.appearance_chat_bg_color
                    ColorSlot.PLAN_BOARD -> R.string.appearance_plan_board_color
                }
            ),
            slot = target,
            current = current,
            onPick = { value ->
                when (target) {
                    ColorSlot.USER_BUBBLE -> userBubbleColor = value
                    ColorSlot.CHAT_BG -> chatBgColor = value
                    ColorSlot.PLAN_BOARD -> planBoardColor = value
                }
                storeColorOverride(context, target, isDark, value)
                revision++
            },
            onFollowTheme = {
                when (target) {
                    ColorSlot.USER_BUBBLE -> userBubbleColor = NO_COLOR_OVERRIDE
                    ColorSlot.CHAT_BG -> chatBgColor = NO_COLOR_OVERRIDE
                    ColorSlot.PLAN_BOARD -> planBoardColor = NO_COLOR_OVERRIDE
                }
                clearColorOverride(context, target, isDark)
                revision++
            },
            onDismiss = { colorPickerTarget = null },
        )
    }
}

/** 每个可自定义的颜色槽。 */
internal enum class ColorSlot { USER_BUBBLE, CHAT_BG, PLAN_BOARD }

internal fun Long.takeOrNullIfNoOverride(): Color? =
    if (this == NO_COLOR_OVERRIDE) null else Color(this and 0xFFFFFFFFL)

/** [T-color-rgb-mode-split] 模式分离读取：模式槽 → 旧单一键 → 跟随主题(null)。 */
internal fun colorOverride(context: android.content.Context, slot: ColorSlot, isDark: Boolean): Long {
    val prefs = getAppearancePrefs(context)
    val modeKey = when {
        slot == ColorSlot.USER_BUBBLE && isDark -> KEY_CHAT_USER_BUBBLE_COLOR_DARK
        slot == ColorSlot.USER_BUBBLE -> KEY_CHAT_USER_BUBBLE_COLOR_LIGHT
        slot == ColorSlot.CHAT_BG && isDark -> KEY_CHAT_BG_COLOR_DARK
        slot == ColorSlot.CHAT_BG -> KEY_CHAT_BG_COLOR_LIGHT
        slot == ColorSlot.PLAN_BOARD && isDark -> KEY_CHAT_PLAN_BOARD_COLOR_DARK
        else -> KEY_CHAT_PLAN_BOARD_COLOR_LIGHT
    }
    val mode = prefs.getLong(modeKey, NO_COLOR_OVERRIDE)
    if (mode != NO_COLOR_OVERRIDE) return mode
    // 旧单一键迁移源：模式槽未设置时两模式共用旧值。
    val legacy = when (slot) {
        ColorSlot.USER_BUBBLE -> prefs.getLong(KEY_CHAT_USER_BUBBLE_COLOR, NO_COLOR_OVERRIDE)
        ColorSlot.CHAT_BG -> prefs.getLong(KEY_CHAT_BG_COLOR, NO_COLOR_OVERRIDE)
        ColorSlot.PLAN_BOARD -> NO_COLOR_OVERRIDE
    }
    return legacy
}

internal fun storeColorOverride(context: android.content.Context, slot: ColorSlot, isDark: Boolean, value: Long) {
    val key = when {
        slot == ColorSlot.USER_BUBBLE && isDark -> KEY_CHAT_USER_BUBBLE_COLOR_DARK
        slot == ColorSlot.USER_BUBBLE -> KEY_CHAT_USER_BUBBLE_COLOR_LIGHT
        slot == ColorSlot.CHAT_BG && isDark -> KEY_CHAT_BG_COLOR_DARK
        slot == ColorSlot.CHAT_BG -> KEY_CHAT_BG_COLOR_LIGHT
        slot == ColorSlot.PLAN_BOARD && isDark -> KEY_CHAT_PLAN_BOARD_COLOR_DARK
        else -> KEY_CHAT_PLAN_BOARD_COLOR_LIGHT
    }
    getAppearancePrefs(context).edit().putLong(key, value).apply()
}

internal fun clearColorOverride(context: android.content.Context, slot: ColorSlot, isDark: Boolean) {
    val key = when {
        slot == ColorSlot.USER_BUBBLE && isDark -> KEY_CHAT_USER_BUBBLE_COLOR_DARK
        slot == ColorSlot.USER_BUBBLE -> KEY_CHAT_USER_BUBBLE_COLOR_LIGHT
        slot == ColorSlot.CHAT_BG && isDark -> KEY_CHAT_BG_COLOR_DARK
        slot == ColorSlot.CHAT_BG -> KEY_CHAT_BG_COLOR_LIGHT
        slot == ColorSlot.PLAN_BOARD && isDark -> KEY_CHAT_PLAN_BOARD_COLOR_DARK
        else -> KEY_CHAT_PLAN_BOARD_COLOR_LIGHT
    }
    getAppearancePrefs(context).edit().putLong(key, NO_COLOR_OVERRIDE).apply()
}

/** [T-android-chat-customization] Gutter slider — mirrors FontScaleSliderRow's
 *  look (label + live value on top, slider below) but over a plain Int dp
 *  range 8..32 with the default at 20. */
@Composable
private fun GutterSliderRow(
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    var sliderPos by remember(level) { mutableFloatStateOf(level.toFloat()) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.appearance_chat_gutter_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                "${sliderPos.roundToInt()} dp",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = sliderPos,
            onValueChange = { sliderPos = it },
            onValueChangeFinished = {
                val newDp = sliderPos.roundToInt().coerceIn(8, 32)
                sliderPos = newDp.toFloat()
                onLevelChange(newDp)
            },
            valueRange = 8f..32f,
            steps = 23,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}

/** Small round swatch shown at the trailing edge of a color row.
 *  null = follow theme, rendered as the theme's own userBubble so the row
 *  previews what the chat currently uses. */
@Composable
private fun ColorSwatch(color: Color?) {
    val resolved = color ?: com.openminis.app.ui.theme.ChatColors.userBubble
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(resolved, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}


/** [T-android-font-scale] Font-scale slider row (moved verbatim from
 *  AppearanceScreen.kt during the T-android-chat-customization ratchet
 *  extraction — behavior unchanged). Label + live scale label on top,
 *  A-slider-A below, optional trailing divider. */
@Composable
internal fun FontScaleSliderRow(
    label: String,
    level: Int,
    onLevelChange: (Int) -> Unit,
    showDivider: Boolean,
) {
    val idx = fontScaleValues.indexOf(level).coerceIn(0, fontScaleValues.lastIndex)
    var sliderPos by remember(level) { mutableFloatStateOf(idx.toFloat()) }
    val currentLabel = fontScaleLabels.getOrElse(sliderPos.roundToInt()) { "Default" }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                currentLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("A", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Slider(
                value = sliderPos,
                onValueChange = { sliderPos = it },
                onValueChangeFinished = {
                    val newIdx = sliderPos.roundToInt().coerceIn(0, fontScaleValues.lastIndex)
                    sliderPos = newIdx.toFloat()
                    onLevelChange(fontScaleValues[newIdx])
                },
                valueRange = 0f..5f,
                steps = 4,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp),
            )
            Text("A", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showDivider) {
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 14.dp)
                .height(0.5.dp)
                .background(dividerColor),
        )
    }
}