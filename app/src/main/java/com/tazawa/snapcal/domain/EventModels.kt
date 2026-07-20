package com.tazawa.snapcal.domain

import java.time.LocalDateTime

/** Android の Rect に依存しない OCR 座標。 */
data class OcrBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    init {
        require(left <= right) { "left must not be greater than right" }
        require(top <= bottom) { "top must not be greater than bottom" }
    }
}

data class OcrLine(
    val text: String,
    val bounds: OcrBounds? = null,
)

data class DateEvidence(
    val rawText: String,
    val year: Int?,
    val month: Int,
    val day: Int,
)

data class ClockEvidence(
    val rawText: String,
    val hour: Int,
    val minute: Int,
)

data class TimeEvidence(
    val start: ClockEvidence,
    val end: ClockEvidence? = null,
)

data class EventCandidate(
    val titleCandidates: List<String>,
    val dateCandidates: List<DateEvidence>,
    val timeCandidates: List<TimeEvidence>,
    val exclusionKeywords: Set<String> = emptySet(),
    val ambiguousEvidence: Boolean = false,
)

data class NormalizedEvent(
    val title: String,
    val start: LocalDateTime,
    val end: LocalDateTime,
)

enum class Classification {
    AUTO_ADD,
    EXCLUDED,
    AMBIGUOUS,
    INVALID,
    PAST,
    TOO_FAR,
    TOO_LONG,
}

data class NormalizationResult(
    val classification: Classification,
    val event: NormalizedEvent? = null,
    val reasons: List<String> = emptyList(),
) {
    init {
        require((classification == Classification.AUTO_ADD) == (event != null)) {
            "Only AUTO_ADD normalization results may contain an event"
        }
    }
}
