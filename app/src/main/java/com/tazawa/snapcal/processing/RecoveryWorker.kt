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
            var retryRequired = false
            repository.staleRecords().forEach { stale ->
                val recordNeedsRetry = when (stale.action) {
                    StaleRecordAction.FAIL_PROCESSING -> {
                        repository.transition(
                            stale.record.contentHash,
                            RecordTransition.Failed("failed_stale_processing"),
                        )
                        false
                    }
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
                    StaleRecordAction.NONE -> false
                }
                retryRequired = retryRequired || recordNeedsRetry
            }
            repository.cleanupExpired()
            if (retryRequired) Result.retry() else Result.success()
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
    ): Boolean {
        val token = record.operationToken ?: return fail(repository, record, "failed_stale_add")
        val signature = record.eventSignature ?: return fail(repository, record, "failed_stale_add")
        val calendarId = record.calendarId ?: return fail(repository, record, "failed_stale_add")
        val result = calendar.reconcileAdd(token, signature)
        return when (result.recoveryDisposition()) {
            ReconcileDisposition.COMPLETE -> {
                check(result is AddReconcileResult.Adopted)
                repository.transition(
                    record.contentHash,
                    RecordTransition.AutoAdded(calendarId, result.eventId),
                )
                false
            }
            ReconcileDisposition.FAIL -> fail(repository, record, "failed_stale_add")
            ReconcileDisposition.RETRY -> true
        }
    }

    private suspend fun reconcileDelete(
        repository: ScreenshotRecordRepository,
        calendar: CalendarGateway,
        record: ScreenshotRecord,
    ): Boolean {
        val token = record.operationToken ?: return fail(repository, record, "failed_stale_delete")
        val signature = record.eventSignature ?: return fail(repository, record, "failed_stale_delete")
        val eventId = record.calendarEventId ?: return fail(repository, record, "failed_stale_delete")
        return when (calendar.reconcileDelete(eventId, token, signature).recoveryDisposition()) {
            ReconcileDisposition.COMPLETE -> {
                repository.transition(record.contentHash, RecordTransition.Undone)
                false
            }
            ReconcileDisposition.FAIL -> fail(repository, record, "failed_stale_delete")
            ReconcileDisposition.RETRY -> true
        }
    }

    private suspend fun fail(
        repository: ScreenshotRecordRepository,
        record: ScreenshotRecord,
        code: String,
    ): Boolean {
        repository.transition(record.contentHash, RecordTransition.Failed(code))
        return false
    }
}

internal enum class ReconcileDisposition {
    COMPLETE,
    FAIL,
    RETRY,
}

internal fun AddReconcileResult.recoveryDisposition(): ReconcileDisposition = when (this) {
    is AddReconcileResult.Adopted -> ReconcileDisposition.COMPLETE
    AddReconcileResult.NoMatch,
    AddReconcileResult.SignatureMismatch,
    is AddReconcileResult.Ambiguous,
    -> ReconcileDisposition.FAIL
    AddReconcileResult.PermissionDenied,
    AddReconcileResult.ProviderUnavailable,
    -> ReconcileDisposition.RETRY
}

internal fun DeleteReconcileResult.recoveryDisposition(): ReconcileDisposition = when (this) {
    is DeleteReconcileResult.Deleted,
    DeleteReconcileResult.AlreadyAbsent,
    -> ReconcileDisposition.COMPLETE
    DeleteReconcileResult.SignatureMismatch,
    DeleteReconcileResult.TokenMismatch,
    DeleteReconcileResult.EventIdMismatch,
    is DeleteReconcileResult.Ambiguous,
    -> ReconcileDisposition.FAIL
    DeleteReconcileResult.DeleteNotConfirmed,
    DeleteReconcileResult.PermissionDenied,
    DeleteReconcileResult.ProviderUnavailable,
    -> ReconcileDisposition.RETRY
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
