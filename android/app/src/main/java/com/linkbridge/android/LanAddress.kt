package com.linkbridge.android

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Pairing runs over plain HTTP (protected by the commit/reveal code), so the app permits
 * cleartext traffic: network security config can't limit it to private IP ranges. This check
 * is that limit: the cleartext client only talks to IP literals on the local network.
 */
object LanAddress {
    private val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val ipv6 = Regex("""^[0-9A-Fa-f:.]+$""")

    /** True for loopback, link-local, RFC 1918 and IPv6 unique-local literals. Never resolves names. */
    fun isLocal(host: String): Boolean {
        val literal = host.removePrefix("[").removeSuffix("]").substringBefore('%')
        if (!ipv4.matches(literal) && !(literal.contains(':') && ipv6.matches(literal))) return false
        val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return false
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        // fc00::/7, which InetAddress doesn't classify.
        return address is Inet6Address && (address.address[0].toInt() and 0xFE) == 0xFC
    }
}
