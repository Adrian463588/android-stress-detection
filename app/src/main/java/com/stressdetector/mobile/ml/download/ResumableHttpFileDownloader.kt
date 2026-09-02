package com.stressdetector.mobile.ml.download

import android.net.Network
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Network policy for a pinned binary artifact. Wildcards are limited to a leading `*.`. */
data class ArtifactDownloadPolicy(
    val allowedRedirectHosts: Set<String>,
    val allowedContentTypes: Set<String> = DEFAULT_BINARY_CONTENT_TYPES,
    val allowPrivateAddresses: Boolean = false,
    /**
     * Some managed Android networks publish a synthetic private IPv6 record beside a public CDN
     * record. Catalog-backed downloads may continue only when a public address is also present;
     * all-private resolution remains rejected.
     */
    val allowMixedPublicAndPrivateAddresses: Boolean = false
) {
    init {
        require(allowedRedirectHosts.isNotEmpty()) { "At least one artifact host must be allowed." }
    }

    internal fun permitsHost(host: String): Boolean {
        val normalized = host.lowercase().trimEnd('.')
        return allowedRedirectHosts.any { rule ->
            val allowed = rule.lowercase().trimEnd('.')
            if (allowed.startsWith("*.")) {
                normalized.endsWith(allowed.removePrefix("*")) && normalized != allowed.removePrefix("*.")
            } else {
                normalized == allowed
            }
        }
    }

    internal fun permitsContentType(value: String?): Boolean {
        val normalized = value?.substringBefore(';')?.trim()?.lowercase() ?: return true
        return normalized in allowedContentTypes
    }

    companion object {
        val DEFAULT_BINARY_CONTENT_TYPES = setOf(
            "application/octet-stream",
            "application/zip",
            "application/x-bzip2",
            "application/x-tar",
            "binary/octet-stream",
            "text/plain"
        )
        val TOKENIZER_CONTENT_TYPES = setOf(
            "application/json",
            "text/json"
        )

        /** Current immutable catalogs resolve only through these artifact CDNs. */
        fun forPinnedArtifact(url: String): ArtifactDownloadPolicy {
            val origin = URI(url).host?.lowercase() ?: error("Artifact URL has no host.")
            return ArtifactDownloadPolicy(
                allowedRedirectHosts = setOf(
                    origin,
                    "github.com",
                    "objects.githubusercontent.com",
                    "release-assets.githubusercontent.com",
                    "huggingface.co",
                    "*.huggingface.co",
                    "hf.co",
                    "*.hf.co"
                )
            )
        }

        fun forTokenizerArtifact(url: String): ArtifactDownloadPolicy {
            val binary = forPinnedArtifact(url)
            return binary.copy(allowedContentTypes = DEFAULT_BINARY_CONTENT_TYPES + TOKENIZER_CONTENT_TYPES)
        }
    }
}

/**
 * Shared HTTPS Range downloader for catalog-pinned model artifacts.
 *
 * The caller remains responsible for SHA-256 verification before activation. This class guarantees
 * that a partial file is never combined across different server validators and that redirects cannot
 * escape the artifact catalog's host boundary.
 */
fun interface HostAddressResolver {
    fun resolve(host: String): Array<InetAddress>
}

class ResumableHttpFileDownloader(private val hostAddressResolver: HostAddressResolver = HostAddressResolver(InetAddress::getAllByName)) {
    suspend fun download(
        url: String,
        destination: File,
        expectedBytes: Long,
        label: String,
        policy: ArtifactDownloadPolicy = ArtifactDownloadPolicy.forPinnedArtifact(url),
        network: Network? = null,
        onProgress: suspend (Long) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(expectedBytes > 0L) { "$label expected size must be positive." }
        destination.parentFile?.mkdirs()
        val partial = File(destination.parentFile, "${destination.name}.part")
        val metadata = File(destination.parentFile, "${destination.name}.part.properties")

        if (destination.isFile && destination.length() == expectedBytes) {
            onProgress(expectedBytes)
            return@withContext destination
        }
        if (destination.exists() && !destination.delete()) {
            throw ResumableDownloadFailure("Stale $label destination cannot be removed.", retryable = false)
        }
        if (partial.length() > expectedBytes) clearPartial(partial, metadata)
        if (partial.length() == expectedBytes) {
            finalizePartial(partial, destination, metadata, label)
            onProgress(expectedBytes)
            return@withContext destination
        }

        var restartCount = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val offset = partial.length().coerceAtLeast(0L)
            val validator = ResumeValidator.read(metadata)
            if (offset > 0L && validator == null) {
                clearPartial(partial, metadata)
                continue
            }
            val opened = try {
                openConnection(
                    initialUrl = URL(url),
                    offset = offset,
                    validator = validator,
                    policy = policy,
                    network = network,
                    label = label
                )
            } catch (error: ResumableDownloadFailure) {
                throw error
            } catch (error: IOException) {
                throw ResumableDownloadFailure(
                    error.message ?: "Network error opening $label.",
                    retryable = true
                )
            }
            val connection = opened.connection
            try {
                val responseCode = connection.responseCode
                if (responseCode == HTTP_RANGE_NOT_SATISFIABLE) {
                    val declaredTotal = parseUnsatisfiedTotal(connection.getHeaderField("Content-Range"))
                    if (partial.length() == expectedBytes && declaredTotal == expectedBytes) {
                        finalizePartial(partial, destination, metadata, label)
                        onProgress(expectedBytes)
                        return@withContext destination
                    }
                    if (restartCount++ >= MAX_RESTARTS) {
                        throw ResumableDownloadFailure("$label resume range was rejected repeatedly.", retryable = false)
                    }
                    clearPartial(partial, metadata)
                    continue
                }

                val append = offset > 0L && responseCode == HttpURLConnection.HTTP_PARTIAL
                when {
                    append -> validateContentRange(
                        value = connection.getHeaderField("Content-Range"),
                        offset = offset,
                        total = expectedBytes
                    )
                    responseCode == HttpURLConnection.HTTP_OK -> Unit
                    else -> throw httpFailure(responseCode, label)
                }

                if (!policy.permitsContentType(connection.contentType)) {
                    throw ResumableDownloadFailure(
                        "$label returned unsupported content type ${connection.contentType}.",
                        retryable = false
                    )
                }

                val responseValidator = ResumeValidator.from(connection)
                if (append && validator != null && responseValidator.conflictsWith(validator)) {
                    if (restartCount++ >= MAX_RESTARTS) {
                        throw ResumableDownloadFailure("$label changed while resuming.", retryable = false)
                    }
                    clearPartial(partial, metadata)
                    continue
                }
                if (!append && offset > 0L) {
                    if (restartCount++ >= MAX_RESTARTS) {
                        throw ResumableDownloadFailure("$label server does not support stable resume.", retryable = false)
                    }
                    clearPartial(partial, metadata)
                }

                val startingBytes = if (append) offset else 0L
                validateContentLength(connection.contentLengthLong, startingBytes, expectedBytes, label)
                responseValidator.takeUnless(ResumeValidator::isEmpty)?.write(metadata)
                copyResponse(
                    connection = connection,
                    partial = partial,
                    append = append,
                    startingBytes = startingBytes,
                    expectedBytes = expectedBytes,
                    label = label,
                    onProgress = onProgress
                )
                if (partial.length() != expectedBytes) {
                    throw ResumableDownloadFailure("$label download is incomplete.", retryable = true)
                }
                finalizePartial(partial, destination, metadata, label)
                return@withContext destination
            } catch (error: CancellationException) {
                throw error
            } catch (error: ResumableDownloadFailure) {
                throw error
            } catch (error: IOException) {
                throw ResumableDownloadFailure(
                    error.message ?: "Network error downloading $label.",
                    retryable = true
                )
            } finally {
                connection.disconnect()
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("Unreachable download state")
    }

    private suspend fun copyResponse(
        connection: HttpURLConnection,
        partial: File,
        append: Boolean,
        startingBytes: Long,
        expectedBytes: Long,
        label: String,
        onProgress: suspend (Long) -> Unit
    ) {
        connection.inputStream.use { input ->
            FileOutputStream(partial, append).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var downloaded = startingBytes
                while (downloaded < expectedBytes) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), expectedBytes - downloaded).toInt())
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onProgress(downloaded)
                }
                if (downloaded == expectedBytes && input.read() >= 0) {
                    throw ResumableDownloadFailure("$label exceeded its catalog size.", retryable = false)
                }
                output.fd.sync()
            }
        }
    }

    private fun openConnection(
        initialUrl: URL,
        offset: Long,
        validator: ResumeValidator?,
        policy: ArtifactDownloadPolicy,
        network: Network?,
        label: String
    ): OpenedConnection {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            validateUrl(current, policy, label)
            val connection = ((network?.openConnection(current) ?: current.openConnection()) as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "StressDetector-ArtifactDownloader/1.0")
                setRequestProperty("Accept", policy.allowedContentTypes.joinToString(", "))
                if (offset > 0L) {
                    setRequestProperty("Range", "bytes=$offset-")
                    validator?.ifRangeValue()?.let { setRequestProperty("If-Range", it) }
                }
            }
            val code = connection.responseCode
            // Resolve again after the connection was established to reject DNS rebinding to a
            // private/reserved address between policy validation and the HTTP response.
            validateUrl(current, policy, label)
            if (code !in REDIRECT_CODES) return OpenedConnection(connection)
            val location = connection.getHeaderField("Location")
                ?: run {
                    connection.disconnect()
                    throw ResumableDownloadFailure("$label redirect has no Location.", retryable = false)
                }
            if (redirectCount >= MAX_REDIRECTS) {
                connection.disconnect()
                throw ResumableDownloadFailure("$label exceeded the redirect limit.", retryable = false)
            }
            current = URL(current, location)
            connection.disconnect()
        }
        error("Unreachable redirect state")
    }

    private fun validateUrl(url: URL, policy: ArtifactDownloadPolicy, label: String) {
        if (!url.protocol.equals("https", ignoreCase = true)) {
            throw ResumableDownloadFailure("$label download must use HTTPS.", retryable = false)
        }
        val host = url.host?.lowercase()?.trimEnd('.').orEmpty()
        if (host.isBlank() || !policy.permitsHost(host)) {
            throw ResumableDownloadFailure("$label redirect host is not approved: $host", retryable = false)
        }
        if (isIpLiteral(host)) {
            throw ResumableDownloadFailure("$label URL must not use an IP literal.", retryable = false)
        }
        if (!policy.allowPrivateAddresses) {
            val addresses = hostAddressResolver.resolve(host)
            val hasPublicAddress = addresses.any { !isPrivateAddress(it) }
            val hasPrivateAddress = addresses.any(::isPrivateAddress)
            val mixedAddressSetRejected = hasPrivateAddress && !policy.allowMixedPublicAndPrivateAddresses
            if (addresses.isEmpty() || !hasPublicAddress || mixedAddressSetRejected) {
                throw ResumableDownloadFailure(
                    "$label host resolved to a private or reserved address.",
                    retryable = false
                )
            }
        }
    }

    private fun validateContentLength(length: Long, offset: Long, expected: Long, label: String) {
        if (length < 0L) return
        val remaining = expected - offset
        if (length > remaining) {
            throw ResumableDownloadFailure("$label response exceeds its catalog size.", retryable = false)
        }
    }

    private fun validateContentRange(value: String?, offset: Long, total: Long) {
        val match = CONTENT_RANGE_PATTERN.matchEntire(value.orEmpty())
            ?: throw ResumableDownloadFailure("Resume endpoint returned an invalid Content-Range.", retryable = true)
        val start = match.groupValues[1].toLong()
        val end = match.groupValues[2].toLong()
        val declaredTotal = match.groupValues[3].toLong()
        if (start != offset || end < start || end >= total || declaredTotal != total) {
            throw ResumableDownloadFailure("Resume endpoint returned an incompatible Content-Range.", retryable = false)
        }
    }

    private fun parseUnsatisfiedTotal(value: String?): Long? =
        UNSATISFIED_RANGE_PATTERN.matchEntire(value.orEmpty())?.groupValues?.get(1)?.toLongOrNull()

    private fun finalizePartial(partial: File, destination: File, metadata: File, label: String) {
        try {
            Files.move(
                partial.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(partial.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        if (!destination.isFile) {
            throw ResumableDownloadFailure("$label download could not be finalized.", retryable = false)
        }
        metadata.delete()
    }

    private fun clearPartial(partial: File, metadata: File) {
        if (partial.exists() && !partial.delete()) {
            throw ResumableDownloadFailure("Stale partial artifact cannot be removed.", retryable = false)
        }
        metadata.delete()
    }

    private fun httpFailure(code: Int, label: String) = ResumableDownloadFailure(
        "HTTP $code while downloading $label.",
        retryable = code >= 500 || code == 408 || code == 429
    )

    private fun isIpLiteral(host: String): Boolean = host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) || ':' in host

    private fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress ||
            address.isLoopbackAddress ||
            address.isLinkLocalAddress ||
            address.isSiteLocalAddress ||
            address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        if (bytes.size == 4) {
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            val third = bytes[2].toInt() and 0xff
            return first == 0 ||
                first == 10 ||
                (first == 100 && second in 64..127) ||
                first == 127 ||
                (first == 169 && second == 254) ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 0 && third in setOf(0, 2)) ||
                (first == 192 && second == 168) ||
                (first == 198 && second in setOf(18, 19, 51)) ||
                (first == 203 && second == 0 && third == 113) ||
                first >= 224
        }
        if (bytes.size != 16) return true
        val uniqueLocal = (bytes[0].toInt() and 0xfe) == 0xfc
        val documentation = bytes[0] == 0x20.toByte() &&
            bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x0d.toByte() &&
            bytes[3] == 0xb8.toByte()
        return uniqueLocal || documentation
    }

    private data class OpenedConnection(val connection: HttpURLConnection)

    private data class ResumeValidator(val etag: String?, val lastModified: String?) {
        fun isEmpty(): Boolean = etag.isNullOrBlank() && lastModified.isNullOrBlank()

        fun ifRangeValue(): String? = etag?.takeIf(String::isNotBlank)
            ?: lastModified?.takeIf(String::isNotBlank)

        fun conflictsWith(previous: ResumeValidator): Boolean = when {
            !etag.isNullOrBlank() && !previous.etag.isNullOrBlank() -> etag != previous.etag
            !lastModified.isNullOrBlank() && !previous.lastModified.isNullOrBlank() ->
                lastModified != previous.lastModified
            else -> false
        }

        fun write(file: File) {
            val properties = Properties().apply {
                etag?.let { setProperty(KEY_ETAG, it) }
                lastModified?.let { setProperty(KEY_LAST_MODIFIED, it) }
            }
            file.outputStream().use { output -> properties.store(output, null) }
        }

        companion object {
            fun from(connection: HttpURLConnection) = ResumeValidator(
                etag = connection.getHeaderField("ETag"),
                lastModified = connection.getHeaderField("Last-Modified")
            )

            fun read(file: File): ResumeValidator? = runCatching {
                if (!file.isFile) return null
                val properties = Properties().also { values -> file.inputStream().use(values::load) }
                ResumeValidator(
                    etag = properties.getProperty(KEY_ETAG),
                    lastModified = properties.getProperty(KEY_LAST_MODIFIED)
                ).takeUnless(ResumeValidator::isEmpty)
            }.getOrNull()

            private const val KEY_ETAG = "etag"
            private const val KEY_LAST_MODIFIED = "last_modified"
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 30_000
        const val READ_TIMEOUT_MS = 60_000
        const val BUFFER_SIZE = 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val MAX_RESTARTS = 2
        const val HTTP_RANGE_NOT_SATISFIABLE = 416
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val CONTENT_RANGE_PATTERN = Regex("bytes (\\d+)-(\\d+)/(\\d+)")
        val UNSATISFIED_RANGE_PATTERN = Regex("bytes \\*/(\\d+)")
    }
}

class ResumableDownloadFailure(message: String, val retryable: Boolean) : IOException(message)
