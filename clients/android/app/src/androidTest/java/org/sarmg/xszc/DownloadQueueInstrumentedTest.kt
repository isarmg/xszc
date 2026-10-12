package org.sarmg.xszc

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class DownloadQueueInstrumentedTest {
    @Test fun reselectingFailedDownloadReplacesItsPendingTileAndCompletesProgress() = runBlocking {
        val profile = "queue-${UUID.randomUUID()}"
        val asset = cloudFixture(0).copy(resources = listOf(
            RemoteResource("original", "primary", "photo.jpg", "image/jpeg", "/content", 100, null)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        val release = listOf(CompletableDeferred<Unit>(), CompletableDeferred<Unit>())
        var attempts = 0
        val queue = SessionDownloadQueue(scope) { _, transfer, checkCurrent ->
            val attempt = attempts++
            DownloadTransfers.update(transfer, 50)
            entered[attempt].complete(Unit); release[attempt].await()
            if (attempt == 0) error("fixture network failure")
            checkCurrent(); DownloadTransfers.update(transfer, 100, "已保存到手机")
        }
        try {
            for (attempt in 0..1) {
                assertEquals(1, queue.enqueue(profile, listOf(asset), current = { true }))
                withTimeout(5000) { entered[attempt].await() }
                assertEquals(0, queue.enqueue(profile, listOf(asset), current = { true }))
                assertEquals(1, DownloadTransfers.rows.value.count { it.profile == profile })
                release[attempt].complete(Unit)
                withTimeout(5000) { TransferTelemetry.downloads.states.first { profile !in it } }
            }
            val rows = DownloadTransfers.rows.value.filter { it.profile == profile }
            assertEquals(2, attempts)
            assertEquals(1, rows.size); assertTrue(rows.single().isCompleted)
            assertTrue(rows.filterNot { it.isCompleted }.isEmpty())
            assertEquals(1f, downloadProgress(rows), 0.0001f)
            DownloadTransfers.clearAllCompleted(profile)
            assertTrue(DownloadTransfers.rows.value.none { it.profile == profile })
        } finally { queue.cancelPending(); scope.cancel(); DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile == profile } }
    }

    @Test fun retryOnlyReplacesMatchingTerminalRowsAndIgnoresRetiredCallbacks() {
        val profile = "queue-${UUID.randomUUID()}"; val foreign = "queue-${UUID.randomUUID()}"
        val asset = cloudFixture(0)
        try {
            for (phase in listOf("下载失败：fixture", "已取消")) {
                val previous = listOf(
                    DownloadTransfers.Entry("$profile-old", profile, "one", 50, 100, phase, asset),
                    DownloadTransfers.Entry("$profile-saved", profile, "one", 100, 100, "已保存到手机", asset),
                    DownloadTransfers.Entry("$profile-other", profile, "other", 50, 100, phase, cloudFixture(1)),
                    DownloadTransfers.Entry("$profile-foreign", foreign, "one", 50, 100, phase, asset),
                    DownloadTransfers.Entry("$profile-unknown", profile, "one", 50, 100, phase),
                    DownloadTransfers.Entry("$profile-active", profile, "one", 50, 100, "正在下载", asset))
                DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile in listOf(profile, foreign) } + previous
                val retry = DownloadTransfers.start(profile, "one", 100, "等待下载", asset)
                val rows = DownloadTransfers.rows.value.filter { it.profile in listOf(profile, foreign) }
                assertEquals(previous.drop(1), rows.dropLast(1))
                assertEquals(retry, rows.last().id); assertEquals(0L, rows.last().bytes)
                DownloadTransfers.update("$profile-old", 100, "已保存到手机")
                assertEquals(rows, DownloadTransfers.rows.value.filter { it.profile in listOf(profile, foreign) })
            }
        } finally { DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile in listOf(profile, foreign) } }
    }

    @Test fun downloadsRemainQueuedDeduplicatedAndContinueAfterOneFailure() = runBlocking {
        val profile = "queue-${UUID.randomUUID()}"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        val queue = SessionDownloadQueue(scope) { asset, transfer, checkCurrent ->
            if (asset.id == "fixture-0") { entered.complete(Unit); gate.await(); error("fixture failure") }
            checkCurrent(); DownloadTransfers.update(transfer, bytes = 1, phase = "已保存到手机")
        }
        try {
            assertEquals(2, queue.enqueue(profile, listOf(cloudFixture(0), cloudFixture(1)), current = { true }))
            withTimeout(5000) { entered.await() }
            assertEquals(0, queue.enqueue(profile, listOf(cloudFixture(0), cloudFixture(1)), current = { true }))
            val waiting = DownloadTransfers.rows.value.filter { it.profile == profile }
            assertEquals(2, waiting.size); assertTrue(waiting.any { it.phase == "等待下载" })
            assertEquals(listOf("fixture-0", "fixture-1"), waiting.map { it.asset!!.id })
            gate.complete(Unit)
            val done = withTimeout(5000) { DownloadTransfers.rows.first { rows ->
                rows.count { it.profile == profile && (it.isCompleted || it.phase.startsWith("下载失败")) } == 2
            } }.filter { it.profile == profile }
            assertEquals(1, done.count { it.isCompleted }); assertEquals(1, done.count { it.phase.startsWith("下载失败") })
            DownloadTransfers.clearAllCompleted(profile)
            assertEquals(1, DownloadTransfers.rows.value.count { it.profile == profile })
        } finally { queue.cancelPending(); scope.cancel(); DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile == profile } }
    }
    @Test fun logoutPreventsCurrentCommitAndCancelsWaitingDownloads() = runBlocking {
        val profile = "queue-${UUID.randomUUID()}"; val current = AtomicBoolean(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>(); val committed = AtomicBoolean(false)
        val queue = SessionDownloadQueue(scope) { _, _, checkCurrent ->
            entered.complete(Unit)
            withContext(NonCancellable) { gate.await() }
            checkCurrent(); committed.set(true)
        }
        try {
            queue.enqueue(profile, listOf(cloudFixture(0), cloudFixture(1)), current::get)
            withTimeout(5000) { entered.await() }
            current.set(false); queue.cancelPending(); gate.complete(Unit)
            assertNull(TransferTelemetry.downloads.states.value[profile])
            delay(100)
            assertFalse(committed.get())
            assertTrue(DownloadTransfers.rows.value.filter { it.profile == profile }.all { it.phase == "已取消" })
        } finally { queue.cancelPending(); scope.cancel(); DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile == profile } }
    }
    @Test fun pickerCalendarDaysUseUtcBoundariesAndInclusiveEnd() {
        val millis = LocalDate.of(2026, 3, 8).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val start = cloudDateBoundary(millis); val end = cloudDateBoundary(millis, endInclusive = true)
        assertEquals(millis, start)
        assertEquals(24L * 3600 * 1000, end - start)
        assertEquals(LocalDate.of(2026, 3, 8), java.time.Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate())
    }

    @Test fun localDirectoryGroupsByUtcDateAtMonthBoundary() {
        val created = java.time.Instant.parse("2026-10-01T00:30:00Z").toEpochMilli()
        val directory = LocalGalleryDirectory.from(listOf(org.json.JSONObject()
            .put("source_id", "utc-boundary").put("name", "photo.jpg").put("media_kind", "photo")
            .put("created_ms", created).put("modified_ms", created)))
        assertEquals("2026-10-01", directory.entries.single().day)
        assertTrue(directory.days.containsKey("2026-10-01"))
    }
}
