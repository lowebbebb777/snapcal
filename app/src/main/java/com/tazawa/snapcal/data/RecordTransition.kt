package com.tazawa.snapcal.data

sealed interface RecordTransition {
    data object StartRecognizing : RecordTransition

    data class CandidateReady(val eventSignature: String) : RecordTransition

    data class BeginAdding(
        val operationToken: String,
        val calendarId: Long,
    ) : RecordTransition

    data class AddReconcileRequired(val errorCode: String) : RecordTransition

    data class AutoAdded(
        val calendarId: Long,
        val calendarEventId: Long,
    ) : RecordTransition

    data class NeedsReview(val errorCode: String? = null) : RecordTransition

    data class Ignored(val errorCode: String? = null) : RecordTransition

    data class Failed(val errorCode: String) : RecordTransition

    data object BeginUndo : RecordTransition

    data class DeleteReconcileRequired(val errorCode: String) : RecordTransition

    data object Undone : RecordTransition
}
