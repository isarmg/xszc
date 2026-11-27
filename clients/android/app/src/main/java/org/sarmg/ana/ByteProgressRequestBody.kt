package org.sarmg.ana

import okhttp3.RequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer

/** Observe streamed request bytes without loading a part into memory. */
internal class ByteProgressRequestBody(
    private val body: RequestBody,
    private val sent: (Long) -> Unit,
    private val clock: () -> Long = System::nanoTime,
) : RequestBody() {
    override fun contentType() = body.contentType()
    override fun contentLength() = body.contentLength()
    override fun isOneShot() = body.isOneShot()
    override fun writeTo(sink: BufferedSink) {
        var count = 0L
        var lastUpdate = clock()
        // OkHttp can resend the same body; reset the attempt before reporting its bytes.
        sent(0)
        val tracked = object : ForwardingSink(sink) {
            override fun write(source: Buffer, byteCount: Long) {
                super.write(source, byteCount)
                count += byteCount
                val now = clock()
                if (now - lastUpdate >= 100_000_000) { sent(count); lastUpdate = now }
            }
        }.buffer()
        body.writeTo(tracked)
        tracked.flush()
        sent(count)
    }
}
