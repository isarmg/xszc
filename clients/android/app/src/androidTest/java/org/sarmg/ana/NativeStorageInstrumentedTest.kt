package org.sarmg.ana

import android.content.ContextWrapper
import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Actual JNI and Android sandbox: no mocked native results or production data. */
@RunWith(AndroidJUnit4::class)
class NativeStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File

    @Before fun setUp() {
        root = File(context.dataDir.canonicalFile, "native-test-${UUID.randomUUID()}")
        Os.mkdir(root.path, 0x1c0)
    }

    @After fun tearDown() {
        // Tests unlink their synthetic symlinks before deleting this isolated fixture.
        Os.chmod(root.path, 0x1c0)
        root.deleteRecursively()
    }

    private fun storage(dataRoot: File = root): NativeStorage.Paths =
        NativeStorage.prepare(object : ContextWrapper(context) {
            override fun getDataDir(): File = dataRoot
        })

    private fun open(paths: NativeStorage.Paths): Long = NativeBridgeV1.open(
        paths.database.path,
        MobileContractV1.putIdentity(JSONObject()).put("part_size", 16).toString(),
    )

    @Test fun galleryPaginationPreservesRowsAcrossPageBoundariesAndRefresh() {
        val handle = open(storage())
        try {
            for (total in listOf(149, 150, 151, 300, 301, 453)) {
                catalog(handle, total)
                val ids = mutableListOf<String>()
                do {
                    val page = LocalCatalog.window(handle, null, null, false, offset = ids.size)
                    ids += page.rows.map { it.getString("source_id") }
                    if (!page.hasMore) break
                    assertEquals(150, page.rows.size)
                    assertTrue(ids.size < total)
                } while (true)
                val expected = (0 until total).reversed().map { "media-$it" }
                assertEquals(expected, ids)
                assertEquals(total, ids.toSet().size)
                val refreshed = LocalCatalog.window(handle, null, null, false, count = total)
                assertEquals(expected, refreshed.rows.map { it.getString("source_id") })
                assertFalse(refreshed.hasMore)
            }
        } finally { NativeBridgeV1.close(handle) }
    }

    @Test fun galleryPaginationFiltersAlbumsAndVideosBeforeOffset() {
        val handle = open(storage())
        try {
            catalog(handle, 1003)
            val ids = mutableListOf<String>()
            do {
                val page = LocalCatalog.window(handle, "a", "video", true, offset = ids.size)
                assertTrue(page.rows.all { it.getString("media_kind") == "video" })
                ids += page.rows.map { it.getString("source_id") }
                if (!page.hasMore) break
            } while (true)
            assertEquals((0 until 1003).reversed().filter { it % 6 == 0 }.map { "media-$it" }, ids)
            assertEquals(ids.size, ids.toSet().size)
        } finally { NativeBridgeV1.close(handle) }
    }

    @Test fun completeGalleryDirectoryAndVisibleDetailsHaveNo150ItemLimit() {
        val handle = open(storage())
        try {
            catalog(handle, 10_003)
            val directory = LocalGalleryDirectory.from(LocalCatalog.index(handle, null, null, false))
            assertEquals(10_003, directory.entries.size)
            assertEquals("media-10002", directory.entries.first().id)
            assertEquals("media-0", directory.entries.last().id)
            assertEquals(10_002, directory.positions["media-0"])
            assertEquals(directory.entries, directory.days.values.flatten())
            val details = LocalCatalog.items(handle, listOf("media-10002", "media-150", "media-0", "missing"))
            assertEquals(setOf("media-10002", "media-150", "media-0"), details.map { it.getString("source_id") }.toSet())
            assertTrue(details.all { it.getString("backup_state") == "unknown" })
            val videos = LocalCatalog.index(handle, "a", "video", true)
            assertEquals((0 until 10_003).reversed().filter { it % 6 == 0 }.map { "media-$it" }, videos.map { it.getString("source_id") })
        } finally { NativeBridgeV1.close(handle) }
    }

    private fun catalog(handle: Long, count: Int) {
        TransferStore.gallery(handle, "begin_catalog")
        (0 until count).chunked(200).forEach { indices ->
            val items = indices.map { index ->
                JSONObject().put("source_id", "media-$index").put("name", "media-$index")
                    .put("media_kind", if (index % 3 == 0) "video" else "photo")
                    .put("album_id", if (index % 2 == 0) "a" else "b")
                    .put("created_ms", index * 1000L).put("modified_ms", index * 1000L)
                    .put("size", 0).put("descriptor", "{}")
            }
            TransferStore.gallery(handle, "catalog", JSONObject().put("items", JSONArray(items)))
        }
        TransferStore.gallery(handle, "finish_catalog")
    }

    @Test fun prepareUploadAndReopenActualDatabase() {
        val paths = storage()
        val source = File(root, "source.jpg")
        val bytes = ByteArray(39) { it.toByte() }
        source.writeBytes(bytes)
        Os.chmod(source.path, 0x180)
        val input = MobileContractV1.putIdentity(JSONObject())
            .put("source_asset_id", "fixture-asset")
            .put("source_resource_id", "fixture-resource")
            .put("media_kind", "photo")
            .put("role", "primary")
            .put("file_path", source.path)
            .put("filename", "source.jpg")
            .put("mime_type", "image/jpeg")
            .put("source_created_at_ms", 1)
            .put("modified_ms", 1)
            .put("source_size", source.length())
            .put("metadata_json", JSONObject.NULL)
            .put("remove_source_after_prepare", false)
        // Model shared Android ancestors: traversal is allowed but listing is not.
        Os.chmod(root.path, 0x49) // 0111
        val handle = open(paths)
        try {
            MobileContractV1.requireEnvelope(NativeBridgeV1.enqueue(handle, input.toString()))
            val job = MobileContractV1.requireEnvelope(NativeBridgeV1.next(handle, paths.staging.path))
                .getJSONObject("value")
            val jobId = job.getString("job_id")
            val parts = job.getJSONArray("local_parts")
            assertEquals(3, parts.length())
            val reconstructed = (0 until parts.length()).flatMap { index ->
                File(parts.getJSONObject(index).getString("path")).readBytes().toList()
            }.toByteArray()
            assertArrayEquals(bytes, reconstructed)
            MobileContractV1.requireEnvelope(NativeBridgeV1.markUpload(handle, jobId, UUID.randomUUID().toString()))
            for (index in 0 until parts.length()) {
                MobileContractV1.requireEnvelope(NativeBridgeV1.markPart(handle, jobId, index))
            }
            MobileContractV1.requireEnvelope(NativeBridgeV1.markComplete(handle, jobId))
        } finally {
            NativeBridgeV1.close(handle)
        }
        val reopened = open(storage())
        try {
            assertFalse(NativeBridgeV1.needs(reopened, "fixture-asset", "fixture-resource", 1))
            MobileContractV1.requireEnvelope(NativeBridgeV1.stats(reopened))
            assertTrue(MobileContractV1.requireEnvelope(NativeBridgeV1.next(reopened, paths.staging.path)).isNull("value"))
        } finally {
            NativeBridgeV1.close(reopened)
        }
    }

    @Test fun deviceSessionRefreshReusesQueueAndIdentityDriftFailsClosed() {
        val isolated = object : ContextWrapper(context) { override fun getDataDir(): File = root }
        val server = "https://backup.example.com"
        val account = UUID.randomUUID().toString()
        val device = UUID.randomUUID().toString()
        val handles = mutableSetOf<Long>()
        fun bind(accountId: String = account): TransferStore.Session = TransferStore.bindAccount(
            isolated, server, accountId, device
        ).also { handles += it.handle }
        try {
            val original = bind()
            val batch = UUID.randomUUID().toString()
            TransferStore.command(original.handle, "create_batch", JSONObject().put("id", batch)
                .put("items", org.json.JSONArray().put(JSONObject().put("id", "selected").put("source", "local-photo"))))
            val repaired = bind()
            assertEquals(original.profile, repaired.profile)
            assertEquals(1, TransferStore.batches(original.handle).length())
            assertEquals(account, (TransferStore.command(original.handle, "binding") as JSONObject).getString("account_id"))
            assertThrows(IllegalStateException::class.java) { bind(UUID.randomUUID().toString()) }
        } finally { handles.forEach { NativeBridgeV1.close(it) } }
    }

    @Test fun completedBatchCleanupPreservesSharedJobsAndReceiptsAfterReopen() {
        val paths = storage()
        val source = File(root, "cleanup.jpg").apply { writeText("12345678"); Os.chmod(path, 0x180) }
        var handle = open(paths)
        val first = UUID.randomUUID().toString(); val other = UUID.randomUUID().toString()
        fun batch(id: String) { TransferStore.command(handle, "create_batch", JSONObject().put("id", id)
            .put("items", org.json.JSONArray().put(JSONObject().put("id", "selected").put("source", "fixture")))) }
        fun enqueue(id: String) { MediaScanner(context).enqueue(handle, "cleanup-asset", "original", "photo", "primary", source,
            "cleanup.jpg", "image/jpeg", 1, 1, JSONObject(), false, id, "selected") }
        try {
            batch(first); enqueue(first)
            assertThrows(Exception::class.java) { TransferStore.clearCompletedBatch(handle, first) }
            val job = MobileContractV1.requireEnvelope(NativeBridgeV1.next(handle, paths.staging.path)).getJSONObject("value")
            TransferStore.command(handle, "bind", JSONObject().put("server", "https://backup.example.com")
                .put("account_id", UUID.randomUUID().toString()).put("device_id", UUID.randomUUID().toString()))
            val receipt = JSONObject().put("op", "receipt").put("job_id", job.getString("job_id"))
                .put("asset_id", UUID.randomUUID().toString()).put("resource_id", UUID.randomUUID().toString())
                .put("content_blake3", job.getJSONObject("request").getString("content_blake3"))
            TransferStore.command(handle, "receipt", receipt)
            TransferStore.setItem(handle, first, "selected", "queued")
            batch(other); enqueue(other); TransferStore.setItem(handle, other, "selected", "queued")
            TransferStore.clearCompletedBatch(handle, first)
            assertEquals("12345678", source.readText())
            NativeBridgeV1.close(handle); handle = open(paths)
            val retained = TransferStore.batches(handle)
            assertEquals(1, retained.length()); assertEquals(other, retained.getJSONObject(0).getString("id"))
            assertEquals(1, retained.getJSONObject(0).getInt("complete"))
            assertFalse(NativeBridgeV1.needs(handle, "cleanup-asset", "original", 1))
            // Replaying the matching receipt after reopening proves cleanup retained it and its job.
            TransferStore.command(handle, "receipt", receipt)
            assertEquals(0, TransferStore.items(handle, first).length())
        } finally { NativeBridgeV1.close(handle) }
    }

    @Test fun pendingQueueIsFifoAndIncludesSelectionsAddedWhilePreparing() {
        val paths = storage(); val handle = open(paths)
        fun create(): String = UUID.randomUUID().toString().also { id ->
            TransferStore.command(handle, "create_batch", JSONObject().put("id", id)
                .put("items", org.json.JSONArray().put(JSONObject().put("id", "selected").put("source", "fixture"))))
        }
        fun next() = TransferStore.command(handle, "next_pending_item") as? JSONObject
        try {
            val first = create(); val second = create()
            assertEquals(first, next()!!.getString("batch_id"))
            TransferStore.setItem(handle, first, "selected", "blocked", "fixture preparation failure")
            val added = create()
            assertEquals(second, next()!!.getString("batch_id"))
            TransferStore.command(handle, "cancel_batch", JSONObject().put("batch_id", second))
            assertEquals(added, next()!!.getString("batch_id"))
            TransferStore.setItem(handle, added, "selected", "queued")
            assertNull(next())
            assertEquals(0, (TransferStore.command(handle, "automatic_uploads") as org.json.JSONArray).length())
        } finally { NativeBridgeV1.close(handle) }
    }

    @Test fun resolvesOnlySystemProvidedRootAlias() {
        val alias = File(root, "system-root-alias")
        Os.symlink(root.path, alias.path)
        try {
            val paths = storage(alias)
            assertEquals(root.path, paths.database.parentFile!!.parent)
            val handle = open(paths)
            NativeBridgeV1.close(handle)
        } finally {
            Files.delete(alias.toPath())
        }
    }

    @Test fun repairsOwnedStagingPermissionsWithoutLosingFiles() {
        val paths = storage()
        val retained = File(paths.staging, "retained")
        retained.writeText("unchanged")
        Os.chmod(paths.staging.path, 0x1ed) // Existing owned staging directory with permissive mode.
        storage()
        assertEquals(0x1c0, Os.stat(paths.staging.path).st_mode and 0x1ff)
        assertEquals("unchanged", retained.readText())
    }

    @Test fun refusesChildDirectoryLinks() {
        val target = File(root, "target")
        Os.mkdir(target.path, 0x1ed)
        // Android's restrictive process umask can turn mkdir(0755) into 0700.
        // Establish the fixture explicitly before proving rejection does not chmod it.
        Os.chmod(target.path, 0x1ed)
        val link = File(root, "databases")
        Os.symlink(target.path, link.path)
        try {
            assertThrows(Exception::class.java) { storage() }
            assertEquals(0x1ed, Os.stat(target.path).st_mode and 0x1ff)
        } finally {
            Files.delete(link.toPath())
        }
    }

    @Test fun refusesDatabaseFileLinksWithoutChangingTarget() {
        val paths = storage()
        val target = File(root, "untouched")
        target.writeText("do not modify")
        Os.symlink(target.path, paths.database.path)
        try {
            assertThrows(RuntimeException::class.java) { open(paths) }
            assertEquals("do not modify", target.readText())
        } finally {
            Files.delete(paths.database.toPath())
        }
    }
}
