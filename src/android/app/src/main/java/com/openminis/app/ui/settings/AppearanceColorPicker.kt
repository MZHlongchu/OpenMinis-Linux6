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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton

/** Swatch presets. Bubble set leans on the iOS system-color family the chat
 *  palette is built from; background/board sets add near-white / near-black
 *  neutrals plus pastel tints that keep dark-mode text legible. */
private val BubbleColorPresets = listOf(
    0xFF007AFF, 0xFF34C759, 0xFFFF9500, 0xFFFF3B30,
    0xFF5856D6, 0xFFFF2D55, 0xFF5AC8FA, 0xFFFFD60E,
    0xFFF2F2F7, 0xFFE5E5EA, 0xFF787880, 0xFF2F3A5C,
    0xFF2C2C2E, 0xFF1C1C1E, 0xFFA2845E, 0xFF6D6D72,
)
private val BgColorPresets = listOf(
    0xFFFFFFFF, 0xFFF2F2F7, 0xFFE5E5EA, 0xFFD1D1D6,
    0xFFEAF4FF, 0xFFEAFBF0, 0xFFFFF6E5, 0xFFF5EEFF,
    0xFF000000, 0xFF1C1C1E, 0xFF2C2C2E, 0xFF26262A,
    0xFF16213E, 0xFF1E2A25, 0xFF2B1E1E, 0xFF241F2E,
)

/**
 * [T-color-rgb-mode-split] Swatch picker dialog: light/dark mode tabs (each
 * mode stores its own override), 4x4 preset grid for the active mode, a
 * HEX/RGB input (#RRGGBB / #AARRGGBB / R,G,B with live preview) and a
 * "follow theme" reset. `current` is the stored Long of the ACTIVE mode.
 */
@Composable
internal fun ChatColorPickerDialog(
    title: String,
    slot: ColorSlot,
    current: Long,
    onPick: (Long) -> Unit,
    onFollowTheme: () -> Unit,
    onDismiss: () -> Unit,
) {
    val systemIsDark = com.openminis.app.ui.theme.ChatColors.isDark
    var modeDark by remember { mutableStateOf(systemIsDark) }
    var hexInput by remember(modeDark) {
        mutableStateOf(
            if (current == NO_COLOR_OVERRIDE) "" else "#%06X".format(current and 0xFFFFFFL),
        )
    }
    val presets = when {
        slot == ColorSlot.USER_BUBBLE -> BubbleColorPresets
        else -> BgColorPresets
    }
    // 实时预览：输入合法即解析；非法保留上次合法值。
    val previewColor = remember(hexInput) { parseColorInput(hexInput) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                // ── 浅色/深色模式切换：每个模式独立存储 ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ModeTab(
                        label = stringResource(R.string.appearance_color_mode_light),
                        selected = !modeDark,
                        onClick = { modeDark = false },
                        modifier = Modifier.weight(1f),
                    )
                    ModeTab(
                        label = stringResource(R.string.appearance_color_mode_dark),
                        selected = modeDark,
                        onClick = { modeDark = true },
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                // ── HEX / RGB 输入：#RRGGBB / #AARRGGBB / R,G,B ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = hexInput,
                        onValueChange = { hexInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.appearance_color_hex_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        modifier = Modifier.weight(1f),
                    )
                    // 预览块：合法输入显示解析色，非法显示轮廓框。
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                previewColor ?: MaterialTheme.colorScheme.surface,
                                CircleShape,
                            )
                            .border(
                                width = if (previewColor == null) 1.dp else 2.dp,
                                color = if (previewColor == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary,
                                shape = CircleShape,
                            ),
                    )
                }
                // 应用按钮：合法输入才可用（输入是草稿，点应用才落盘）。
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MinisTextButton(
                        enabled = previewColor != null,
                        onClick = {
                            // Color 组件的 alpha/red/green/blue 是 0..1 浮点，
                            // 存槽是 ARGB Long（0xAARRGGBB）。
                            previewColor?.let { c ->
                                onPick(
                                    (
                                        ((c.alpha * 255).toInt().toLong() shl 24) or
                                            ((c.red * 255).toInt().toLong() shl 16) or
                                            ((c.green * 255).toInt().toLong() shl 8) or
                                            (c.blue * 255).toInt().toLong()
                                        ) and 0xFFFFFFFFL
                                )
                            }
                        },
                    ) { Text(stringResource(R.string.appearance_color_apply)) }
                }
                Spacer(Modifier.height(6.dp))
                presets.chunked(4).forEach { rowColors ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        rowColors.forEach { c ->
                            val selected = current != NO_COLOR_OVERRIDE &&
                                (current and 0xFFFFFFFFL) == (c.toLong() and 0xFFFFFFFFL)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(Color(c), CircleShape)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        onPick(c.toLong() and 0xFFFFFFFFL)
                                        onDismiss()
                                    },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onFollowTheme()
                            onDismiss()
                        }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.appearance_color_follow_theme),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close))
            }
        },
    )
}

/** 浅色/深色模式 tab。 */
@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                else MaterialTheme.colorScheme.surface,
                RoundedCornerShape(8.dp),
            )
            .border(
                width = if (selected) 1.5.dp else 0.5.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * HEX/RGB 输入解析：#RRGGBB（alpha=FF）、#AARRGGBB、0xRRGGBB、RRGGBB、
 * R,G,B（十进制 0-255）。非法返回 null（预览块显错误轮廓）。
 */
internal fun parseColorInput(input: String): Color? {
    val text = input.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
    if (text.isEmpty()) return null
    // R,G,B 十进制
    if (text.contains(',')) {
        val parts = text.split(',').map { it.trim() }
        if (parts.size != 3) return null
        val rgb = parts.map { it.toIntOrNull() ?: return null }
        if (rgb.any { it < 0 || it > 255 }) return null
        return Color(0xFF000000L or (rgb[0].toLong() shl 16) or (rgb[1].toLong() shl 8) or rgb[2].toLong())
    }
    if (!text.matches(Regex("^[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?$"))) return null
    return when (text.length) {
        6 -> Color(0xFF000000L or text.toLong(16))
        8 -> {
            // #AARRGGBB：前两位是 alpha。
            val a = text.substring(0, 2).toLong(16)
            val rgb = text.substring(2).toLong(16)
            Color((a shl 24) or rgb)
        }
        else -> null
    }
}