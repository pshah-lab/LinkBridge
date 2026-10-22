package com.linkbridge.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // A Row rather than NavigationBar: NavigationBar is a fixed 80 dp and clips labels at
    // large font scales. This grows instead; items keep their 48 dp targets and labels.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .glassSurface(RoundedCornerShape(32.dp))
            .selectableGroup()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
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
