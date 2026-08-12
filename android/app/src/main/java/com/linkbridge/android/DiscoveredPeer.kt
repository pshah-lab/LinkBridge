package com.linkbridge.android

data class DiscoveredPeer(
    val serviceName: String,
    val deviceId: String?,
    val deviceName: String,
    val platform: String?,
    val host: String,
    val port: Int
) {
    val endpoint: String
        get() = "$host:$port"
}

