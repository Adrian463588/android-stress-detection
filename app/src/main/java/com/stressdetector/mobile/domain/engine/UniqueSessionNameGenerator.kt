package com.stressdetector.mobile.domain.engine

import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Produces human-readable, collision-free session names without touching I/O.
 * Persistence remains the repository's responsibility; callers pass known
 * names and reserve the returned value before starting a session.
 */
class UniqueSessionNameGenerator(private val clock: Clock = Clock.systemDefaultZone()) {
    fun next(requestedName: String?, existingNames: Collection<String>): String {
        val normalizedExisting = existingNames
            .asSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(String::lowercase)
            .toSet()
        val requested = requestedName?.trim().orEmpty()
        val base = if (requested.isBlank() ||
            requested.equals(DEFAULT_NAME, ignoreCase = true) ||
            requested.equals(LEGACY_DEFAULT_NAME, ignoreCase = true)
        ) {
            "Verbatim ${LocalDateTime.now(clock).format(NAME_FORMATTER)}"
        } else {
            requested
        }
        if (base.lowercase(Locale.ROOT) !in normalizedExisting) return base

        var suffix = 2
        while ("$base ($suffix)".lowercase(Locale.ROOT) in normalizedExisting) suffix++
        return "$base ($suffix)"
    }

    private companion object {
        const val DEFAULT_NAME = "Session"
        const val LEGACY_DEFAULT_NAME = "Sesi Verbatim"
        val NAME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    }
}
