package com.linkbridge.android

data class DiscoveredPeer(
    val serviceName: String,
    val deviceId: String?,
    val deviceName: String,
    val platform: String?,
    val host: String,
    val port: Int,
    /** TLS-PSK port for transfers (protocol 2); null when the peer runs an older app. */
    val tlsPort: Int? = null
) {
    val endpoint: String
        get() = "$host:$port"
}

