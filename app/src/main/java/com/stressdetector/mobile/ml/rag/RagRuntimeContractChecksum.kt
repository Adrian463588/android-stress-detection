package com.stressdetector.mobile.ml.rag

import com.google.gson.JsonElement
import com.google.gson.JsonPrimitive
import java.security.MessageDigest

internal fun JsonElement.canonicalContractSha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(canonicalContractJson().toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }

private fun JsonElement.canonicalContractJson(): String = when {
    isJsonObject -> asJsonObject.entrySet()
        .sortedBy { entry -> entry.key }
        .joinToString(separator = ",", prefix = "{", postfix = "}") { entry ->
            "${JsonPrimitive(entry.key)}:${entry.value.canonicalContractJson()}"
        }
    isJsonArray -> asJsonArray.joinToString(separator = ",", prefix = "[", postfix = "]") { value -> value.canonicalContractJson() }
    else -> toString()
}
