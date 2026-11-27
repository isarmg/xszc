package org.sarmg.ana

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDiscoveryTest {
    @Test fun memoriesFilterUsesTheSameUtcMonthAcrossPreviousYears() {
        val day = LocalDate.of(2026, 11, 27)
        val base = CloudFilters(kind = "video")
        assertEquals(mapOf("media_kind" to "video", "capture_month" to "11", "capture_before_ms" to "1767225600000"),
            PhotoDiscovery.MEMORIES.filters(base, day).params())
        assertEquals(mapOf("media_kind" to "video", "has_location" to "true"),
            PhotoDiscovery.PLACES.filters(base, day).params())
        assertEquals(base, PhotoDiscovery.TIMELINE.filters(base, day))
    }

    @Test fun captureLocationAcceptsSignedVideoCoordinatesAndRejectsInvalidValues() {
        val location = CaptureLocation.fromVideoMetadata("+34.0522-118.2437+78.0/")
        assertEquals("34.0522, -118.2437", location?.label(4))
        assertNull(CaptureLocation.fromVideoMetadata("+91.2345+150.0000/"))
        assertNull(CaptureLocation.fromVideoMetadata("+10.0000+181.0000/"))
        assertNull(CaptureLocation.of(Double.NaN, 0.0))
        assertNull(CaptureLocation.of(0.0, null))
    }

    @Test fun resourceVersionDistinguishesSameSizeEditsAndValidatesMediaResponse() {
        val original = RemoteResource("resource", "primary", "photo.jpg", "image/jpeg", "/content", 42, null)
        val before = original.copy(contentBlake3 = "a".repeat(64))
        val after = before.copy(contentBlake3 = "b".repeat(64))
        assertNotEquals(before.cacheIdentity, after.cacheIdentity)
        assertTrue(after.acceptsETag("\"${after.contentBlake3}\"", preview = false))
        assertTrue(after.acceptsETag("\"account:${after.contentBlake3}:preview1600-v1\"", preview = true))
        assertFalse(after.acceptsETag("\"account:${before.contentBlake3}:preview1600-v1\"", preview = true))
        assertFalse(after.acceptsETag(null, preview = false))
        assertTrue(original.acceptsETag(null, preview = false))
    }
}
