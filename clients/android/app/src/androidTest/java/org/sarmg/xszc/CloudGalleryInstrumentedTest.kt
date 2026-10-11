package org.sarmg.xszc

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun cloudFixture(index: Int): RemoteAsset = RemoteAsset("fixture-$index",
    LocalDate.of(2026, if (index < 36) 6 else 5, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    if (index % 2 == 0) "photo" else "video", false, false, false, emptyList(), emptyList())

internal fun isolatedConfig(context: Context): SecureConfig {
    val prefix = "sync-test-${UUID.randomUUID()}-"
    return SecureConfig(object : ContextWrapper(context) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = context.getSharedPreferences(prefix + name, mode)
    })
}

@RunWith(AndroidJUnit4::class)
class CloudGalleryInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private class Pages(private val secondPage: CompletableDeferred<Unit>? = null) : CloudGallerySource {
        val requests = AtomicInteger()
        override suspend fun page(cursor: String?, trash: Boolean, favorite: Boolean, album: String?, filters: CloudFilters): RemoteLibrary.Page {
            requests.incrementAndGet()
            if (cursor != null && secondPage != null) withContext(NonCancellable) { secondPage.await() }
            val all = (0 until 54).map(::cloudFixture).filter { filters.kind == null || it.mediaKind == filters.kind }
            val offset = cursor?.toInt() ?: 0
            val items = all.drop(offset).take(18)
            return RemoteLibrary.Page(items, (offset + items.size).takeIf { it < all.size }?.toString())
        }
    }
    private fun show(source: CloudGallerySource, download: (List<RemoteAsset>) -> Unit = {}) {
        context.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE).edit().putInt("columns", 3).commit()
        val config = isolatedConfig(context)
        compose.setContent { AppTheme { Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding)) { CloudGalleryScreen(context, config, "cloud-test", source, download) } } } }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("cloud.photo.fixture-0").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(100) // Allow the emulator compositor to present the verified native dialog frame.
        val dir = File(context.filesDir, "layout-screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun allSelectionDownloadsEveryPageAndToolbarStaysFixed() {
        val pages = Pages(); var downloaded = emptyList<RemoteAsset>()
        show(pages) { downloaded = it }
        val before = compose.onNodeWithTag("cloud.filter").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("cloud.select").performClick()
        compose.onNodeWithTag("cloud.select-all").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已选 54 项").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(pages.requests.get() >= 3)
        val all = compose.onNodeWithTag("cloud.select-all").fetchSemanticsNode().boundsInRoot
        val cancel = compose.onNodeWithTag("cloud.cancel").fetchSemanticsNode().boundsInRoot
        assertTrue(all.left > before.right && cancel.left > all.left)
        assertEquals(before.height, compose.onNodeWithTag("cloud.filter").fetchSemanticsNode().boundsInRoot.height)
        compose.onNodeWithTag("cloud.grid").performTouchInput { swipeUp() }
        assertEquals(before.top, compose.onNodeWithTag("cloud.filter").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithTag("cloud.month.2026年6月").assertIsNotDisplayed()
        screenshot("cloud-selection-scrolled")
        compose.onNodeWithTag("cloud.download").performClick()
        compose.runOnIdle { assertEquals(54, downloaded.map { it.id }.toSet().size) }
        compose.onNodeWithTag("cloud.select").assertIsDisplayed()
        compose.onNodeWithTag("cloud.selection-count").assertDoesNotExist()
        compose.onNodeWithText("视图").assertDoesNotExist()
        compose.onNodeWithTag("cloud.grid").performScrollToNode(hasTestTag("cloud.download-notice"))
        compose.onNodeWithTag("cloud.download-notice").assertTextEquals("已加入下载：54 项")
    }
    @Test fun monthSelectionLoadsRemainingPagesAndFiltersResetSelection() {
        val pages = Pages(); show(pages)
        compose.onNodeWithTag("cloud.select").performClick()
        compose.onNodeWithTag("cloud.month-all.2026年6月").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已选 36 项").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cloud.filter").performClick()
        compose.onNodeWithText("类型").performClick()
        compose.onNodeWithText("照片", useUnmergedTree = true).performClick()
        compose.onNodeWithText("已选 0 项").assertIsDisplayed()
        compose.onNodeWithTag("cloud.select-all").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("已选 27 项").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("cloud.cancel").performClick()
        compose.onNodeWithTag("cloud.filter").performClick()
        compose.onNodeWithText("日期").performClick()
        compose.onNodeWithText("自定日期范围…").performClick()
        compose.onNodeWithText("日期范围").assertIsDisplayed()
        compose.onNodeWithText("应用").assertIsNotEnabled()
        screenshot("native-date-range")
        compose.onNodeWithText("取消").performClick()
        compose.onNodeWithText("日期范围").assertDoesNotExist()
    }
    @Test fun cancelledAllSelectionCannotReappearAfterLatePage() {
        val gate = CompletableDeferred<Unit>(); val pages = Pages(gate); show(pages)
        compose.onNodeWithTag("cloud.select").performClick()
        compose.onNodeWithTag("cloud.select-all").performClick()
        compose.waitUntil(10_000) { pages.requests.get() >= 2 }
        compose.onNodeWithTag("cloud.filter").assertIsNotEnabled()
        compose.onNodeWithTag("cloud.photo.fixture-0").performClick()
        compose.onNodeWithTag("cloud.cancel").performClick()
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("cloud.select").assertIsDisplayed()
        compose.onNodeWithTag("cloud.selection-count").assertDoesNotExist()
        compose.onNodeWithTag("cloud.select").performClick()
        compose.onNodeWithText("已选 0 项").assertIsDisplayed()
    }
    @Test fun syncFinishingDuringPreviewDefersRefreshUntilPreviewCloses() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val source = object : CloudGallerySource {
            // Empty resource fixtures never issue image/video network requests.
            override val api = BackupApi("https://fixture.invalid", "fixture-token")
            override suspend fun page(cursor: String?, trash: Boolean, favorite: Boolean,
                album: String?, filters: CloudFilters): RemoteLibrary.Page {
                requests.incrementAndGet()
                return RemoteLibrary.Page(listOf(cloudFixture(0)), null)
            }
            override suspend fun synchronize(): Boolean {
                entered.complete(Unit)
                release.await()
                finished.complete(Unit)
                return true
            }
        }
        show(source)
        // Wait for the real 30-second idle-sync timer, then hold its result.
        compose.waitUntil(40_000) { entered.isCompleted }
        compose.onNodeWithTag("cloud.photo.fixture-0").performClick()
        val preview = compose.onNodeWithContentDescription("照片预览，点按关闭，双指缩放")
        preview.assertIsDisplayed()
        val before = requests.get()
        compose.runOnIdle { release.complete(Unit) }
        compose.waitUntil(5_000) { finished.isCompleted }
        compose.waitForIdle()
        preview.assertIsDisplayed()
        compose.runOnIdle { assertEquals(before, requests.get()) }
        preview.performClick()
        compose.waitUntil(5_000) { requests.get() > before }
        compose.onNodeWithTag("cloud.photo.fixture-0").assertIsDisplayed()
    }

    @Test fun pullRefreshUpdatesAlbumAndDeviceOptions() {
        val version = AtomicInteger(0)
        val albumReads = AtomicInteger()
        val deviceReads = AtomicInteger()
        val source = object : CloudGallerySource {
            override suspend fun page(cursor: String?, trash: Boolean, favorite: Boolean,
                album: String?, filters: CloudFilters) = RemoteLibrary.Page(listOf(cloudFixture(0)), null)
            override suspend fun albums(): List<Pair<String, String>> {
                albumReads.incrementAndGet()
                return listOf("album" to if (version.get() == 0) "Old album" else "Renamed album")
            }
            override suspend fun devices(): List<Pair<String, String>> {
                deviceReads.incrementAndGet()
                return listOf("device" to if (version.get() == 0) "Old phone" else "Renamed phone")
            }
        }
        show(source)
        compose.waitUntil(5_000) { albumReads.get() >= 1 && deviceReads.get() >= 1 }
        // Initial composition and ON_RESUME can both load metadata. Establish the
        // idle baseline instead of assuming the activity has loaded exactly once.
        val before = compose.runOnIdle {
            version.set(1)
            albumReads.get() to deviceReads.get()
        }
        compose.onNodeWithTag("cloud.grid").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { albumReads.get() > before.first && deviceReads.get() > before.second }
        compose.onNodeWithTag("cloud.filter").performClick()
        compose.onNodeWithText("相册").performClick()
        compose.onNodeWithText("Renamed album").assertIsDisplayed()
        compose.onNodeWithText("Old album").assertDoesNotExist()
        compose.onNodeWithText("全部相册").performClick()
        compose.onNodeWithTag("cloud.filter").performClick()
        compose.onNodeWithText("设备").performClick()
        compose.onNodeWithText("Renamed phone").assertIsDisplayed()
        compose.onNodeWithText("Old phone").assertDoesNotExist()
    }

    @Test fun pullRefreshRecoversAlbumOptionsAfterOptionalFailure() {
        val albumsAvailable = AtomicBoolean(false)
        val albumReads = AtomicInteger()
        val deviceReads = AtomicInteger()
        val source = object : CloudGallerySource {
            override suspend fun page(cursor: String?, trash: Boolean, favorite: Boolean,
                album: String?, filters: CloudFilters) = RemoteLibrary.Page(listOf(cloudFixture(0)), null)
            override suspend fun albums(): List<Pair<String, String>> {
                albumReads.incrementAndGet()
                // Every initial/resume load fails until this test enables recovery.
                if (!albumsAvailable.get()) error("temporary album failure")
                return listOf("album" to "Recovered album")
            }
            override suspend fun devices(): List<Pair<String, String>> {
                deviceReads.incrementAndGet()
                return listOf("device" to "Available phone")
            }
        }
        show(source)
        compose.waitUntil(5_000) { albumReads.get() >= 1 && deviceReads.get() >= 1 }
        compose.onNodeWithTag("cloud.photo.fixture-0").assertIsDisplayed()
        compose.onNodeWithText("云端图库加载失败").assertDoesNotExist()
        val before = compose.runOnIdle {
            albumsAvailable.set(true)
            albumReads.get() to deviceReads.get()
        }
        compose.onNodeWithTag("cloud.grid").performTouchInput { swipeDown() }
        compose.waitUntil(5_000) { albumReads.get() > before.first && deviceReads.get() > before.second }
        compose.onNodeWithTag("cloud.filter").performClick()
        compose.onNodeWithText("相册").performClick()
        compose.onNodeWithText("Recovered album").assertIsDisplayed()
    }

    @Test fun queuedDownloadSurvivesLeavingCloudComposition() {
        val profile = "view-queue-${UUID.randomUUID()}"
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val queue = SessionDownloadQueue(workerScope) { _, transfer, checkCurrent ->
            gate.await(); checkCurrent(); DownloadTransfers.update(transfer, phase = "已保存到手机")
        }
        val config = isolatedConfig(context); val pages = Pages()
        var showingCloud by mutableStateOf(true)
        try {
            compose.setContent { AppTheme {
                if (showingCloud) CloudGalleryScreen(context, config, profile, pages) { values ->
                    queue.enqueue(profile, values, current = { true }); showingCloud = false
                } else Text("另一页面")
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("cloud.photo.fixture-0").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("cloud.select").performClick()
            compose.onNodeWithTag("cloud.photo.fixture-0").performClick()
            compose.onNodeWithTag("cloud.download").performClick()
            compose.onNodeWithText("另一页面").assertIsDisplayed()
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil(5000) { DownloadTransfers.rows.value.any { it.profile == profile && it.isCompleted } }
        } finally { queue.cancelPending(); workerScope.cancel(); DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile == profile } }
    }

}
