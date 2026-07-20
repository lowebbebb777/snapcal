package com.tazawa.snapcal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordStateMachineTest {
    @Test
    fun `all transitions advance phase entered time`() {
        var now = 1_000L
        var record = queued(now)

        fun apply(transition: RecordTransition) {
            now += 100
            record = RecordStateMachine.transition(record, transition, now)
            assertEquals(now, record.phaseEnteredAt)
        }

        apply(RecordTransition.StartRecognizing)
        apply(RecordTransition.CandidateReady("event-hash"))
        apply(RecordTransition.BeginAdding("add-token", calendarId = 2))
        apply(RecordTransition.AddReconcileRequired("add_unknown"))
        apply(RecordTransition.AutoAdded(calendarId = 2, calendarEventId = 3))
        apply(RecordTransition.BeginUndo)
        apply(RecordTransition.DeleteReconcileRequired("delete_unknown"))
        apply(RecordTransition.Undone)

        assertEquals(RecordStatus.UNDONE, record.status)
        assertEquals(now, record.terminalAt)
        assertEquals(now + RecordStateMachine.TERMINAL_RETENTION_MILLIS, record.recordExpiresAt)
        assertEquals(1, record.recognitionAttempts)
        assertEquals(2, record.calendarAttempts)
    }

    @Test
    fun `begin undo clears old terminal retention clocks`() {
        val autoAdded = queued(100)
            .let { RecordStateMachine.transition(it, RecordTransition.StartRecognizing, 200) }
            .let { RecordStateMachine.transition(it, RecordTransition.CandidateReady("signature"), 300) }
            .let {
                RecordStateMachine.transition(
                    it,
                    RecordTransition.BeginAdding("add-token", calendarId = 1),
                    400,
                )
            }
            .let { RecordStateMachine.transition(it, RecordTransition.AutoAdded(1, 9), 500) }

        val undoPending = RecordStateMachine.transition(
            autoAdded,
            RecordTransition.BeginUndo,
            600,
        )

        assertEquals(RecordStatus.UNDO_PENDING, undoPending.status)
        assertEquals(600, undoPending.phaseEnteredAt)
        assertNull(undoPending.terminalAt)
        assertNull(undoPending.recordExpiresAt)
        assertEquals("add-token", undoPending.operationToken)
        assertEquals(1L, undoPending.calendarId)
        assertEquals(9L, undoPending.calendarEventId)
        assertEquals("signature", undoPending.eventSignature)
    }

    @Test
    fun `invalid transition is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordStateMachine.transition(
                queued(100),
                RecordTransition.AutoAdded(1, 2),
                200,
            )
        }
    }

    @Test
    fun `ambiguous OCR can finish as needs review without an event signature`() {
        val recognizing = RecordStateMachine.transition(
            queued(100),
            RecordTransition.StartRecognizing,
            200,
        )

        val needsReview = RecordStateMachine.transition(
            recognizing,
            RecordTransition.NeedsReview("ambiguous"),
            300,
        )

        assertEquals(RecordStatus.NEEDS_REVIEW, needsReview.status)
        assertNull(needsReview.eventSignature)
        assertEquals(300, needsReview.phaseEnteredAt)
    }

    private fun queued(now: Long) = ScreenshotRecord(
        contentHash = "content-hash",
        capturedAt = now,
        detectedAt = now,
        createdAt = now,
        phaseEnteredAt = now,
        terminalAt = null,
        recordExpiresAt = null,
        status = RecordStatus.QUEUED,
        eventSignature = null,
        operationToken = null,
        calendarId = null,
        calendarEventId = null,
        recognitionAttempts = 0,
        calendarAttempts = 0,
        errorCode = null,
    )
}
