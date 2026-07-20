package com.tazawa.snapcal.calendar

import java.time.Instant

data class WritableCalendar(
    val id: Long,
    val displayName: String,
    val accountName: String,
    val color: Int,
    val maxReminders: Int,
    val reminderMethod: Int,
)

data class CalendarEventDraft(
    val title: String,
    val startInstant: Instant,
    val endInstant: Instant,
    val timeZoneId: String,
    val description: String? = null,
    val location: String? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
        require(endInstant.isAfter(startInstant)) { "endInstant must be after startInstant" }
        require(timeZoneId.isNotBlank()) { "timeZoneId must not be blank" }
    }
}

data class CalendarEventPayload(
    val calendarId: Long,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val timeZoneId: String,
    val description: String,
    val location: String?,
    val reminderMinutes: Int,
    val reminderMethod: Int,
)

data class EventFingerprint(
    val eventId: Long,
    val signature: String,
)

sealed interface CalendarListResult {
    data class Available(val calendars: List<WritableCalendar>) : CalendarListResult
    data object PermissionDenied : CalendarListResult
    data object ProviderUnavailable : CalendarListResult
}

sealed interface AddEventResult {
    data class Added(
        val eventId: Long,
        val operationToken: String,
        val signature: String,
    ) : AddEventResult

    /** The provider may have committed the batch; call reconcileAdd before retrying. */
    data class ReconcileRequired(
        val operationToken: String,
        val signature: String,
    ) : AddEventResult

    data object PermissionDenied : AddEventResult
    data object CalendarUnavailable : AddEventResult
    data object ReminderUnsupported : AddEventResult
    data object ProviderUnavailable : AddEventResult
}

sealed interface UndoEventResult {
    data class Deleted(val eventId: Long) : UndoEventResult
    data object AlreadyAbsent : UndoEventResult
    data object TokenMismatch : UndoEventResult
    data object DeleteNotConfirmed : UndoEventResult
    data object PermissionDenied : UndoEventResult
    data object ProviderUnavailable : UndoEventResult
}

sealed interface AddReconcileResult {
    data object NoMatch : AddReconcileResult
    data class Adopted(val eventId: Long) : AddReconcileResult
    data object SignatureMismatch : AddReconcileResult
    data class Ambiguous(val matchCount: Int) : AddReconcileResult
    data object PermissionDenied : AddReconcileResult
    data object ProviderUnavailable : AddReconcileResult
}

sealed interface DeleteReconcileResult {
    data object AlreadyAbsent : DeleteReconcileResult
    data class Deleted(val eventId: Long) : DeleteReconcileResult
    data object SignatureMismatch : DeleteReconcileResult
    data object TokenMismatch : DeleteReconcileResult
    data object EventIdMismatch : DeleteReconcileResult
    data class Ambiguous(val matchCount: Int) : DeleteReconcileResult
    data object DeleteNotConfirmed : DeleteReconcileResult
    data object PermissionDenied : DeleteReconcileResult
    data object ProviderUnavailable : DeleteReconcileResult
}

sealed interface MatchClassification {
    data object NoMatch : MatchClassification
    data class ExactMatch(val eventId: Long) : MatchClassification
    data object SignatureMismatch : MatchClassification
    data class Ambiguous(val matchCount: Int) : MatchClassification
}
