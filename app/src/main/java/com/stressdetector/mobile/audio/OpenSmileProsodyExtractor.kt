package com.stressdetector.mobile.audio

import android.content.Context
import com.stressdetector.mobile.domain.engine.VerbatimProsodyEvidence
import java.io.File
import java.io.IOException

/** Native eGeMAPS from the SAME recorded PCM, never a second capture source. */
internal class OpenSmileProsodyExtractor(private val context: Context) {
    private val configuration: File by lazy(::prepareConfiguration)

    fun analyze(samples: FloatArray): VerbatimProsodyEvidence {
        if (samples.size !in MIN_SAMPLES..MAX_SAMPLES || samples.any { !it.isFinite() }) {
            return VerbatimProsodyEvidence(unavailableReason = "PCM harus finite dan berdurasi 1–20 detik.")
        }
        return try {
            val config = configuration
            System.loadLibrary("verbatim_prosody")
            val values = extractNative(config.absolutePath, samples).associate { row ->
                row.substringBefore('=') to row.substringAfter('=').toDouble()
            }
            val frames = values["frames"]?.toInt() ?: 0
            val voiced = values["voiced"]?.toInt() ?: 0
            VerbatimProsodyEvidence(
                features = values.filterKeys { it != "frames" && it != "voiced" }.filterValues(Double::isFinite),
                frameCount = frames,
                voicedFrameCount = voiced,
                unavailableReason = if (frames == 0) "openSMILE tidak menghasilkan frame analisis." else null
            )
        } catch (error: UnsatisfiedLinkError) {
            VerbatimProsodyEvidence(unavailableReason = "openSMILE native unavailable: ${error.message}")
        } catch (error: IOException) {
            unavailable(error)
        } catch (error: IllegalStateException) {
            unavailable(error)
        } catch (error: IllegalArgumentException) {
            unavailable(error)
        }
    }

    private fun unavailable(error: Exception) = VerbatimProsodyEvidence(unavailableReason = "openSMILE gagal: ${error.message}")

    private fun prepareConfiguration(): File {
        val root = File(context.noBackupFilesDir, ASSET_ROOT)
        copyAssets(ASSET_ROOT, root)
        return File(root, "verbatim-lld.conf").also { check(it.isFile) { "openSMILE configuration missing" } }
    }

    private fun copyAssets(path: String, destination: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            destination.parentFile?.mkdirs()
            context.assets.open(path).use { input -> destination.outputStream().use(input::copyTo) }
        } else {
            children.forEach { copyAssets("$path/$it", File(destination, it)) }
        }
    }

    private external fun extractNative(configPath: String, samples: FloatArray): Array<String>

    companion object {
        private const val ASSET_ROOT = "opensmile-v3.0.2"
        private const val MIN_SAMPLES = 16_000
        private const val MAX_SAMPLES = 320_000
    }
}
