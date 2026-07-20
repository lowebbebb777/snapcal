package com.tazawa.snapcal.data

class ScreenshotRecordRepository(
    private val dao: ScreenshotRecordDao,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    suspend fun enqueue(
        contentHash: String,
        capturedAt: Long,
        detectedAt: Long = nowEpochMillis(),
    ): EnqueueResult {
        require(contentHash.isNotBlank()) { "contentHash must not be blank" }
        val now = nowEpochMillis()
        val record = ScreenshotRecord(
            contentHash = contentHash,
            capturedAt = capturedAt,
            detectedAt = detectedAt,
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
        return dao.enqueue(record, now)
    }

    suspend fun transition(
        contentHash: String,
        transition: RecordTransition,
    ): ScreenshotRecord? {
        val now = nowEpochMillis()
        dao.deleteExpired(now)
        return dao.transition(contentHash, transition, now)
    }

    suspend fun findByContentHash(contentHash: String): ScreenshotRecord? {
        val now = nowEpochMillis()
        dao.deleteExpired(now)
        return dao.findByContentHash(contentHash, now)
    }

    suspend fun findByEventSignature(eventSignature: String): ScreenshotRecord? {
        val now = nowEpochMillis()
        dao.deleteExpired(now)
        return dao.findByEventSignature(eventSignature, now)
    }

    suspend fun staleRecords(): List<ClassifiedStaleRecord> {
        val now = nowEpochMillis()
        dao.deleteExpired(now)
        val cutoff = now - StaleRecordPolicy.STALE_AFTER_MILLIS
        return dao.findPotentiallyStale(cutoff).mapNotNull { record ->
            val action = StaleRecordPolicy.classify(record, now)
            action.takeUnless { it == StaleRecordAction.NONE }
                ?.let { ClassifiedStaleRecord(record, it) }
        }
    }

    suspend fun cleanupExpired(): Int = dao.deleteExpired(nowEpochMillis())
}

sealed interface EnqueueResult {
    data class Created(val record: ScreenshotRecord) : EnqueueResult

    data class AlreadyExists(val record: ScreenshotRecord) : EnqueueResult

    /** Defensive fallback for a provider/transaction invariant violation. */
    data object Conflict : EnqueueResult
}

data class ClassifiedStaleRecord(
    val record: ScreenshotRecord,
    val action: StaleRecordAction,
)
