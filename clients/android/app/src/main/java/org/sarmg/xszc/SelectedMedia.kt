package org.sarmg.xszc

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal object SelectedMedia {
    // Reserve room for chunk preparation as well as the source. Process one item at a time.
    private const val MAX_SOURCE_BYTES = 2L * 1024 * 1024 * 1024

    internal data class MediaVersion(val modifiedMs: Long, val generation: Long)

    internal fun readVersion(cursor: Cursor): MediaVersion {
        check(cursor.moveToFirst()) { "所选媒体已不可访问，请重新选择" }
        val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
        val generationColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED)
        check(!cursor.isNull(modifiedColumn) && !cursor.isNull(generationColumn)) {
            "无法读取所选媒体版本，请重试"
        }
        return MediaVersion(cursor.getLong(modifiedColumn) * 1000L, cursor.getLong(generationColumn))
    }

    fun prepare(context: Context, handle: Long, staging: File, batch: String, item: JSONObject) {
        val descriptor = JSONObject(item.getString("source"))
        val uri = Uri.parse(descriptor.getString("uri"))
        val resolver = context.contentResolver
        var name = "media"
        var size = -1L
        // A queued selection stores an identity, not a frozen copy of the media.
        // Bind the bytes read now to the current version, never the selection-time version.
        val version = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE,
            MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use {
            val current = readVersion(it)
            name = it.getString(0) ?: name
            if (!it.isNull(1)) size = it.getLong(1)
            current
        } ?: error("无法读取所选媒体版本，请重新选择")
        val modifiedMs = version.modifiedMs
        val mime = resolver.getType(uri) ?: error("无法读取媒体类型，需要重新选择")
        require(mime.startsWith("image/") || mime.startsWith("video/")) { "请选择照片或视频" }
        val sourceDir = File(staging, "sources").also { check(it.mkdirs() || it.isDirectory) }
        val budget = minOf(MAX_SOURCE_BYTES, (sourceDir.usableSpace - 128L * 1024 * 1024) / 2)
        check(budget > 0 && (size < 0 || size <= budget)) { "暂存空间不足；单项上限 2 GiB，请释放空间后重试" }
        val output = File(sourceDir, UUID.randomUUID().toString())
        var primaryQueued = false
        try {
            resolver.openInputStream(uri)?.use { input ->
                output.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        check(copied <= budget) { "媒体超过暂存预算" }
                        target.write(buffer, 0, n)
                    }
                }
            } ?: error("需要重新授权访问所选媒体")
            val source = descriptor.getString("source_id")
            check(source.isNotBlank()) { "所选项目缺少图库来源身份" }
            // Reject edits/replacements during the copy before linking or declaring resources.
            resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.GENERATION_MODIFIED), null, null, null)?.use {
                check(readVersion(it) == version) {
                    "所选媒体在准备期间发生变化，请重试"
                }
            } ?: error("无法确认所选媒体版本，请重试")
            TransferStore.gallery(handle, "declare_resources", JSONObject().put("source_id", source).put("modified_ms", modifiedMs).put("originals", 1))
            var createdMs = System.currentTimeMillis()
            runCatching {
                resolver.query(uri, arrayOf("datetaken", "date_added"), null, null, null)?.use {
                    if (it.moveToFirst()) createdMs = if (!it.isNull(0) && it.getLong(0) > 0) it.getLong(0) else it.getLong(1) * 1000
                }
            }
            val kind = if (mime.startsWith("video/")) "video" else "photo"
            val scanner = MediaScanner(context)
            val itemId = item.getString("id")
            fun linked(resource: String, sourceSize: Long? = null): Boolean {
                val request = JSONObject().put("batch_id", batch).put("item_id", itemId)
                    .put("asset", source).put("resource", resource).put("modified_ms", modifiedMs)
                if (sourceSize != null) request.put("source_size", sourceSize)
                return TransferStore.command(handle, "link_resource", request) == true
            }
            if (!linked(source, output.length())) {
                scanner.enqueue(handle, source, source, kind, "primary", output, name, mime,
                    createdMs, modifiedMs, JSONObject(), true, batch, itemId)
                primaryQueued = true
            }
            val thumbnailId = "$source#thumbnail-v1"
            if (primaryQueued || !linked(thumbnailId)) {
                scanner.createThumbnail(output, kind, sourceDir)?.let { thumbnail ->
                    if (primaryQueued || !linked(thumbnailId, thumbnail.length())) {
                        try {
                            scanner.enqueue(handle, source, thumbnailId, kind, "thumbnail", thumbnail, "$name.thumbnail.jpg", "image/jpeg",
                                createdMs, modifiedMs, JSONObject().put("thumbnail_of", source)
                                    .put("source_size", output.length()), true, batch, itemId)
                        } catch (error: Exception) {
                            thumbnail.delete()
                            throw error
                        }
                    } else thumbnail.delete()
                }
            }
            TransferStore.setItem(handle, batch, itemId, "queued")
        } finally {
            if (!primaryQueued) output.delete()
        }
    }
}
