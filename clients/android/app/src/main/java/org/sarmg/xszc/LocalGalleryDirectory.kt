package org.sarmg.xszc

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Only small directory records stay resident; bitmap and receipt caches are bounded. */
internal data class LocalGalleryEntry(
    val id: String, val name: String, val kind: String, val created: Long, val modified: Long, val day: String,
)
internal sealed interface LocalGalleryGridItem {
    val key: String
    data class Day(val day: String) : LocalGalleryGridItem {
        override val key = "day-$day"
    }
    data class Media(val entry: LocalGalleryEntry) : LocalGalleryGridItem {
        override val key = entry.id
    }
}
internal data class LocalGalleryDirectory(
    val entries: List<LocalGalleryEntry> = emptyList(),
    val grid: List<LocalGalleryGridItem> = emptyList(),
    val positions: Map<String, Int> = emptyMap(),
    val days: Map<String, List<LocalGalleryEntry>> = emptyMap(),
) {
    companion object {
        fun from(rows: List<JSONObject>): LocalGalleryDirectory {
            val entries = rows.map { row ->
                val created = row.getLong("created_ms")
                LocalGalleryEntry(row.getString("source_id"), row.getString("name"), row.getString("media_kind"),
                    created, row.getLong("modified_ms"), Instant.ofEpochMilli(created).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE))
            }
            val grid = buildList {
                var previous: String? = null
                entries.forEach { entry ->
                    if (entry.day != previous) { add(LocalGalleryGridItem.Day(entry.day)); previous = entry.day }
                    add(LocalGalleryGridItem.Media(entry))
                }
            }
            return LocalGalleryDirectory(entries, grid, entries.mapIndexed { index, entry -> entry.id to index }.toMap(), entries.groupBy { it.day })
        }
    }
}

internal class LocalGalleryDetails {
    companion object {
        const val LIMIT = 768
        const val PRELOAD = 96
    }
    var rows by mutableStateOf<Map<String, JSONObject>>(emptyMap())
        private set
    private val cache = LinkedHashMap<String, JSONObject>(LIMIT, 0.75f, true)
    private var revision = 0
    fun clear() { revision++; cache.clear(); rows = emptyMap() }

    suspend fun warm(context: Context, profile: String, directory: LocalGalleryDirectory, first: Int, last: Int) {
        if (directory.entries.isEmpty()) return
        val generation = revision
        val start = (first - PRELOAD).coerceAtLeast(0)
        val end = (last + PRELOAD + 1).coerceAtMost(directory.entries.size).coerceAtMost(start + LIMIT)
        val wanted = directory.entries.subList(start, end)
        val missing = wanted.map { it.id }.filter { cache[it] == null }
        val fetched = if (missing.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
            LocalCatalog.items(TransferStore.open(context, profile).handle, missing)
        }
        if (generation != revision) return
        fetched.forEach { cache[it.getString("source_id")] = it }
        wanted.forEach { cache[it.id] }
        while (cache.size > LIMIT) cache.remove(cache.keys.first())
        rows = cache.toMap()
    }

    suspend fun resolve(context: Context, profile: String, entry: LocalGalleryEntry): JSONObject {
        rows[entry.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            LocalCatalog.items(TransferStore.open(context, profile).handle, listOf(entry.id)).firstOrNull()
                ?: error("此项目已不可访问，请刷新图库")
        }
    }
}
