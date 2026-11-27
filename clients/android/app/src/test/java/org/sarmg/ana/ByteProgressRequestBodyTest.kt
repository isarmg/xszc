package org.sarmg.ana

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ByteProgressRequestBodyTest {
    private fun body(chunks: Int = 128) = object : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength() = chunks * 8192L
        override fun isOneShot() = true
        override fun writeTo(sink: BufferedSink) { repeat(chunks) { sink.write(ByteArray(8192) { it.toByte() }) } }
    }
    @Test fun reportsDuringStreamingAndRestartsForRetryWithoutChangingPayload() {
        val delegate = body()
        val sink = Buffer()
        val reports = mutableListOf<Long>()
        var clock = 0L
        val observed = ByteProgressRequestBody(delegate, { bytes ->
            assertEquals(bytes, sink.size)
            reports.add(bytes)
        }, { clock += 100_000_000; clock })
        assertEquals(delegate.contentType(), observed.contentType())
        assertEquals(delegate.contentLength(), observed.contentLength())
        assertTrue(observed.isOneShot())
        observed.writeTo(sink)
        assertTrue(reports.size > 2)
        assertTrue(reports.any { it > 0 && it < delegate.contentLength() })
        assertEquals(delegate.contentLength(), reports.last())
        val expected = Buffer().also(delegate::writeTo)
        assertEquals(expected.readByteString(), sink.readByteString())
        reports.clear()
        observed.writeTo(sink)
        assertTrue(reports.first() < delegate.contentLength())
        assertEquals(delegate.contentLength(), reports.last())
    }
    @Test fun failedWritesDoNotReportUnsentBytes() {
        var clock = 0L
        val reports = mutableListOf<Long>()
        val accepted = Buffer()
        val failing = object : ForwardingSink(accepted) {
            override fun write(source: Buffer, byteCount: Long) {
                if (accepted.size > 0) throw IOException("fixture disconnect")
                super.write(source, byteCount)
            }
        }.buffer()
        val observed = ByteProgressRequestBody(body(3), reports::add, { clock += 100_000_000; clock })
        try { observed.writeTo(failing); fail("Expected disconnect") } catch (_: IOException) { }
        assertEquals(listOf(0L, 8192L), reports)
        assertEquals(8192L, accepted.size)
    }
    @Test fun retriesOfSmallBodiesCountEveryPhysicalSend() {
        var previous = 0L
        var physicalBytes = 0L
        val observed = ByteProgressRequestBody(body(1), { bytes ->
            physicalBytes += if (bytes >= previous) bytes - previous else bytes
            previous = bytes
        }, { 0L })
        repeat(2) { observed.writeTo(Buffer()) }
        assertEquals(16384L, physicalBytes)
    }
}
