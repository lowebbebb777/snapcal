package com.tazawa.snapcal.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StaleRecordPolicyTest {
    private val now = StaleRecordPolicy.STALE_AFTER_MILLIS + 10_000L

    @Test
    fun `processing states fail after 24 hours`() {
        listOf(
            RecordStatus.QUEUED,
            RecordStatus.RECOGNIZING,
            RecordStatus.CANDIDATE_READY,
        ).forEach { status ->
            assertEquals(
                StaleRecordAction.FAIL_PROCESSING,
                StaleRecordPolicy.classify(record(status), now),
            )
        }
    }

    @Test
    fun `add states reconcile after 24 hours`() {
        listOf(RecordStatus.ADDING, RecordStatus.ADD_RECONCILE_REQUIRED).forEach { status ->
            assertEquals(
                StaleRecordAction.RECONCILE_ADD,
                StaleRecordPolicy.classify(record(status), now),
            )
        }
    }

    @Test
    fun `delete states reconcile after 24 hours`() {
        listOf(RecordStatus.UNDO_PENDING, RecordStatus.DELETE_RECONCILE_REQUIRED).forEach { status ->
            assertEquals(
                StaleRecordAction.RECONCILE_DELETE,
                StaleRecordPolicy.classify(record(status), now),
            )
        }
    }

    @Test
    fun `auto added is never classified as stale`() {
        assertEquals(
            StaleRecordAction.NONE,
            StaleRecordPolicy.classify(record(RecordStatus.AUTO_ADDED), now),
        )
    }

    @Test
    fun `phase younger than 24 hours is not stale`() {
        val recent = record(RecordStatus.ADDING).copy(
            phaseEnteredAt = now - StaleRecordPolicy.STALE_AFTER_MILLIS + 1,
        )
        assertEquals(StaleRecordAction.NONE, StaleRecordPolicy.classify(recent, now))
    }

    private fun record(status: RecordStatus) = ScreenshotRecord(
        contentHash = "content-hash-${status.storedValue}",
        capturedAt = 0,
        detectedAt = 0,
        createdAt = 0,
        phaseEnteredAt = 0,
        terminalAt = null,
        recordExpiresAt = null,
        status = status,
        eventSignature = null,
        operationToken = null,
        calendarId = if (status == RecordStatus.AUTO_ADDED) 1 else null,
        calendarEventId = if (status == RecordStatus.AUTO_ADDED) 2 else null,
        recognitionAttempts = 0,
        calendarAttempts = 0,
        errorCode = null,
    )
}
