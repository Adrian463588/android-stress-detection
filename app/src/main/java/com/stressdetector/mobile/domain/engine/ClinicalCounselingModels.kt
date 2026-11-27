package com.stressdetector.mobile.domain.engine

enum class CounselingInteractionMode(val displayName: String) {
    GENERAL_COUNSELING("Konseling"),
    ASSESSMENT_INTERPRETATION("Asesmen"),
    COUNSELOR_PLANNING("Rencana")
}

enum class ResponseProfile(val displayName: String, val maxOutputChars: Int, val responseReserveTokens: Int) {
    SHORT("Ringkas", 1_200, 384),
    STANDARD("Standar", 2_400, 512),
    DETAILED("Detail", 4_800, 768),
    COUNSELING_PLAN("Rencana 10 Bagian", 6_400, 1_024)
}

enum class PlanReviewStatus(val label: String) {
    PENDING("Menunggu Tinjauan Konselor"),
    ACCEPTED("Disetujui Konselor"),
    REVISION_REQUESTED("Perlu Revisi"),
    REJECTED("Ditolak")
}

enum class CompletionReason {
    COMPLETE,
    OUTPUT_LIMIT_REACHED,
    UNFINISHED_SENTENCE,
    INTERRUPTED
}

enum class FollowUpReminderKind(val displayLabel: String, val defaultNonClinicalTitle: String, val defaultDelayHours: Long) {
    COUNSELING_SESSION("Sesi Konseling", "Jadwal sesi konseling tindak lanjut", 24L),
    CHECK_IN("Check-in Refleksi", "Waktunya check-in refleksi harian", 12L),
    HOMEWORK_PRACTICE("Latihan Mandiri", "Pengingat latihan mandiri CBT/REBT", 24L),
    PROGRESS_REVIEW("Tinjauan Progres", "Tinjauan perkembangan latihan mandiri", 48L),
    FOLLOW_UP_CHAT("Lanjutan Diskusi", "Lanjutkan diskusi tindak lanjut di AI+", 24L)
}
