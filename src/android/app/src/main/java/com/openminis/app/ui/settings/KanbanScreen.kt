package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.KanbanTaskDao
import com.openminis.app.data.db.KanbanTaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STATUS_TODO = "todo"
private const val STATUS_DOING = "doing"
private const val STATUS_DONE = "done"
private const val LABEL_TODO = "待办"
private const val LABEL_DOING = "进行中"
private const val LABEL_DONE = "已完成"

@Composable
fun KanbanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val dao = remember(context) { AppDatabase.getInstance(context).kanbanTaskDao() }
    val tasks by dao.observeAll().collectAsState(initial = emptyList())
    var showAdd by remember { mutableStateOf(false) }
    var newTitle by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = "看板",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showAdd = true }) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "新建",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(tasks, key = { it.id }) { task ->
                KanbanTaskCard(task = task, dao = dao)
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }

    if (showAdd) {
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("新建任务") },
            text = {
                OutlinedTextField(
                    value = newTitle,
                    onValueChange = { newTitle = it },
                    placeholder = { Text("任务标题") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newTitle.isNotBlank()) {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                dao.insert(KanbanTaskEntity(title = newTitle.trim()))
                            }
                        }
                        showAdd = false
                        newTitle = ""
                    }
                }) {
                    Text("创建")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdd = false }) {
                    Text("取消")
                }
            }
        )
    }
}

@Composable
private fun KanbanTaskCard(task: KanbanTaskEntity, dao: KanbanTaskDao) {
    val scope = rememberCoroutineScope()

    val statusLabel = when (task.status) {
        STATUS_TODO -> LABEL_TODO
        STATUS_DOING -> LABEL_DOING
        STATUS_DONE -> LABEL_DONE
        else -> task.status
    }

    val badgeColor = when (task.status) {
        STATUS_TODO -> Color(0xFF64748B)
        STATUS_DOING -> Color(0xFF0EA5E9)
        STATUS_DONE -> Color(0xFF22C55E)
        else -> Color(0xFF94A3B8)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(12.dp)
    ) {
        Column {
            Text(
                text = task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (!task.description.isNullOrBlank()) {
                Text(
                    text = task.description!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(Modifier.weight(1f))
                val canGoLeft = task.status != STATUS_TODO
                IconButton(
                    onClick = {
                        if (canGoLeft) {
                            val newStatus = when (task.status) {
                                STATUS_DOING -> STATUS_TODO
                                STATUS_DONE -> STATUS_DOING
                                else -> STATUS_TODO
                            }
                            val ts = System.currentTimeMillis()
                            scope.launch { withContext(Dispatchers.IO) { dao.updateStatus(task.id, newStatus, ts) } }
                        }
                    },
                    enabled = canGoLeft
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一个状态",
                        tint = if (canGoLeft) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
                val canGoRight = task.status != STATUS_DONE
                IconButton(
                    onClick = {
                        if (canGoRight) {
                            val newStatus = when (task.status) {
                                STATUS_TODO -> STATUS_DOING
                                STATUS_DOING -> STATUS_DONE
                                else -> STATUS_DONE
                            }
                            val ts = System.currentTimeMillis()
                            scope.launch { withContext(Dispatchers.IO) { dao.updateStatus(task.id, newStatus, ts) } }
                        }
                    },
                    enabled = canGoRight
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "下一个状态",
                        tint = if (canGoRight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
        }
    }
}
