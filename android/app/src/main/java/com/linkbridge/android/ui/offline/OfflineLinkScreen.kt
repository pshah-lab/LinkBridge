package com.linkbridge.android.ui.offline

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linkbridge.android.OfflineScreenUi
import com.linkbridge.android.ui.glass.glassSurface
import com.linkbridge.android.ui.theme.backdrop

private val QR_SIZE = 240.dp

/**
 * Full-screen "Offline link is on": the QR code a Mac scans (or, until the address is known,
 * "Getting ready…"), the network name, password and address to join by hand, the idle countdown
 * and Stop. Keeps the display on while it is visible; back hides it and leaves the link on.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OfflineLinkScreen(screen: OfflineScreenUi, actions: OfflineActions, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions.onCloseScreen)
    KeepScreenOn()
    Box(modifier.fillMaxSize().backdrop().safeDrawingPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Offline link is on",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Box(
                Modifier.size(QR_SIZE).clip(RoundedCornerShape(16.dp)).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                val qr = screen.qrText?.let { text -> remember(text) { qrBitmap(text, QR_PIXELS) } }
                if (qr != null) {
                    Image(qr.asImageBitmap(), contentDescription = "QR code to join the offline link", Modifier.fillMaxSize())
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        LoadingIndicator()
                        Text("Getting ready…", style = MaterialTheme.typography.bodyMedium, color = Color.Black)
                    }
                }
            }
            Column(
                Modifier.fillMaxWidth().glassSurface(RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Detail("Network", screen.ssid)
                Detail("Password", screen.passphrase)
                Detail("Address", screen.address)
            }
            Text(
                "Stops after 10 min idle",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                screen.minutesLeftLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "This network has no internet.\nYour phone may leave its current Wi-Fi while this is on.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = actions.onStop) { Text("Stop") }
        }
    }
}

/** QR render size in pixels; the bitmap is scaled to [QR_SIZE]. */
private const val QR_PIXELS = 720

@Composable
private fun Detail(label: String, value: String?) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value ?: "…",
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
