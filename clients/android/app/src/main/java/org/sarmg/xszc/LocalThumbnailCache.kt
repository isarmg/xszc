package org.sarmg.xszc

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.CancellationSignal
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import java.util.concurrent.Executors

internal object LocalThumbnailCache {
    private const val BUDGET = 32 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(BUDGET) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val permits = Semaphore(3)
    private val decoders = Executors.newFixedThreadPool(3) { task -> Thread(task, "gallery-thumbnail").apply { isDaemon = true } }
    private var registered = false
    var revision by mutableIntStateOf(0)
        private set
    private var cacheGeneration = 0
    @Synchronized private fun register(context: Context) {
        if (registered) return
        registered = true
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onConfigurationChanged(configuration: Configuration) {}
            override fun onLowMemory() { clearMemory() }
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cache.trimToSize(BUDGET / 2)
                if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) clearMemory()
            }
        })
    }
    @Synchronized private fun clearMemory() { cacheGeneration++; cache.evictAll() }
    @Synchronized fun invalidate(ids: Set<String>? = null) {
        revision++; cacheGeneration++
        if (ids == null) cache.evictAll()
        else cache.snapshot().keys.filter { it.substringBeforeLast(':').substringBeforeLast(':') in ids }.forEach(cache::remove)
    }

    suspend fun image(context: Context, id: String, kind: String, modified: Long, size: Int, preview: Boolean = false): Bitmap? {
        register(context)
        val key = "$id:$modified:$size"
        if (!preview) cache.get(key)?.let { return it }
        return permits.withPermit {
            currentCoroutineContext().ensureActive()
            if (!preview) cache.get(key)?.let { return@withPermit it }
            val generation = synchronized(this) { cacheGeneration }
            val bitmap = suspendCancellableCoroutine<Bitmap?> { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                decoders.execute {
                    if (!continuation.isActive) return@execute
                    val result = runCatching {
                        val uri = Uri.parse(id)
                        if (!preview) context.contentResolver.loadThumbnail(uri, Size(size, size), signal)
                        else if (kind == "video") {
                            val retriever = MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(context, uri)
                                val frame = retriever.getFrameAtTime(0) ?: return@runCatching null
                                val scale = minOf(1f, size.toFloat() / maxOf(frame.width, frame.height))
                                if (scale == 1f) frame else Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt().coerceAtLeast(1),
                                    (frame.height * scale).toInt().coerceAtLeast(1), true).also { frame.recycle() }
                            } finally { retriever.release() }
                        } else {
                            MediaBitmapDecoder.decode(size * 2) {
                                signal.throwIfCanceled()
                                context.contentResolver.openInputStream(uri)
                            }
                        }
                    }.getOrNull()
                    continuation.resume(result)
                }
            }
            currentCoroutineContext().ensureActive()
            if (!preview && bitmap != null) synchronized(this) {
                if (generation == cacheGeneration) cache.put(key, bitmap)
            }
            bitmap
        }
    }
    suspend fun prefetch(context: Context, entries: List<LocalGalleryEntry>, size: Int) = coroutineScope {
        // Visible loads use the same semaphore/cache; only a nearby window is queued.
        entries.forEach { entry -> launch { image(context, entry.id, entry.kind, entry.modified, size) } }
    }
}

@Composable
internal fun LocalMediaImage(context: Context, id: String, name: String, kind: String, modified: Long,
    preview: Boolean, modifier: Modifier, thumbnailSize: Int) {
    val size = if (preview) 2048 else thumbnailSize
    val revision = LocalThumbnailCache.revision
    var bitmap by remember(id, modified, preview, size) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(id, modified, preview, size, revision) { bitmap = LocalThumbnailCache.image(context, id, kind, modified, size, preview) }
    Box(modifier.background(if (preview) androidx.compose.ui.graphics.Color.Black else MaterialTheme.colorScheme.surfaceVariant)) {
        bitmap?.let { Image(it.asImageBitmap(), name, Modifier.fillMaxSize(), contentScale = if (preview) ContentScale.Fit else ContentScale.Crop) }
    }
}
