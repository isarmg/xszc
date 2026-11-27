package org.sarmg.ana

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FilterOutputStream
import java.util.UUID

internal object RemoteImageCache {
    private val requests = Semaphore(3)
    private val diskLock = Any()
    private var generation = 0L
    private val idPattern = Regex("[0-9a-fA-F-]{36}")
    private const val THUMBNAIL_BUDGET = 8L * 1024 * 1024
    private val memory = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun setLimit(context: Context, mib: Int) {
        val value = mib.coerceIn(64, 1024)
        context.getSharedPreferences("gallery-cache", 0).edit().putInt("limit_mib", value).apply()
        withContext(Dispatchers.IO) { synchronized(diskLock) { trim(context) } }
    }

    fun limit(context: Context): Int = context.getSharedPreferences("gallery-cache", 0)
        .getInt("limit_mib", 256).coerceIn(64, 1024)

    private fun budget(context: Context): Long = limit(context) * 1024L * 1024

    private fun directories(context: Context): List<File> =
        context.cacheDir.listFiles()?.filter { it.isDirectory && it.name.startsWith("gallery-") }.orEmpty()

    fun clear(context: Context) = synchronized(diskLock) {
        generation++
        memory.evictAll()
        directories(context).forEach { it.deleteRecursively() }
    }

    private fun trim(context: Context, keep: File? = null) {
        val maximum = budget(context)
        val files = directories(context).flatMap { directory ->
            directory.listFiles()?.filter { it.isFile && it.extension != "part" }.orEmpty()
        }
        var size = files.sumOf(File::length)
        if (size <= maximum) return
        for (old in files.sortedBy(File::lastModified)) {
            if (size <= maximum) break
            if (old == keep) continue
            val bytes = old.length()
            if (old.delete()) size -= bytes
        }
    }

    suspend fun load(context: Context, api: BackupApi, profile: String, resource: RemoteResource, preview: Boolean): Bitmap? {
        val stamp = synchronized(diskLock) { generation }
        return requests.withPermit {
            withContext(Dispatchers.IO) {
                fun checkCache() { if (stamp != generation) throw CancellationException("浏览缓存已清空") }
                val suffix = if (preview) "preview-v1" else "thumbnail"
                val filename = "${resource.cacheIdentity}-$suffix"
                val key = "$profile/$filename"
                require(idPattern.matches(resource.id))
                val root = File(context.cacheDir, "gallery-$profile")
                val file = File(root, filename)
                synchronized(diskLock) {
                    checkCache()
                    memory.get(key)?.let { return@withContext it }
                    check(root.mkdirs() || root.isDirectory) { "无法创建浏览缓存" }
                }
                var downloaded = false
                if (!file.exists()) {
                    val temporary = File(root, "${UUID.randomUUID()}.part")
                    try {
                        if (!preview || !downloadPreview(api, resource, temporary)) {
                            val limit = if (preview) budget(context) else THUMBNAIL_BUDGET
                            require(resource.contentSize in 0..limit) { "原件过大，使用保存到手机下载" }
                            download(api, resource, temporary, limit, preview = false)
                        }
                        synchronized(diskLock) {
                            checkCache()
                            if (!file.exists()) {
                                check(temporary.length() <= budget(context)) { "图片超过缓存上限" }
                                check(temporary.renameTo(file)) { "无法保存预览缓存" }
                                downloaded = true
                            }
                        }
                    } finally { temporary.delete() }
                }
                val bitmap = MediaBitmapDecoder.decode(if (preview) 2048 else 512) { file.inputStream() }
                synchronized(diskLock) {
                    checkCache()
                    if (bitmap == null) { file.delete(); return@withContext null }
                    file.setLastModified(System.currentTimeMillis())
                    memory.put(key, bitmap)
                    if (downloaded) trim(context, keep = file)
                }
                bitmap
            }
        }
    }

    private suspend fun downloadPreview(api: BackupApi, resource: RemoteResource, file: File): Boolean = try {
        download(api, resource, file, THUMBNAIL_BUDGET, preview = true)
        true
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { file.delete(); false }

    private suspend fun download(api: BackupApi, resource: RemoteResource, file: File, maximum: Long, preview: Boolean) {
        val path = if (preview) "/v1/resources/${resource.id}/preview" else resource.contentPath
        file.outputStream().use { output ->
            api.downloadCancellable(path, object : FilterOutputStream(output) {
                private var bytes = 0L
                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    bytes += length
                    check(bytes <= maximum) { "图片超过缓存上限" }
                    out.write(buffer, offset, length)
                }
                override fun write(value: Int) {
                    check(++bytes <= maximum) { "图片超过缓存上限" }
                    out.write(value)
                }
            }) { etag -> resource.acceptsETag(etag, preview) }
        }
    }
}
