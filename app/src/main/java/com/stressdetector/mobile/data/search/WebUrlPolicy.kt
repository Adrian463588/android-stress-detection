package com.stressdetector.mobile.data.search

import com.stressdetector.mobile.domain.search.WebSearchLimits
import java.net.InetAddress
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object SecureWebUrlPolicy {
    fun normalize(rawUrl: String): HttpUrl? {
        val parsed = normalizeForApproval(rawUrl) ?: return null
        if (!isPublicHost(parsed.host)) return null
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

    fun isAllowed(url: HttpUrl): Boolean = url.scheme == HTTPS_SCHEME &&
        url.username.isEmpty() &&
        url.password.isEmpty() &&
        isPublicHost(url.host)

    /** Resolves once on the crawler boundary so the HTTP client can pin that public answer. */
    fun resolvePublicAddresses(url: HttpUrl): List<InetAddress>? {
        if (!isPotentiallyPublicHost(url.host)) return null
        return runCatching {
            InetAddress.getAllByName(url.host.trim().lowercase(Locale.US).trimEnd('.'))
                .toList()
                .takeIf { it.isNotEmpty() && it.all(::isPublicAddress) }
        }.getOrNull()
    }

    private fun isPublicHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank() || normalized == "localhost" || normalized.endsWith(".local")) return false
        if (normalized == "::1" ||
            normalized.startsWith("fc") ||
            normalized.startsWith("fd") ||
            normalized.startsWith("fe80:")
        ) {
            return false
        }
        return resolvePublicAddresses(
            "https://$normalized".toHttpUrlOrNull() ?: return false
        ) != null
    }

    private fun isPotentiallyPublicHost(host: String): Boolean {
        val normalized = host.trim().lowercase(Locale.US).trimEnd('.')
        if (normalized.isBlank() || normalized == "localhost" || normalized.endsWith(".local")) return false
        if (normalized == "::1" ||
            normalized.startsWith("fc") ||
            normalized.startsWith("fd") ||
            normalized.startsWith("fe80:")
        ) {
            return false
        }
        if (!normalized.matches(Regex("\\d+(?:\\.\\d+){3}"))) return true
        val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        val first = octets[0]
        val second = octets[1]
        return when {
            first == 10 || first == 127 -> false
            first == 169 && second == 254 -> false
            first == 192 && second == 168 -> false
            first == 172 && second in 16..31 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicAddress(address: InetAddress): Boolean = !address.isAnyLocalAddress &&
        !address.isLoopbackAddress &&
        !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress &&
        !address.isMulticastAddress

    private const val HTTPS_SCHEME = "https"
}
