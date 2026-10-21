package com.linkbridge.android.ui.devices

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linkbridge.android.PairingStep
import com.linkbridge.android.ui.isDismissible
import com.linkbridge.android.ui.spokenCode
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.LocalAccents

/** What the pairing sheet can ask for. */
class PairingActions(
    val onContinue: () -> Unit,
    /** Cancels a running step (Connecting, Code, Waiting). */
    val onCancel: () -> Unit,
    val onTryAgain: (itemId: String) -> Unit,
    /** Closes a finished sheet; also what swipe, back and the scrim do when allowed. */
    val onDismiss: () -> Unit,
)

/**
 * Modal sheet over the app for one pairing attempt. Swipe, back and scrim dismissal are off
 * while Connecting or Waiting; Cancel is the way out then.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairingSheet(step: PairingStep, actions: PairingActions) {
    val dismissible by rememberUpdatedState(step.isDismissible)
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden || dismissible },
    )
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = step.isDismissible,
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = step.isDismissible),
    ) {
        PairingSheetContent(step, actions, Modifier.navigationBarsPadding())
    }
}

/** The sheet's body for [step], cross-fading between steps. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingSheetContent(step: PairingStep, actions: PairingActions, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = step,
        contentKey = { it::class },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        modifier = modifier.fillMaxWidth(),
        label = "pairing step",
    ) { current ->
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (current) {
                is PairingStep.Connecting -> {
                    LoadingIndicator()
                    Title("Connecting to ${current.name}…")
                    TextButton(onClick = actions.onCancel) { Text("Cancel") }
                }
                is PairingStep.Code -> {
                    Title("Type this code on ${current.name}")
                    Text(
                        current.code,
                        style = MaterialTheme.typography.displayMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.clearAndSetSemantics { contentDescription = spokenCode(current.code) },
                    )
                    Body("If the Mac won't accept the code, cancel — someone may be intercepting the connection.")
                    Buttons {
                        TextButton(onClick = actions.onCancel) { Text("Cancel") }
                        Button(onClick = actions.onContinue) { Text("Continue") }
                    }
                }
                is PairingStep.Waiting -> {
                    LoadingIndicator()
                    Title("Waiting for you to confirm on ${current.name}…")
                    TextButton(onClick = actions.onCancel) { Text("Cancel") }
                }
                is PairingStep.Paired -> {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = LocalAccents.current.signal,
                        modifier = Modifier.size(56.dp),
                    )
                    Title("Paired with ${current.name}")
                }
                is PairingStep.Failed -> {
                    Icon(
                        Icons.Filled.ErrorOutline,
                        contentDescription = null,
                        tint = LocalAccents.current.attention,
                        modifier = Modifier.size(56.dp),
                    )
                    Title("Couldn't pair with ${current.name}: ${current.message}")
                    Buttons {
                        OutlinedButton(onClick = actions.onDismiss) { Text("Close") }
                        Button(onClick = { actions.onTryAgain(current.itemId) }) { Text("Try again") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** Buttons side by side, wrapping onto a second line at large font scales. */
@Composable
private fun Buttons(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) { content() }
}

// ---- Previews ----

private val previewActions = PairingActions({}, {}, {}, {})

@Composable
private fun SheetPreview(step: PairingStep) {
    LinkBridgeTheme(dynamicColor = false) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            PairingSheetContent(step, previewActions)
        }
    }
}

@ScreenPreviews
@Composable
private fun PairingConnectingPreview() = SheetPreview(PairingStep.Connecting("Studio"))

@ScreenPreviews
@Composable
private fun PairingCodePreview() = SheetPreview(PairingStep.Code("Studio", "482 913"))

@ScreenPreviews
@Composable
private fun PairingWaitingPreview() = SheetPreview(PairingStep.Waiting("Studio"))

@ScreenPreviews
@Composable
private fun PairingPairedPreview() = SheetPreview(PairingStep.Paired("Studio"))

@ScreenPreviews
@Composable
private fun PairingFailedPreview() =
    SheetPreview(PairingStep.Failed("Studio", "The Mac didn't answer.", itemId = "LinkBridge-Studio"))
