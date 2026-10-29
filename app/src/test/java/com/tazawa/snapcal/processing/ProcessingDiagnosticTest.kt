package com.tazawa.snapcal.processing

import com.tazawa.snapcal.domain.Classification
import com.tazawa.snapcal.domain.EventCandidate
import com.tazawa.snapcal.domain.DateEvidence
import com.tazawa.snapcal.domain.TimeEvidence
import com.tazawa.snapcal.domain.ClockEvidence
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only counts and generic status are shared in diagnostics, never OCR text. */
class ProcessingDiagnosticTest {
    private fun candidate(titleCount: Int, dateCount: Int, timeCount: Int, separated: Boolean = false) =
        EventCandidate(
            titleCandidates = List(titleCount) { "架空の施設名・患者名$it" },
            dateCandidates = List(dateCount) { DateEvidence("秘密の日付", 2026, 11, 2) },
            timeCandidates = List(timeCount) { TimeEvidence(ClockEvidence("秘密の時刻", 16, 50)) },
            ambiguousEvidence = separated,
        )

    @Test
    fun `ambiguous report explains candidate counts without OCR`() {
        val d = ProcessingDiagnostic.describe(Classification.AMBIGUOUS, candidate(4, 2, 1))
        assertTrue(d.contains("タイトル4"))
        assertTrue(d.contains("日付2"))
        assertFalse(d.contains("架空の施設名"))
        assertFalse(d.contains("秘密"))
    }

    @Test
    fun `separated timestamps have actionable message`() {
        val d = ProcessingDiagnostic.describe(Classification.AMBIGUOUS, candidate(1, 1, 1, true))
        assertTrue(d.contains("離れた場所"))
    }

    @Test
    fun `missing title is explicit`() {
        val d = ProcessingDiagnostic.describe(Classification.INVALID, candidate(0, 1, 1))
        assertTrue(d.contains("不足"))
        assertTrue(d.contains("タイトル0"))
    }

    @Test
    fun `excluded and past dates have distinct messages`() {
        val c = candidate(1, 1, 1)
        assertTrue(ProcessingDiagnostic.describe(Classification.EXCLUDED, c).contains("予定以外"))
        assertTrue(ProcessingDiagnostic.describe(Classification.PAST, c).contains("過去"))
    }
}
