package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.service.SubAgentActivityTracker
import com.openminis.app.ui.theme.ChatColors

@Composable
fun SubAgentLiveBar(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val members by SubAgentActivityTracker.members.collectAsState()
    val mine = members.filter { it.parentSessionId == sessionId }
    if (mine.isEmpty()) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ChatColors.background.copy(alpha = 0.94f))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        mine.forEach { m ->
            val (bg, fg) = when (m.status) {
                SubAgentActivityTracker.Status.RUNNING -> Color(0xFF007AFF) to Color.White
                SubAgentActivityTracker.Status.SUCCESS -> Color(0xFF34C759) to Color.White
                SubAgentActivityTracker.Status.FAILED -> Color(0xFFFF3B30) to Color.White
            }
            val label = buildString {
                if (m.index > 0 && m.total > 0) {
                    append("子代理 ${m.index}/${m.total}")
                } else {
                    append(m.title.take(28))
                }
                m.kind?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                    ?: m.role?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                append(
                    when (m.status) {
                        SubAgentActivityTracker.Status.RUNNING -> " · run"
                        SubAgentActivityTracker.Status.SUCCESS -> " · done"
                        SubAgentActivityTracker.Status.FAILED -> " · fail"
                    },
                )
                if (m.status == SubAgentActivityTracker.Status.RUNNING) {
                    val cap = m.turnCap
                    if (cap > 0) {
                        append(" · turn ${m.turnIndex.coerceAtLeast(1)}/$cap")
                    }
                    m.currentTool.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                        ?: m.lastStep.takeIf { it.isNotBlank() && cap <= 0 }?.let {
                            append(" · ")
                            append(it.take(48))
                        }
                }
            }
            Text(
                text = label,
                color = fg,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .background(bg, RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}
