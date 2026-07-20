package com.tazawa.snapcal.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SnapCalDomainTest {
    private val extractor = TextEvidenceExtractor()
    private val normalizer = EventNormalizer()
    private val gate = AutoAddGate()
    private val reference = LocalDateTime.of(2026, 7, 17, 12, 0)

    @Test
    fun `正例 日本語日時を自動登録できる`() {
        val result = normalize("夏の音楽祭", "2026年7月20日 18時30分〜20時")

        assertEquals(Classification.AUTO_ADD, gate.classify(result, reference))
        assertEquals("夏の音楽祭", result.event?.title)
        assertEquals(LocalDateTime.of(2026, 7, 20, 18, 30), result.event?.start)
        assertEquals(LocalDateTime.of(2026, 7, 20, 20, 0), result.event?.end)
    }

    @Test
    fun `正例 スラッシュ日付と午後表記を読める`() {
        val result = normalize("プロダクト発表会", "2026/8/2 午後7時〜9時")

        assertEquals(Classification.AUTO_ADD, gate.classify(result, reference))
        assertEquals(LocalDateTime.of(2026, 8, 2, 19, 0), result.event?.start)
        assertEquals(LocalDateTime.of(2026, 8, 2, 21, 0), result.event?.end)
    }

    @Test
    fun `明示年の過去日時は不合格`() {
        val result = normalize("終了した勉強会", "2025/7/20 19:00")

        assertEquals(Classification.PAST, gate.classify(result, reference))
    }

    @Test
    fun `除外語を含む営業時間は不合格`() {
        val result = normalize("カフェ青空", "営業時間 7/20 10:00〜18:00")

        assertEquals(Classification.EXCLUDED, gate.classify(result, reference))
    }

    @Test
    fun `年省略で過ぎた月日は翌年になる`() {
        val result = normalize("初詣オフ会", "1月5日 10:00", now = LocalDateTime.of(2026, 12, 31, 12, 0))

        assertEquals(LocalDateTime.of(2027, 1, 5, 10, 0), result.event?.start)
        assertEquals(Classification.AUTO_ADD, gate.classify(result, LocalDateTime.of(2026, 12, 31, 12, 0)))
    }

    @Test
    fun `異なる日付候補が複数なら曖昧`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("夏祭り"),
                OcrLine("7/20 18:00"),
                OcrLine("雨天時 7/21 18:00"),
            ),
        )
        val result = normalizer.normalize(candidate, reference)

        assertEquals(Classification.AMBIGUOUS, result.classification)
        assertEquals(null, result.event)
    }

    @Test
    fun `異なるタイトル候補が複数なら曖昧`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("第一部 トーク", OcrBounds(0, 100, 200, 140)),
                OcrLine("第二部 ライブ", OcrBounds(0, 100, 200, 140)),
                OcrLine("7/20 18:00", OcrBounds(0, 160, 200, 200)),
            ),
        )
        val result = normalizer.normalize(candidate, reference)

        assertEquals(Classification.AMBIGUOUS, result.classification)
    }

    @Test
    fun `同じ日時のイベントが複数行にあれば一件へ潰さず曖昧にする`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("第一部 トーク", OcrBounds(0, 100, 200, 140)),
                OcrLine("7/20 18:00", OcrBounds(0, 150, 200, 190)),
                OcrLine("第二部 ライブ", OcrBounds(0, 300, 200, 340)),
                OcrLine("7/20 18:00", OcrBounds(0, 350, 200, 390)),
            ),
        )

        val result = normalizer.normalize(candidate, reference)

        assertEquals(2, candidate.dateCandidates.size)
        assertEquals(2, candidate.timeCandidates.size)
        assertEquals(Classification.AMBIGUOUS, result.classification)
    }

    @Test
    fun `共有日時の近くに複数タイトルがあれば曖昧にする`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("第一部 トーク", OcrBounds(0, 100, 240, 140)),
                OcrLine("第二部 ライブ", OcrBounds(0, 160, 240, 200)),
                OcrLine("7/20 18:00", OcrBounds(0, 220, 240, 260)),
            ),
        )

        assertEquals(2, candidate.titleCandidates.size)
        assertEquals(
            Classification.AMBIGUOUS,
            normalizer.normalize(candidate, reference).classification,
        )
    }

    @Test
    fun `共有日時の近くに同名タイトルが複数行あっても曖昧にする`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("技術勉強会", OcrBounds(0, 100, 240, 140)),
                OcrLine("技術勉強会", OcrBounds(0, 160, 240, 200)),
                OcrLine("7/20 18:00", OcrBounds(0, 220, 240, 260)),
            ),
        )

        assertEquals(2, candidate.titleCandidates.size)
        assertEquals(
            Classification.AMBIGUOUS,
            normalizer.normalize(candidate, reference).classification,
        )
    }

    @Test
    fun `離れた日付と時刻を一つのイベントへ結合しない`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("夏祭り", OcrBounds(0, 50, 240, 90)),
                OcrLine("7/20", OcrBounds(0, 100, 240, 140)),
                OcrLine("18:00", OcrBounds(0, 900, 240, 940)),
            ),
        )

        assertEquals(true, candidate.ambiguousEvidence)
        assertEquals(
            Classification.AMBIGUOUS,
            normalizer.normalize(candidate, reference).classification,
        )
    }

    @Test
    fun `座標なしでは日時直前の行だけをタイトルにする`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("LINEの関係ない会話"),
                OcrLine("あとで連絡します"),
                OcrLine("夏祭り"),
                OcrLine("7/20 18:00"),
            ),
        )

        assertEquals(listOf("夏祭り"), candidate.titleCandidates)
        assertEquals(Classification.AUTO_ADD, gate.classify(normalizer.normalize(candidate, reference), reference))
    }

    @Test
    fun `座標ありでは日時に近い直上タイトルを優先する`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("遠くの会話文", OcrBounds(0, 10, 300, 40)),
                OcrLine("花火大会", OcrBounds(20, 300, 220, 340)),
                OcrLine("7月20日 19:00", OcrBounds(20, 350, 240, 390)),
                OcrLine("下にある別の会話", OcrBounds(20, 600, 300, 640)),
            ),
        )

        assertEquals(listOf("花火大会"), candidate.titleCandidates)
    }

    @Test
    fun `日時直下のUIボタンはタイトル候補にしない`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("花火大会", OcrBounds(20, 100, 240, 140)),
                OcrLine("7月20日 19:00", OcrBounds(20, 150, 260, 190)),
                OcrLine("チケット購入", OcrBounds(20, 200, 260, 240)),
                OcrLine("共有する", OcrBounds(20, 250, 260, 290)),
            ),
        )

        assertEquals(listOf("花火大会"), candidate.titleCandidates)
        assertEquals(
            Classification.AUTO_ADD,
            gate.classify(normalizer.normalize(candidate, reference), reference),
        )
    }

    @Test
    fun `共有日時の直上と直下に異なるタイトルがあれば曖昧にする`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("第一部 トーク", OcrBounds(20, 100, 260, 140)),
                OcrLine("7月20日 19:00", OcrBounds(20, 150, 260, 190)),
                OcrLine("第二部 ライブ", OcrBounds(20, 200, 260, 240)),
            ),
        )

        assertEquals(2, candidate.titleCandidates.size)
        assertEquals(
            Classification.AMBIGUOUS,
            normalizer.normalize(candidate, reference).classification,
        )
    }

    @Test
    fun `共有日時の直上と直下に同名タイトルがあっても曖昧にする`() {
        val candidate = extractor.extract(
            listOf(
                OcrLine("技術勉強会", OcrBounds(20, 100, 260, 140)),
                OcrLine("7月20日 19:00", OcrBounds(20, 150, 260, 190)),
                OcrLine("技術勉強会", OcrBounds(20, 200, 260, 240)),
            ),
        )

        assertEquals(2, candidate.titleCandidates.size)
        assertEquals(
            Classification.AMBIGUOUS,
            normalizer.normalize(candidate, reference).classification,
        )
    }

    @Test
    fun `終了省略時は60分で補完する`() {
        val result = normalize("技術勉強会", "7月20日 18:00")

        assertNotNull(result.event)
        assertEquals(LocalDateTime.of(2026, 7, 20, 19, 0), result.event?.end)
    }

    @Test
    fun `400日より未来は不合格`() {
        val result = normalize("未来のイベント", "2027/9/1 12:00")

        assertEquals(Classification.TOO_FAR, gate.classify(result, reference))
    }

    @Test
    fun `24時間を超える予定は不合格`() {
        val result = NormalizationResult(
            classification = Classification.AUTO_ADD,
            event = NormalizedEvent(
                title = "長すぎるイベント",
                start = reference.plusDays(1),
                end = reference.plusDays(2).plusMinutes(1),
            ),
        )

        assertEquals(Classification.TOO_LONG, gate.classify(result, reference))
    }

    @Test
    fun `未来400日ちょうどかつ24時間ちょうどは登録できる`() {
        val start = reference.plusDays(400)
        val result = NormalizationResult(
            classification = Classification.AUTO_ADD,
            event = NormalizedEvent(
                title = "境界イベント",
                start = start,
                end = start.plusHours(24),
            ),
        )

        assertEquals(Classification.AUTO_ADD, gate.classify(result, reference))
    }

    @Test
    fun `現在時刻ちょうどは未来として扱わない`() {
        val result = NormalizationResult(
            classification = Classification.AUTO_ADD,
            event = NormalizedEvent(
                title = "現在イベント",
                start = reference,
                end = reference.plusHours(1),
            ),
        )

        assertEquals(Classification.PAST, gate.classify(result, reference))
    }

    @Test
    fun `現在時刻より最小単位でも未来なら登録できる`() {
        val start = reference.plusNanos(1)
        val result = NormalizationResult(
            classification = Classification.AUTO_ADD,
            event = NormalizedEvent("直後イベント", start, start.plusHours(1)),
        )

        assertEquals(Classification.AUTO_ADD, gate.classify(result, reference))
    }

    @Test
    fun `400日を最小単位でも超えれば登録しない`() {
        val start = reference.plusDays(400).plusNanos(1)
        val result = NormalizationResult(
            classification = Classification.AUTO_ADD,
            event = NormalizedEvent("遠未来イベント", start, start.plusHours(1)),
        )

        assertEquals(Classification.TOO_FAR, gate.classify(result, reference))
    }

    private fun normalize(vararg text: String, now: LocalDateTime = reference): NormalizationResult {
        val candidate = extractor.extract(text.map(::OcrLine))
        return normalizer.normalize(candidate, now)
    }
}
