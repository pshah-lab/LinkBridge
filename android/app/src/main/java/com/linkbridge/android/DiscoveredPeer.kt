package com.linkbridge.android

data class DiscoveredPeer(
    val serviceName: String,
    val deviceId: String?,
    val deviceName: String,
    val platform: String?,
    val host: String,
    val port: Int,
    /** TLS-PSK port for transfers (protocol 2); null when the peer runs an older app. */
    val tlsPort: Int? = null,
    /** From the TXT `protocol` / `minProtocol` keys; defaults to what a 0.2.x (or pre-TLS) record implies. */
    val level: ProtocolLevel = ProtocolLevel.fromTxt(if (tlsPort != null) "2" else null, null, tlsPort != null, deviceId != null)
) {
    val endpoint: String
        get() = "$host:$port"
}

