package com.tazawa.snapcal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
abstract class ScreenshotRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(record: ScreenshotRecord): Long

    @Query(
        """
        SELECT * FROM screenshot_records
        WHERE content_hash = :contentHash
          AND (record_expires_at IS NULL OR record_expires_at > :nowEpochMillis)
        LIMIT 1
        """,
    )
    abstract suspend fun findByContentHash(
        contentHash: String,
        nowEpochMillis: Long,
    ): ScreenshotRecord?

    @Query(
        """
        SELECT * FROM screenshot_records
        WHERE event_signature = :eventSignature
          AND (record_expires_at IS NULL OR record_expires_at > :nowEpochMillis)
        LIMIT 1
        """,
    )
    abstract suspend fun findByEventSignature(
        eventSignature: String,
        nowEpochMillis: Long,
    ): ScreenshotRecord?

    @Query(
        """
        SELECT * FROM screenshot_records
        WHERE phase_entered_at <= :cutoffEpochMillis
          AND status IN (
            'queued', 'recognizing', 'candidate_ready',
            'adding', 'add_reconcile_required',
            'undo_pending', 'delete_reconcile_required'
          )
        """,
    )
    abstract suspend fun findPotentiallyStale(cutoffEpochMillis: Long): List<ScreenshotRecord>

    @Query(
        """
        DELETE FROM screenshot_records
        WHERE record_expires_at IS NOT NULL
          AND record_expires_at <= :nowEpochMillis
        """,
    )
    abstract suspend fun deleteExpired(nowEpochMillis: Long): Int

    @Update
    protected abstract suspend fun update(record: ScreenshotRecord): Int

    /** Cleanup, insert, and conflict lookup share one SQLite transaction. */
    @Transaction
    open suspend fun enqueue(
        record: ScreenshotRecord,
        nowEpochMillis: Long,
    ): EnqueueResult {
        deleteExpired(nowEpochMillis)
        if (insert(record) != -1L) return EnqueueResult.Created(record)
        return findByContentHash(record.contentHash, nowEpochMillis)
            ?.let { EnqueueResult.AlreadyExists(it) }
            ?: EnqueueResult.Conflict
    }

    /** Reads, validates, and writes the complete state change in one DB transaction. */
    @Transaction
    open suspend fun transition(
        contentHash: String,
        stateTransition: RecordTransition,
        nowEpochMillis: Long,
    ): ScreenshotRecord? {
        val current = findByContentHash(contentHash, nowEpochMillis) ?: return null
        val next = RecordStateMachine.transition(current, stateTransition, nowEpochMillis)
        check(update(next) == 1) { "Record disappeared during transition" }
        return next
    }
}
