package com.linkbridge.android.ui.send

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.linkbridge.android.presentation.SendPhase
import com.linkbridge.android.ui.FabAction
import com.linkbridge.android.ui.devices.ScreenPreviews
import com.linkbridge.android.ui.fabAction
import com.linkbridge.android.ui.glass.glassSurface
import com.linkbridge.android.ui.percentText
import com.linkbridge.android.ui.theme.LinkBridgeTheme
import com.linkbridge.android.ui.theme.LocalAccents
import com.linkbridge.android.ui.theme.animationsEnabled
import com.linkbridge.android.ui.theme.backdrop

private val FabShape = RoundedCornerShape(28.dp)

/**
 * The glass Send FAB. Its bounds morph between [SendPhase]s (a spring size transform plus a
 * fade/scale of the content); with animations off it only cross-fades. Cobalt tint while it
 * acts, coral for Retry. [onPick] opens the picker (Idle); [onSend] sends or retries.
 */
@Composable
fun SendFab(
    phase: SendPhase,
    targetSendable: Boolean,
    onPick: () -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accents = LocalAccents.current
    val action = fabAction(phase)
    val enabled = when (action) {
        FabAction.PICK -> true
        FabAction.SEND -> phase.isEnabled(targetSendable)
        FabAction.NONE -> false
    }
    val tint = when (phase) {
        is SendPhase.Failed -> accents.attention
        is SendPhase.Sent -> accents.signal
        else -> accents.action
    }
    val click = if (enabled) {
        Modifier.clickable(role = Role.Button) {
            if (action == FabAction.PICK) onPick() else onSend()
        }
    } else {
        Modifier
    }
    val animate = animationsEnabled()
    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    Box(
        modifier
            .widthIn(max = 320.dp)
            .defaultMinSize(minWidth = 56.dp, minHeight = 56.dp)
            .glassSurface(FabShape, tint = if (enabled || phase is SendPhase.Sending) tint else null)
            .then(click),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = phase,
            contentKey = { it::class },
            transitionSpec = {
                if (animate) {
                    (fadeIn() + scaleIn(initialScale = 0.9f)) togetherWith fadeOut() using
                        SizeTransform(clip = false) { _, _ -> sizeSpec }
                } else {
                    fadeIn() togetherWith fadeOut() using null
                }
            },
            label = "send fab",
        ) { current ->
            FabContent(current, enabled)
        }
    }
}

@Composable
private fun FabContent(phase: SendPhase, enabled: Boolean) {
    val accents = LocalAccents.current
    val colors = MaterialTheme.colorScheme
    val textColor = if (enabled || phase !is SendPhase.Ready) colors.onSurface else colors.onSurfaceVariant
    Row(
        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (phase) {
            is SendPhase.Sending -> {
                CircularProgressIndicator(
                    progress = { phase.progress },
                    color = accents.action,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(24.dp),
                )
                val percent = percentText(phase.progress)
                Column(
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "Sending ${phase.fileName}, $percent"
                        progressBarRangeInfo = ProgressBarRangeInfo(phase.progress, 0f..1f)
                    },
                ) {
                    Text(percent, style = MaterialTheme.typography.labelLarge, color = textColor)
                    Text(
                        phase.fileName,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            else -> {
                val (icon, iconTint) = when (phase) {
                    SendPhase.Idle -> Icons.Filled.AttachFile to accents.action
                    is SendPhase.Ready -> Icons.AutoMirrored.Filled.Send to accents.action
                    is SendPhase.Sent -> Icons.Filled.Check to accents.signal
                    is SendPhase.Failed -> Icons.Filled.Refresh to accents.attention
                    is SendPhase.Sending -> error("handled above")
                }
                Icon(icon, contentDescription = null, tint = iconTint)
                Text(
                    phase.actionTitle,
                    style = MaterialTheme.typography.labelLarge,
                    color = textColor,
                    maxLines = 2,
                    modifier = if (phase is SendPhase.Sent) {
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
}

// ---- Previews ----

@Composable
private fun FabPreview(phase: SendPhase) {
    LinkBridgeTheme(dynamicColor = false) {
        Surface(Modifier.backdrop()) {
            Box(Modifier.padding(24.dp)) { SendFab(phase, targetSendable = true, onPick = {}, onSend = {}) }
        }
    }
}

@ScreenPreviews
@Composable
private fun FabIdlePreview() = FabPreview(SendPhase.Idle)

@ScreenPreviews
@Composable
private fun FabReadyPreview() = FabPreview(SendPhase.Ready("Report.pdf"))

@ScreenPreviews
@Composable
private fun FabSendingPreview() = FabPreview(SendPhase.Sending("Holiday photos.zip", 0.42f))

@ScreenPreviews
@Composable
private fun FabSentPreview() = FabPreview(SendPhase.Sent("Report.pdf"))

@ScreenPreviews
@Composable
private fun FabFailedPreview() = FabPreview(SendPhase.Failed("Report.pdf", "Connection refused"))
