package com.tazawa.snapcal.calendar

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconcileClassifierTest {
    @Test
    fun `zero token matches is no match`() {
        assertEquals(
            MatchClassification.NoMatch,
            CalendarEventFactory.classifyMatches(emptyList(), "expected"),
        )
    }

    @Test
    fun `one token and matching signature is exact`() {
        assertEquals(
            MatchClassification.ExactMatch(7L),
            CalendarEventFactory.classifyMatches(
                listOf(EventFingerprint(7L, "expected")),
                "expected",
            ),
        )
    }

    @Test
    fun `one token and mismatching signature fails closed`() {
        assertEquals(
            MatchClassification.SignatureMismatch,
            CalendarEventFactory.classifyMatches(
                listOf(EventFingerprint(7L, "different")),
                "expected",
            ),
        )
    }

    @Test
    fun `multiple token matches are always ambiguous`() {
        assertEquals(
            MatchClassification.Ambiguous(2),
            CalendarEventFactory.classifyMatches(
                listOf(
                    EventFingerprint(7L, "expected"),
                    EventFingerprint(8L, "expected"),
                ),
                "expected",
            ),
        )
    }
}
