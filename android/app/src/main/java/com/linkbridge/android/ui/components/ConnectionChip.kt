package com.linkbridge.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linkbridge.android.presentation.Accent
import com.linkbridge.android.presentation.ConnectionState
import com.linkbridge.android.ui.glass.glassSurface
import com.linkbridge.android.ui.theme.LocalAccents

/**
 * Glass pill: a state dot (a thin progress ring while sending) and the state title on one
 * line. TalkBack reads "<dotLabel>, <title>". Tapping opens Devices.
 */
@Composable
fun ConnectionChip(state: ConnectionState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val accents = LocalAccents.current
    val dotColor = when (state.accent) {
        Accent.SIGNAL -> accents.signal
        Accent.ATTENTION -> accents.attention
        Accent.ACTION -> accents.action
        null -> MaterialTheme.colorScheme.outline
    }
    val description = "${state.dotLabel}, ${state.title}"
    Row(
        modifier
            .defaultMinSize(minHeight = 48.dp)
            .glassSurface(CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                onClick(label = "Open Devices") { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
            if (state is ConnectionState.Sending) {
                CircularProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.size(12.dp),
                    color = dotColor,
                    trackColor = dotColor.copy(alpha = 0.25f),
                    strokeWidth = 2.dp,
                )
            } else {
                Box(Modifier.size(10.dp).background(dotColor, CircleShape))
            }
        }
        Text(
            state.title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
