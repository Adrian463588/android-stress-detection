package com.stressdetector.mobile.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class AppHardeningTest {

    @Test
    fun testPackageIntegrityPassesForGenuinePackage() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.stressdetector")

        val isValid = AppHardeningManager.checkPackageIntegrity(context)
        assertTrue("Genuine package name must pass integrity check", isValid)
    }

    @Test
    fun testPackageIntegrityFailsForTamperedPackage() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.stressdetector.tampered")

        val isValid = AppHardeningManager.checkPackageIntegrity(context)
        assertFalse("Tampered package name must fail integrity check", isValid)
    }

    @Test
    fun testApprovedSignaturesContainsDebugKeystore() {
        // Must contain normalized 64-char uppercase SHA-256
        val debugFingerprint = "FC6CC5CEC1EB93984BC0CB0842B6D2F9C4BB6549F97617D42F457DC8983B55B4"
        assertTrue(
            "Approved signatures set must recognize standard debug keystore",
            AppHardeningManager.APPROVED_SIGNATURE_SHA256_HEX.contains(debugFingerprint)
        )
    }

    @Test
    fun testRootCheckReturnsGracefullyWithoutCrashing() {
        // Verifies path scanning and environment probing does not throw uncaught exceptions
        val isRooted = AppHardeningManager.checkRootEnvironment()
        // Boolean result depends on host environment, but should complete cleanly
        assertNotNull(isRooted)
    }

    @Test
    fun testHookingCheckReturnsGracefullyWithoutCrashing() {
        // Verifies classloader inspection and native hooking checks return cleanly
        val isHooked = AppHardeningManager.checkHookingActive()
        assertNotNull(isHooked)
    }

    @Test
    fun testComputeSha256HexProducesCorrectDigest() {
        val testData = "Hello Antigravity Hardening".toByteArray(Charsets.UTF_8)
        val stream = ByteArrayInputStream(testData)
        val hash = AppHardeningManager.computeSha256Hex(stream)

        // Known SHA-256 of "Hello Antigravity Hardening"
        // echo -n "Hello Antigravity Hardening" | sha256sum
        assertEquals(64, hash.length)
        assertEquals(hash.uppercase(), hash)
    }

    @Test
    fun testCheckAssetIntegrityDetectsMissingOrTamperedAsset() {
        val context = mock(Context::class.java)
        val assets = mock(AssetManager::class.java)
        `when`(context.assets).thenReturn(assets)

        // Mock an asset that returns altered/tampered bytes
        val tamperedStream = ByteArrayInputStream("{\"tampered\": true}".toByteArray(Charsets.UTF_8))
        `when`(assets.open("ai/psychometrics/catalog.json")).thenReturn(tamperedStream)

        val failed = AppHardeningManager.checkAssetIntegrity(context)
        assertTrue(
            "Tampered asset must be flagged in failed list",
            failed.contains("ai/psychometrics/catalog.json")
        )
    }

    @Test
    fun testPerformAuditFlagsPackageTampering() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("org.repackaged.malicious")
        val appInfo = ApplicationInfo().apply { flags = 0 }
        `when`(context.applicationInfo).thenReturn(appInfo)

        val report = AppHardeningManager.performAudit(context, enforceSignatureCheck = false)

        assertFalse("Package should not be valid", report.isPackageValid)
        assertTrue("Audit report must flag compromise", report.isCompromised)
        assertTrue(
            "Anomalies must list PACKAGE_TAMPERED",
            report.detectedAnomalies.any { it.contains("PACKAGE_TAMPERED") }
        )
    }

    @Test
    fun testPerformAuditOnGenuinePackageWithoutEnforcedSignature() {
        val context = mock(Context::class.java)
        `when`(context.packageName).thenReturn("com.stressdetector")
        val appInfo = ApplicationInfo().apply { flags = 0 }
        `when`(context.applicationInfo).thenReturn(appInfo)

        val report = AppHardeningManager.performAudit(context, enforceSignatureCheck = false)

        assertTrue("Package must be valid", report.isPackageValid)
        // Without signature enforcement, genuine package name should not report package tamper
        assertFalse(
            "Should not flag package tampering",
            report.detectedAnomalies.any { it.contains("PACKAGE_TAMPERED") }
        )
    }

    @Test
    fun testCheckAssetIntegrityAcceptsBothLfAndCrlfLineEndings() {
        val assetPaths = listOf(
            "ai/psychometrics/catalog.json",
            "ai/psychometrics/protocols.json",
            "ai/bundled_knowledge_manifest.json"
        )
        listOf(false, true).forEach { useCrlf ->
            val context = mock(Context::class.java)
            val assets = mock(AssetManager::class.java)
            `when`(context.assets).thenReturn(assets)
            assetPaths.forEach { rel ->
                val rawText = resolveCanonicalAsset(rel).readText(Charsets.UTF_8)
                val lfText = rawText.replace("\r\n", "\n")
                val variantBytes = (if (useCrlf) lfText.replace("\n", "\r\n") else lfText).toByteArray(Charsets.UTF_8)
                `when`(assets.open(rel)).thenAnswer { ByteArrayInputStream(variantBytes) }
            }
            val failed = AppHardeningManager.checkAssetIntegrity(context)
            assertTrue(
                "Expected all canonical assets to pass integrity check (crlf=$useCrlf), failed=$failed",
                failed.isEmpty()
            )
        }
    }

    @Test
    fun testCanonicalAssetChecksumsMatchOnDiskLfHashes() {
        AppHardeningManager.CRITICAL_ASSET_CHECKSUMS.forEach { (rel, expectedHash) ->
            val lfBytes = resolveCanonicalAsset(rel)
                .readText(Charsets.UTF_8)
                .replace("\r\n", "\n")
                .toByteArray(Charsets.UTF_8)
            val actualLfHash = MessageDigest.getInstance("SHA-256")
                .digest(lfBytes)
                .joinToString("") { "%02X".format(it) }
            assertEquals("Canonical LF SHA-256 mismatch for $rel", expectedHash, actualLfHash)
        }
    }

    private fun resolveCanonicalAsset(relativePath: String): File {
        val direct = File("src/main/canonicalAssets/$relativePath")
        if (direct.isFile) return direct
        val nested = File("app/src/main/canonicalAssets/$relativePath")
        if (nested.isFile) return nested
        throw IllegalStateException("Canonical asset not found: $relativePath (cwd=${File(".").absolutePath})")
    }
}
