package com.linkbridge.android.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PauseCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.linkbridge.android.presentation.Banner
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.ui.label
import com.linkbridge.android.ui.theme.LocalAccents

/**
 * The one Devices-screen banner (solid, not glass). The whole banner and its button both run
 * the action. A coral icon marks it; the text stays on-surface for contrast.
 */
@Composable
fun StatusBanner(banner: Banner, onAction: (BannerAction) -> Unit, modifier: Modifier = Modifier) {
    val icon = when (banner.action) {
        BannerAction.START -> Icons.Filled.PauseCircleOutline
        BannerAction.RETRY -> Icons.Filled.ErrorOutline
        BannerAction.ENABLE_NOTIFICATIONS -> Icons.Filled.NotificationsOff
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = banner.action.label) { onAction(banner.action) },
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = LocalAccents.current.attention)
            Column(Modifier.weight(1f)) {
                Text(
                    banner.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            TextButton(onClick = { onAction(banner.action) }) { Text(banner.action.label) }
        }
    }
}
