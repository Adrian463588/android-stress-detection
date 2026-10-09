package com.stressdetector.mobile.data.search

import com.stressdetector.mobile.domain.search.WebSearchLimits
import java.net.InetAddress
import java.util.Locale
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SecureWebUrlPolicy {
    fun normalize(rawUrl: String): HttpUrl? {
        val parsed = normalizeForApproval(rawUrl) ?: return null
        if (resolvePublicAddresses(parsed) == null) return null
        return parsed
    }

    /**
     * Synchronous UI validation: syntax and obviously private literals only.
     * DNS is deliberately deferred to [normalize] on the crawler's IO boundary.
     */
    fun normalizeForApproval(rawUrl: String): HttpUrl? {
        val normalizedInput = rawUrl.trim()
        if (normalizedInput.length > WebSearchLimits.MAX_SOURCE_URL_LENGTH) return null
        val parsed = normalizedInput.toHttpUrlOrNull() ?: return null
        if (parsed.scheme != HTTPS_SCHEME || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return null
        if (!isPotentiallyPublicHost(parsed.host)) return null
        return parsed.newBuilder().fragment(null).build()
    }

    fun isAllowed(url: HttpUrl): Boolean = resolvePublicAddresses(url) != null

    /** Resolves once on the crawler boundary so the HTTP client can pin that public answer. */
    fun resolvePublicAddresses(url: HttpUrl, dns: Dns = Dns.SYSTEM): List<InetAddress>? {
        if (!hasPublicOriginSyntax(url) || !isPotentiallyPublicHost(url.host)) return null
        return runCatching {
            dns.lookup(url.host.trim().lowercase(Locale.US).trimEnd('.'))
                .takeIf { it.isNotEmpty() && it.all(::isPublicAddress) }
        }.getOrNull()
    }

    private fun hasPublicOriginSyntax(url: HttpUrl): Boolean =
        url.scheme == HTTPS_SCHEME && url.username.isEmpty() && url.password.isEmpty()

    private fun isPotentiallyPublicHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank() || PRIVATE_HOST_SUFFIXES.any { normalized == it || normalized.endsWith(".$it") }) {
            return false
        }
        // HttpUrl has already parsed IPv6 syntax. Ordinary fc/fd hostnames never enter this branch.
        if (':' !in normalized && !IPV4_LITERAL.matches(normalized)) return true
        return runCatching { isPublicAddress(InetAddress.getByName(normalized)) }.getOrDefault(false)
    }

    private fun isPublicAddress(address: InetAddress): Boolean {
        if (listOf(
                address.isAnyLocalAddress,
                address.isLoopbackAddress,
                address.isLinkLocalAddress,
                address.isSiteLocalAddress,
                address.isMulticastAddress
            ).any { it }
        ) {
            return false
        }
        val bytes = address.address
        return when (bytes.size) {
            IPV4_BYTES -> bytes.inRanges(PUBLIC_IPV4_EXCEPTIONS) || !bytes.inRanges(NON_PUBLIC_IPV4)
            IPV6_BYTES -> isPublicIpv6(bytes)
            else -> false
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean = when {
        bytes.hasPrefix(IPV4_MAPPED_PREFIX, EMBEDDED_IPV4_PREFIX_BITS) ||
            bytes.hasPrefix(NAT64_PREFIX, EMBEDDED_IPV4_PREFIX_BITS) ->
            isPublicAddress(InetAddress.getByAddress(bytes.takeLast(IPV4_BYTES).toByteArray()))
        else -> bytes.hasPrefix(GLOBAL_IPV6_PREFIX, GLOBAL_IPV6_PREFIX_BITS) &&
            (bytes.inRanges(PUBLIC_IPV6_EXCEPTIONS) || !bytes.inRanges(NON_PUBLIC_IPV6))
    }

    private fun ByteArray.inRanges(ranges: List<Pair<ByteArray, Int>>): Boolean = ranges.any { (prefix, bits) ->
        hasPrefix(prefix, bits)
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray, bits: Int): Boolean {
        if (size != prefix.size) return false
        val wholeBytes = bits / Byte.SIZE_BITS
        val remainingBits = bits % Byte.SIZE_BITS
        val mask = (UNSIGNED_BYTE_MASK shl (Byte.SIZE_BITS - remainingBits)) and UNSIGNED_BYTE_MASK
        return (0 until wholeBytes).all { this[it] == prefix[it] } &&
            (remainingBits == 0 || (this[wholeBytes].toInt() and mask) == (prefix[wholeBytes].toInt() and mask))
    }

    private const val HTTPS_SCHEME = "https"
    private const val IPV4_BYTES = 4
    private const val IPV6_BYTES = 16
    private const val EMBEDDED_IPV4_PREFIX_BITS = 96
    private const val GLOBAL_IPV6_PREFIX_BITS = 3
    private const val UNSIGNED_BYTE_MASK = 0xff
    private val PRIVATE_HOST_SUFFIXES = setOf("localhost", "local")
    private val IPV4_LITERAL = Regex("\\d+(?:\\.\\d+){3}")

    // IANA special-purpose ranges: non-public, documentation, benchmarking, and deprecated tunnels.
    private val NON_PUBLIC_IPV4 = ranges(
        "0.0.0.0/8", "10.0.0.0/8", "100.64.0.0/10", "127.0.0.0/8",
        "169.254.0.0/16", "172.16.0.0/12", "192.0.0.0/24", "192.0.2.0/24",
        "192.88.99.0/24", "192.168.0.0/16", "198.18.0.0/15",
        "198.51.100.0/24", "203.0.113.0/24", "224.0.0.0/3"
    )
    private val PUBLIC_IPV4_EXCEPTIONS = ranges("192.0.0.9/32", "192.0.0.10/32")
    private val NON_PUBLIC_IPV6 = ranges("2001::/23", "2001:db8::/32", "2002::/16", "3fff::/20")
    private val PUBLIC_IPV6_EXCEPTIONS = ranges(
        "2001:1::1/128",
        "2001:1::2/128",
        "2001:1::3/128",
        "2001:3::/32",
        "2001:4:112::/48",
        "2001:20::/28",
        "2001:30::/28"
    )
    private val GLOBAL_IPV6_PREFIX = InetAddress.getByName("2000::").address
    private val IPV4_MAPPED_PREFIX = ByteArray(IPV6_BYTES).also {
        it[IPV6_BYTES - IPV4_BYTES - 2] = -1
        it[IPV6_BYTES - IPV4_BYTES - 1] = -1
    }
    private val NAT64_PREFIX = InetAddress.getByName("64:ff9b::").address
}

private fun ranges(vararg entries: String): List<Pair<ByteArray, Int>> = entries.map { cidr ->
    InetAddress.getByName(cidr.substringBefore('/')).address to cidr.substringAfter('/').toInt()
}
