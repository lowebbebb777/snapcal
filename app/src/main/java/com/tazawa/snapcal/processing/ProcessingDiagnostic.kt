package com.tazawa.snapcal.processing

import com.tazawa.snapcal.domain.Classification
import com.tazawa.snapcal.domain.EventCandidate

/** Generic reasons only: no OCR text or personally identifiable information. */
internal object ProcessingDiagnostic {
    fun describe(classification: Classification, candidate: EventCandidate): String {
        val count = "タイトル${candidate.titleCandidates.size}・日付${candidate.dateCandidates.size}・時刻${candidate.timeCandidates.size}"
        return when (classification) {
            Classification.AUTO_ADD -> "登録条件を満たしています"
            Classification.AMBIGUOUS -> {
                if (candidate.ambiguousEvidence &&
                    candidate.titleCandidates.size <= 1 &&
                    candidate.dateCandidates.size <= 1 &&
                    candidate.timeCandidates.size <= 1
                ) {
                    "日時が離れた場所にあります（$count）"
                } else {
                    "候補が複数か曖昧です（$count）"
                }
            }
            Classification.INVALID -> "必要な予定情報が不足しています（$count）"
            Classification.EXCLUDED -> "営業時間・締切など、予定以外の日時と判定しました"
            Classification.PAST -> "開始日時が過去です"
            Classification.TOO_FAR -> "開始日時が400日より先です"
            Classification.TOO_LONG -> "予定時間が24時間を超えています"
        }
    }
}
