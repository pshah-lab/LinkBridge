package com.linkbridge.android.ui.glass

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.function.Consumer

/** Whether glass may blur. False means solid glass fallbacks (and in previews by default). */
val LocalBlurEnabled: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * Tracks `WindowManager.isCrossWindowBlurEnabled` for [activity]. Listens only while the
 * lifecycle is started (the listener reports the current value when it registers), and
 * unregisters when stopped or when this leaves composition.
 */
@Composable
fun rememberBlurEnabled(activity: Activity): State<Boolean> {
    val windowManager = activity.windowManager
    val state = remember(activity) { mutableStateOf(windowManager.isCrossWindowBlurEnabled) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle) {
        val listener = Consumer<Boolean> { state.value = it }
        var registered = false
        fun register() {
            if (!registered) {
                windowManager.addCrossWindowBlurEnabledListener(activity.mainExecutor, listener)
                registered = true
            }
        }
        fun unregister() {
            if (registered) {
                windowManager.removeCrossWindowBlurEnabledListener(listener)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> register()
                Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            unregister()
        }
    }
    return state
}
