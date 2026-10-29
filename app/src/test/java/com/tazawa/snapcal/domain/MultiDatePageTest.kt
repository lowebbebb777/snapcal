package com.tazawa.snapcal.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Synthetic test screens only: never include user medical details. */
class MultiDatePageTest {
    private val extractor = TextEvidenceExtractor()
    private val normalizer = EventNormalizer()
    private val reference = LocalDateTime.of(2026, 10, 29, 12, 0)

    @Test
    fun `unrelated header date is ignored when appointment has explicit datetime`() {
        val c = extractor.extract(
            listOf(
                line("10月29日 お知らせ", 20),
                line("デモ診察券", 100),
                line("青空クリニック", 380),
                line("11月2日（月）16:50", 510),
            ),
        )
        assertEquals(1, c.dateCandidates.size)
        assertEquals(1, c.timeCandidates.size)
        assertEquals(11, c.dateCandidates.single().month)
        assertEquals(2, c.dateCandidates.single().day)
        assertEquals("青空クリニック", normalizer.normalize(c, reference).event?.title)
    }

    @Test
    fun `unrelated expiry date should not veto a real booking`() {
        val c = extractor.extract(
            listOf(
                line("11月9日 有効期限", 20),
                line("デモ診察券", 80),
                line("青空クリニック", 360),
                line("11月2日（月）16:50", 480),
            ),
        )
        assertEquals(1, c.dateCandidates.size)
        assertEquals(emptySet<String>(), c.exclusionKeywords)
        assertEquals(Classification.AUTO_ADD, normalizer.normalize(c, reference).classification)
    }

    @Test
    fun `two distinct events with separate start times stay ambiguous`() {
        val c = extractor.extract(
            listOf(
                line("第一部 トーク", 100),
                line("11月2日 16:50", 150),
                line("第二部 ライブ", 500),
                line("11月3日 17:50", 550),
            ),
        )
        assertEquals(2, c.dateCandidates.size)
        assertEquals(2, c.timeCandidates.size)
        assertEquals(Classification.AMBIGUOUS, normalizer.normalize(c, reference).classification)
    }

    @Test
    fun `two dates on the same appointment row stay ambiguous`() {
        val c = extractor.extract(listOf(line("11月2日 または 11月3日 16:50", 120)))
        assertEquals(2, c.dateCandidates.size)
        assertEquals(Classification.AMBIGUOUS, normalizer.normalize(c, reference).classification)
    }

    @Test
    fun `opening hours remain excluded`() {
        val c = extractor.extract(listOf(line("営業時間 11月2日 16:50", 120)))
        assertEquals(Classification.EXCLUDED, normalizer.normalize(c, reference).classification)
    }

    private fun line(text: String, top: Int) =
        OcrLine(text, OcrBounds(100, top, 480, top + 38))
}
