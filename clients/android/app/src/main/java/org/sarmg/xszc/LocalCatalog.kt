package org.sarmg.xszc

import android.Manifest
import android.content.Context
import android.content.ContentUris
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.database.Cursor
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal object LocalCatalog {
    const val PAGE_SIZE = 150
    data class Page(val rows: List<JSONObject>, val hasMore: Boolean)
    fun permissions() = arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    fun hasAccess(context: Context): Boolean = permissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
    fun hasFullAccess(context: Context): Boolean =
        listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
            .all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    data class Scan(val albums: Map<String, String>, val sources: Set<String>, val changes: Set<String>? = null)
    fun scan(context: Context, h: Long): Scan {
        check(permissions().any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) {
            "需要重新授权访问本地照片"
        }
        TransferStore.gallery(h, "begin_catalog")
        val albums = mutableMapOf<String, String>()
        val sources = mutableSetOf<String>()
        for ((collection, kind) in listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "photo", MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video")) {
            val permission = if (kind == "photo") Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_MEDIA_VIDEO
            if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED) continue
            val columns = arrayOf("_id", "_display_name", "_size", "date_modified", "date_added", "datetaken", "bucket_id", "bucket_display_name")
            val cursor = context.contentResolver.query(collection, columns, null, null, null)
                ?: error("无法读取系统媒体库，请稍后重试")
            cursor.use { c ->
                var page = JSONArray()
                while (c.moveToNext()) {
                    val source = ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                    sources += source
                    albums[c.getString(6) ?: ""] = c.getString(7) ?: "其他相册"
                    page.put(catalogRow(c, source, kind))
                    if (page.length() == 200) { TransferStore.gallery(h, "catalog", JSONObject().put("items", page)); page = JSONArray() }
                }
                if (page.length() > 0) TransferStore.gallery(h, "catalog", JSONObject().put("items", page))
            }
        }
        TransferStore.gallery(h, "finish_catalog")
        return Scan(albums, sources)
    }
    private fun catalogRow(c: Cursor, source: String, kind: String): JSONObject {
        val name = c.getString(1) ?: "媒体"
        val modified = c.getLong(3) * 1000
        val created = c.getLong(5).takeIf { it > 0 } ?: (c.getLong(4) * 1000)
        val descriptor = JSONObject().put("uri", source).put("source_id", source).put("modified_ms", modified).put("name", name)
        return JSONObject().put("source_id", source).put("name", name).put("media_kind", kind).put("album_id", c.getString(6) ?: "")
            .put("created_ms", created).put("modified_ms", modified).put("size", c.getLong(2).coerceAtLeast(0)).put("descriptor", descriptor.toString())
    }
    private fun incremental(context: Context, h: Long, since: Long): Scan {
        val previous = index(h, null, null, false).map { it.getString("source_id") }.toSet()
        val sources = mutableSetOf<String>()
        val albums = mutableMapOf<String, String>()
        val changes = mutableSetOf<String>()
        val collections = listOf(MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "photo", MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "video")
        // The small ID/bucket walk reconciles deletions; full metadata is read only for changed rows.
        for ((collection, _) in collections) {
            val cursor = context.contentResolver.query(collection, arrayOf("_id", "bucket_id", "bucket_display_name"), null, null, null)
                ?: error("无法读取系统媒体库，请稍后重试")
            cursor.use { c -> while (c.moveToNext()) {
                sources += ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                albums[c.getString(1) ?: ""] = c.getString(2) ?: "其他相册"
            } }
        }
        val columns = arrayOf("_id", "_display_name", "_size", "date_modified", "date_added", "datetaken", "bucket_id", "bucket_display_name")
        for ((collection, kind) in collections) {
            val cursor = context.contentResolver.query(collection, columns, "${MediaStore.MediaColumns.GENERATION_MODIFIED} > ?", arrayOf(since.toString()), null)
                ?: error("无法读取系统媒体变更，请稍后重试")
            cursor.use { c ->
                var items = JSONArray()
                while (c.moveToNext()) {
                    val id = ContentUris.withAppendedId(collection, c.getLong(0)).toString()
                    if (id !in sources) continue
                    changes += id
                    items.put(catalogRow(c, id, kind))
                    if (items.length() == 200) {
                        TransferStore.gallery(h, "patch_catalog", JSONObject().put("items", items).put("removed_source_ids", JSONArray()))
                        items = JSONArray()
                    }
                }
                if (items.length() > 0) TransferStore.gallery(h, "patch_catalog", JSONObject().put("items", items).put("removed_source_ids", JSONArray()))
            }
        }
        val removed = previous - sources
        removed.chunked(200).forEach { ids ->
            TransferStore.gallery(h, "patch_catalog", JSONObject().put("items", JSONArray()).put("removed_source_ids", JSONArray(ids)))
        }
        return Scan(albums, sources, changes + removed)
    }
    fun page(h: Long, album: String?, kind: String?, unbacked: Boolean, offset: Int = 0, limit: Int = PAGE_SIZE): List<JSONObject> {
        val rows = TransferStore.gallery(h, "local_page", JSONObject().put("album", album ?: JSONObject.NULL).put("media_kind", kind ?: JSONObject.NULL)
            .put("unbacked", unbacked).put("offset", offset).put("limit", limit)) as JSONArray
        return (0 until rows.length()).map { rows.getJSONObject(it) }
    }
    fun window(h: Long, album: String?, kind: String?, unbacked: Boolean, offset: Int = 0, count: Int = PAGE_SIZE): Page {
        require(offset >= 0 && count > 0)
        val rows = mutableListOf<JSONObject>()
        while (rows.size < count) {
            val limit = minOf(PAGE_SIZE, count - rows.size)
            // Look ahead without consuming the first row of the following page.
            val fetched = page(h, album, kind, unbacked, offset + rows.size, limit + 1)
            rows += fetched.take(limit)
            if (fetched.size <= limit) return Page(rows, false)
        }
        return Page(rows, true)
    }
    fun index(h: Long, album: String?, kind: String?, unbacked: Boolean): List<JSONObject> {
        val rows = TransferStore.gallery(h, "local_index", JSONObject().put("album", album ?: JSONObject.NULL)
            .put("media_kind", kind ?: JSONObject.NULL).put("unbacked", unbacked)) as JSONArray
        return (0 until rows.length()).map(rows::getJSONObject)
    }
    fun items(h: Long, ids: List<String>): List<JSONObject> {
        if (ids.isEmpty()) return emptyList()
        require(ids.size <= 1000)
        val rows = TransferStore.gallery(h, "local_items", JSONObject().put("source_ids", JSONArray(ids))) as JSONArray
        return (0 until rows.length()).map(rows::getJSONObject)
    }
    fun cachedAlbums(context: Context, profile: String): Map<String, String> {
        val raw = context.getSharedPreferences("local_catalog_sync", Context.MODE_PRIVATE).getString("albums.$profile", null)
            ?: return emptyMap()
        return runCatching { val json = JSONObject(raw); json.keys().asSequence().associateWith(json::getString) }.getOrDefault(emptyMap())
    }
    /** A MediaStore version reset or permission change needs reconciliation. */
    fun refresh(context: Context, h: Long, profile: String): Scan? {
        val preferences = context.getSharedPreferences("local_catalog_sync", Context.MODE_PRIVATE)
        val permissionKey = permissions().joinToString { ContextCompat.checkSelfPermission(context, it).toString() }
        val versions = MediaStore.getExternalVolumeNames(context).sorted().associateWith {
            MediaStore.getVersion(context, it) to MediaStore.getGeneration(context, it)
        }
        val signature = JSONObject().put("permissions", permissionKey).put("volumes", JSONObject().apply {
            versions.forEach { (volume, value) -> put(volume, JSONObject().put("version", value.first).put("generation", value.second)) }
        }).toString()
        // Partial photo permission can change its selection without a MediaStore generation change.
        val fullAccess = hasFullAccess(context)
        val previous = preferences.getString("signature.$profile", null)
        if (versions.isNotEmpty() && fullAccess && previous == signature) return null
        val old = previous?.let { runCatching { JSONObject(it) }.getOrNull() }
        val oldVolumes = old?.optJSONObject("volumes")
        val canIncrement = fullAccess && versions.isNotEmpty() && old?.optString("permissions") == permissionKey &&
            oldVolumes != null && oldVolumes.keys().asSequence().toSet() == versions.keys &&
            versions.all { (volume, value) -> oldVolumes.optJSONObject(volume)?.optString("version") == value.first }
        val result = if (canIncrement) {
            val since = versions.keys.minOf { oldVolumes!!.getJSONObject(it).getLong("generation") }
            incremental(context, h, since)
        } else scan(context, h)
        // Save the watermark observed before scanning; a concurrent change will be picked up next time.
        preferences.edit().putString("signature.$profile", signature).putString("albums.$profile", JSONObject(result.albums).toString()).apply()
        return result
    }
    fun persist(h: Long, selected: List<JSONObject>) {
        selected.chunked(1000).forEach { chunk ->
            TransferStore.command(h, "create_batch", JSONObject().put("id", UUID.randomUUID().toString()).put("items",
                JSONArray(chunk.map { JSONObject().put("id", UUID.randomUUID().toString()).put("source", it.getString("descriptor")) })))
        }
    }
    fun status(value: String): String = when(value) {
        "complete" -> "已备份"; "original_complete" -> "原件完成，缩略图待重试"; "queued" -> "排队中"
        "uploading" -> "上传中"; "failed" -> "备份失败"; else -> "状态待确认"
    }
}
