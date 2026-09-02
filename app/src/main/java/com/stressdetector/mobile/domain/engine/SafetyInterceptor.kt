package com.stressdetector.mobile.domain.engine

import java.text.Normalizer
import java.util.Locale

/**
 * A deterministic decision made before any model, retrieval, consent, or network work starts.
 */
sealed interface CrisisInterceptionResult {
    data object NoCrisis : CrisisInterceptionResult

    data class Escalate(val response: CrisisResponse, val matchedKeyword: String) : CrisisInterceptionResult
}

data class CrisisResponse(val templateVersion: String, val text: String, val supportActions: List<CrisisSupportAction>)

data class CrisisSupportAction(val id: String, val label: String, val uri: String)

/** Provides a versioned, user-facing safety response independent of persona and LLM output. */
interface CrisisResponseTemplateProvider {
    fun current(): CrisisResponse

    fun supportActionsFor(templateVersion: String?): List<CrisisSupportAction>
}

object IndonesianCrisisResponseTemplateProvider : CrisisResponseTemplateProvider {
    private const val TEMPLATE_VERSION = "crisis-id-v1"

    private val response =
        CrisisResponse(
            templateVersion = TEMPLATE_VERSION,
            text =
            """
                Terima kasih sudah memberi tahu saya. Keselamatanmu penting.

                Jika kamu mungkin melukai diri sekarang atau merasa tidak aman, segera hubungi layanan darurat setempat atau minta seseorang yang kamu percaya untuk menemani kamu sekarang.

                Kamu juga dapat menghubungi Healing119 melalui 119 lalu pilih ekstensi 8, atau membuka Healing119.id untuk dukungan. Apakah kamu berada di tempat yang aman saat ini?
            """.trimIndent(),
            supportActions = listOf(
                CrisisSupportAction(
                    id = "dial_healing_119",
                    label = "Hubungi 119 (ekstensi 8)",
                    uri = "tel:119"
                ),
                CrisisSupportAction(
                    id = "open_healing_119",
                    label = "Buka Healing119.id",
                    uri = "https://healing119.id"
                )
            )
        )

    override fun current(): CrisisResponse = response

    override fun supportActionsFor(templateVersion: String?): List<CrisisSupportAction> =
        if (templateVersion == TEMPLATE_VERSION) response.supportActions else emptyList()
}

interface SafetyInterceptor {
    fun intercept(userMessage: String): CrisisInterceptionResult
}

class ClinicalSafetyInterceptor(private val templateProvider: CrisisResponseTemplateProvider = IndonesianCrisisResponseTemplateProvider) :
    SafetyInterceptor {
    private val crisisKeywords =
        listOf(
            "bunuh diri",
            "ingin mati",
            "tidak mau hidup",
            "suicide",
            "self-harm",
            "kill myself",
            "hurt myself",
            "menyakiti diri",
            "hopeless",
            "putus asa"
        ).map(::normalize)

    override fun intercept(userMessage: String): CrisisInterceptionResult {
        val normalizedMessage = normalize(userMessage)
        val matchedKeyword = crisisKeywords.firstOrNull(normalizedMessage::contains)
            ?: return CrisisInterceptionResult.NoCrisis
        return CrisisInterceptionResult.Escalate(
            response = templateProvider.current(),
            matchedKeyword = matchedKeyword
        )
    }

    private companion object {
        fun normalize(value: String): String = Normalizer
            .normalize(value, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
