package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.service.SubAgentActivityTracker
import com.openminis.app.ui.theme.ChatColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubAgentLiveBar(
    sessionId: String,
    modifier: Modifier = Modifier,
) {
    val members by SubAgentActivityTracker.members.collectAsState()
    val mine = members.filter { it.parentSessionId == sessionId }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = selectedId?.let { id -> members.firstOrNull { it.id == id } }

    if (mine.isNotEmpty()) {
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
                    .clickable { selectedId = m.id }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
    }

    if (selected != null) {
        SubAgentDetailSheet(
            member = selected,
            onDismiss = { selectedId = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubAgentDetailSheet(
    member: SubAgentActivityTracker.Member,
    onDismiss: () -> Unit,
) {
    val statusLabel = when (member.status) {
        SubAgentActivityTracker.Status.RUNNING -> "运行中"
        SubAgentActivityTracker.Status.SUCCESS -> "已完成"
        SubAgentActivityTracker.Status.FAILED -> "失败"
    }
    val statusColor = when (member.status) {
        SubAgentActivityTracker.Status.RUNNING -> Color(0xFF007AFF)
        SubAgentActivityTracker.Status.SUCCESS -> Color(0xFF34C759)
        SubAgentActivityTracker.Status.FAILED -> Color(0xFFFF3B30)
    }
    val body = buildString {
        append(member.title)
        member.kind?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        member.model?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        append('\n')
        append(statusLabel)
        if (member.turnCap > 0) {
            append(" · turn ${member.turnIndex.coerceAtLeast(0)}/${member.turnCap}")
        }
        member.currentTool.takeIf { it.isNotBlank() }?.let { append(" · $it") }
        member.error?.takeIf { it.isNotBlank() }?.let {
            append("\nerror: ")
            append(it)
        }
        append("\n\n")
        val transcript = member.transcript.ifBlank { member.lastStep }
        if (transcript.isNotBlank()) append(transcript) else append("（暂无步骤日志，子代理刚启动）")
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "子代理详情",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = statusLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = statusColor,
            )
            Spacer(modifier = Modifier.height(12.dp))
            SelectionContainer {
                Text(
                    text = body,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.primaryText,
                    lineHeight = 18.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                )
            }
        }
    }
}
