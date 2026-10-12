package org.sarmg.xszc

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UploadHTTPFailureInstrumentedTest {
    @Test fun envelopeAndRetryAfterOverrideStatusFallback() {
        val busy = UploadHttpFailure.parse(503, "{\"code\":\"storage_unavailable\",\"retryable\":false}", "120", 0)
        assertEquals("storage_unavailable", busy.code)
        assertFalse(uploadFailurePolicy(busy).retryable)
        assertEquals(120_000L, busy.retryAfterMs)
        val limited = UploadHttpFailure.parse(429, "{\"code\":\"upload_busy\",\"retryable\":true}", "Thu, 01 Jan 1970 00:02:00 GMT", 0)
        assertTrue(limited.retryable); assertEquals(120_000L, limited.retryAfterMs)
        for (status in listOf(400,401,403,404,409,413,422)) assertFalse(UploadHttpFailure.parse(status,"",null).retryable)
        for (status in listOf(408,425,429,500,503)) assertTrue(UploadHttpFailure.parse(status,"",null).retryable)
        assertEquals(Long.MAX_VALUE, UploadHttpFailure.parse(429,"",Long.MAX_VALUE.toString()).retryAfterMs)
    }
}
