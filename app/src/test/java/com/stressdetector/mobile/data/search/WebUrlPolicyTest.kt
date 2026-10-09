package com.stressdetector.mobile.data.search

import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebUrlPolicyTest {
    @Test
    fun `approval rejects private reserved and documentation literals without DNS`() {
        NON_PUBLIC_ADDRESSES.forEach { address ->
            val host = if (address.contains(':')) "[$address]" else address
            assertNull(address, SecureWebUrlPolicy.normalizeForApproval("https://$host/source"))
        }
    }

    @Test
    fun `ordinary fc fd domain names remain eligible for approval`() {
        listOf("fda.gov", "fct.example", "fd.example", "fc.example").forEach { host ->
            assertNotNull(host, SecureWebUrlPolicy.normalizeForApproval("https://$host/source#section"))
        }
        assertNull(SecureWebUrlPolicy.normalizeForApproval("https://localhost/source"))
        assertNull(SecureWebUrlPolicy.normalizeForApproval("https://host.local/source"))
        assertNull(SecureWebUrlPolicy.normalizeForApproval("http://example.com/source"))
        assertNull(SecureWebUrlPolicy.normalizeForApproval("https://user:secret@example.com/source"))
    }

    @Test
    fun `public IPv4 and IPv6 literals remain eligible for approval`() {
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111", "2001:4860:4860::8888", "64:ff9b::808:808").forEach { address ->
            val host = if (address.contains(':')) "[$address]" else address
            assertNotNull(address, SecureWebUrlPolicy.normalizeForApproval("https://$host/source"))
        }
    }

    @Test
    fun `DNS rejects nonpublic answer even when public answer is present`() {
        val url = "https://source.example/article".toHttpUrl()
        val public = InetAddress.getByName("8.8.8.8")
        NON_PUBLIC_ADDRESSES.forEach { address ->
            val dns = fixedDns(listOf(public, InetAddress.getByName(address)))
            assertNull(address, SecureWebUrlPolicy.resolvePublicAddresses(url, dns))
        }
        // Inet6Address can preserve the mapped form that InetAddress normally converts to IPv4.
        val mappedPrivate = Inet6Address.getByAddress(
            null,
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, -1, 10, 0, 0, 1),
            -1
        )
        assertNull(SecureWebUrlPolicy.resolvePublicAddresses(url, fixedDns(listOf(public, mappedPrivate))))
    }

    @Test
    fun `DNS returns exact approved addresses from one lookup for pinning`() {
        val addresses = listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("2606:4700:4700::1111"))
        var lookups = 0
        val dns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                lookups++
                assertEquals("fda.gov", hostname)
                return addresses
            }
        }

        assertEquals(addresses, SecureWebUrlPolicy.resolvePublicAddresses("https://fda.gov/article".toHttpUrl(), dns))
        assertEquals(1, lookups)
    }

    @Test
    fun `empty failed or unsafe DNS requests fail closed`() {
        val url = "https://source.example/article".toHttpUrl()
        assertNull(SecureWebUrlPolicy.resolvePublicAddresses(url, fixedDns(emptyList())))
        val failingDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("unavailable")
        }
        assertNull(SecureWebUrlPolicy.resolvePublicAddresses(url, failingDns))
        assertNull(SecureWebUrlPolicy.resolvePublicAddresses("http://source.example/article".toHttpUrl(), failingDns))
    }

    private fun fixedDns(addresses: List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = addresses
    }

    private companion object {
        val NON_PUBLIC_ADDRESSES = listOf(
            "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.254", "127.0.0.1",
            "169.254.1.1", "172.16.0.1", "192.168.0.1", "192.0.0.1", "192.0.2.1",
            "192.88.99.1", "198.18.0.1", "198.19.255.254", "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "240.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fd12::1",
            "fe80::1", "fec0::1", "ff02::1", "100::1", "2001:2::1", "2001:db8::1", "3fff::1",
            "64:ff9b:1::1", "64:ff9b::a00:1", "2002:a00:1::1", "::ffff:10.0.0.1"
        )
    }
}
