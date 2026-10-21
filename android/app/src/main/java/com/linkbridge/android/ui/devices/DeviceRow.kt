package com.linkbridge.android.ui.devices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.LaptopMac
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.presentation.DevicePlatform
import com.linkbridge.android.ui.theme.LocalAccents

private fun DevicePlatform.icon(): ImageVector = when (this) {
    DevicePlatform.MAC -> Icons.Filled.LaptopMac
    DevicePlatform.ANDROID -> Icons.Filled.PhoneAndroid
    DevicePlatform.IPHONE -> Icons.Filled.PhoneIphone
    DevicePlatform.UNKNOWN -> Icons.Filled.Devices
}

private fun DevicePlatform.spoken(): String = when (this) {
    DevicePlatform.MAC -> "Mac"
    DevicePlatform.ANDROID -> "Android"
    DevicePlatform.IPHONE -> "iPhone"
    DevicePlatform.UNKNOWN -> "Device"
}

/**
 * One device: platform icon, name, subtitle, and either a "Paired" badge or a Pair button.
 * Solid (rows are never glass). A sendable row selects; an unpaired Mac row pairs; other rows
 * are inert.
 */
@Composable
fun DeviceRow(
    item: DeviceListItem,
    selected: Boolean,
    onSelect: () -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val click = when {
        item.isSendable -> Modifier.clickable(role = Role.Button, onClickLabel = "Send to ${item.name}", onClick = onSelect)
        item.canPair -> Modifier.clickable(role = Role.Button, onClickLabel = "Pair with ${item.name}", onClick = onPair)
        else -> Modifier
    }
    ListItem(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .then(click)
            .semantics { this.selected = selected },
        colors = ListItemDefaults.colors(
            containerColor = if (selected) colors.secondaryContainer else colors.surfaceContainerLow,
        ),
        leadingContent = {
            Icon(
                item.platform.icon(),
                contentDescription = item.platform.spoken(),
                tint = if (item.isNearby) colors.onSurface else colors.onSurfaceVariant,
            )
        },
        headlineContent = { Text(item.name) },
        supportingContent = { Text(item.subtitle) },
        trailingContent = when {
            item.canPair -> {
                { Button(onClick = onPair) { Text("Pair") } }
            }
            item.isPaired -> {
                { PairedBadge() }
            }
            else -> null
        },
    )
}

@Composable
private fun PairedBadge() {
    val accents = LocalAccents.current
    Surface(shape = CircleShape, color = accents.signal, contentColor = accents.onSignal) {
        Text(
            "Paired",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
