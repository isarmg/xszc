package org.sarmg.xszc

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Scaffold
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TransfersInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val profile = provisionalProfileKey("https://fixture.example.com", UUID.randomUUID().toString())
    private var media: Uri? = null
    private var source: File? = null
    private var session: TransferStore.Session? = null
    @After fun cleanUp() {
        TransferTelemetry.reset()
        DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile == profile }
        media?.let { context.contentResolver.delete(it, null, null) }; source?.delete()
        session?.let { NativeBridgeV1.close(it.handle); it.paths.database.parentFile!!.deleteRecursively(); it.paths.staging.parentFile!!.deleteRecursively() }
    }
    private fun screenshot(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(100) // Allow the emulator compositor to present the verified native dialog frame.
        val dir = File(context.filesDir, "layout-screenshots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun nativeProgressHeadersShowActivePhotosAndRetainCompletedHistory() {
        val config = isolatedConfig(context)
        val storage = TransferStore.open(context, profile).also { session = it }; val handle = storage.handle
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.rgb(30, 130, 190)) }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "transfer-fixture-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png"); put(MediaStore.Images.Media.IS_PENDING, 1)
        })!!.also { media = it }
        context.contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val file = File(context.cacheDir, "transfer-${UUID.randomUUID()}.png").also { source = it }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val finished = UUID.randomUUID().toString(); val pending = UUID.randomUUID().toString()
        fun create(id: String, count: Int) {
            TransferStore.command(handle, "create_batch", JSONObject().put("id", id).put("items", JSONArray((0 until count).map {
                JSONObject().put("id", if (count == 1) "selected" else "pending-$it")
                    .put("source", JSONObject().put("uri", uri.toString()).put("source_id", uri.toString()).put("modified_ms", 1L).put("name", "fixture-$it.png").toString())
            })))
        }
        create(finished, 1)
        MediaScanner(context).enqueue(handle, uri.toString(), "primary", "photo", "primary", file, "fixture.png", "image/png", 1, 1, JSONObject(), false, finished, "selected")
        val job = MobileContractV1.requireEnvelope(NativeBridgeV1.next(handle, storage.paths.staging.path)).getJSONObject("value")
        MobileContractV1.requireEnvelope(NativeBridgeV1.markComplete(handle, job.getString("job_id")))
        TransferStore.setItem(handle, finished, "selected", "queued"); create(pending, 6)
        val done = DownloadTransfers.start(profile, "finished.png", 100, asset = cloudFixture(0))
        DownloadTransfers.update(done, 100, "已保存到手机")
        val waiting = DownloadTransfers.start(profile, "waiting.png", 100, "等待下载", cloudFixture(1))
        assertEquals(1, TransferStore.items(handle, finished).getJSONObject(0).getInt("complete"))
        compose.setContent { AppTheme { Scaffold { padding -> Box(Modifier.fillMaxSize().padding(padding)) { TransfersScreen(context, config, profile) } } } }
        try {
            compose.waitUntil(10_000) { compose.onNodeWithTag("transfers.upload").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("14%") }
        } catch (error: Throwable) { screenshot("transfers-failure"); throw AssertionError(compose.onRoot().printToString(), error) }
        compose.onNodeWithText("传输概览").assertDoesNotExist()
        compose.onNodeWithTag("transfers.photo.upload.$finished.selected").assertDoesNotExist()
        screenshot("transfers-collapsed")
        compose.onNodeWithTag("transfers.upload").performClick()
        compose.onNodeWithTag("transfers.photo.upload.$finished.selected").assertDoesNotExist()
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.photo.upload.$pending.pending-0"))
        compose.onNodeWithTag("transfers.photo.upload.$pending.pending-0").performClick()
        compose.onNodeWithTag("transfers.preview").assertIsDisplayed()
        screenshot("transfers-full-screen")
        compose.onNodeWithTag("transfers.preview").performClick()
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.upload"))
        compose.onNodeWithTag("transfers.upload.speed", useUnmergedTree = true).assertTextEquals("0 KB/s")
        val lease = TransferTelemetry.uploads.begin(profile, TransferRate.now() - 0.5)
        TransferTelemetry.uploads.resource(profile, lease, uri.toString())
        TransferTelemetry.uploads.report(profile, lease, uri.toString(), 1000, 0.5)
        compose.waitUntil(5000) { compose.onNodeWithTag("transfers.upload").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("57%") }
        compose.waitUntil(5000) { !compose.onNodeWithTag("transfers.upload").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].endsWith("0 KB/s") }
        screenshot("transfers-live-progress")
        TransferTelemetry.uploads.end(profile, lease)
        compose.waitUntil(5000) { compose.onNodeWithTag("transfers.upload").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("14%") }
        compose.onNodeWithTag("transfers.upload").performTouchInput { longClick() }
        compose.onNodeWithText("继续上传").assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()
        assertEquals(2, TransferStore.batches(handle).length())
        assertFalse(NativeBridgeV1.needs(handle, uri.toString(), "primary", 1)); assertTrue(file.exists())
        compose.onNodeWithTag("transfers.photo.upload.$finished.selected").assertDoesNotExist()
        MediaScanner(context).enqueue(handle, uri.toString(), "primary", "photo", "primary", file, "fixture.png", "image/png", 1, 1, JSONObject(), false, pending, "pending-0")
        TransferStore.setItem(handle, pending, "pending-0", "queued")
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("transfers.photo.upload.$pending.pending-0").fetchSemanticsNodes().isEmpty() }
        assertEquals(2, TransferStore.batches(handle).length())
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.download"))
        compose.onNodeWithTag("transfers.download").performClick()
        compose.onNodeWithTag("transfers.photo.download.$done").assertDoesNotExist()
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.photo.download.$waiting"))
        compose.onNodeWithTag("transfers.photo.download.$waiting").assertIsDisplayed()
        screenshot("transfers-expanded")
        DownloadTransfers.update(waiting, bytes = 50, phase = "正在下载")
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.download"))
        compose.waitUntil(5000) { compose.onNodeWithTag("transfers.download").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("75%") }
        DownloadTransfers.update(waiting, bytes = 100, phase = "已保存到手机")
        compose.waitUntil(5000) { compose.onAllNodesWithTag("transfers.photo.download.$waiting").fetchSemanticsNodes().isEmpty() }
        compose.waitUntil(5000) { compose.onNodeWithTag("transfers.download").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription].contains("100%") }
        assertEquals(2, DownloadTransfers.rows.value.count { it.profile == profile && it.isCompleted })
        compose.onNodeWithTag("transfers.row.uploads").assertDoesNotExist()
        compose.onNodeWithTag("transfers.row.downloads").assertDoesNotExist()

        // One tile explicitly controls its six-item batch, with confirmation and recovery.
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.photo.upload.$pending.pending-1"))
        compose.onNodeWithTag("transfers.photo.upload.$pending.pending-1").performTouchInput { longClick() }
        compose.onNodeWithText("取消整个批次（6项）").performClick()
        assertEquals(6, TransferStore.items(handle, pending).length())
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag("transfers.photo.upload.$pending.pending-1").performTouchInput { longClick() }
        compose.onNodeWithText("取消整个批次（6项）").performClick()
        compose.onNodeWithText("确认").performClick()
        compose.waitUntil(5000) { TransferStore.items(handle, pending).length() == 0 }
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.upload"))
        compose.onNodeWithTag("transfers.upload").performTouchInput { longClick() }
        compose.onNodeWithText("已取消批次").performClick()
        compose.onNodeWithText("恢复整个批次（6项，1项已完成）").performClick()
        compose.onNodeWithText("确认").performClick()
        compose.waitUntil(5000) { TransferStore.items(handle, pending).length() == 6 }
        compose.onNodeWithTag("transfers.upload").performTouchInput { longClick() }
        compose.onNodeWithText("清除已完成手动批次记录").performClick()
        compose.onNodeWithText("清除记录").performClick()
        compose.waitUntil(5000) { TransferStore.batches(handle).length() == 1 }
        assertFalse(NativeBridgeV1.needs(handle, uri.toString(), "primary", 1)); assertTrue(file.exists())
        compose.onNodeWithTag("transfers.grid").performScrollToNode(hasTestTag("transfers.download"))
        compose.onNodeWithTag("transfers.download").performTouchInput { longClick() }
        compose.onNodeWithText("清除已完成下载记录").performClick()
        compose.onNodeWithText("清除记录").performClick()
        assertTrue(DownloadTransfers.rows.value.none { it.profile == profile })
    }
}
