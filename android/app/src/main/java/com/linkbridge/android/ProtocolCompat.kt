package com.linkbridge.android

import org.json.JSONObject

/** The protocol a device speaks and the oldest it still accepts. See "Versions" in docs/protocol.md. */
data class ProtocolLevel(val current: Int, val minimum: Int) {
    companion object {
        const val CURRENT = DiscoveryService.PROTOCOL_VERSION
        const val MINIMUM = 2
        val LOCAL = ProtocolLevel(CURRENT, MINIMUM)
        private val LEGACY = ProtocolLevel(1, 1)

        private fun positive(value: String?): Int? = value?.toIntOrNull()?.takeIf { it > 0 }

        /** From a TXT record. Without a TLS port the peer predates protocol 2 and can't receive. */
        @Suppress("UNUSED_PARAMETER")
        fun fromTxt(protocol: String?, minProtocol: String?, hasTlsPort: Boolean, hasDeviceId: Boolean): ProtocolLevel {
            val current = positive(protocol)
            if (!hasTlsPort && current == null) return LEGACY
            val value = current ?: 2
            return ProtocolLevel(value, positive(minProtocol) ?: minOf(2, value))
        }

        /**
         * From `/device`, `/pair/request` or upload headers. The older `protocolVersion` field is stale
         * on every platform and ignored; a missing `protocol` means 2 (TLS shipped before pairing v3).
         */
        fun fromJson(protocol: Int?, minProtocol: Int?): ProtocolLevel {
            val current = protocol?.takeIf { it > 0 } ?: return ProtocolLevel(2, 2)
            return ProtocolLevel(current, minProtocol?.takeIf { it > 0 } ?: minOf(2, current))
        }
    }
}

enum class Compatibility { OK, PEER_TOO_OLD, LOCAL_TOO_OLD }

object ProtocolCompat {
    fun check(local: ProtocolLevel = ProtocolLevel.LOCAL, peer: ProtocolLevel): Compatibility = when {
        peer.current < local.minimum -> Compatibility.PEER_TOO_OLD
        local.current < peer.minimum -> Compatibility.LOCAL_TOO_OLD
        else -> Compatibility.OK
    }

    fun message(compatibility: Compatibility, peerName: String): String? = when (compatibility) {
        Compatibility.OK -> null
        Compatibility.PEER_TOO_OLD -> "Update LinkBridge on $peerName"
        Compatibility.LOCAL_TOO_OLD -> "Update LinkBridge on this device"
    }

    /** What a receiver tells its own user after refusing a sender; [compatibility] is `check(receiver, sender)`. */
    fun receiverNotice(compatibility: Compatibility, senderName: String): String? = when (compatibility) {
        Compatibility.OK -> null
        Compatibility.PEER_TOO_OLD -> "Update LinkBridge on $senderName to receive its files."
        Compatibility.LOCAL_TOO_OLD -> "Update LinkBridge on this device to receive files from $senderName."
    }

    /** Reads a `426 update_required` body from the requester's side; null for anything else. */
    fun rejection(body: String): Compatibility? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optString("error") != "update_required") return null
        return when (json.optString("update")) {
            "sender" -> Compatibility.LOCAL_TOO_OLD
            "receiver" -> Compatibility.PEER_TOO_OLD
            else -> null
        }
    }

    /** The receiver's `426` body, given `check(local = receiver, peer = sender)`. */
    fun rejectionBody(compatibility: Compatibility, local: ProtocolLevel = ProtocolLevel.LOCAL): String {
        val update = if (compatibility == Compatibility.LOCAL_TOO_OLD) "receiver" else "sender"
        return """{"error":"update_required","update":"$update","protocol":${local.current},"minProtocol":${local.minimum}}"""
    }
}

/** Thrown by pairing and sending; its message is shown as is. */
class UpdateRequiredException(message: String) : IllegalStateException(message)
