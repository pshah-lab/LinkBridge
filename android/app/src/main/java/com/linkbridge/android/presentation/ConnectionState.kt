package com.linkbridge.android.presentation

/** What the connection chip shows. Mirrors `ConnectionState` in macos/Sources/LinkBridgePresentation. */
sealed interface ConnectionState {
    data object Searching : ConnectionState
    data object NoPairedDevice : ConnectionState
    data class Ready(val deviceName: String) : ConnectionState
    data class Sending(val deviceName: String, val progress: Float) : ConnectionState
    data class Error(val message: String) : ConnectionState

    val title: String
        get() = when (this) {
            Searching -> "Looking for devices…"
            NoPairedDevice -> "No paired device nearby"
            is Ready -> deviceName
            is Sending -> deviceName
            is Error -> message
        }

    val dotLabel: String
        get() = when (this) {
            is Ready, is Sending -> "Connected"
            Searching, NoPairedDevice -> "Searching"
            is Error -> "Error"
        }

    /** Null means the neutral grey dot. */
    val accent: Accent?
        get() = when (this) {
            is Ready, is Sending -> Accent.SIGNAL
            is Error -> Accent.ATTENTION
            Searching, NoPairedDevice -> null
        }

    companion object {
        /** Priority: error, then an active send, then a sendable selection, then searching. */
        fun derive(
            nearbyCount: Int,
            selected: DeviceListItem?,
            sendPhase: SendPhase,
            errorMessage: String?
        ): ConnectionState {
            if (errorMessage != null) return Error(errorMessage)
            // A transfer in flight stays visible even if its target briefly drops off discovery.
            if (selected != null && sendPhase is SendPhase.Sending) return Sending(selected.name, sendPhase.progress)
            if (selected != null && selected.isSendable) return Ready(selected.name)
            return if (nearbyCount == 0) Searching else NoPairedDevice
        }
    }
}
