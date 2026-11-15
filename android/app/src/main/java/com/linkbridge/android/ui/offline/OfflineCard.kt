package com.linkbridge.android.ui.offline

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linkbridge.android.OfflineUi
import com.linkbridge.android.ui.glass.glassSurface
import com.linkbridge.android.ui.theme.LocalAccents

/** What the card can ask for. */
class OfflineActions(
    /** Card tapped: start the link, or reopen its screen when it is already on. */
    val onCard: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** Back from the screen: hide it, keep the link on. */
    val onCloseScreen: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onDismissMessage: () -> Unit = {},
)

/** "No Wi-Fi? Connect directly": starts the offline link, or reopens it when on. Glass, like the nav bar. */
@Composable
fun OfflineCard(ui: OfflineUi, actions: OfflineActions, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val tap = if (ui.cardEnabled) Modifier.clickable(role = Role.Button, onClick = actions.onCard) else Modifier
    Column(
        modifier.fillMaxWidth().glassSurface(shape, tint = LocalAccents.current.action).then(tap).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                Icons.Filled.WifiTethering,
                contentDescription = null,
                tint = if (ui.cardEnabled) LocalAccents.current.action else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                ui.cardText,
                style = MaterialTheme.typography.titleMedium,
                color = if (ui.cardEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        ui.message?.let { message ->
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                if (ui.openSettings) TextButton(onClick = actions.onOpenSettings) { Text("Open Settings") }
                TextButton(onClick = actions.onDismissMessage) { Text("Dismiss") }
            }
        }
    }
}
