package com.stressdetector.mobile.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale

/**
 * Mobile App Hardening & RASP (Runtime Application Self-Protection) Manager
 * strictly designed for 100% offline environments (OWASP MASVS-RESILIENCE).
 *
 * Implements Defense-in-Depth:
 * 1. APK Signature Digest (SHA-256) Verification (Anti-repackaging & anti-re-signing)
 * 2. Package Name Integrity Verification
 * 3. Anti-Debugging Detection (Java Debugger + Native TracerPid / Ptrace)
 * 4. Root & Tampered Environment Detection (su binaries, test-keys, Magisk paths)
 * 5. Hooking & Dynamic Instrumentation Detection (Frida maps/ports, Xposed, Substrate)
 * 6. Asset & Resource Integrity Verification (SHA-256 hash checks of critical psychometrics & RAG manifest)
 */
object AppHardeningManager {

    const val EXPECTED_PACKAGE_NAME = "com.stressdetector"

    // Recognized genuine signing certificates SHA-256 fingerprints (normalized uppercase, no colons)
    val APPROVED_SIGNATURE_SHA256_HEX: Set<String> = setOf(
        // Standard Android Debug Keystore SHA-256 (for local development & test runs)
        "FC6CC5CEC1EB93984BC0CB0842B6D2F9C4BB6549F97617D42F457DC8983B55B4",
        // Additional authorized developer/CI signing keys can be registered here
    )

    // Expected genuine SHA-256 hashes of critical offline assets
    val CRITICAL_ASSET_CHECKSUMS: Map<String, String> = mapOf(
        "ai/psychometrics/catalog.json" to "52DAC3696C90EF8244FE55FC8962CABEF6A92AB4B4356CD1ADE1EA1183BFFA7E",
        "ai/psychometrics/protocols.json" to "E99F428A2ACBA8E4090DDBC131F5342F018CCBAC99C8A0CB449FFBA6434EA501",
        "ai/bundled_knowledge_manifest.json" to "864A31D260D84AFF30DC20F016988A837D64B4A8EB73B1D02260665BFD332F32"
    )

    private val KNOWN_SU_BINARY_PATHS = listOf(
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su",
        "/su/bin/su"
    )

    private val KNOWN_ROOT_DIRS = listOf(
        "/sbin/.magisk",
        "/data/adb/magisk",
        "/system/app/Superuser.apk",
        "/system/app/SuperSU.apk"
    )

    private val KNOWN_HOOKING_CLASSES = listOf(
        "de.robv.android.xposed.XposedBridge",
        "com.saurik.substrate.MS\$MethodPointer",
        "io.github.libxposed.api.XposedInterface"
    )

    data class HardeningAuditReport(
        val isCompromised: Boolean,
        val detectedAnomalies: List<String>,
        val isSignatureValid: Boolean,
        val isPackageValid: Boolean,
        val isDebugged: Boolean,
        val isRootDetected: Boolean,
        val isHookFrameworkDetected: Boolean,
        val isAssetTampered: Boolean = false,
        val computedSignatureSha256: String? = null
    )

    /**
     * Conducts a full offline security posture assessment.
     * @param context Application context
     * @param enforceSignatureCheck Whether to flag unknown signatures (can be soft-warned in debug mode)
     * @param enforceAssetCheck Whether to verify asset SHA-256 integrity
     */
    fun performAudit(
        context: Context,
        enforceSignatureCheck: Boolean = false,
        enforceAssetCheck: Boolean = false
    ): HardeningAuditReport {
        val anomalies = mutableListOf<String>()

        // 1. Package Name Integrity
        val isPackageValid = checkPackageIntegrity(context)
        if (!isPackageValid) {
            anomalies.add("PACKAGE_TAMPERED: actual=${context.packageName}, expected=$EXPECTED_PACKAGE_NAME")
        }

        // 2. Signature SHA-256 Verification
        val signatureSha256 = getApkSignatureSha256(context)
        val isSignatureValid = if (signatureSha256 != null) {
            APPROVED_SIGNATURE_SHA256_HEX.contains(signatureSha256)
        } else {
            false
        }
        if (!isSignatureValid && enforceSignatureCheck) {
            anomalies.add("SIGNATURE_UNRECOGNIZED: sha256=$signatureSha256")
        }

        // 3. Debugger & Tracing Detection
        val isDebugged = checkDebuggerActive(context)
        if (isDebugged) {
            anomalies.add("DEBUGGER_ATTACHED_OR_TRACED")
        }

        // 4. Root & Tampered Environment Detection
        val isRooted = checkRootEnvironment()
        if (isRooted) {
            anomalies.add("ROOT_OR_TEST_ENVIRONMENT_DETECTED")
        }

        // 5. Dynamic Hooking Frameworks Detection (Frida / Xposed)
        val isHooked = checkHookingActive()
        if (isHooked) {
            anomalies.add("HOOKING_FRAMEWORK_DETECTED")
        }

        // 6. Asset & Resource Integrity
        var isAssetTampered = false
        if (enforceAssetCheck) {
            val tamperedAssets = checkAssetIntegrity(context)
            if (tamperedAssets.isNotEmpty()) {
                isAssetTampered = true
                anomalies.add("ASSET_TAMPERED: ${tamperedAssets.joinToString(", ")}")
            }
        }

        val isCompromised = anomalies.isNotEmpty()

        return HardeningAuditReport(
            isCompromised = isCompromised,
            detectedAnomalies = anomalies,
            isSignatureValid = isSignatureValid,
            isPackageValid = isPackageValid,
            isDebugged = isDebugged,
            isRootDetected = isRooted,
            isHookFrameworkDetected = isHooked,
            isAssetTampered = isAssetTampered,
            computedSignatureSha256 = signatureSha256
        )
    }

    /**
     * Checks if package name matches genuine expected identity.
     */
    fun checkPackageIntegrity(context: Context): Boolean =
        context.packageName == EXPECTED_PACKAGE_NAME

    /**
     * Extracts SHA-256 of the active APK signing certificate.
     * Implements proper API 33+ PackageInfoFlags compatibility to prevent Android Lint NewApi errors.
     */
    fun getApkSignatureSha256(context: Context): String? = runCatching {
        val pm = context.packageManager
        val packageName = context.packageName

        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val packageInfo = pm.getPackageInfo(
                packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
            val signingInfo = packageInfo.signingInfo
            if (signingInfo?.hasMultipleSigners() == true) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            @Suppress("DEPRECATION")
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = packageInfo.signingInfo
            if (signingInfo?.hasMultipleSigners() == true) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo?.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }

        val primaryCert = signatures?.firstOrNull() ?: return null
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(primaryCert.toByteArray())
        digest.joinToString("") { "%02X".format(it) }
    }.getOrNull()

    /**
     * Verifies SHA-256 integrity of critical offline assets.
     * Returns list of asset paths that failed verification or were missing.
     */
    fun checkAssetIntegrity(context: Context): List<String> {
        val failed = mutableListOf<String>()
        val assets = context.assets

        for ((path, expectedHash) in CRITICAL_ASSET_CHECKSUMS) {
            val actualHash = runCatching {
                assets.open(path).use { stream ->
                    computeSha256Hex(stream)
                }
            }.getOrNull()

            if (actualHash == null || !actualHash.equals(expectedHash, ignoreCase = true)) {
                failed.add(path)
            }
        }
        return failed
    }

    /**
     * Computes uppercase hex SHA-256 digest from an input stream.
     */
    fun computeSha256Hex(stream: InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var read: Int
        while (stream.read(buffer).also { read = it } != -1) {
            md.update(buffer, 0, read)
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }

    /**
     * Detects if debugger is connected via standard API or native tracerpid / ptrace.
     */
    fun checkDebuggerActive(context: Context): Boolean {
        val javaDebugConnected = runCatching {
            Debug.isDebuggerConnected() || Debug.waitingForDebugger()
        }.getOrDefault(false)
        if (javaDebugConnected) return true

        if (AppHardeningNative.isDebuggerAttached()) return true

        // Check if application is debuggable in release configuration
        val isDebuggable = runCatching {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        }.getOrDefault(false)
        if (isDebuggable) return true

        // Native TracerPid check in /proc/self/status
        if (AppHardeningNative.isTracerPidActive()) return true

        return false
    }

    /**
     * Checks for su binaries, test-keys build tags, or Magisk directories.
     */
    fun checkRootEnvironment(): Boolean {
        // 1. Check Build tags
        val buildTags = Build.TAGS
        if (buildTags != null && buildTags.contains("test-keys")) {
            return true
        }

        // 2. Check known su paths
        for (path in KNOWN_SU_BINARY_PATHS) {
            try {
                if (File(path).exists()) return true
            } catch (_: Throwable) {
                // Access denied or sandboxed
            }
        }

        // 3. Check known root directories
        for (dir in KNOWN_ROOT_DIRS) {
            try {
                if (File(dir).exists()) return true
            } catch (_: Throwable) {
                // Access denied
            }
        }

        return false
    }

    /**
     * Checks for in-memory hook injection (Frida agent/gadget, XposedBridge).
     */
    fun checkHookingActive(): Boolean {
        // 1. Native maps scan & Frida port scan
        if (AppHardeningNative.isHookFrameworkPresent()) return true

        // 2. Java reflection/classloader checks
        val cl = ClassLoader.getSystemClassLoader()
        for (className in KNOWN_HOOKING_CLASSES) {
            try {
                Class.forName(className, false, cl)
                return true
            } catch (_: ClassNotFoundException) {
                // Not present
            } catch (_: Throwable) {
                // Security exception
            }
        }

        return false
    }
}
