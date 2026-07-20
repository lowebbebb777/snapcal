package com.tazawa.snapcal.notification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ResultNotificationIdentityTest {
    @Test
    fun `each added event has an independent notification and pending intent identity`() {
        val first = "a".repeat(64)
        val second = "b".repeat(64)

        assertNotEquals(ResultNotificationIdentity.tag(first), ResultNotificationIdentity.tag(second))
        assertNotEquals(
            ResultNotificationIdentity.undoData(first),
            ResultNotificationIdentity.undoData(second),
        )
        assertEquals("snapcal-result-$first", ResultNotificationIdentity.tag(first))
        assertEquals("snapcal://undo/$first", ResultNotificationIdentity.undoData(first))
    }
}
