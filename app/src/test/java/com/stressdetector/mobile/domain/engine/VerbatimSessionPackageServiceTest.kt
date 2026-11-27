@file:Suppress("MagicNumber", "LongMethod")

package com.stressdetector.mobile.domain.engine

import android.content.Context
import com.stressdetector.mobile.data.db.DivergenceEventEntity
import com.stressdetector.mobile.security.EncryptedFileRef
import com.stressdetector.mobile.security.EncryptedStorageService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class VerbatimSessionPackageServiceTest {

    private lateinit var cacheRoot: java.io.File
    private lateinit var repository: InMemoryVerbatimSessionRepository
    private lateinit var storage: InMemoryEncryptedStorage
    private lateinit var service: VerbatimSessionPackageService

    @Before
    fun setUp() {
        cacheRoot = Files.createTempDirectory("verbatim-pkg-test").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.cacheDir).thenReturn(cacheRoot)
        repository = InMemoryVerbatimSessionRepository()
        storage = InMemoryEncryptedStorage()
        service = VerbatimSessionPackageService(context, storage, repository)
    }

    @After
    fun tearDown() {
        cacheRoot.deleteRecursively()
    }

    @Test
    fun exportAndImportRoundTripPreservesExactTemperatureAndMarkersWithoutRegeneration() = runBlocking {
        val sampleA = SkinTemperatureObservation(
            sampleId = "s-orig:temp:1:1:0",
            sessionId = "s-orig",
            generation = 1L,
            sequenceNumber = 1L,
            sensorTimestampMs = 1_760_000_060_000L,
            receivedTimestampMs = 1_760_000_060_100L,
            elapsedMillis = 60_000L,
            clockUncertaintyMs = 250L,
            skinTemperatureC = 33.4123f,
            ambientTemperatureC = 26.2109f,
            measurementStatus = 0,
            isValid = true,
            wearerRole = "CLIENT",
            source = "WATCH_SDK_SKIN_TEMP"
        )
        val sampleB = SkinTemperatureObservation(
            sampleId = "s-orig:temp:1:2:0",
            sessionId = "s-orig",
            generation = 1L,
            sequenceNumber = 2L,
            sensorTimestampMs = 1_760_000_120_000L,
            receivedTimestampMs = 1_760_000_120_100L,
            elapsedMillis = 120_000L,
            clockUncertaintyMs = 250L,
            skinTemperatureC = null,
            ambientTemperatureC = 26.20f,
            measurementStatus = -2,
            isValid = false,
            wearerRole = "CLIENT",
            source = "WATCH_SDK_SKIN_TEMP"
        )
        val marker = DivergenceEventEntity(
            eventId = "s-orig:thermal:1",
            sessionId = "s-orig",
            timestampMs = 95_000L,
            delta = -0.6f,
            currentProbability = null,
            baselineProbability = null,
            copyText = "Suhu kulit menurun (-0.6 °C terhadap baseline)",
            channel = "SKIN_TEMPERATURE",
            markerElapsedMillis = 95_000L,
            episodeStartElapsedMillis = 70_000L,
            episodeEndElapsedMillis = 120_000L,
            markerType = ThermalPolicyConfig.MARKER_TYPE_THERMAL_DIVERGENCE,
            sourceChannels = "SKIN_TEMPERATURE",
            markerConfigVersion = "thermal-median-v1",
            baselineValue = 33.4f,
            observedValue = 32.8f,
            deltaValue = -0.6f,
            unit = "°C",
            detectedAtElapsedMillis = 95_000L,
            thermalDirection = "DECREASE",
            ambientTemperatureC = 26.2f,
            ambientDeltaC = -0.01f,
            slopeCelsiusPerMin = -0.35f
        )
        val detail = VerbatimSessionDetail(
            summary = VerbatimSessionSummary(
                sessionId = "s-orig",
                sessionName = "Sesi Thermal QA",
                startedAtMillis = 1_760_000_000_000L,
                endedAtMillis = 1_760_000_180_000L,
                durationSeconds = 180L,
                audioFilePath = "",
                artifactFolderPath = "verbatim_sessions/test",
                finalizationStatus = "COMPLETED",
                summaryStatus = "GENERATED",
                watchWearerRole = "CLIENT"
            ),
            transcript = listOf(
                SpeechSegment(
                    id = "seg-1",
                    speakerId = "Client",
                    startOffsetMs = 68_000L,
                    endOffsetMs = 74_000L,
                    startTimestampUtc = 1_760_000_068_000L,
                    endTimestampUtc = 1_760_000_074_000L,
                    rawText = "Saya merasa tegang",
                    confidence = 0.92f,
                    language = "id",
                    sessionId = "s-orig"
                )
            ),
            divergenceEvents = listOf(marker),
            skinTemperatureSamples = listOf(sampleA, sampleB)
        )

        val csv = VerbatimResearchExporter.buildSkinTemperatureCsv(listOf(sampleA, sampleB))
        assertTrue(csv.contains("33.4123"))
        assertTrue(csv.contains(",-2,false,"))

        val zipBytes = ByteArrayOutputStream().also { out ->
            service.exportSessionPackageToStream(
                userId = "local",
                detail = detail,
                annotations = listOf(DomainAnnotation("ann-1", "s-orig", 70_000L, "Catatan klinis", "user")),
                aiSummaryText = "Ringkasan uji lokal",
                output = out
            )
        }.toByteArray()

        val result = service.importSessionPackageFromStream("local", ByteArrayInputStream(zipBytes))
        assertTrue(result is SessionPackageImportResult.Imported)
        val imported = result as SessionPackageImportResult.Imported
        assertEquals("s-orig", imported.sessionId)
        assertEquals(2, imported.temperatureSampleCount)
        assertEquals(1, imported.markerCount)
        assertFalse(imported.legacyFormat)

        val restored = repository.observeSession("s-orig").first()!!
        assertEquals(2, restored.skinTemperatureSamples.size)
        assertEquals(33.4123f, restored.skinTemperatureSamples[0].skinTemperatureC!!, 0.0001f)
        assertEquals(-2, restored.skinTemperatureSamples[1].measurementStatus)
        assertFalse(restored.skinTemperatureSamples[1].isValid)
        assertEquals(1, restored.divergenceEvents.size)
        assertEquals("DECREASE", restored.divergenceEvents[0].thermalDirection)

        val dup = service.importSessionPackageFromStream("local", ByteArrayInputStream(zipBytes))
        assertTrue(dup is SessionPackageImportResult.DuplicateSession)
    }

    @Test
    fun legacyPackageWithoutTemperatureImportsCleanlyWithZeroFabricatedSamples() = runBlocking {
        val detail = VerbatimSessionDetail(
            summary = VerbatimSessionSummary(
                sessionId = "s-legacy",
                sessionName = "Sesi Lama Audio",
                startedAtMillis = 1_760_000_000_000L,
                endedAtMillis = 1_760_000_060_000L,
                durationSeconds = 60L,
                audioFilePath = "",
                artifactFolderPath = "verbatim_sessions/legacy",
                finalizationStatus = "COMPLETED",
                summaryStatus = "NOT_REQUESTED"
            ),
            transcript = emptyList(),
            divergenceEvents = emptyList(),
            skinTemperatureSamples = emptyList()
        )
        val zipBytes = ByteArrayOutputStream().also { out ->
            service.exportSessionPackageToStream("local", detail, emptyList(), null, out)
        }.toByteArray()

        val result = service.importSessionPackageFromStream("local", ByteArrayInputStream(zipBytes))
        assertTrue(result is SessionPackageImportResult.Imported)
        val imported = result as SessionPackageImportResult.Imported
        assertTrue(imported.legacyFormat)
        assertEquals(0, imported.temperatureSampleCount)
        assertEquals(0, repository.observeSession("s-legacy").first()!!.skinTemperatureSamples.size)
    }

    @Test
    fun rejectsTraversalAndStandaloneCsvAtomically() = runBlocking {
        val traversalZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("../evil.txt"))
                zip.write("bad".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()

        val traversalResult = service.importSessionPackageFromStream(
            "local",
            ByteArrayInputStream(traversalZip)
        )
        assertTrue(traversalResult is SessionPackageImportResult.Rejected)
        assertEquals(0, storage.listEncryptedFiles("local").size)

        val standaloneCsvZip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { zip ->
                zip.putNextEntry(ZipEntry("signals/skin_temperature.csv"))
                zip.write("elapsed_ms,skin_c\n1000,33.1\n".toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }.toByteArray()
        assertTrue(
            service.importSessionPackageFromStream("local", ByteArrayInputStream(standaloneCsvZip)) is SessionPackageImportResult.Rejected
        )
    }

    private class InMemoryEncryptedStorage : EncryptedStorageService {
        private val files = linkedMapOf<String, ByteArray>()

        override fun writeEncryptedFile(userId: String, fileName: String, data: InputStream): EncryptedFileRef {
            val bytes = data.readBytes()
            files["$userId/$fileName"] = bytes
            return EncryptedFileRef(fileName, bytes.size.toLong())
        }

        override fun readEncryptedFile(userId: String, relativePath: String): InputStream =
            ByteArrayInputStream(files.getValue("$userId/$relativePath"))

        override fun deleteEncryptedFile(userId: String, relativePath: String): Boolean = files.remove("$userId/$relativePath") != null

        override fun listEncryptedFiles(userId: String): List<EncryptedFileRef> = files.keys
            .filter { it.startsWith("$userId/") }
            .map { EncryptedFileRef(it.removePrefix("$userId/"), files.getValue(it).size.toLong()) }

        override fun deleteAllUserFiles(userId: String) {
            files.keys.removeIf { it.startsWith("$userId/") }
        }
    }

    private class InMemoryVerbatimSessionRepository : VerbatimSessionRepository {
        private val sessions = MutableStateFlow<Map<String, VerbatimSessionDetail>>(emptyMap())

        override fun observeSessions(): Flow<List<VerbatimSessionSummary>> = sessions.map { map ->
            map.values.map { it.summary }
        }

        override fun observeSession(sessionId: String): Flow<VerbatimSessionDetail?> = sessions.map { it[sessionId] }

        override suspend fun hasSession(sessionId: String): Boolean = sessions.value.containsKey(sessionId)

        override suspend fun importSessionAtomically(record: VerbatimImportedSessionRecord): Result<Boolean> {
            val id = record.session.sessionId
            if (sessions.value.containsKey(id)) return Result.success(false)
            val detail = VerbatimSessionDetail(
                summary = VerbatimSessionSummary(
                    sessionId = id,
                    sessionName = record.session.sessionName,
                    startedAtMillis = record.session.startedAtMillis,
                    endedAtMillis = record.session.endedAtMillis,
                    durationSeconds = record.session.durationSeconds,
                    audioFilePath = record.session.audioFilePath,
                    artifactFolderPath = record.session.artifactFolderPath,
                    finalizationStatus = record.session.finalizationStatus,
                    summaryStatus = record.session.summaryStatus,
                    watchWearerRole = record.session.watchWearerRole,
                    languageCode = record.session.languageCode
                ),
                transcript = record.segments.map {
                    SpeechSegment(
                        id = it.segmentId,
                        speakerId = it.speakerId,
                        startOffsetMs = it.startOffsetMs,
                        endOffsetMs = it.endOffsetMs,
                        startTimestampUtc = record.session.startedAtMillis + it.startOffsetMs,
                        endTimestampUtc = record.session.startedAtMillis + it.endOffsetMs,
                        rawText = it.rawText,
                        editedText = it.cleanedText?.takeIf { c -> c != it.rawText },
                        confidence = it.confidence,
                        language = it.language,
                        sessionId = id
                    )
                },
                divergenceEvents = record.events,
                skinTemperatureSamples = record.temperatureSamples
            )
            sessions.value = sessions.value + (id to detail)
            return Result.success(true)
        }

        override suspend fun deleteSession(sessionId: String): Result<Unit> {
            sessions.value = sessions.value - sessionId
            return Result.success(Unit)
        }

        override suspend fun deleteAllSessions(): Result<Unit> {
            sessions.value = emptyMap()
            return Result.success(Unit)
        }
    }
}
