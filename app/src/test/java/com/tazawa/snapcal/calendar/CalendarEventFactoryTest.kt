package com.tazawa.snapcal.calendar

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEventFactoryTest {
    private val token = "018f7a64-2ed1-7f52-9f22-a28913c68000"

    @Test
    fun `payload contains calendar values and 30 minute reminder`() {
        val draft = CalendarEventDraft(
            title = "Team sync",
            startInstant = Instant.parse("2026-07-17T03:00:00Z"),
            endInstant = Instant.parse("2026-07-17T04:00:00Z"),
            timeZoneId = "Asia/Tokyo",
            description = "Bring notes  ",
            location = "Meeting room",
        )

        val payload = CalendarEventFactory.createPayload(42L, draft, token)

        assertEquals(42L, payload.calendarId)
        assertEquals("Team sync", payload.title)
        assertEquals(1784257200000L, payload.startMillis)
        assertEquals(1784260800000L, payload.endMillis)
        assertEquals("Asia/Tokyo", payload.timeZoneId)
        assertEquals("Meeting room", payload.location)
        assertEquals(30, payload.reminderMinutes)
        assertEquals(CalendarEventFactory.REMINDER_METHOD_ALERT, payload.reminderMethod)
        assertEquals("Bring notes\n\nSnapCal-ID: $token", payload.description)
    }

    @Test
    fun `token marker is the complete description when original is empty`() {
        assertEquals(
            "SnapCal-ID: $token",
            CalendarEventFactory.appendOperationToken(null, token),
        )
    }

    @Test
    fun `token check requires exact marker at description end`() {
        val description = "memo\n\nSnapCal-ID: $token"

        assertTrue(CalendarEventFactory.hasOperationTokenAtEnd(description, token))
        assertEquals(false, CalendarEventFactory.hasOperationTokenAtEnd("$description\n", token))
        assertEquals(
            false,
            CalendarEventFactory.hasOperationTokenAtEnd(
                "memo\n\nSnapCal-ID: 018f7a64-2ed1-7f52-9f22-a28913c68001",
                token,
            ),
        )
    }

    @Test
    fun `operation token rejects SQL wildcard and newline characters`() {
        assertThrows(IllegalArgumentException::class.java) {
            CalendarEventFactory.validateOperationToken("018f7a64_2ed1_7f52")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CalendarEventFactory.validateOperationToken("018f7a64-2ed1\n7f52")
        }
    }

    @Test
    fun `reminder method prefers alert and safely falls back to default`() {
        assertEquals(
            CalendarEventFactory.REMINDER_METHOD_ALERT,
            CalendarEventFactory.selectReminderMethod(2, setOf(0, 1, 2)),
        )
        assertEquals(
            CalendarEventFactory.REMINDER_METHOD_DEFAULT,
            CalendarEventFactory.selectReminderMethod(1, setOf(0, 2)),
        )
        assertEquals(null, CalendarEventFactory.selectReminderMethod(0, setOf(0, 1)))
        assertEquals(null, CalendarEventFactory.selectReminderMethod(1, setOf(2)))
    }

    @Test
    fun `allowed reminder methods parser ignores malformed provider values`() {
        assertEquals(
            setOf(0, 1, 2),
            CalendarEventFactory.parseAllowedReminderMethods("0, 1,bad,2"),
        )
        assertTrue(CalendarEventFactory.parseAllowedReminderMethods(null).isEmpty())
    }

    @Test
    fun `signature is SHA-256 of title instant and calendar id`() {
        val signature = CalendarEventFactory.signature(
            title = "Team sync",
            startInstant = Instant.parse("2026-07-17T03:00:00Z"),
            calendarId = 42L,
        )

        assertEquals(
            "899411e10fa0cbcd5c58f27932fa19a647a4b62b938a5248cf1e39516b553357",
            signature,
        )
    }

    @Test
    fun `signature changes when any signed field changes`() {
        val start = Instant.parse("2026-07-17T03:00:00Z")
        val base = CalendarEventFactory.signature("Team sync", start, 42L)

        assertNotEquals(base, CalendarEventFactory.signature("Other", start, 42L))
        assertNotEquals(base, CalendarEventFactory.signature("Team sync", start.plusSeconds(1), 42L))
        assertNotEquals(base, CalendarEventFactory.signature("Team sync", start, 43L))
    }

    @Test
    fun `draft rejects zero length event`() {
        val instant = Instant.parse("2026-07-17T03:00:00Z")

        assertThrows(IllegalArgumentException::class.java) {
            CalendarEventDraft(
                title = "Event",
                startInstant = instant,
                endInstant = instant,
                timeZoneId = "Asia/Tokyo",
            )
        }
    }
}
