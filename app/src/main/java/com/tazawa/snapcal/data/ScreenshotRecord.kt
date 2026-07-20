package com.tazawa.snapcal.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Privacy-minimal processing metadata.
 *
 * Screenshot URIs, OCR output, event names, dates, locations, and URLs must
 * never be added to this entity. SQLite permits multiple NULL values in a
 * UNIQUE index, which is required while an event signature is not known yet.
 */
@Entity(
    tableName = "screenshot_records",
    indices = [Index(value = ["event_signature"], unique = true)],
)
data class ScreenshotRecord(
    @PrimaryKey
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "captured_at")
    val capturedAt: Long,
    @ColumnInfo(name = "detected_at")
    val detectedAt: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "phase_entered_at")
    val phaseEnteredAt: Long,
    @ColumnInfo(name = "terminal_at")
    val terminalAt: Long?,
    @ColumnInfo(name = "record_expires_at")
    val recordExpiresAt: Long?,
    val status: RecordStatus,
    @ColumnInfo(name = "event_signature")
    val eventSignature: String?,
    @ColumnInfo(name = "operation_token")
    val operationToken: String?,
    @ColumnInfo(name = "calendar_id")
    val calendarId: Long?,
    @ColumnInfo(name = "calendar_event_id")
    val calendarEventId: Long?,
    @ColumnInfo(name = "recognition_attempts")
    val recognitionAttempts: Int,
    @ColumnInfo(name = "calendar_attempts")
    val calendarAttempts: Int,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
)
