package org.sarmg.xszc

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ScanOptions(
    val includePhotos: Boolean,
    val includeVideos: Boolean,
    val cameraOnly: Boolean,
    val selectedAlbumIds: Set<String> = emptySet(),
    val maxItems: Int = 40,
)

data class ScanResult(
    val discovered: Int,
    val discoveredBytes: Long,
    val skipped: Int,
    val limitReached: Boolean,
    val albums: Map<String, ScannedAlbum>,
    val error: String? = null,
)

data class ScannedAlbum(val name: String, val sourceAssetIds: Set<String>)

class MediaScanner(private val context: Context) {
    fun scan(handle: Long, stagingRoot: File, options: ScanOptions): ScanResult {
        val sourceDir = File(stagingRoot, "sources").also { it.mkdirs() }
        var discovered = 0
        var discoveredBytes = 0L
        var skipped = 0
        var failure: String? = null
        val albums = mutableMapOf<String, MutableAlbum>()
        if (options.includePhotos) {
            val result = scanCollection(
                handle,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "photo",
                sourceDir,
                options,
                options.maxItems,
            )
            discovered += result.discovered
            discoveredBytes += result.bytes
            skipped += result.skipped
            mergeAlbums(albums, result.albums)
            failure = failure ?: result.error
        }
        if (options.includeVideos && discovered < options.maxItems) {
            val result = scanCollection(
                handle,
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                "video",
                sourceDir,
                options,
                options.maxItems - discovered,
            )
            discovered += result.discovered
            discoveredBytes += result.bytes
            skipped += result.skipped
            mergeAlbums(albums, result.albums)
            failure = failure ?: result.error
        }
        return ScanResult(
            discovered,
            discoveredBytes,
            skipped,
            discovered >= options.maxItems,
            albums.mapValues { ScannedAlbum(it.value.name, it.value.sourceAssetIds) },
            failure,
        )
    }

    private fun scanCollection(
        handle: Long,
        collection: android.net.Uri,
        mediaKind: String,
        sourceDir: File,
        options: ScanOptions,
        limit: Int,
    ): CollectionResult {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.Images.ImageColumns.DATE_TAKEN,
            MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Images.ImageColumns.BUCKET_ID,
            MediaStore.MediaColumns.RELATIVE_PATH,
        )
        var queued = 0
        var bytes = 0L
        var skipped = 0
        var failure: String? = null
        val albums = mutableMapOf<String, MutableAlbum>()
        try {
            val cursor = context.contentResolver.query(
                collection,
                projection.toTypedArray(),
                null,
                null,
                "${MediaStore.MediaColumns.DATE_ADDED} ASC",
            ) ?: return CollectionResult(0, 0, 1, emptyMap(), "无法读取系统媒体库，请稍后重试")
            cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val takenColumn = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATE_TAKEN)
            val createdColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val bucketColumn = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME)
            val bucketIdColumn = cursor.getColumnIndex(MediaStore.Images.ImageColumns.BUCKET_ID)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                if (queued >= limit) break
                val bucketName = if (bucketColumn >= 0) cursor.getString(bucketColumn) else null
                val bucketId = if (bucketIdColumn >= 0) cursor.getString(bucketIdColumn) else null
                val relativePath = if (relativePathColumn >= 0) cursor.getString(relativePathColumn) else null
                if (options.cameraOnly && !isCameraMedia(bucketName, relativePath)) continue
                if (!options.cameraOnly && bucketId !in options.selectedAlbumIds) continue
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                val sourceId = uri.toString()
                if (TransferStore.gallery(handle, "is_excluded", JSONObject().put("source_id", sourceId)) == true) continue
                val modifiedMs = cursor.getLong(modifiedColumn) * 1000L
                val createdMs = if (takenColumn >= 0 && cursor.getLong(takenColumn) > 0) cursor.getLong(takenColumn) else cursor.getLong(createdColumn) * 1000L
                val sourceSize = if (cursor.isNull(sizeColumn)) null else cursor.getLong(sizeColumn).takeIf { it > 0 }
                if (bucketId != null && bucketName != null) {
                    albums.getOrPut(bucketId) { MutableAlbum(bucketName) }.sourceAssetIds += sourceId
                }
                TransferStore.gallery(handle, "declare_resources", JSONObject().put("source_id", sourceId).put("modified_ms", modifiedMs).put("originals", 1))
                val thumbnailId = "$sourceId#thumbnail-v1"
                var needsPrimary = if (sourceSize == null) NativeBridgeV1.needs(handle, sourceId, sourceId, modifiedMs)
                    else NativeBridgeV1.needsWithSize(handle, sourceId, sourceId, modifiedMs, sourceSize)
                var needsThumbnail = NativeBridgeV1.needs(handle, sourceId, thumbnailId, modifiedMs)
                if (!needsPrimary && !needsThumbnail) continue
                val output = File(sourceDir, UUID.randomUUID().toString())
                var primaryEnqueued = false
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        output.outputStream().use { target ->
                            val budget = minOf(2L * 1024 * 1024 * 1024, (sourceDir.usableSpace - 128L * 1024 * 1024) / 2)
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            while (true) {
                                val n = input.read(buffer); if (n < 0) break
                                bytes += n; check(bytes <= budget) { "媒体超过暂存预算" }
                                target.write(buffer, 0, n)
                            }
                        }
                    } ?: error("无法读取媒体内容，请重新授权")
                    // MediaStore.SIZE may lag behind the actual stream. Recheck using the bytes
                    // that will be queued, otherwise a stale size creates endless "new" work.
                    if (needsPrimary && !NativeBridgeV1.needsWithSize(handle, sourceId, sourceId,
                            modifiedMs, output.length())) {
                        needsPrimary = false
                        if (!needsThumbnail) { output.delete(); continue }
                    }
                    // A same-second source size change also invalidates the old thumbnail.
                    // MediaStore cannot distinguish same-second edits that keep both source
                    // and thumbnail byte counts unchanged; those need a content version.
                    if (needsPrimary) needsThumbnail = true
                    val metadata = JSONObject()
                        .put("android_uri", sourceId)
                        .put("display_name", cursor.getString(nameColumn))
                        .put("bucket_name", bucketName ?: JSONObject.NULL)
                        .put("relative_path", relativePath ?: JSONObject.NULL)
                        .put("date_modified_ms", modifiedMs)
                    if (needsPrimary) {
                        enqueue(
                            handle,
                            sourceId,
                            sourceId,
                            mediaKind,
                            "primary",
                            output,
                            cursor.getString(nameColumn) ?: "media",
                            cursor.getString(mimeColumn) ?: "application/octet-stream",
                            createdMs,
                            modifiedMs,
                            metadata,
                            true,
                        )
                        primaryEnqueued = true
                        queued++
                        bytes += output.length()
                    }
                    if (needsThumbnail) {
                        createThumbnail(output, mediaKind, sourceDir)?.let { thumbnail ->
                            if (needsPrimary || NativeBridgeV1.needsWithSize(handle, sourceId, thumbnailId,
                                    modifiedMs, thumbnail.length())) {
                                try {
                                    enqueue(
                                        handle,
                                        sourceId,
                                        thumbnailId,
                                        mediaKind,
                                        "thumbnail",
                                        thumbnail,
                                        "${cursor.getString(nameColumn) ?: "media"}.thumbnail.jpg",
                                        "image/jpeg",
                                        createdMs,
                                        modifiedMs,
                                        JSONObject().put("thumbnail_of", sourceId)
                                            .put("source_size", output.length()),
                                        true,
                                    )
                                    queued++
                                    bytes += thumbnail.length()
                                } catch (error: Exception) {
                                    thumbnail.delete()
                                    throw error
                                }
                            } else thumbnail.delete()
                        }
                        if (!needsPrimary) output.delete()
                    }
                } catch (error: Exception) {
                    if (!primaryEnqueued) output.delete()
                    failure = failure ?: error.message ?: "无法准备媒体"
                    skipped++
                }
            }
        }
        } catch (error: SecurityException) {
            failure = "需要重新授权访问照片或视频"
            skipped++
        }
        return CollectionResult(queued, bytes, skipped, albums, failure)
    }

    private fun isCameraMedia(bucketName: String?, relativePath: String?): Boolean {
        if (relativePath?.replace('\\', '/')?.startsWith("DCIM/", ignoreCase = true) == true) return true
        return bucketName.equals("Camera", ignoreCase = true)
    }

    internal fun enqueue(
        handle: Long,
        sourceAssetId: String,
        sourceResourceId: String,
        mediaKind: String,
        role: String,
        file: File,
        filename: String,
        mimeType: String,
        createdMs: Long,
        modifiedMs: Long,
        metadata: JSONObject,
        removeSource: Boolean,
        batch: String? = null,
        item: String? = null,
    ) {
        val input = MobileContractV1.putIdentity(JSONObject())
            .put("source_asset_id", sourceAssetId)
            .put("source_resource_id", sourceResourceId)
            .put("media_kind", mediaKind)
            .put("role", role)
            .put("file_path", file.absolutePath)
            .put("filename", filename)
            .put("mime_type", mimeType)
            .put("source_created_at_ms", createdMs)
            .put("modified_ms", modifiedMs)
            .put("source_size", file.length())
            .put("metadata_json", metadata.toString())
            .put("remove_source_after_prepare", removeSource)
            .put("batch_id", batch ?: JSONObject.NULL).put("batch_item_id", item ?: JSONObject.NULL)
        MobileContractV1.requireEnvelope(NativeBridgeV1.enqueue(handle, input.toString()))
    }

    internal fun createThumbnail(source: File, mediaKind: String, sourceDir: File): File? {
        val bitmap = if (mediaKind == "video") {
            ThumbnailUtils.createVideoThumbnail(source.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
        } else {
            MediaBitmapDecoder.decode(1024) { source.inputStream() }
        } ?: return null
        val scale = minOf(1f, 512f / maxOf(bitmap.width, bitmap.height).toFloat())
        var scaled = bitmap
        val output = File(sourceDir, "${UUID.randomUUID()}.thumbnail.jpg")
        try {
            if (scale < 1f) scaled = Bitmap.createScaledBitmap(bitmap,
                maxOf(1, (bitmap.width * scale).toInt()), maxOf(1, (bitmap.height * scale).toInt()), true)
            output.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 82, it)) {
                "无法编码媒体缩略图"
            } }
            check(output.length() > 0) { "媒体缩略图为空" }
            return output
        } catch (error: Exception) {
            output.delete()
            throw error
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
        }
    }

    private fun mergeAlbums(target: MutableMap<String, MutableAlbum>, source: Map<String, MutableAlbum>) {
        for ((id, album) in source) {
            target.getOrPut(id) { MutableAlbum(album.name) }.sourceAssetIds += album.sourceAssetIds
        }
    }

    private data class MutableAlbum(val name: String, val sourceAssetIds: MutableSet<String> = mutableSetOf())
    private data class CollectionResult(
        val discovered: Int,
        val bytes: Long,
        val skipped: Int,
        val albums: Map<String, MutableAlbum>,
        val error: String?,
    )
}
