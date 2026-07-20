package com.tazawa.snapcal.ocr

import com.google.android.datatransport.Encoding
import com.google.android.datatransport.cct.CCTDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DisabledTelemetryDestinationTest {
    @Test
    fun `ML Kit compatibility destination contains no network destination`() {
        val destination = CCTDestination.INSTANCE

        assertEquals("snapcal-disabled-telemetry", destination.name)
        assertNull(destination.extras)
        assertNull(destination.endPoint)
        assertNull(destination.apiKey)
        assertNull(destination.asByteArray())
        assertEquals(
            setOf(Encoding.of("proto"), Encoding.of("json")),
            destination.supportedEncodings,
        )
    }

    @Test
    fun `public construction and decoding cannot restore an endpoint`() {
        val constructed = CCTDestination("https://example.invalid", "secret")
        val decoded = CCTDestination.fromByteArray(
            ("1" + '$' + "https://example.invalid" + '\\' + "secret").toByteArray(),
        )

        assertNull(constructed.endPoint)
        assertNull(constructed.apiKey)
        assertNull(constructed.extras)
        assertSame(CCTDestination.INSTANCE, decoded)
        assertSame(CCTDestination.INSTANCE, CCTDestination.LEGACY_INSTANCE)
    }
}
