package com.tazawa.snapcal.processing

import com.tazawa.snapcal.domain.NormalizedEvent
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class ReviewSessionStoreTest {
    private fun event(title: String) = NormalizedEvent(
        title = title,
        start = LocalDateTime.of(2026, 11, 2, 16, 50),
        end = LocalDateTime.of(2026, 11, 2, 17, 50),
    )

    @Test fun `selected option is consumed exactly once`() {
        val id = checkNotNull(ReviewSessionStore.put(listOf(event("第一部"), event("第二部")), now = 1000L))
        assertEquals("第二部", ReviewSessionStore.consume(id, 1, now = 1000L)?.title)
        assertNull(ReviewSessionStore.consume(id, 0, now = 1000L))
        assertNull(ReviewSessionStore.get(id, now = 1000L))
    }

    @Test fun `expired session cannot register`() {
        val id = checkNotNull(ReviewSessionStore.put(listOf(event("期限切れ")), now = 100L))
        assertNull(ReviewSessionStore.consume(id, 0, now = 600_101L))
    }

    @Test fun `invalid selection never consumes a valid session`() {
        val id = checkNotNull(ReviewSessionStore.put(listOf(event("正しい候補")), now = 200L))
        assertNull(ReviewSessionStore.consume(id, 40, now = 200L))
        assertNotNull(ReviewSessionStore.consume(id, 0, now = 200L))
    }

    @Test fun `an empty option list cannot create a session`() {
        assertNull(ReviewSessionStore.put(emptyList(), now = 10L))
    }

    @Test fun `sessions never store more than four choices`() {
        val id = checkNotNull(ReviewSessionStore.put(List(8) { event("候補$it") }, now = 300L))
        assertEquals(4, ReviewSessionStore.get(id, now = 300L)?.size)
        ReviewSessionStore.discard(id)
    }
}
