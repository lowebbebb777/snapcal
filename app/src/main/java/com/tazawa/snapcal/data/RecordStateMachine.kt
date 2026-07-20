package com.tazawa.snapcal.data

object RecordStateMachine {
    const val TERMINAL_RETENTION_MILLIS: Long = 30L * 24L * 60L * 60L * 1_000L

    fun transition(
        record: ScreenshotRecord,
        transition: RecordTransition,
        nowEpochMillis: Long,
    ): ScreenshotRecord {
        require(nowEpochMillis >= record.phaseEnteredAt) {
            "A transition cannot move phaseEnteredAt backwards"
        }

        val next = when (transition) {
            RecordTransition.StartRecognizing -> {
                requireStatus(record, RecordStatus.QUEUED)
                record.copy(
                    status = RecordStatus.RECOGNIZING,
                    recognitionAttempts = record.recognitionAttempts + 1,
                    errorCode = null,
                )
            }

            is RecordTransition.CandidateReady -> {
                requireStatus(record, RecordStatus.RECOGNIZING)
                require(transition.eventSignature.isNotBlank()) { "eventSignature must not be blank" }
                record.copy(
                    status = RecordStatus.CANDIDATE_READY,
                    eventSignature = transition.eventSignature,
                    errorCode = null,
                )
            }

            is RecordTransition.BeginAdding -> {
                requireStatus(record, RecordStatus.CANDIDATE_READY)
                require(transition.operationToken.isNotBlank()) { "operationToken must not be blank" }
                require(transition.calendarId >= 0) { "calendarId must be non-negative" }
                record.copy(
                    status = RecordStatus.ADDING,
                    operationToken = transition.operationToken,
                    calendarId = transition.calendarId,
                    calendarAttempts = record.calendarAttempts + 1,
                    errorCode = null,
                )
            }

            is RecordTransition.AddReconcileRequired -> {
                requireStatus(record, RecordStatus.ADDING)
                require(transition.errorCode.isNotBlank()) { "errorCode must not be blank" }
                record.copy(
                    status = RecordStatus.ADD_RECONCILE_REQUIRED,
                    errorCode = transition.errorCode,
                )
            }

            is RecordTransition.AutoAdded -> {
                requireStatus(
                    record,
                    RecordStatus.ADDING,
                    RecordStatus.ADD_RECONCILE_REQUIRED,
                )
                require(transition.calendarId >= 0) { "calendarId must be non-negative" }
                require(transition.calendarEventId >= 0) { "calendarEventId must be non-negative" }
                record.copy(
                    status = RecordStatus.AUTO_ADDED,
                    calendarId = transition.calendarId,
                    calendarEventId = transition.calendarEventId,
                    errorCode = null,
                ).asTerminal(nowEpochMillis)
            }

            is RecordTransition.NeedsReview -> {
                requireStatus(
                    record,
                    RecordStatus.RECOGNIZING,
                    RecordStatus.CANDIDATE_READY,
                )
                record.copy(
                    status = RecordStatus.NEEDS_REVIEW,
                    errorCode = transition.errorCode,
                ).asTerminal(nowEpochMillis)
            }

            is RecordTransition.Ignored -> {
                requireStatus(
                    record,
                    RecordStatus.RECOGNIZING,
                    RecordStatus.CANDIDATE_READY,
                )
                record.copy(
                    status = RecordStatus.IGNORED,
                    errorCode = transition.errorCode,
                ).asTerminal(nowEpochMillis)
            }

            is RecordTransition.Failed -> {
                require(
                    record.status in setOf(
                        RecordStatus.QUEUED,
                        RecordStatus.RECOGNIZING,
                        RecordStatus.CANDIDATE_READY,
                        RecordStatus.ADDING,
                        RecordStatus.ADD_RECONCILE_REQUIRED,
                        RecordStatus.UNDO_PENDING,
                        RecordStatus.DELETE_RECONCILE_REQUIRED,
                    ),
                ) { "Cannot fail a record in ${record.status.storedValue}" }
                require(transition.errorCode.isNotBlank()) { "errorCode must not be blank" }
                record.copy(
                    status = RecordStatus.FAILED,
                    errorCode = transition.errorCode,
                ).asTerminal(nowEpochMillis)
            }

            RecordTransition.BeginUndo -> {
                requireStatus(record, RecordStatus.AUTO_ADDED)
                check(!record.operationToken.isNullOrBlank()) {
                    "An auto-added record must retain its operationToken"
                }
                checkNotNull(record.calendarId) { "An auto-added record must have calendarId" }
                checkNotNull(record.calendarEventId) { "An auto-added record must have calendarEventId" }
                record.copy(
                    status = RecordStatus.UNDO_PENDING,
                    terminalAt = null,
                    recordExpiresAt = null,
                    calendarAttempts = record.calendarAttempts + 1,
                    errorCode = null,
                )
            }

            is RecordTransition.DeleteReconcileRequired -> {
                requireStatus(record, RecordStatus.UNDO_PENDING)
                require(transition.errorCode.isNotBlank()) { "errorCode must not be blank" }
                record.copy(
                    status = RecordStatus.DELETE_RECONCILE_REQUIRED,
                    errorCode = transition.errorCode,
                )
            }

            RecordTransition.Undone -> {
                requireStatus(
                    record,
                    RecordStatus.UNDO_PENDING,
                    RecordStatus.DELETE_RECONCILE_REQUIRED,
                )
                record.copy(
                    status = RecordStatus.UNDONE,
                    errorCode = null,
                ).asTerminal(nowEpochMillis)
            }
        }

        return next.copy(phaseEnteredAt = nowEpochMillis)
    }

    private fun ScreenshotRecord.asTerminal(nowEpochMillis: Long): ScreenshotRecord = copy(
        terminalAt = nowEpochMillis,
        recordExpiresAt = nowEpochMillis + TERMINAL_RETENTION_MILLIS,
    )

    private fun requireStatus(record: ScreenshotRecord, vararg allowed: RecordStatus) {
        require(record.status in allowed) {
            "Cannot transition from ${record.status.storedValue}; expected " +
                allowed.joinToString { it.storedValue }
        }
    }
}
