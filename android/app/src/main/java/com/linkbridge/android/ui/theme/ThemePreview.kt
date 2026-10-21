package com.linkbridge.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linkbridge.android.ui.glass.LocalBlurEnabled
import com.linkbridge.android.ui.glass.LocalHazeState
import com.linkbridge.android.ui.glass.glassSurface
import dev.chrisbanes.haze.hazeSource

@Composable
private fun ThemeSample(dark: Boolean) {
    LinkBridgeTheme(darkTheme = dark, dynamicColor = false) {
        val accents = LocalAccents.current
        Column(
            Modifier.backdrop().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (dark) "Dark" else "Light", color = MaterialTheme.colorScheme.onBackground)
            listOf(true to "Blur", false to "Solid").forEach { (blur, label) ->
                CompositionLocalProvider(LocalBlurEnabled provides blur) {
                    Box(Modifier.fillMaxWidth().height(72.dp)) {
                        // Busy content behind the glass so the blur is visible.
                        Row(
                            Modifier.hazeSource(LocalHazeState.current!!).padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(accents.action, accents.signal, accents.attention).forEach {
                                Box(Modifier.size(56.dp).background(it, CircleShape))
                            }
                        }
                        Box(
                            Modifier.align(Alignment.CenterEnd)
                                .glassSurface(RoundedCornerShape(28.dp), tint = if (blur) null else accents.action)
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                        ) {
                            Text(label, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentChip("Send", accents.action, accents.onAction)
                AccentChip("Ready", accents.signal, accents.onSignal)
                AccentChip("Failed", accents.attention, accents.onAttention)
            }
        }
    }
}

@Composable
private fun AccentChip(label: String, fill: Color, on: Color) {
    Text(
        label,
        color = on,
        modifier = Modifier.background(fill, CircleShape).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Preview(name = "Theme · light", widthDp = 360)
@Composable
private fun ThemeLightPreview() = ThemeSample(dark = false)

@Preview(name = "Theme · dark", widthDp = 360)
@Composable
private fun ThemeDarkPreview() = ThemeSample(dark = true)
