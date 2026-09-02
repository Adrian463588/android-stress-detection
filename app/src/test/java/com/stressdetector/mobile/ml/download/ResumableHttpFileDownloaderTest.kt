package com.stressdetector.mobile.ml.download

import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ResumableHttpFileDownloaderTest {
    private val server = MockWebServer()
    private lateinit var temporary: File
    private val originalSslFactory = HttpsURLConnection.getDefaultSSLSocketFactory()

    @Before
    fun setUp() {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("kubernetes.docker.internal")
            .build()
        server.useHttps(
            HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(),
            false
        )
        HttpsURLConnection.setDefaultSSLSocketFactory(
            HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build().sslSocketFactory()
        )
        server.start()
        temporary = Files.createTempDirectory("artifact-download-test").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        HttpsURLConnection.setDefaultSSLSocketFactory(originalSslFactory)
        temporary.deleteRecursively()
    }

    @Test
    fun `fresh download publishes exact bytes`() = runBlocking {
        val payload = "abcdef".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/octet-stream").setBody("abcdef"))
        val target = File(temporary, "artifact.bin")
        ResumableHttpFileDownloader().download(url(), target, payload.size.toLong(), "test", policy())
        assertArrayEquals(payload, target.readBytes())
        assertTrue(!File(temporary, "artifact.bin.part").exists())
    }

    @Test
    fun `tokenizer json content type is accepted by tokenizer policy`() = runBlocking {
        val payload = "{\"version\":1}".toByteArray()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json; charset=utf-8")
                .setBody(payload.decodeToString())
        )
        val target = File(temporary, "tokenizer.json")

        ResumableHttpFileDownloader().download(
            url(),
            target,
            payload.size.toLong(),
            "tokenizer",
            tokenizerPolicy()
        )

        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `html error page is rejected even when response is successful`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html")
                .setBody("<html>temporary error</html>")
        )
        val target = File(temporary, "tokenizer.json")

        val failure = runCatching {
            ResumableHttpFileDownloader().download(
                url(),
                target,
                "<html>temporary error</html>".toByteArray().size.toLong(),
                "tokenizer",
                tokenizerPolicy()
            )
        }.exceptionOrNull() as ResumableDownloadFailure

        assertFalse(failure.retryable)
        assertFalse(target.exists())
    }

    @Test
    fun `valid 206 resumes an existing partial`() = runBlocking {
        val target = File(temporary, "artifact.bin")
        File(temporary, "artifact.bin.part").writeText("abc")
        File(temporary, "artifact.bin.part.properties").writeText("etag=\"v1\"")
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes 3-5/6")
                .setHeader("ETag", "\"v1\"")
                .setBody("def")
        )
        ResumableHttpFileDownloader().download(url(), target, 6L, "test", policy())
        assertEquals("abcdef", target.readText())
        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `short response remains retryable and never activates`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/octet-stream").setBody("abc"))
        val target = File(temporary, "artifact.bin")
        val failure = runCatching {
            ResumableHttpFileDownloader().download(url(), target, 6L, "test", policy())
        }.exceptionOrNull() as ResumableDownloadFailure
        assertTrue(failure.retryable)
        assertFalse(target.exists())
    }

    @Test
    fun `partial without validator restarts from byte zero`() = runBlocking {
        val target = File(temporary, "artifact.bin")
        File(temporary, "artifact.bin.part").writeText("abc")
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setBody("abcdef")
        )

        ResumableHttpFileDownloader().download(url(), target, 6L, "test", policy())

        assertEquals("abcdef", target.readText())
        assertEquals(null, server.takeRequest().getHeader("Range"))
    }

    @Test
    fun `changed etag discards partial and restarts`() = runBlocking {
        val target = File(temporary, "artifact.bin")
        File(temporary, "artifact.bin.part").writeText("abc")
        File(temporary, "artifact.bin.part.properties").writeText("etag=\"v1\"")
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes 3-5/6")
                .setHeader("ETag", "\"v2\"")
                .setBody("def")
        )
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("ETag", "\"v2\"")
                .setBody("abcdef")
        )

        ResumableHttpFileDownloader().download(url(), target, 6L, "test", policy())

        assertEquals("bytes=3-", server.takeRequest().getHeader("Range"))
        assertEquals(null, server.takeRequest().getHeader("Range"))
        assertEquals("abcdef", target.readText())
    }

    @Test
    fun `redirect outside catalog allowlist is rejected`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(302)
                .setHeader("Location", "https://example.com/artifact.bin")
        )
        val target = File(temporary, "artifact.bin")

        val failure = runCatching {
            ResumableHttpFileDownloader().download(url(), target, 6L, "test", policy())
        }.exceptionOrNull() as ResumableDownloadFailure

        assertFalse(failure.retryable)
        assertFalse(target.exists())
    }

    @Test
    fun `catalog download tolerates synthetic private address beside public address`() = runBlocking {
        val payload = "abcdef".toByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/octet-stream").setBody("abcdef"))
        val target = File(temporary, "artifact.bin")
        val resolver = HostAddressResolver {
            arrayOf(
                InetAddress.getByName("fd00:aa:bb::1"),
                InetAddress.getByName("8.8.8.8")
            )
        }

        ResumableHttpFileDownloader(resolver).download(
            url(),
            target,
            payload.size.toLong(),
            "catalog-test",
            ArtifactDownloadPolicy(
                allowedRedirectHosts = setOf(java.net.URI(url()).host),
                allowMixedPublicAndPrivateAddresses = true
            )
        )

        assertArrayEquals(payload, target.readBytes())
    }

    @Test
    fun `all-private DNS resolution remains rejected`() = runBlocking {
        val failure = runCatching {
            ResumableHttpFileDownloader {
                arrayOf(InetAddress.getByName("fd00:aa:bb::1"))
            }.download(
                url(),
                File(temporary, "artifact.bin"),
                6L,
                "catalog-test",
                ArtifactDownloadPolicy(
                    allowedRedirectHosts = setOf(java.net.URI(url()).host),
                    allowMixedPublicAndPrivateAddresses = true
                )
            )
        }.exceptionOrNull() as ResumableDownloadFailure

        assertFalse(failure.retryable)
        assertTrue(failure.message!!.contains("private or reserved"))
    }

    private fun url(): String = server.url("/artifact.bin").toString()

    private fun policy() = ArtifactDownloadPolicy(
        setOf(java.net.URI(url()).host),
        allowPrivateAddresses = true
    )

    private fun tokenizerPolicy() = policy().copy(
        allowedContentTypes = ArtifactDownloadPolicy.DEFAULT_BINARY_CONTENT_TYPES +
            ArtifactDownloadPolicy.TOKENIZER_CONTENT_TYPES
    )
}
