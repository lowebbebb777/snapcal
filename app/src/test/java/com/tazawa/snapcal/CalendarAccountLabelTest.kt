package com.tazawa.snapcal

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarAccountLabelTest {
    @Test
    fun masksEmailAddressWhileKeepingItDistinguishable() {
        assertEquals("h•••o@example.com", calendarAccountLabel("hiro@example.com"))
    }

    @Test
    fun masksSingleCharacterEmailLocalPart() {
        assertEquals("h•••@example.com", calendarAccountLabel("h@example.com"))
    }

    @Test
    fun hidesBlankAccountName() {
        assertEquals("", calendarAccountLabel("  \n  "))
    }

    @Test
    fun normalizesWhitespaceForNonEmailAccount() {
        assertEquals("Snap Cal", calendarAccountLabel(" Snap\n Cal "))
    }
}
