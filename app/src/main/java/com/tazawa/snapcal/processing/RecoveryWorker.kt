package com.tazawa.snapcal.processing

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.tazawa.snapcal.calendar.AddReconcileResult
import com.tazawa.snapcal.calendar.CalendarGateway
import com.tazawa.snapcal.calendar.DeleteReconcileResult
import com.tazawa.snapcal.data.RecordTransition
import com.tazawa.snapcal.data.ScreenshotRecord
import com.tazawa.snapcal.data.ScreenshotRecordRepository
import com.tazawa.snapcal.data.SnapCalDatabase
import com.tazawa.snapcal.data.StaleRecordAction
import java.util.concurrent.TimeUnit

class RecoveryWorker(
    appContext: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(appContext, parameters) {
    override suspend fun doWork(): Result {
        val database = Room.databaseBuilder(
            applicationContext,
            SnapCalDatabase::class.java,
            "snapcal.db",
        ).build()
        return try {
            val repository = ScreenshotRecordRepository(database.screenshotRecordDao())
            val calendar = CalendarGateway(applicationContext.contentResolver)
            repository.staleRecords().forEach { stale ->
                when (stale.action) {
                    StaleRecordAction.FAIL_PROCESSING -> repository.transition(
                        stale.record.contentHash,
                        RecordTransition.Failed("failed_stale_processing"),
                    )
                    StaleRecordAction.RECONCILE_ADD -> reconcileAdd(
                        repository,
                        calendar,
                        stale.record,
                    )
                    StaleRecordAction.RECONCILE_DELETE -> reconcileDelete(
                        repository,
                        calendar,
                        stale.record,
                    )
                    StaleRecordAction.NONE -> Unit
                }
            }
            repository.cleanupExpired()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        } finally {
            database.close()
        }
    }

    private suspend fun reconcileAdd(
        repository: ScreenshotRecordRepository,
        calendar: CalendarGateway,
        record: ScreenshotRecord,
    ) {
        val token = record.operationToken ?: return fail(repository, record, "failed_stale_add")
        val signature = record.eventSignature ?: return fail(repository, record, "failed_stale_add")
        val calendarId = record.calendarId ?: return fail(repository, record, "failed_stale_add")
        when (val result = calendar.reconcileAdd(token, signature)) {
            is AddReconcileResult.Adopted -> repository.transition(
                record.contentHash,
                RecordTransition.AutoAdded(calendarId, result.eventId),
            )
            else -> fail(repository, record, "failed_stale_add")
        }
    }

    private suspend fun reconcileDelete(
        repository: ScreenshotRecordRepository,
        calendar: CalendarGateway,
        record: ScreenshotRecord,
    ) {
        val token = record.operationToken ?: return fail(repository, record, "failed_stale_delete")
        val signature = record.eventSignature ?: return fail(repository, record, "failed_stale_delete")
        val eventId = record.calendarEventId ?: return fail(repository, record, "failed_stale_delete")
        when (calendar.reconcileDelete(eventId, token, signature)) {
            is DeleteReconcileResult.Deleted,
            DeleteReconcileResult.AlreadyAbsent,
            -> repository.transition(record.contentHash, RecordTransition.Undone)
            else -> fail(repository, record, "failed_stale_delete")
        }
    }

    private suspend fun fail(
        repository: ScreenshotRecordRepository,
        record: ScreenshotRecord,
        code: String,
    ) {
        repository.transition(record.contentHash, RecordTransition.Failed(code))
    }
}

object RecoveryScheduler {
    private const val ON_START = "snapcal-recovery-on-start"
    private const val PERIODIC = "snapcal-recovery-daily"

    fun schedule(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.enqueueUniqueWork(
            ON_START,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<RecoveryWorker>().build(),
        )
        workManager.enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<RecoveryWorker>(24, TimeUnit.HOURS).build(),
        )
    }
}
