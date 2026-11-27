package org.sarmg.ana

import org.json.JSONObject

internal data class UploadPhoto(
    val id: String, val batchId: String?, val name: String, val uri: String?,
    val resources: Int, val complete: Int, val originalsExpected: Int, val state: String, val error: String?,
) {
    val isCompleted get() = state == "queued" && resources > 0 && complete == resources
    val progress: Float get() = progress(0.0)
    fun progress(activeResource: Double): Float = if (isCompleted) 1f else {
        val expected = maxOf(1, resources, if (originalsExpected > 0) originalsExpected + 1 else 0)
        val active = if (activeResource.isFinite()) activeResource.coerceIn(0.0, 1.0) else 0.0
        ((complete + active) / expected).toFloat().coerceIn(0f, 0.99f)
    }
    val status get() = error ?: if (isCompleted) "已完成" else if (state == "queued") "排队中 / 上传中" else "正在准备"

    companion object {
        fun fromItem(id: String, batch: String?, item: JSONObject, automatic: Boolean = false): UploadPhoto {
            val descriptor = runCatching { JSONObject(item.getString("source")) }.getOrNull()
            val uri = if (automatic) item.optString("asset") else descriptor?.optString("uri")
            return UploadPhoto(id, batch, if (automatic) item.optString("name", "媒体") else descriptor?.optString("name", "媒体") ?: "媒体",
                uri?.takeIf { it.startsWith("content://") }, item.optInt("resources").coerceAtLeast(0),
                item.optInt("complete").coerceAtLeast(0), item.optInt("originals_expected").coerceAtLeast(0),
                item.optString("state", "pending"), listOf("error", "upload_error").firstNotNullOfOrNull { key ->
                    if (item.isNull(key)) null else item.optString(key).takeIf { it.isNotBlank() }
                })
        }
    }
}

internal fun uploadProgress(photos: List<UploadPhoto>, active: Map<String, Double> = emptyMap()): Float =
    if (photos.isEmpty()) 0f else photos.sumOf { it.progress(active[it.uri] ?: 0.0).toDouble() }.toFloat() / photos.size

internal fun downloadProgress(rows: List<DownloadTransfers.Entry>): Float {
    val total = rows.sumOf { maxOf(1L, it.total).toDouble() }
    if (total == 0.0) return 0f
    return (rows.sumOf { row ->
        val weight = maxOf(1L, row.total).toDouble()
        weight * if (row.isCompleted) 1.0 else (row.bytes / weight).coerceIn(0.0, 0.99)
    } / total).toFloat()
}
