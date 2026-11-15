package com.linkbridge.android.offline

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * What a device says about itself while discovering peers. Field names equal the Bonjour TXT keys,
 * so the same JSON works over the offline hello and inside the QR payload. Must match the Swift
 * `DiscoveryRecord`; limits and format are in docs/protocol.md ("Offline link").
 */
data class DiscoveryRecord(
    val rid: String,
    val tags: String,
    val deviceName: String,
    val platform: String,
    val protocol: Int,
    val port: Int,
    val tlsPort: Int?,
    val features: String,
) {
    /** Keys sorted, `/` unescaped, no whitespace: byte-identical to the Swift encoder. */
    fun toJson(): String = buildString {
        append('{')
        member("deviceName", quote(deviceName)).append(',')
        member("features", quote(features)).append(',')
        member("platform", quote(platform)).append(',')
        member("port", port.toString()).append(',')
        member("protocol", protocol.toString()).append(',')
        member("rid", quote(rid)).append(',')
        member("tags", quote(tags))
        if (tlsPort != null) append(',').member("tlsPort", tlsPort.toString())
        append('}')
    }

    companion object {
        private val KEYS = setOf("rid", "tags", "deviceName", "platform", "protocol", "port", "tlsPort", "features")
        const val MAX_TAGS = 14

        /** Null for anything that is not a well-formed, in-limits record. */
        fun fromJson(text: String): DiscoveryRecord? = parseObject(text)?.let(::fromObject)

        internal fun fromObject(o: JSONObject): DiscoveryRecord? {
            if (!o.keys().asSequence().all { it in KEYS }) return null
            val tlsPort = if (!o.has("tlsPort") || o.isNull("tlsPort")) null else (o.get("tlsPort") as? Int ?: return null)
            val tags = o.opt("tags") as? String ?: return null
            val record = DiscoveryRecord(
                rid = o.opt("rid") as? String ?: return null,
                tags = tags,
                deviceName = o.opt("deviceName") as? String ?: return null,
                platform = o.opt("platform") as? String ?: return null,
                protocol = o.opt("protocol") as? Int ?: return null,
                port = o.opt("port") as? Int ?: return null,
                tlsPort = tlsPort,
                features = o.opt("features") as? String ?: return null,
            )
            val tagCount = if (tags.isEmpty()) 0 else tags.split(',').size
            if (record.port !in 1..65535 || (tlsPort != null && tlsPort !in 1..65535) || tagCount > MAX_TAGS) return null
            return record
        }
    }
}

/**
 * The text an Android hotspot shows as a QR code: how to join its Wi-Fi, where its hello endpoint
 * is, and who it is. `linkbridge-offline:1:` + base64url (no padding) of the UTF-8 JSON
 * `{"addr","hello","pass","record":{DiscoveryRecord},"ssid"}`. Shared vectors:
 * docs/test-vectors/offline-link.json.
 */
data class OfflineLinkPayload(
    val ssid: String,
    val passphrase: String,
    val address: Inet4Address,
    val helloPort: Int,
    val record: DiscoveryRecord,
) {
    fun encode(): String {
        val json = buildString {
            append('{')
            member("addr", quote(address.hostAddress ?: "")).append(',')
            member("hello", helloPort.toString()).append(',')
            member("pass", quote(passphrase)).append(',')
            member("record", record.toJson()).append(',')
            member("ssid", quote(ssid))
            append('}')
        }
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    companion object {
        const val PREFIX = "linkbridge-offline:1:"
        private const val MAX_TEXT_LENGTH = 1024
        private val TOP_KEYS = setOf("ssid", "pass", "addr", "hello", "record")

        /** Null for anything that is not a well-formed, in-limits payload. */
        fun parse(text: String): OfflineLinkPayload? {
            if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_LENGTH || !text.startsWith(PREFIX)) return null
            val json = decodeBase64Url(text.substring(PREFIX.length)) ?: return null
            val o = parseObject(String(json, Charsets.UTF_8)) ?: return null
            if (o.keys().asSequence().toSet() != TOP_KEYS) return null
            val ssid = o.opt("ssid") as? String ?: return null
            val pass = o.opt("pass") as? String ?: return null
            val hello = o.opt("hello") as? Int ?: return null
            val address = parseIPv4(o.opt("addr") as? String ?: return null) ?: return null
            val record = DiscoveryRecord.fromObject(o.optJSONObject("record") ?: return null) ?: return null
            if (!isPrivate(address) || ssid.toByteArray(Charsets.UTF_8).size !in 1..32 ||
                pass.codePointCount(0, pass.length) !in 8..63 || hello !in 1..65535
            ) return null
            return OfflineLinkPayload(ssid, pass, address, hello, record)
        }

        /** Strict dotted quad: four decimal parts, no leading zeros, nothing else. */
        private fun parseIPv4(text: String): Inet4Address? {
            val parts = text.split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            for ((i, part) in parts.withIndex()) {
                if (part.length !in 1..3 || !part.all { it in '0'..'9' } || (part.length > 1 && part[0] == '0')) return null
                val value = part.toInt()
                if (value > 255) return null
                bytes[i] = value.toByte()
            }
            return InetAddress.getByAddress(bytes) as Inet4Address
        }

        /** RFC 1918 only: 10/8, 172.16/12, 192.168/16. */
        private fun isPrivate(address: Inet4Address): Boolean {
            val b = address.address
            val first = b[0].toInt() and 0xFF
            val second = b[1].toInt() and 0xFF
            return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
        }

        /** Rejects padding, the standard alphabet and any other stray character. */
        private fun decodeBase64Url(text: String): ByteArray? {
            if (text.isEmpty() || text.length % 4 == 1) return null
            if (!text.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) return null
            return try { Base64.getUrlDecoder().decode(text) } catch (_: IllegalArgumentException) { null }
        }
    }
}

/** Parses one JSON object. Duplicate keys (org.json throws) and trailing text are invalid. */
private fun parseObject(text: String): JSONObject? = try {
    val tokener = JSONTokener(text)
    val obj = JSONObject(tokener)
    if (tokener.nextClean() != 0.toChar()) null else obj
} catch (_: JSONException) {
    null
}

private fun StringBuilder.member(key: String, value: String): StringBuilder =
    append(quote(key)).append(':').append(value)

/** JSON string literal as Swift's JSONEncoder writes it: `"` `\` and control characters escaped, nothing else. */
private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\b' -> append("\\b")
        '\u000C' -> append("\\f")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c < ' ') append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
