@file:Suppress("unused")

package com.openminis.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.data.model.ContextUsage
import kotlin.math.min

/**
 * Input-composer context-usage ring.
 *
 * A 24 × 24 ring drawn with Canvas: a track + an arc whose sweep is
 * `usage.ratio * 360`. Colour goes green→blue→yellow→red as the context
 * window fills (see [ringColor]). Tap to expand a stats card
 * (handled by the caller via [onClick]).
 *
 * Mirrors XINCODE `ChatScreen.kt:ContextRing` but is a standalone
 * composable so it can be dropped into any composer layout.
 */
@Composable
fun ContextRing(
    usage: ContextUsage,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val ratio = usage.ratio
    // Smooth animation to the target ratio — avoids the "jank flash" when
    // the value updates faster than the eye can resolve.
    val animated by animateFloatAsState(
        targetValue = if (usage.known) ratio else 0f,
        animationSpec = tween(durationMillis = 500),
        label = "ctxRing",
    )
    val fg = ringColor(if (usage.known) ratio else 0f)
    val trackColor = ChatColors.toolBorder

    Box(
        modifier = modifier
            .size(28.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
            ) { onClick() }
            .background(Color.Transparent, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(24.dp)) {
            val stroke = 3.dp.toPx()
            // Track (full circle, dimmed).
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Occupied arc.
            if (usage.known && animated > 0f) {
                drawArc(
                    color = fg,
                    startAngle = -90f,
                    sweepAngle = animated.coerceIn(0f, 1f) * 360f,
                    useCenter = false,
                    style = Stroke(width = stroke, cap = StrokeCap.Round),
                )
            }
        }
        Text(
            text = if (usage.known) "${(ratio * 100).toInt()}" else "?",
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            color = if (usage.known) fg else ChatColors.secondaryText,
        )
    }
}

/**
 * Context-window colours: green (healthy) → blue → yellow (warning) → red (critical).
 * Thresholds mirror XINCODE: 45% and 75% are the inflection points.
 */
private val RingGreen = Color(0xFF7BE0A4)
private val RingBlue = Color(0xFF4FA3FF)
private val RingYellow = Color(0xFFF2C14E)
private val RingRed = Color(0xFFE5484D)

private fun ringColor(ratio: Float): Color {
    val r = ratio.coerceIn(0f, 1f)
    return when {
        r <= 0.45f -> androidx.compose.ui.graphics.lerp(RingGreen, RingBlue, r / 0.45f)
        r <= 0.75f -> androidx.compose.ui.graphics.lerp(RingBlue, RingYellow, (r - 0.45f) / 0.30f)
        else -> androidx.compose.ui.graphics.lerp(RingYellow, RingRed, (r - 0.75f) / 0.25f)
    }
}
