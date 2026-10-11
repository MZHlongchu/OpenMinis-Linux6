package com.openminis.app.ui.sessions

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.ChatRepository

/**
 * [T-search-truncation-visible] 会话搜索命中数超过仓库上限
 * （[ChatRepository.MAX_SEARCH_SESSIONS]）时的显式提示。
 *
 * 截断本身是必要的（每次防抖击键不能把全部命中会话实体拉回来），但**静默**截断会让
 * 用户以为"历史里就这么多"，从而放弃继续找。这里把上限说出来：用户知道要细化关键词，
 * 而不是以为搜索漏了结果。独立小文件同时也是文件尺寸棘轮的要求
 * （`SessionListScreen.kt` 已在 `.architecture-baseline.json` 基线值上）。
 */
@Composable
internal fun SearchTruncatedNotice() {
    Text(
        text = stringResource(R.string.search_truncated_notice, ChatRepository.MAX_SEARCH_SESSIONS),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}
