package com.openminis.app.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R

@Composable
fun ConversationCardShareDialog(
    onDismiss: () -> Unit,
    onShare: (ConversationCardOptions) -> Unit,
) {
    var theme by remember { mutableStateOf(ConversationCardOptions.Theme.DARK) }
    var hideTools by remember { mutableStateOf(true) }
    var paginate by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_card_share_title)) },
        text = {
            Column {
                ThemeRow(stringResource(R.string.chat_card_theme_dark), theme == ConversationCardOptions.Theme.DARK) {
                    theme = ConversationCardOptions.Theme.DARK
                }
                ThemeRow(stringResource(R.string.chat_card_theme_light), theme == ConversationCardOptions.Theme.LIGHT) {
                    theme = ConversationCardOptions.Theme.LIGHT
                }
                ThemeRow(stringResource(R.string.chat_card_theme_paper), theme == ConversationCardOptions.Theme.PAPER) {
                    theme = ConversationCardOptions.Theme.PAPER
                }
                CheckRow(stringResource(R.string.chat_card_hide_tools), hideTools) { hideTools = it }
                CheckRow(stringResource(R.string.chat_card_paginate), paginate) { paginate = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onShare(ConversationCardOptions(theme = theme, hideTools = hideTools, paginate = paginate))
                },
            ) {
                Text(stringResource(R.string.chat_menu_share_card))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ThemeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label)
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChecked(!checked) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked)
        Text(label)
    }
}
