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
    /** The advertised per-launch ID and paired-device tags; used to recognise one peer seen two ways. */
    val rid: String? = null,
    val tags: String = ""
) {
    val endpoint: String
        get() = "$host:$port"
}

