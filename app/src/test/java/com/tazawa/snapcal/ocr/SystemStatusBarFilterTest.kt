package com.tazawa.snapcal.ocr

import com.tazawa.snapcal.domain.Classification
import com.tazawa.snapcal.domain.EventNormalizer
import com.tazawa.snapcal.domain.OcrBounds
import com.tazawa.snapcal.domain.OcrLine
import com.tazawa.snapcal.domain.TextEvidenceExtractor
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Uses synthetic status bars and appointment labels, without user data. */
class SystemStatusBarFilterTest {
    @Test
    fun `status bar clock and date do not become extra event candidates`() {
        val lines = listOf(
            line("12:04 10月29日(木)", 15, 42),
            line("100%", 760, 40),
            line("デモ診察券", 100, 240),
            line("青空クリニック", 100, 510),
            line("テスト患者", 100, 565),
            line("11月2日（月）16:50", 100, 615),
            line("予約時間をお待ち下さい", 100, 730),
        )

        val filtered = SystemStatusBarFilter.filter(lines, 900, 2200)
        assertEquals(5, filtered.size)
        val candidate = TextEvidenceExtractor().extract(
            filtered.map { OcrLine(it.text, OcrBounds(it.left, it.top, it.right, it.bottom)) },
        )
        assertEquals(1, candidate.dateCandidates.size)
        assertEquals(1, candidate.timeCandidates.size)
        assertEquals(listOf("青空クリニック"), candidate.titleCandidates)
        assertEquals(
            Classification.AUTO_ADD,
            EventNormalizer().normalize(candidate, LocalDateTime.of(2026, 10, 29, 12, 4)).classification,
        )
    }

    @Test
    fun `ordinary portrait flyer without a system clock remains unchanged`() {
        val lines = listOf(
            line("イベントのお知らせ", 40, 25),
            line("11月2日 16:50", 40, 80),
        )
        assertEquals(lines, SystemStatusBarFilter.filter(lines, 900, 2200))
    }

    @Test
    fun `landscape image remains unchanged`() {
        val lines = listOf(line("12:04", 20, 15), line("11月2日 16:50", 80, 90))
        assertEquals(lines, SystemStatusBarFilter.filter(lines, 2200, 900))
    }

    @Test
    fun `event timestamp in the body remains unchanged`() {
        val lines = listOf(line("12:04", 20, 20), line("11月2日 16:50", 30, 240))
        val filtered = SystemStatusBarFilter.filter(lines, 900, 2200)
        assertEquals(listOf(lines[1]), filtered)
    }

    @Test
    fun `right aligned time inside top band is not treated as system clock`() {
        val lines = listOf(line("12:04", 700, 30), line("11月2日 16:50", 60, 200))
        assertEquals(lines, SystemStatusBarFilter.filter(lines, 900, 2200))
    }

    @Test
    fun `top band line without clock does not trigger filtering`() {
        val lines = listOf(line("10月29日", 20, 28), line("11月2日 16:50", 20, 250))
        assertTrue(SystemStatusBarFilter.filter(lines, 900, 2200).containsAll(lines))
    }

    private fun line(text: String, left: Int, top: Int): RecognizedLine =
        RecognizedLine(text, left, top, left + 160, top + 35)
}
