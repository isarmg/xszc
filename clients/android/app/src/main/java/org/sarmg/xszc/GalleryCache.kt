package org.sarmg.xszc

import android.content.Context
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Every field is a server-side filter, included in the persisted query identity. */
data class CloudFilters(val kind: String? = null, val from: Long? = null, val to: Long? = null, val device: String? = null,
    val archived: Boolean? = null, val tag: String? = null) {
    fun params(): Map<String, String> = buildMap {
        kind?.let { put("media_kind", it) }; from?.let { put("from_ms", it.toString()) }
        to?.let { put("to_ms", it.toString()) }; device?.let { put("device_id", it) }
        archived?.let { put("archived", it.toString()) }; tag?.let { put("tag_id", it) }
    }
}
internal object GalleryCache {
    fun page(context: Context, profile: String, api: BackupApi, cursor: String?, trash: Boolean, favorite: Boolean,
        album: String?, filters: CloudFilters): RemoteLibrary.Page {
        val handle = TransferStore.open(context, profile).handle
        val key = JSONObject().put("trash", trash).put("favorite", favorite).put("album", album ?: JSONObject.NULL)
            .put("filters", JSONObject(filters.params())).toString()
        val fields = JSONObject().put("query_key", key).put("cursor", cursor ?: JSONObject.NULL)
        var cached = false
        val page = try {
            api.timeline(cursor, trash, favorite, album, filters).also {
                TransferStore.gallery(handle, "save_page", JSONObject(fields.toString()).put("items", it.getJSONArray("items"))
                    .put("next_cursor", it.opt("next_cursor") ?: JSONObject.NULL))
            }
        } catch (error: Exception) {
            cached = true
            TransferStore.gallery(handle, "read_page", fields) as? JSONObject ?: throw error
        }
        val items = page.getJSONArray("items")
        val next = page.optString("next_cursor").takeIf { it.isNotEmpty() && it != "null" }
        check(next == null || next != cursor) { "分页游标重复" }
        return RemoteLibrary.Page((0 until items.length()).map { RemoteLibrary.parseAsset(items.getJSONObject(it)) }, next, cached)
    }

    // Snapshot captures its watermark BEFORE walking stable UUIDs. Events after that watermark
    // reconcile inserts behind the cursor, updates and deletes. Each page survives process death.
    suspend fun synchronize(context: Context, profile: String, api: BackupApi): Boolean {
        val h = TransferStore.open(context, profile).handle
        var state = TransferStore.gallery(h, "state") as? JSONObject
        if (state == null) {
            val head = api.get("/v1/sync/head")!!
            check(head.getString("snapshot_protocol") == "watermark-before-uuid-walk-v1")
            TransferStore.gallery(h, "begin_snapshot", JSONObject().put("sequence", head.getLong("sequence")))
            state = TransferStore.gallery(h, "state") as JSONObject
        }
        var changed = false
        repeat(10) {
            currentCoroutineContext().ensureActive()
            if (!state!!.getBoolean("snapshot_complete")) {
                val cursor = state!!.optString("snapshot_cursor").takeIf { it.isNotEmpty() && it != "null" }
                val page = api.get("/v1/library/snapshot?limit=100" + (cursor?.let { "&cursor=$it" } ?: ""))!!
                TransferStore.gallery(h, "snapshot_page", JSONObject().put("cursor", cursor ?: JSONObject.NULL)
                    .put("items", page.getJSONArray("items")).put("next_cursor", page.opt("next_cursor") ?: JSONObject.NULL))
            } else {
                val sequence = state!!.getLong("sequence")
                val page = api.sync(sequence)
                val events = page.getJSONArray("events")
                val applied = JSONArray()
                for (i in 0 until events.length()) {
                    currentCoroutineContext().ensureActive()
                    val event = events.getJSONObject(i)
                    val asset = if (event.getString("entity_kind") == "asset") api.get("/v1/assets/${event.getString("entity_id")}", true) else null
                    applied.put(JSONObject().put("event", event).put("asset", asset ?: JSONObject.NULL))
                }
                TransferStore.gallery(h, "apply_events", JSONObject().put("expected_sequence", sequence)
                    .put("next_sequence", page.getLong("next_sequence")).put("events", applied))
                changed = changed || events.length() > 0
                if (!page.getBoolean("has_more")) return changed
            }
            state = TransferStore.gallery(h, "state") as JSONObject
        }
        return changed
    }
}
