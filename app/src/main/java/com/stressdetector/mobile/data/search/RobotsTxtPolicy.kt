package com.stressdetector.mobile.data.search

import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Fail closed when robots.txt explicitly disallows this crawler. */
class RobotsTxtPolicy(private val client: OkHttpClient) {
    private val redirectControlledClient = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Returns the accepted, bounded crawl delay for this domain. */
    suspend fun requirePermitted(url: HttpUrl): Long? = withContext(Dispatchers.IO) {
        val robotsUrl = url.newBuilder().encodedPath("/robots.txt").query(null).fragment(null).build()
        // Pin the public DNS answer for robots.txt too. Otherwise the policy
        // fetch itself could resolve to a private address during a rebinding
        // window even though the page fetch is protected.
        val approvedAddresses = SecureWebUrlPolicy.resolvePublicAddresses(robotsUrl)
            ?: error("Host robots.txt tidak menyelesaikan ke alamat publik.")
        coroutineContext.ensureActive()
        val pinnedClient = redirectControlledClient.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    if (!hostname.equals(robotsUrl.host, ignoreCase = true)) {
                        throw UnknownHostException("Unexpected robots host during web request")
                    }
                    return approvedAddresses
                }
            })
            .build()
        pinnedClient.newCall(
            Request.Builder()
                .url(robotsUrl)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "text/plain")
                .build()
        ).readCancellable {
            if (it.code == 404) {
                null
            } else {
                require(it.isSuccessful) { "robots.txt tidak dapat diverifikasi (${it.code})." }
                val rules = RobotsRules.parse(it.body?.let { body -> readBoundedWebBody(body, MAX_ROBOTS_BYTES) }.orEmpty())
                require(rules.permits(url.encodedPath)) {
                    "robots.txt melarang pengambilan halaman ini."
                }
                rules.crawlDelayMillis
            }
        }
    }

    private companion object {
        const val MAX_ROBOTS_BYTES = 128 * 1024L
        const val USER_AGENT = "StressDetector-AI-Counselor/1.0"
    }
}

internal class RobotsRules private constructor(private val rules: List<Rule>, val crawlDelayMillis: Long?) {
    fun permits(path: String): Boolean {
        val matchingRule = rules.asSequence()
            .filter { it.matches(path) }
            .maxWithOrNull(compareBy<Rule> { it.specificity }.thenBy { it.allow })
            ?: return true
        return matchingRule.allow
    }

    companion object {
        fun parse(raw: String): RobotsRules {
            val groups = mutableListOf<RobotsGroup>()
            var agents = mutableListOf<String>()
            var rules = mutableListOf<Rule>()
            var crawlDelayMillis: Long? = null
            var seenDirective = false

            fun flushGroup() {
                if (agents.isNotEmpty()) groups += RobotsGroup(agents.toList(), rules.toList(), crawlDelayMillis)
                agents = mutableListOf()
                rules = mutableListOf()
                crawlDelayMillis = null
                seenDirective = false
            }

            raw.lineSequence().forEach { rawLine ->
                val line = rawLine.substringBefore('#').trim()
                val separator = line.indexOf(':')
                if (separator <= 0) return@forEach
                val key = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                when (key) {
                    "user-agent" -> {
                        if (seenDirective) flushGroup()
                        if (value.isNotBlank()) agents += value.lowercase()
                    }
                    "allow", "disallow" -> if (agents.isNotEmpty() && value.isNotBlank()) {
                        seenDirective = true
                        rules += Rule(allow = key == "allow", pattern = value)
                    }
                    "crawl-delay" -> if (agents.isNotEmpty()) {
                        seenDirective = true
                        crawlDelayMillis = value.toDoubleOrNull()
                            ?.times(1_000.0)
                            ?.toLong()
                            ?.coerceIn(0L, MAX_CRAWL_DELAY_MILLIS)
                    }
                }
            }
            flushGroup()
            val namedGroups = groups.filter { group -> group.matches(USER_AGENT_TOKEN) }
            val wildcardGroups = groups.filter { "*" in it.agents }
            val selectedGroups = if (namedGroups.isNotEmpty()) namedGroups else wildcardGroups
            return RobotsRules(
                rules = selectedGroups.flatMap(RobotsGroup::rules),
                crawlDelayMillis = selectedGroups.mapNotNull(RobotsGroup::crawlDelayMillis).maxOrNull()
            )
        }

        private const val USER_AGENT_TOKEN = "stressdetector-ai-counselor"
        private const val MAX_CRAWL_DELAY_MILLIS = 10_000L
    }

    private data class RobotsGroup(val agents: List<String>, val rules: List<Rule>, val crawlDelayMillis: Long? = null) {
        fun matches(userAgent: String): Boolean = agents.any { agent ->
            agent == "*" || userAgent.startsWith(agent)
        }
    }

    private data class Rule(val allow: Boolean, val pattern: String) {
        val specificity: Int = pattern.removeSuffix("$").replace("*", "").length

        fun matches(path: String): Boolean {
            val anchored = pattern.endsWith('$')
            val regexBody = Regex.escape(pattern.removeSuffix("$")).replace("\\*", ".*")
            return Regex("^$regexBody${if (anchored) "$" else ".*"}").matches(path)
        }
    }
}
