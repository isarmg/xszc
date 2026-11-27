package org.sarmg.ana

import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadRestoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val profile = instanceProfileKey("https://fixture.example.com", UUID.randomUUID().toString())
    private val filename = "restore-fixture-${UUID.randomUUID()}.png"
    private fun rows(includePending: Boolean = false): List<Uri> {
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.DISPLAY_NAME} = ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(filename))
            if (includePending) putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        }
        return context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID), args, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))) }
        }
    }
    @After fun cleanUp() {
        rows(includePending = true).forEach { context.contentResolver.delete(it, null, null) }
        DownloadTransfers.rows.value = DownloadTransfers.rows.value.filterNot { it.profile == profile }
    }
    private fun fixture(invalidManifest: Boolean = false): Pair<BackupApi, RemoteAsset> {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        val output = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle()
        val bytes = output.toByteArray()
        val paths = NativeStorage.prepare(context, profile)
        val file = File(context.cacheDir, filename).apply { writeBytes(bytes) }
        val handle = NativeBridgeV1.open(paths.database.path, MobileContractV1.putIdentity(JSONObject()).put("part_size", 4096).toString())
        val hash = try {
            MediaScanner(context).enqueue(handle, filename, "primary", "photo", "primary", file, filename, "image/png", 1, 1, JSONObject(), false)
            MobileContractV1.requireEnvelope(NativeBridgeV1.next(handle, paths.staging.path)).getJSONObject("value").getJSONObject("request").getString("content_blake3")
        } finally { NativeBridgeV1.close(handle); file.delete(); paths.database.parentFile!!.deleteRecursively(); paths.staging.parentFile!!.deleteRecursively() }
        val resource = RemoteResource(UUID.randomUUID().toString(), "primary", filename, "image/png", "/fixture-original", bytes.size.toLong(), null)
        val asset = cloudFixture(0).copy(id = UUID.randomUUID().toString(), resources = listOf(resource))
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.startsWith("/v1/resources/")) {
                JSONObject().put("resource_id", resource.id).put("content_size", if (invalidManifest) resource.contentSize + 1 else resource.contentSize)
                    .put("content_path", resource.contentPath).put("storage_encoding", "plain-v1").put("content_blake3", hash).toString().toResponseBody()
            } else bytes.toResponseBody()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        return BackupApi("https://fixture.example.com", "fixture-token", client) to asset
    }
    @Test fun verifiedOriginalPublishesOnePhotoAndCompletesExistingQueuedRow() = runBlocking {
        val (api, asset) = fixture()
        val transfer = DownloadTransfers.start(profile, filename, asset.resources.first().contentSize, "等待下载", asset)
        assertEquals(filename, RemoteLibrary.restore(context, api, asset, profile, transfer))
        assertEquals(1, rows().size)
        val entry = DownloadTransfers.rows.value.single { it.id == transfer }
        assertTrue(entry.isCompleted); assertEquals(entry.total, entry.bytes)
        assertEquals(1, DownloadTransfers.rows.value.count { it.profile == profile })
    }
    @Test fun invalidManifestFailsQueuedRowBeforeCreatingPhoto() = runBlocking {
        val (api, asset) = fixture(invalidManifest = true)
        val transfer = DownloadTransfers.start(profile, filename, asset.resources.first().contentSize, "等待下载", asset)
        try { RemoteLibrary.restore(context, api, asset, profile, transfer); fail("Must reject a changed resource") }
        catch (_: IllegalStateException) {}
        assertTrue(rows().isEmpty())
        assertTrue(DownloadTransfers.rows.value.single { it.id == transfer }.phase.startsWith("下载失败"))
    }
    @Test fun cancellationAfterPendingInsertRemovesUnpublishedPhoto() = runBlocking {
        val (api, asset) = fixture()
        val transfer = DownloadTransfers.start(profile, filename, asset.resources.first().contentSize, "等待下载", asset)
        var interrupted = false
        try {
            RemoteLibrary.restore(context, api, asset, profile, transfer) {
                if (rows(includePending = true).isNotEmpty()) { interrupted = true; throw CancellationException("fixture logout") }
            }
            fail("Must stop before publishing the photo")
        } catch (_: CancellationException) {}
        assertTrue(interrupted); assertTrue(rows(includePending = true).isEmpty())
        assertEquals("已取消", DownloadTransfers.rows.value.single { it.id == transfer }.phase)
    }
}
