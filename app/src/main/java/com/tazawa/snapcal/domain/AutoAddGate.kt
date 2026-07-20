package com.tazawa.snapcal.domain

import java.time.Duration
import java.time.LocalDateTime

/** 正規化済み候補を無確認で登録してよいかを、保守的に最終判定する。 */
class AutoAddGate {
    fun classify(result: NormalizationResult, reference: LocalDateTime): Classification {
        if (result.classification != Classification.AUTO_ADD) return result.classification
        val event = requireNotNull(result.event)

        if (!event.start.isAfter(reference)) return Classification.PAST
        if (event.start.isAfter(reference.plusDays(MAX_FUTURE_DAYS))) return Classification.TOO_FAR

        val duration = Duration.between(event.start, event.end)
        if (duration.isZero || duration.isNegative || duration > MAX_DURATION) {
            return Classification.TOO_LONG
        }
        return Classification.AUTO_ADD
    }

    companion object {
        private const val MAX_FUTURE_DAYS = 400L
        private val MAX_DURATION: Duration = Duration.ofHours(24)
    }
}
