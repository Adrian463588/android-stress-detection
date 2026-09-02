package com.stressdetector.mobile.ml.llm

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** Network states used by the Gemma provisioning policy and its UI. */
enum class GemmaNetworkState {
    WIFI_VALIDATED,
    WIFI_CONNECTED_UNVALIDATED,
    CELLULAR,
    OFFLINE,
    UNKNOWN
}

data class GemmaNetworkEvaluation(val state: GemmaNetworkState, val detail: String) {
    val mayDownload: Boolean get() = state == GemmaNetworkState.WIFI_VALIDATED

    val userMessage: String
        get() = when (state) {
            GemmaNetworkState.WIFI_VALIDATED -> "Wi-Fi tervalidasi; download Gemma siap dimulai."
            GemmaNetworkState.WIFI_CONNECTED_UNVALIDATED ->
                "Wi-Fi tersambung, tetapi internet belum tervalidasi. Periksa captive portal atau koneksi internet."
            GemmaNetworkState.CELLULAR -> "Jaringan seluler terdeteksi. Gemma hanya dapat diunduh melalui Wi-Fi tervalidasi."
            GemmaNetworkState.OFFLINE -> "Tidak ada jaringan aktif. Download Gemma akan dilanjutkan saat Wi-Fi tersedia."
            GemmaNetworkState.UNKNOWN -> "Status jaringan belum dapat diverifikasi. Download Gemma ditahan demi keamanan."
        }
}
fun interface GemmaNetworkEvaluator {
    fun evaluate(): GemmaNetworkEvaluation
}

class AndroidGemmaNetworkEvaluator(context: Context) : GemmaNetworkEvaluator {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    override fun evaluate(): GemmaNetworkEvaluation {
        val (activeNetwork, capabilities) = activeNetworkAndCapabilities()
        return when {
            connectivityManager == null -> GemmaNetworkEvaluation(
                GemmaNetworkState.UNKNOWN,
                "ConnectivityManager tidak tersedia."
            )
            activeNetwork == null -> GemmaNetworkEvaluation(
                GemmaNetworkState.OFFLINE,
                "Tidak ada active network."
            )
            capabilities == null -> GemmaNetworkEvaluation(
                GemmaNetworkState.UNKNOWN,
                "NetworkCapabilities tidak tersedia."
            )
            else -> capabilities.toEvaluation()
        }
    }

    /** Returns the same validated Wi-Fi network that the transfer must bind to. */
    fun validatedWifiNetwork(): Network? {
        val (network, capabilities) = activeNetworkAndCapabilities()
        return network.takeIf {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private fun activeNetworkAndCapabilities(): Pair<Network?, NetworkCapabilities?> {
        val manager = connectivityManager ?: return null to null
        val network = manager.activeNetwork
        return network to network?.let(manager::getNetworkCapabilities)
    }

    private fun NetworkCapabilities.toEvaluation(): GemmaNetworkEvaluation {
        val hasWifi = hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val hasCellular = hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val hasInternet = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return when {
            hasWifi && hasInternet && isValidated -> GemmaNetworkEvaluation(
                GemmaNetworkState.WIFI_VALIDATED,
                "Wi-Fi memiliki akses internet tervalidasi."
            )
            hasWifi -> GemmaNetworkEvaluation(
                GemmaNetworkState.WIFI_CONNECTED_UNVALIDATED,
                "Wi-Fi aktif tetapi belum memiliki validasi internet."
            )
            hasCellular -> GemmaNetworkEvaluation(
                GemmaNetworkState.CELLULAR,
                "Active network hanya seluler."
            )
            !hasInternet -> GemmaNetworkEvaluation(
                GemmaNetworkState.OFFLINE,
                "Network aktif tidak menyediakan internet."
            )
            else -> GemmaNetworkEvaluation(
                GemmaNetworkState.UNKNOWN,
                "Transport network tidak didukung oleh policy Gemma."
            )
        }
    }
}
