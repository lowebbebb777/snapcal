package com.tazawa.snapcal.calendar

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.provider.CalendarContract
import java.time.Instant

class CalendarGateway internal constructor(
    private val store: CalendarStore,
) {
    constructor(contentResolver: ContentResolver) : this(AndroidCalendarStore(contentResolver))

    fun listWritableCalendars(): CalendarListResult = store.listWritableCalendars()

    fun addEvent(
        calendarId: Long,
        draft: CalendarEventDraft,
        operationToken: String,
    ): AddEventResult {
        CalendarEventFactory.validateOperationToken(operationToken)
        val reminderMethod = when (val capability = store.calendarCapability(calendarId)) {
            is CapabilityCheck.Available -> capability.reminderMethod
            CapabilityCheck.MissingOrReadOnly -> return AddEventResult.CalendarUnavailable
            CapabilityCheck.ReminderUnsupported -> return AddEventResult.ReminderUnsupported
            CapabilityCheck.PermissionDenied -> return AddEventResult.PermissionDenied
            CapabilityCheck.ProviderUnavailable -> return AddEventResult.ProviderUnavailable
        }
        val payload = CalendarEventFactory.createPayload(
            calendarId = calendarId,
            draft = draft,
            operationToken = operationToken,
            reminderMethod = reminderMethod,
        )
        val signature = CalendarEventFactory.signature(draft.title, draft.startInstant, calendarId)
        return when (val write = store.addEventAndReminder(payload)) {
            is BatchWrite.Added -> AddEventResult.Added(write.eventId, operationToken, signature)
            BatchWrite.OutcomeUnknown -> AddEventResult.ReconcileRequired(operationToken, signature)
            BatchWrite.PermissionDenied -> AddEventResult.PermissionDenied
        }
    }

    fun undoSavedEvent(eventId: Long, operationToken: String): UndoEventResult {
        CalendarEventFactory.validateOperationToken(operationToken)
        return when (val lookup = store.eventById(eventId)) {
            EventRead.Absent -> UndoEventResult.AlreadyAbsent
            EventRead.PermissionDenied -> UndoEventResult.PermissionDenied
            EventRead.ProviderUnavailable -> UndoEventResult.ProviderUnavailable
            is EventRead.Found -> {
                if (!CalendarEventFactory.hasOperationTokenAtEnd(
                        lookup.event.description,
                        operationToken,
                    )
                ) {
                    UndoEventResult.TokenMismatch
                } else {
                    store.deleteSavedEventByIdAndDescription(lookup.event).toUndoResult(eventId)
                }
            }
        }
    }

    fun reconcileAdd(
        operationToken: String,
        expectedSignature: String,
    ): AddReconcileResult {
        CalendarEventFactory.validateOperationToken(operationToken)
        return when (val search = store.eventsByOperationToken(operationToken)) {
            EventSearch.PermissionDenied -> AddReconcileResult.PermissionDenied
            EventSearch.ProviderUnavailable -> AddReconcileResult.ProviderUnavailable
            is EventSearch.Found -> when (
                val classification = CalendarEventFactory.classifyMatches(
                    search.events.map { it.fingerprint },
                    expectedSignature,
                )
            ) {
                MatchClassification.NoMatch -> AddReconcileResult.NoMatch
                is MatchClassification.ExactMatch ->
                    AddReconcileResult.Adopted(classification.eventId)
                MatchClassification.SignatureMismatch -> AddReconcileResult.SignatureMismatch
                is MatchClassification.Ambiguous ->
                    AddReconcileResult.Ambiguous(classification.matchCount)
            }
        }
    }

    fun reconcileDelete(
        savedEventId: Long,
        operationToken: String,
        expectedSignature: String,
    ): DeleteReconcileResult {
        CalendarEventFactory.validateOperationToken(operationToken)
        return when (val search = store.eventsByOperationToken(operationToken)) {
            EventSearch.PermissionDenied -> DeleteReconcileResult.PermissionDenied
            EventSearch.ProviderUnavailable -> DeleteReconcileResult.ProviderUnavailable
            is EventSearch.Found -> reconcileDeleteSearch(
                search.events,
                savedEventId,
                expectedSignature,
            )
        }
    }

    private fun reconcileDeleteSearch(
        events: List<StoredCalendarEvent>,
        savedEventId: Long,
        expectedSignature: String,
    ): DeleteReconcileResult = when (
        val classification = CalendarEventFactory.classifyMatches(
            events.map { it.fingerprint },
            expectedSignature,
        )
    ) {
        MatchClassification.NoMatch -> reconcileDeleteWithoutToken(
            savedEventId,
            expectedSignature,
        )
        MatchClassification.SignatureMismatch -> DeleteReconcileResult.SignatureMismatch
        is MatchClassification.Ambiguous ->
            DeleteReconcileResult.Ambiguous(classification.matchCount)
        is MatchClassification.ExactMatch -> {
            if (classification.eventId != savedEventId) {
                DeleteReconcileResult.EventIdMismatch
            } else {
                store.deleteReconciledEvent(events.single()).toDeleteReconcileResult(savedEventId)
            }
        }
    }

    private fun reconcileDeleteWithoutToken(
        savedEventId: Long,
        expectedSignature: String,
    ): DeleteReconcileResult = when (val savedEvent = store.eventById(savedEventId)) {
        EventRead.Absent -> DeleteReconcileResult.AlreadyAbsent
        EventRead.PermissionDenied -> DeleteReconcileResult.PermissionDenied
        EventRead.ProviderUnavailable -> DeleteReconcileResult.ProviderUnavailable
        is EventRead.Found -> {
            if (savedEvent.event.fingerprint.signature == expectedSignature) {
                // The event still exists but its SnapCal marker was removed. Never delete it.
                DeleteReconcileResult.TokenMismatch
            } else {
                DeleteReconcileResult.SignatureMismatch
            }
        }
    }

    private fun DeleteAttempt.toUndoResult(eventId: Long): UndoEventResult = when (this) {
        DeleteAttempt.Deleted -> UndoEventResult.Deleted(eventId)
        DeleteAttempt.NotConfirmed -> UndoEventResult.DeleteNotConfirmed
        DeleteAttempt.PermissionDenied -> UndoEventResult.PermissionDenied
        DeleteAttempt.ProviderUnavailable -> UndoEventResult.ProviderUnavailable
    }

    private fun DeleteAttempt.toDeleteReconcileResult(
        eventId: Long,
    ): DeleteReconcileResult = when (this) {
        DeleteAttempt.Deleted -> DeleteReconcileResult.Deleted(eventId)
        DeleteAttempt.NotConfirmed -> DeleteReconcileResult.DeleteNotConfirmed
        DeleteAttempt.PermissionDenied -> DeleteReconcileResult.PermissionDenied
        DeleteAttempt.ProviderUnavailable -> DeleteReconcileResult.ProviderUnavailable
    }
}

internal data class StoredCalendarEvent(
    val eventId: Long,
    val title: String,
    val startMillis: Long,
    val calendarId: Long,
    val description: String,
) {
    val fingerprint: EventFingerprint
        get() = EventFingerprint(
            eventId = eventId,
            signature = CalendarEventFactory.signature(
                title = title,
                startInstant = Instant.ofEpochMilli(startMillis),
                calendarId = calendarId,
            ),
        )
}

internal sealed interface CapabilityCheck {
    data class Available(val reminderMethod: Int) : CapabilityCheck
    data object MissingOrReadOnly : CapabilityCheck
    data object ReminderUnsupported : CapabilityCheck
    data object PermissionDenied : CapabilityCheck
    data object ProviderUnavailable : CapabilityCheck
}

internal sealed interface BatchWrite {
    data class Added(val eventId: Long) : BatchWrite
    data object OutcomeUnknown : BatchWrite
    data object PermissionDenied : BatchWrite
}

internal sealed interface EventSearch {
    data class Found(val events: List<StoredCalendarEvent>) : EventSearch
    data object PermissionDenied : EventSearch
    data object ProviderUnavailable : EventSearch
}

internal sealed interface EventRead {
    data class Found(val event: StoredCalendarEvent) : EventRead
    data object Absent : EventRead
    data object PermissionDenied : EventRead
    data object ProviderUnavailable : EventRead
}

internal sealed interface DeleteAttempt {
    data object Deleted : DeleteAttempt
    data object NotConfirmed : DeleteAttempt
    data object PermissionDenied : DeleteAttempt
    data object ProviderUnavailable : DeleteAttempt
}

internal interface CalendarStore {
    fun listWritableCalendars(): CalendarListResult
    fun calendarCapability(calendarId: Long): CapabilityCheck
    fun addEventAndReminder(payload: CalendarEventPayload): BatchWrite
    fun eventsByOperationToken(operationToken: String): EventSearch
    fun eventById(eventId: Long): EventRead
    fun deleteSavedEventByIdAndDescription(event: StoredCalendarEvent): DeleteAttempt
    fun deleteReconciledEvent(event: StoredCalendarEvent): DeleteAttempt
}

private class AndroidCalendarStore(
    private val contentResolver: ContentResolver,
) : CalendarStore {
    override fun listWritableCalendars(): CalendarListResult = try {
        val calendars = buildList {
            val cursor = contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                CALENDAR_LIST_PROJECTION,
                "${CalendarContract.Calendars.VISIBLE}=? AND " +
                    "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
                arrayOf(
                    "1",
                    CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString(),
                ),
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME + " COLLATE NOCASE ASC",
            ) ?: return CalendarListResult.ProviderUnavailable

            cursor.use {
                while (it.moveToNext()) {
                    val maxReminders = it.int(CalendarContract.Calendars.MAX_REMINDERS)
                    val reminderMethod = CalendarEventFactory.selectReminderMethod(
                        maxReminders,
                        CalendarEventFactory.parseAllowedReminderMethods(
                            it.string(CalendarContract.Calendars.ALLOWED_REMINDERS),
                        ),
                    )
                    if (reminderMethod != null) {
                        add(
                            WritableCalendar(
                                id = it.long(CalendarContract.Calendars._ID),
                                displayName =
                                    it.string(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
                                accountName = it.string(CalendarContract.Calendars.ACCOUNT_NAME),
                                color = it.int(CalendarContract.Calendars.CALENDAR_COLOR),
                                maxReminders = maxReminders,
                                reminderMethod = reminderMethod,
                            ),
                        )
                    }
                }
            }
        }
        CalendarListResult.Available(calendars)
    } catch (_: SecurityException) {
        CalendarListResult.PermissionDenied
    } catch (_: Exception) {
        CalendarListResult.ProviderUnavailable
    }

    override fun calendarCapability(calendarId: Long): CapabilityCheck = try {
        val uri = ContentUris.withAppendedId(CalendarContract.Calendars.CONTENT_URI, calendarId)
        val cursor = contentResolver.query(uri, CALENDAR_CAPABILITY_PROJECTION, null, null, null)
            ?: return CapabilityCheck.ProviderUnavailable
        cursor.use {
            if (!it.moveToFirst()) return CapabilityCheck.MissingOrReadOnly
            if (it.int(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL) <
                CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR
            ) {
                return CapabilityCheck.MissingOrReadOnly
            }
            val method = CalendarEventFactory.selectReminderMethod(
                it.int(CalendarContract.Calendars.MAX_REMINDERS),
                CalendarEventFactory.parseAllowedReminderMethods(
                    it.string(CalendarContract.Calendars.ALLOWED_REMINDERS),
                ),
            ) ?: return CapabilityCheck.ReminderUnsupported
            CapabilityCheck.Available(method)
        }
    } catch (_: SecurityException) {
        CapabilityCheck.PermissionDenied
    } catch (_: Exception) {
        CapabilityCheck.ProviderUnavailable
    }

    override fun addEventAndReminder(payload: CalendarEventPayload): BatchWrite {
        val operations = arrayListOf(
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValues(payload.toEventContentValues())
                .build(),
            ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                .withValueBackReference(CalendarContract.Reminders.EVENT_ID, 0)
                .withValue(CalendarContract.Reminders.MINUTES, payload.reminderMinutes)
                .withValue(CalendarContract.Reminders.METHOD, payload.reminderMethod)
                .build(),
        )
        return try {
            val results = contentResolver.applyBatch(CalendarContract.AUTHORITY, operations)
            val eventId = results.firstOrNull()?.uri?.lastPathSegment?.toLongOrNull()
                ?: return BatchWrite.OutcomeUnknown
            BatchWrite.Added(eventId)
        } catch (_: SecurityException) {
            BatchWrite.PermissionDenied
        } catch (_: Exception) {
            // A remote failure is not proof that the atomic batch rolled back.
            BatchWrite.OutcomeUnknown
        }
    }

    override fun eventsByOperationToken(operationToken: String): EventSearch = try {
        val marker = CalendarEventFactory.tokenMarker(operationToken)
        val now = System.currentTimeMillis()
        val cursor = contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            EVENT_PROJECTION,
            "${CalendarContract.Events.DESCRIPTION} LIKE ? AND " +
                "${CalendarContract.Events.DTSTART}>=? AND " +
                "${CalendarContract.Events.DTSTART}<=?",
            arrayOf(
                "%$marker",
                (now - RECONCILE_LOOKBACK_MILLIS).toString(),
                (now + RECONCILE_LOOKAHEAD_MILLIS).toString(),
            ),
            null,
        ) ?: return EventSearch.ProviderUnavailable
        val events = buildList {
            cursor.use {
                while (it.moveToNext()) {
                    val event = it.toStoredCalendarEvent()
                    if (CalendarEventFactory.hasOperationTokenAtEnd(
                            event.description,
                            operationToken,
                        )
                    ) {
                        add(event)
                    }
                }
            }
        }
        EventSearch.Found(events)
    } catch (_: SecurityException) {
        EventSearch.PermissionDenied
    } catch (_: Exception) {
        EventSearch.ProviderUnavailable
    }

    override fun eventById(eventId: Long): EventRead = try {
        val uri = ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId)
        val cursor = contentResolver.query(uri, EVENT_PROJECTION, null, null, null)
            ?: return EventRead.ProviderUnavailable
        cursor.use {
            if (!it.moveToFirst()) EventRead.Absent else EventRead.Found(it.toStoredCalendarEvent())
        }
    } catch (_: SecurityException) {
        EventRead.PermissionDenied
    } catch (_: Exception) {
        EventRead.ProviderUnavailable
    }

    override fun deleteSavedEventByIdAndDescription(
        event: StoredCalendarEvent,
    ): DeleteAttempt = deleteAndVerify(
        event = event,
        selection = "${CalendarContract.Events.DESCRIPTION}=?",
        arguments = arrayOf(event.description),
    )

    override fun deleteReconciledEvent(event: StoredCalendarEvent): DeleteAttempt = deleteAndVerify(
        event = event,
        selection =
            "${CalendarContract.Events.DESCRIPTION}=? AND " +
                "${CalendarContract.Events.TITLE}=? AND " +
                "${CalendarContract.Events.DTSTART}=? AND " +
                "${CalendarContract.Events.CALENDAR_ID}=?",
        arguments = arrayOf(
            event.description,
            event.title,
            event.startMillis.toString(),
            event.calendarId.toString(),
        ),
    )

    private fun deleteAndVerify(
        event: StoredCalendarEvent,
        selection: String,
        arguments: Array<String>,
    ): DeleteAttempt = try {
        // CalendarProvider rejects a selection when the URI already identifies one event.
        // Keep the ownership checks atomic by deleting from the collection URI with both
        // the saved event id and the exact marker/fingerprint in the WHERE clause.
        val ownedSelection =
            "${CalendarContract.Events._ID}=? AND ($selection)"
        val ownedArguments = arrayOf(event.eventId.toString(), *arguments)
        if (
            contentResolver.delete(
                CalendarContract.Events.CONTENT_URI,
                ownedSelection,
                ownedArguments,
            ) != 1
        ) {
            return DeleteAttempt.NotConfirmed
        }
        when (eventById(event.eventId)) {
            EventRead.Absent -> DeleteAttempt.Deleted
            EventRead.PermissionDenied -> DeleteAttempt.PermissionDenied
            EventRead.ProviderUnavailable -> DeleteAttempt.ProviderUnavailable
            is EventRead.Found -> DeleteAttempt.NotConfirmed
        }
    } catch (_: SecurityException) {
        DeleteAttempt.PermissionDenied
    } catch (_: Exception) {
        DeleteAttempt.ProviderUnavailable
    }

    private fun android.database.Cursor.toStoredCalendarEvent() = StoredCalendarEvent(
        eventId = long(CalendarContract.Events._ID),
        title = string(CalendarContract.Events.TITLE),
        startMillis = long(CalendarContract.Events.DTSTART),
        calendarId = long(CalendarContract.Events.CALENDAR_ID),
        description = string(CalendarContract.Events.DESCRIPTION),
    )

    private fun android.database.Cursor.string(column: String): String =
        getString(getColumnIndexOrThrow(column)).orEmpty()

    private fun android.database.Cursor.long(column: String): Long =
        getLong(getColumnIndexOrThrow(column))

    private fun android.database.Cursor.int(column: String): Int =
        getInt(getColumnIndexOrThrow(column))

    private fun CalendarEventPayload.toEventContentValues() = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calendarId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, startMillis)
        put(CalendarContract.Events.DTEND, endMillis)
        put(CalendarContract.Events.EVENT_TIMEZONE, timeZoneId)
        put(CalendarContract.Events.DESCRIPTION, description)
        location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
    }

    private companion object {
        const val RECONCILE_LOOKBACK_MILLIS = 24L * 60L * 60L * 1_000L
        const val RECONCILE_LOOKAHEAD_MILLIS = 400L * 24L * 60L * 60L * 1_000L
        val CALENDAR_LIST_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.MAX_REMINDERS,
            CalendarContract.Calendars.ALLOWED_REMINDERS,
        )
        val CALENDAR_CAPABILITY_PROJECTION = arrayOf(
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.MAX_REMINDERS,
            CalendarContract.Calendars.ALLOWED_REMINDERS,
        )
        val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.DESCRIPTION,
        )
    }
}
