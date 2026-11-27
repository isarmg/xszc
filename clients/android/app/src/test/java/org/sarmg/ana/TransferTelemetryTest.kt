package org.sarmg.ana

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class TransferTelemetryTest {
    @Test fun rateUsesRecentBytesAndDecaysAfterIdle() {
        val rate = TransferRate().start(100.0).record(1000, 100.5).record(1000, 101.0)
        assertEquals(2000.0, rate.bytesPerSecond(101.0), 0.001)
        assertEquals(500.0, rate.bytesPerSecond(102.5), 0.001)
        assertEquals(0.0, rate.bytesPerSecond(103.0), 0.0)
        assertEquals(0.0, rate.bytesPerSecond(Double.NaN), 0.0)
        assertEquals(rate, rate.record(-1, 102.0))
        assertEquals(0.0, TransferRate().record(1000, 1.0).bytesPerSecond(2.0), 0.0)
    }
    @Test fun rollingWindowExcludesOldTrafficAndCountsRepeatedTimestamps() {
        var rate = TransferRate().start(0.0)
        for (i in 1..24) rate = rate.record(1000, i * 0.25)
        assertEquals(4000.0, rate.bytesPerSecond(6.0), 0.001)
        rate = rate.record(1000, 6.0)
        assertEquals(4500.0, rate.bytesPerSecond(6.0), 0.001)
    }
    @Test fun oldSessionCallbacksCannotChangeOrEndNewSession() {
        val monitor = TransferMonitor()
        val old = monitor.begin("account", 100.0)
        monitor.resource("account", old, "old-photo")
        val current = monitor.begin("account", 101.0)
        monitor.resource("account", current, "new-photo")
        monitor.report("account", old, "old-photo", 9000, 0.9, 101.5)
        monitor.end("account", old)
        assertEquals(current, monitor.states.value["account"]!!.session)
        monitor.report("account", current, "wrong-photo", 9000, 0.9, 101.5)
        assertEquals(0.0, monitor.states.value["account"]!!.progress, 0.0)
        monitor.report("account", current, "new-photo", 0, 0.6, 101.5)
        assertEquals(0.0, monitor.states.value["account"]!!.rate.bytesPerSecond(101.5), 0.0)
        monitor.report("account", current, "new-photo", 1000, 0.8, 101.5)
        monitor.resource("account", current, "next-photo")
        val next = monitor.states.value["account"]!!
        assertEquals(0.0, next.progress, 0.0)
        assertEquals(2000.0, next.rate.bytesPerSecond(101.5), 0.001)
        monitor.end("account", current)
        assertTrue(monitor.states.value.isEmpty())
    }
    @Test fun speedFormatsDecimalUnits() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1.5 KB/s", TransferRate.formatted(1500.0))
            assertEquals("2.5 MB/s", TransferRate.formatted(2_500_000.0))
            assertEquals("1.0 GB/s", TransferRate.formatted(1_000_000_000.0))
            assertEquals("0 KB/s", TransferRate.formatted(Double.POSITIVE_INFINITY))
        } finally { Locale.setDefault(previous) }
    }
    @Test fun downloadObservationsCountNetworkBytesAndExcludeSavedFileUpdates() {
        val profile = "download-rate-fixture"
        val monitor = TransferTelemetry.downloads
        val lease = monitor.begin(profile, TransferRate.now() - 0.5)
        val id = DownloadTransfers.start(profile, "fixture", 100, "等待下载")
        try {
            monitor.resource(profile, lease, id)
            DownloadTransfers.update(id, phase = "正在下载")
            DownloadTransfers.update(id, bytes = 30)
            val state = monitor.states.value[profile]!!
            val at = TransferRate.now()
            assertEquals(0.3, state.progress, 0.0001)
            assertTrue(state.rate.bytesPerSecond(at) > 0)
            DownloadTransfers.update(id, bytes = 100, phase = "已保存到手机")
            assertEquals(state.rate.bytesPerSecond(at), monitor.states.value[profile]!!.rate.bytesPerSecond(at), 0.0)
            assertTrue(DownloadTransfers.rows.value.first { it.id == id }.isCompleted)
        } finally {
            monitor.end(profile, lease)
            DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.id == id }
        }
    }
}
