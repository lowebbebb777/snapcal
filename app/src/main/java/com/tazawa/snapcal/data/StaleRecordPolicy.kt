package com.tazawa.snapcal.data

enum class StaleRecordAction {
    NONE,
    FAIL_PROCESSING,
    RECONCILE_ADD,
    RECONCILE_DELETE,
}

object StaleRecordPolicy {
    const val STALE_AFTER_MILLIS: Long = 24L * 60L * 60L * 1_000L

    fun classify(record: ScreenshotRecord, nowEpochMillis: Long): StaleRecordAction {
        if (nowEpochMillis - record.phaseEnteredAt < STALE_AFTER_MILLIS) {
            return StaleRecordAction.NONE
        }

        return when (record.status) {
            RecordStatus.QUEUED,
            RecordStatus.RECOGNIZING,
            RecordStatus.CANDIDATE_READY,
            -> StaleRecordAction.FAIL_PROCESSING

            RecordStatus.ADDING,
            RecordStatus.ADD_RECONCILE_REQUIRED,
            -> StaleRecordAction.RECONCILE_ADD

            RecordStatus.UNDO_PENDING,
            RecordStatus.DELETE_RECONCILE_REQUIRED,
            -> StaleRecordAction.RECONCILE_DELETE

            RecordStatus.AUTO_ADDED,
            RecordStatus.NEEDS_REVIEW,
            RecordStatus.IGNORED,
            RecordStatus.FAILED,
            RecordStatus.UNDONE,
            -> StaleRecordAction.NONE
        }
    }
}

