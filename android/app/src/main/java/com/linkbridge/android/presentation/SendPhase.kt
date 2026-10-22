package com.linkbridge.android.presentation

/** State of the Send control. Mirrors `SendPhase` in macos/Sources/LinkBridgePresentation. */
sealed interface SendPhase {
    data object Idle : SendPhase
    data class Ready(val fileName: String) : SendPhase
    data class Sending(val fileName: String, val progress: Float) : SendPhase
    data class Sent(val fileName: String) : SendPhase
    data class Failed(val fileName: String, val message: String) : SendPhase

    val actionTitle: String
        get() = when (this) {
            Idle -> "Choose files"
            is Ready -> "Send"
            is Sending -> "Sending…"
            is Sent -> "Sent"
            is Failed -> "Retry"
        }

    /** Only a chosen (or failed) file going to a sendable device can be sent. */
    fun isEnabled(targetSendable: Boolean): Boolean = when (this) {
        is Ready, is Failed -> targetSendable
        Idle, is Sending, is Sent -> false
    }
}
