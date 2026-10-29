package com.tazawa.snapcal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression tests use synthetic appointment data; no real patient details. */
class AppointmentTitleExtractionTest {
    private val extractor = TextEvidenceExtractor()

    @Test
    fun `診察予約スクショでは曜日のOCR誤読より施設名を優先する`() {
        val candidate = extractor.extract(
            listOf(
                line("診察券", 30),
                line("予約サービスからのお知らせ", 280),
                line("青空クリニック", 380),
                line("山田花子", 445),
                line("11月2日（定）16:50", 490),
                line("予約時間をお待ち下さい", 610),
                line("詳細を確認する", 910),
            ),
        )

        assertEquals(listOf("青空クリニック"), candidate.titleCandidates)
        assertEquals(11, candidate.dateCandidates.single().month)
        assertEquals(2, candidate.dateCandidates.single().day)
        assertEquals(16, candidate.timeCandidates.single().start.hour)
        assertEquals(50, candidate.timeCandidates.single().start.minute)
    }

    @Test
    fun `日時行にある曜日はタイトルにならない`() {
        for (weekday in listOf("月", "火", "水", "木", "金", "土", "日", "定")) {
            val candidate = extractor.extract(
                listOf(OcrLine("定例ミーティング"), OcrLine("11月2日（$weekday）16:50")),
            )
            assertEquals(listOf("定例ミーティング"), candidate.titleCandidates)
        }
    }

    @Test
    fun `曜日付き日時と同一行にある本当のタイトルを保持する`() {
        val candidate = extractor.extract(
            listOf(OcrLine("定例ミーティング 11月2日（月）16:50")),
        )
        assertEquals(listOf("定例ミーティング"), candidate.titleCandidates)
    }

    @Test
    fun `施設名の普通の日本語と時刻範囲を壊さない`() {
        val candidate = extractor.extract(
            listOf(OcrLine("からだクリニック 11月2日（月）16:50から17:50")),
        )
        assertEquals(listOf("からだクリニック"), candidate.titleCandidates)
        assertEquals(17, candidate.timeCandidates.single().end?.hour)
    }

    @Test
    fun `施設名が複数なら自動的には選ばない`() {
        val candidate = extractor.extract(
            listOf(
                line("診察券", 30),
                line("青空クリニック", 290),
                line("さくら医院", 350),
                line("11月2日（月）16:50", 400),
            ),
        )
        assertTrue(candidate.titleCandidates.size > 1)
    }

    @Test
    fun `診察予約ではない普通の複数タイトルは曖昧なままにする`() {
        val candidate = extractor.extract(
            listOf(
                line("第一部 トーク", 100),
                line("第二部 ライブ", 160),
                line("7月20日 18:00", 220),
            ),
        )
        assertEquals(2, candidate.titleCandidates.size)
    }

    private fun line(text: String, top: Int): OcrLine =
        OcrLine(text, OcrBounds(100, top, 430, top + 38))
}
