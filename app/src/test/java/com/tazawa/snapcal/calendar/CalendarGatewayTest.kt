package com.tazawa.snapcal.calendar

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarGatewayTest {
    private val token = "018f7a64-2ed1-7f52-9f22-a28913c68000"
    private val start = Instant.parse("2026-07-17T03:00:00Z")
    private val signature = CalendarEventFactory.signature("Team sync", start, 42L)

    @Test
    fun `add rechecks capability and returns provider event id`() {
        val store = FakeCalendarStore()
        val gateway = CalendarGateway(store)

        val result = gateway.addEvent(42L, draft(), token)

        assertEquals(AddEventResult.Added(91L, token, signature), result)
        assertEquals(42L, store.capabilityCalendarId)
        assertEquals(42L, store.addedPayload?.calendarId)
        assertEquals(30, store.addedPayload?.reminderMinutes)
        assertEquals(
            "memo\n\nSnapCal-ID: $token",
            store.addedPayload?.description,
        )
    }

    @Test
    fun `unknown batch result requires reconcile and is never reported as added`() {
        val store = FakeCalendarStore(batchWrite = BatchWrite.OutcomeUnknown)

        assertEquals(
            AddEventResult.ReconcileRequired(token, signature),
            CalendarGateway(store).addEvent(42L, draft(), token),
        )
    }

    @Test
    fun `add reconcile handles zero exact mismatch and multiple separately`() {
        val store = FakeCalendarStore(eventSearch = EventSearch.Found(emptyList()))
        val gateway = CalendarGateway(store)
        assertEquals(AddReconcileResult.NoMatch, gateway.reconcileAdd(token, signature))

        store.eventSearch = EventSearch.Found(listOf(event(7L)))
        assertEquals(AddReconcileResult.Adopted(7L), gateway.reconcileAdd(token, signature))

        store.eventSearch = EventSearch.Found(listOf(event(7L, title = "Changed")))
        assertEquals(
            AddReconcileResult.SignatureMismatch,
            gateway.reconcileAdd(token, signature),
        )

        store.eventSearch = EventSearch.Found(listOf(event(7L), event(8L)))
        assertEquals(AddReconcileResult.Ambiguous(2), gateway.reconcileAdd(token, signature))
    }

    @Test
    fun `delete reconcile with no token only succeeds when saved id is absent`() {
        val store = FakeCalendarStore(
            eventSearch = EventSearch.Found(emptyList()),
            eventRead = EventRead.Absent,
        )
        val gateway = CalendarGateway(store)

        assertEquals(
            DeleteReconcileResult.AlreadyAbsent,
            gateway.reconcileDelete(7L, token, signature),
        )
        assertEquals(7L, store.readEventId)
    }

    @Test
    fun `delete reconcile with no token fails closed when saved event still exists`() {
        val store = FakeCalendarStore(
            eventSearch = EventSearch.Found(emptyList()),
            eventRead = EventRead.Found(event(7L, description = "marker removed")),
        )

        assertEquals(
            DeleteReconcileResult.TokenMismatch,
            CalendarGateway(store).reconcileDelete(7L, token, signature),
        )
        assertEquals(0, store.reconciledDeleteCalls)
    }

    @Test
    fun `delete reconcile with no token reports signature mismatch after edit`() {
        val store = FakeCalendarStore(
            eventSearch = EventSearch.Found(emptyList()),
            eventRead = EventRead.Found(event(7L, title = "Changed", description = "marker removed")),
        )

        assertEquals(
            DeleteReconcileResult.SignatureMismatch,
            CalendarGateway(store).reconcileDelete(7L, token, signature),
        )
        assertEquals(0, store.reconciledDeleteCalls)
    }

    @Test
    fun `delete reconcile deletes only exact token signature and saved event id`() {
        val store = FakeCalendarStore(eventSearch = EventSearch.Found(listOf(event(7L))))

        assertEquals(
            DeleteReconcileResult.Deleted(7L),
            CalendarGateway(store).reconcileDelete(7L, token, signature),
        )
        assertEquals(1, store.reconciledDeleteCalls)
    }

    @Test
    fun `delete reconcile refuses a matching token on a different event id`() {
        val store = FakeCalendarStore(eventSearch = EventSearch.Found(listOf(event(8L))))

        assertEquals(
            DeleteReconcileResult.EventIdMismatch,
            CalendarGateway(store).reconcileDelete(7L, token, signature),
        )
        assertEquals(0, store.reconciledDeleteCalls)
    }

    @Test
    fun `delete reconcile refuses multiple token matches without deletion`() {
        val store = FakeCalendarStore(
            eventSearch = EventSearch.Found(listOf(event(7L), event(8L))),
        )

        assertEquals(
            DeleteReconcileResult.Ambiguous(2),
            CalendarGateway(store).reconcileDelete(7L, token, signature),
        )
        assertEquals(0, store.reconciledDeleteCalls)
    }

    @Test
    fun `delete reconcile does not claim success when provider cannot confirm deletion`() {
        val store = FakeCalendarStore(
            eventSearch = EventSearch.Found(listOf(event(7L))),
            reconciledDelete = DeleteAttempt.NotConfirmed,
        )

        assertEquals(
            DeleteReconcileResult.DeleteNotConfirmed,
            CalendarGateway(store).reconcileDelete(7L, token, signature),
        )
    }

    @Test
    fun `direct undo requires both saved id and exact token`() {
        val store = FakeCalendarStore(eventRead = EventRead.Found(event(7L)))
        val gateway = CalendarGateway(store)

        assertEquals(UndoEventResult.Deleted(7L), gateway.undoSavedEvent(7L, token))
        assertEquals(1, store.savedDeleteCalls)

        store.eventRead = EventRead.Found(event(7L, description = "other description"))
        assertEquals(UndoEventResult.TokenMismatch, gateway.undoSavedEvent(7L, token))
        assertEquals(1, store.savedDeleteCalls)
    }

    @Test
    fun `provider read failures never become reconcile success`() {
        val addStore = FakeCalendarStore(eventSearch = EventSearch.ProviderUnavailable)
        assertEquals(
            AddReconcileResult.ProviderUnavailable,
            CalendarGateway(addStore).reconcileAdd(token, signature),
        )

        val deleteStore = FakeCalendarStore(
            eventSearch = EventSearch.Found(emptyList()),
            eventRead = EventRead.ProviderUnavailable,
        )
        assertEquals(
            DeleteReconcileResult.ProviderUnavailable,
            CalendarGateway(deleteStore).reconcileDelete(7L, token, signature),
        )
    }

    @Test
    fun `read only or reminder incapable calendars stop before batch write`() {
        val readOnly = FakeCalendarStore(capability = CapabilityCheck.MissingOrReadOnly)
        assertEquals(
            AddEventResult.CalendarUnavailable,
            CalendarGateway(readOnly).addEvent(42L, draft(), token),
        )
        assertTrue(readOnly.addedPayload == null)

        val noReminder = FakeCalendarStore(capability = CapabilityCheck.ReminderUnsupported)
        assertEquals(
            AddEventResult.ReminderUnsupported,
            CalendarGateway(noReminder).addEvent(42L, draft(), token),
        )
        assertTrue(noReminder.addedPayload == null)
    }

    private fun draft() = CalendarEventDraft(
        title = "Team sync",
        startInstant = start,
        endInstant = start.plusSeconds(3600),
        timeZoneId = "Asia/Tokyo",
        description = "memo",
    )

    private fun event(
        id: Long,
        title: String = "Team sync",
        description: String = "memo\n\nSnapCal-ID: $token",
    ) = StoredCalendarEvent(
        eventId = id,
        title = title,
        startMillis = start.toEpochMilli(),
        calendarId = 42L,
        description = description,
    )

    private class FakeCalendarStore(
        var capability: CapabilityCheck =
            CapabilityCheck.Available(CalendarEventFactory.REMINDER_METHOD_ALERT),
        var batchWrite: BatchWrite = BatchWrite.Added(91L),
        var eventSearch: EventSearch = EventSearch.Found(emptyList()),
        var eventRead: EventRead = EventRead.Absent,
        var savedDelete: DeleteAttempt = DeleteAttempt.Deleted,
        var reconciledDelete: DeleteAttempt = DeleteAttempt.Deleted,
    ) : CalendarStore {
        var capabilityCalendarId: Long? = null
        var addedPayload: CalendarEventPayload? = null
        var readEventId: Long? = null
        var savedDeleteCalls = 0
        var reconciledDeleteCalls = 0

        override fun listWritableCalendars(): CalendarListResult =
            CalendarListResult.Available(emptyList())

        override fun calendarCapability(calendarId: Long): CapabilityCheck {
            capabilityCalendarId = calendarId
            return capability
        }

        override fun addEventAndReminder(payload: CalendarEventPayload): BatchWrite {
            addedPayload = payload
            return batchWrite
        }

        override fun eventsByOperationToken(operationToken: String): EventSearch = eventSearch

        override fun eventById(eventId: Long): EventRead {
            readEventId = eventId
            return eventRead
        }

        override fun deleteSavedEventByIdAndDescription(
            event: StoredCalendarEvent,
        ): DeleteAttempt {
            savedDeleteCalls += 1
            return savedDelete
        }

        override fun deleteReconciledEvent(event: StoredCalendarEvent): DeleteAttempt {
            reconciledDeleteCalls += 1
            return reconciledDelete
        }
    }
}
