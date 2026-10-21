package com.linkbridge.android.ui.components

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkbridge.android.ui.Tab
import com.linkbridge.android.ui.glass.glassSurface

private fun Tab.title() = when (this) {
    Tab.DEVICES -> "Devices"
    Tab.SEND -> "Send"
    Tab.HISTORY -> "History"
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.DEVICES -> Icons.Filled.Devices
    Tab.SEND -> Icons.AutoMirrored.Filled.Send
    Tab.HISTORY -> Icons.Filled.History
}

/**
 * The floating glass nav bar: Devices · Send · History, icon and label. The caller places it
 * above the navigation-bar inset; this bar adds no insets of its own.
 */
@Composable
fun GlassNavBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    NavigationBar(
        modifier = modifier.fillMaxWidth().glassSurface(RoundedCornerShape(32.dp)),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0),
    ) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = { Icon(tab.icon(), contentDescription = null) },
                label = { Text(tab.title(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}
