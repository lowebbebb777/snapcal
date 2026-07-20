package com.tazawa.snapcal.domain

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class EventNormalizer {
    fun normalize(candidate: EventCandidate, reference: LocalDateTime): NormalizationResult {
        if (candidate.exclusionKeywords.isNotEmpty()) {
            return rejected(
                Classification.EXCLUDED,
                "予定ではない日時表現: ${candidate.exclusionKeywords.sorted().joinToString()}"
            )
        }
        if (candidate.ambiguousEvidence ||
            candidate.titleCandidates.size > 1 ||
            candidate.dateCandidates.size > 1 ||
            candidate.timeCandidates.size > 1
        ) {
            return rejected(Classification.AMBIGUOUS, "日時またはタイトルの候補が複数あります")
        }
        if (candidate.titleCandidates.size != 1 ||
            candidate.dateCandidates.size != 1 ||
            candidate.timeCandidates.size != 1
        ) {
            return rejected(Classification.INVALID, "タイトル・日付・開始時刻が揃っていません")
        }

        val dateEvidence = candidate.dateCandidates.single()
        val timeEvidence = candidate.timeCandidates.single()
        val startTime = try {
            LocalTime.of(timeEvidence.start.hour, timeEvidence.start.minute)
        } catch (_: DateTimeException) {
            return rejected(Classification.INVALID, "存在しない開始時刻です")
        }
        val date = try {
            resolveDate(dateEvidence, startTime, reference)
        } catch (_: DateTimeException) {
            return rejected(Classification.INVALID, "存在しない日付または時刻です")
        }

        val start = LocalDateTime.of(date, startTime)
        val end = timeEvidence.end?.let { endClock ->
            val endTime = try {
                LocalTime.of(endClock.hour, endClock.minute)
            } catch (_: DateTimeException) {
                return rejected(Classification.INVALID, "存在しない終了時刻です")
            }
            LocalDateTime.of(date, endTime).let { sameDateEnd ->
                if (sameDateEnd <= start) sameDateEnd.plusDays(1) else sameDateEnd
            }
        } ?: start.plusMinutes(DEFAULT_DURATION_MINUTES)

        return NormalizationResult(
            classification = Classification.AUTO_ADD,
            event = NormalizedEvent(
                title = candidate.titleCandidates.single(),
                start = start,
                end = end,
            ),
        )
    }

    private fun resolveDate(
        evidence: DateEvidence,
        startTime: LocalTime,
        reference: LocalDateTime,
    ): LocalDate {
        evidence.year?.let { return LocalDate.of(it, evidence.month, evidence.day) }

        var year = reference.year
        var date = validDateInOrAfterYear(year, evidence.month, evidence.day)
        var start = LocalDateTime.of(date, startTime)
        while (!start.isAfter(reference)) {
            year = date.year + 1
            date = validDateInOrAfterYear(year, evidence.month, evidence.day)
            start = LocalDateTime.of(date, startTime)
        }
        return date
    }

    /** 2/29 のように年によって存在しない日付は、次に存在する年まで進める。 */
    private fun validDateInOrAfterYear(year: Int, month: Int, day: Int): LocalDate {
        var candidateYear = year
        repeat(MAX_YEAR_SEARCH) {
            try {
                return LocalDate.of(candidateYear, month, day)
            } catch (_: DateTimeException) {
                candidateYear += 1
            }
        }
        throw DateTimeException("Date cannot be resolved")
    }

    private fun rejected(classification: Classification, reason: String) =
        NormalizationResult(classification = classification, reasons = listOf(reason))

    companion object {
        private const val DEFAULT_DURATION_MINUTES = 60L
        private const val MAX_YEAR_SEARCH = 8
    }
}
