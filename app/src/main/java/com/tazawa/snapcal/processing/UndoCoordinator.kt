package com.tazawa.snapcal.processing

import android.content.Context
import androidx.room.Room
import com.tazawa.snapcal.calendar.CalendarGateway
import com.tazawa.snapcal.calendar.UndoEventResult
import com.tazawa.snapcal.data.RecordStatus
import com.tazawa.snapcal.data.RecordTransition
import com.tazawa.snapcal.data.ScreenshotRecordRepository
import com.tazawa.snapcal.data.SnapCalDatabase
import java.io.Closeable

class UndoCoordinator(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val database = Room.databaseBuilder(
        appContext,
        SnapCalDatabase::class.java,
        "snapcal.db",
    ).build()
    private val repository = ScreenshotRecordRepository(database.screenshotRecordDao())
    private val calendarGateway = CalendarGateway(appContext.contentResolver)

    suspend fun undo(contentHash: String): Boolean {
        val record = repository.findByContentHash(contentHash) ?: return false
        if (record.status != RecordStatus.AUTO_ADDED) return record.status == RecordStatus.UNDONE
        val eventId = record.calendarEventId ?: return false
        val operationToken = record.operationToken ?: return false
        repository.transition(contentHash, RecordTransition.BeginUndo)
        return when (calendarGateway.undoSavedEvent(eventId, operationToken)) {
            is UndoEventResult.Deleted,
            UndoEventResult.AlreadyAbsent,
            -> {
                repository.transition(contentHash, RecordTransition.Undone)
                true
            }
            UndoEventResult.DeleteNotConfirmed,
            UndoEventResult.ProviderUnavailable,
            -> {
                repository.transition(
                    contentHash,
                    RecordTransition.DeleteReconcileRequired("delete_result_unknown"),
                )
                false
            }
            UndoEventResult.PermissionDenied -> {
                repository.transition(contentHash, RecordTransition.Failed("calendar_permission_denied"))
                false
            }
            UndoEventResult.TokenMismatch -> {
                repository.transition(contentHash, RecordTransition.Failed("delete_token_mismatch"))
                false
            }
        }
    }

    override fun close() {
        database.close()
    }
}
