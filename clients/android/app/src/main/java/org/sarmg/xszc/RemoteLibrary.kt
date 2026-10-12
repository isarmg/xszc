package org.sarmg.xszc

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

data class RemoteResource(
    val id: String,
    val role: String,
    val filename: String,
    val mimeType: String,
    val contentPath: String,
    val contentSize: Long,
    val metadata: JSONObject?,
)

data class RemoteAsset(
    val id: String,
    val createdAtMs: Long,
    val mediaKind: String,
    val favorite: Boolean,
    val archived: Boolean,
    val trashed: Boolean,
    val tagNames: List<String>,
    val resources: List<RemoteResource>,
)

object RemoteLibrary {
    data class Page(val items: List<RemoteAsset>, val nextCursor: String?, val cached: Boolean = false)

    suspend fun restore(context: Context, api: BackupApi, asset: RemoteAsset, profile: String,
        transferId: String? = null, checkCurrent: () -> Unit = {}): String {
        val transfer = transferId ?: DownloadTransfers.start(profile,
            asset.resources.firstOrNull { it.role == "primary" }?.filename ?: "媒体",
            asset.resources.firstOrNull { it.role == "primary" }?.contentSize ?: 0)
        var temporary: File? = null
        var destination: Uri? = null
        try {
            checkCurrent()
            val resource = asset.resources.firstOrNull { it.role == "primary" }
                ?: asset.resources.firstOrNull { it.role != "thumbnail" }
                ?: error("该资产没有可恢复的原始资源")
            val manifest = api.manifest(resource.id)
            check(manifest.getString("resource_id") == resource.id &&
                manifest.getLong("content_size") == resource.contentSize &&
                manifest.getString("content_path") == resource.contentPath &&
                manifest.getString("storage_encoding") == "plain-v1") { "云端资源已更新，请刷新图库后重试" }
            val expectedHash = manifest.getString("content_blake3")
            check(expectedHash.matches(Regex("[0-9a-f]{64}"))) { "服务器返回无效的资源校验值" }
            check(resource.contentSize >= 0 &&
                resource.contentSize <= (context.cacheDir.usableSpace - 128L * 1024 * 1024).coerceAtLeast(0)) {
                "暂存空间不足，无法验证原件"
            }
            checkCurrent()
            val downloaded = File.createTempFile("xszc-restore-", ".part", context.cacheDir)
            temporary = downloaded
            downloaded.outputStream().use { output ->
                var bytes = 0L; var lastUpdate = System.nanoTime()
                api.downloadCancellable(resource.contentPath, object : java.io.FilterOutputStream(output) {
                    override fun write(buffer: ByteArray, off: Int, len: Int) {
                        checkCurrent()
                        bytes += len
                        check(bytes <= resource.contentSize) { "原件下载超过预期长度" }
                        out.write(buffer, off, len)
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 100_000_000) { DownloadTransfers.update(transfer, bytes); lastUpdate = now }
                    }
                    override fun write(value: Int) {
                        checkCurrent()
                        bytes++
                        check(bytes <= resource.contentSize) { "原件下载超过预期长度" }
                        out.write(value)
                    }
                })
                check(bytes == resource.contentSize) { "原件下载长度不匹配" }
                DownloadTransfers.update(transfer, bytes)
            }
            check(NativeBridgeV1.verifyFileBlake3(downloaded.absolutePath, resource.contentSize, expectedHash)) {
                "原件校验失败，请重新下载"
            }
            checkCurrent()
            val collection = if (resource.mimeType.startsWith("video/")) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, resource.filename)
                put(MediaStore.MediaColumns.MIME_TYPE, resource.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (resource.mimeType.startsWith("video/")) "Movies/Xszc Restored" else "Pictures/Xszc Restored")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            checkCurrent()
            val uri = context.contentResolver.insert(collection, values) ?: error("无法创建恢复文件")
            destination = uri
            context.contentResolver.openOutputStream(uri)?.use { output ->
                var copied = 0L
                downloaded.inputStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        checkCurrent()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count); copied += count
                    }
                }
                check(copied == resource.contentSize) { "写入手机的文件长度不匹配" }
            } ?: error("无法打开恢复文件")
            checkCurrent()
            check(context.contentResolver.update(uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "无法完成恢复文件"
            }
            destination = null
            DownloadTransfers.update(transfer, bytes = resource.contentSize, phase = "已保存到手机")
            return resource.filename
        } catch (error: Exception) {
            DownloadTransfers.update(transfer, phase = if (error is kotlinx.coroutines.CancellationException) "已取消" else "下载失败：${error.message}")
            destination?.let { runCatching { context.contentResolver.delete(it, null, null) } }
            throw error
        } finally {
            temporary?.delete()
        }
    }

    internal fun parseAsset(value: JSONObject): RemoteAsset {
        val resources = value.getJSONArray("resources")
        return RemoteAsset(
            id = value.getString("asset_id"),
            mediaKind = value.getString("media_kind"),
            createdAtMs = value.getLong("source_created_at_ms"),
            favorite = value.getBoolean("favorite"),
            archived = value.getBoolean("archived"),
            trashed = !value.isNull("trashed_at_ms"),
            tagNames = buildList {
                val tags = value.optJSONArray("tag_names") ?: org.json.JSONArray()
                for (position in 0 until tags.length()) add(tags.getString(position))
            },
            resources = buildList {
                for (position in 0 until resources.length()) {
                    val resource = resources.getJSONObject(position)
                    check(resource.getString("storage_encoding") == "plain-v1") {
                        "服务器返回了非当前存储协议"
                    }
                    add(
                        RemoteResource(
                            id = resource.getString("resource_id"),
                            role = resource.getString("role"),
                            filename = resource.getString("filename"),
                            mimeType = resource.getString("mime_type"),
                            contentPath = resource.getString("content_path"),
                            contentSize = resource.getLong("content_size"),
                            metadata = resource.optJSONObject("metadata"),
                        ),
                    )
                }
            },
        )
    }
}
