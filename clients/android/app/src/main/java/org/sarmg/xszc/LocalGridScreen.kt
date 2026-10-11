package org.sarmg.xszc

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext
import org.json.JSONObject

private data class LocalGalleryQuery(val profile: String, val album: String?, val kind: String?, val unbacked: Boolean)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LocalGalleryScreen(context: Context, config: SecureConfig, profile: String, onSubmitted: () -> Unit, onLogin: () -> Unit) {
    val scope = rememberCoroutineScope()
    var directory by remember(profile) { mutableStateOf(LocalGalleryDirectory()) }
    val details = remember(profile) { LocalGalleryDetails() }
    val gridState = rememberLazyGridState()
    var selection by remember(profile) { mutableStateOf<Map<String, JSONObject>>(emptyMap()) }
    var selecting by remember(profile) { mutableStateOf(false) }
    var selectionGeneration by remember(profile) { mutableIntStateOf(0) }
    var entryGeneration by remember(profile) { mutableIntStateOf(0) }
    var previewGeneration by remember(profile) { mutableIntStateOf(0) }
    val galleryPreferences = remember { context.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE) }
    var gridColumns by rememberSaveable(profile) { mutableIntStateOf(galleryPreferences.getInt("columns", 3).coerceIn(1, 8)) }
    var albums by remember(profile) { mutableStateOf(LocalCatalog.cachedAlbums(context, profile)) }
    var album by remember(profile) { mutableStateOf<String?>(null) }
    var kind by remember(profile) { mutableStateOf<String?>(null) }
    var unbacked by remember(profile) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    var menu by remember { mutableStateOf(false) }
    var loadedQuery by remember(profile) { mutableStateOf<LocalGalleryQuery?>(null) }
    var queuedRefresh by remember { mutableStateOf(false) }
    var queuedScan by remember { mutableStateOf(false) }
    val hasFilters = album != null || kind != null || unbacked
    val needsPairing = !config.isLoggedIn
    val density = LocalDensity.current.density
    val thumbnailSize = (((LocalConfiguration.current.screenWidthDp - 32) * density / gridColumns / 64).roundToInt() * 64).coerceIn(64, 512)
    fun resetSelection() {
        selectionGeneration++; entryGeneration++; previewGeneration++
        selection = emptyMap(); selecting = false
    }
    fun setPreview(row: JSONObject?) {
        if (row == null) previewGeneration++
        preview = row
    }
    fun toggle(row: JSONObject) {
        selectionGeneration++
        val id = row.getString("source_id")
        selection = if (id in selection) selection - id else selection + (id to row)
    }
    fun load(scan: Boolean = false) {
        if (busy) { queuedRefresh = true; queuedScan = queuedScan || scan; return }
        val query = LocalGalleryQuery(profile, album, kind, unbacked)
        val changingFilter = query != loadedQuery
        if (changingFilter) entryGeneration++
        busy = true
        scope.launch {
            try {
                if (!LocalCatalog.hasAccess(context)) {
                    directory = LocalGalleryDirectory(); details.clear(); LocalThumbnailCache.invalidate(); albums = emptyMap(); resetSelection(); notice = ""
                    loadedQuery = null
                    return@launch
                }
                val h = withContext(Dispatchers.IO) { TransferStore.open(context, profile).handle }
                suspend fun readDirectory() {
                    val next = withContext(Dispatchers.IO) { LocalGalleryDirectory.from(LocalCatalog.index(h, query.album, query.kind, query.unbacked)) }
                    if (query != LocalGalleryQuery(profile, album, kind, unbacked)) { queuedRefresh = true; return }
                    directory = next; details.clear(); loadedQuery = query; notice = ""
                }
                // Render the committed directory while the system catalog is being reconciled.
                val fullAccess = LocalCatalog.hasFullAccess(context)
                if (fullAccess) readDirectory() else {
                    directory = LocalGalleryDirectory(); details.clear(); LocalThumbnailCache.invalidate()
                }
                if (changingFilter) gridState.requestScrollToItem(0)
                if (scan || !fullAccess) {
                    val catalog = withContext(Dispatchers.IO) { LocalCatalog.refresh(context, h, profile) }
                    if (catalog != null) {
                        albums = catalog.albums
                        selection = selection.filterKeys { it in catalog.sources }
                        LocalThumbnailCache.invalidate(catalog.changes)
                        readDirectory()
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { notice = e.message ?: "图库读取失败" } finally {
                busy = false
                if (queuedRefresh && isActive) {
                    val pendingScan = queuedScan
                    queuedRefresh = false; queuedScan = false; load(scan = pendingScan)
                }
            }
        }
    }
    fun selectScope(day: String? = null) { if (selecting && !busy && LocalCatalog.hasAccess(context)) scope.launch {
        busy = true
        val generation = selectionGeneration
        val query = LocalGalleryQuery(profile, album, kind, unbacked)
        val entries = if (day == null) directory.entries else directory.days[day].orEmpty()
        try {
            val chosen = withContext(Dispatchers.IO) {
                val h = TransferStore.open(context, profile).handle
                entries.chunked(1000).flatMap { LocalCatalog.items(h, it.map { entry -> entry.id }) }
            }
            if (selecting && generation == selectionGeneration && query == LocalGalleryQuery(profile, album, kind, unbacked)) selection = selection + chosen.associateBy { it.getString("source_id") }
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (selecting && generation == selectionGeneration && query == LocalGalleryQuery(profile, album, kind, unbacked)) notice = e.message ?: "选择失败"
        } finally {
            busy = false
            if (queuedRefresh) {
                val pendingScan = queuedScan
                queuedRefresh = false; queuedScan = false; load(scan = pendingScan)
            }
        }
    } }
    fun openEntry(entry: LocalGalleryEntry, select: Boolean = selecting) {
        // Preview taps supersede one another; independent selection taps do not.
        if (!select) previewGeneration++
        val previewRequest = previewGeneration
        val generation = entryGeneration
        val query = LocalGalleryQuery(profile, album, kind, unbacked)
        scope.launch {
            try {
                val row = details.resolve(context, profile, entry)
                if (generation != entryGeneration || query != LocalGalleryQuery(profile, album, kind, unbacked)) return@launch
                if (select) { selecting = true; toggle(row) }
                else if (previewRequest == previewGeneration && !selecting) setPreview(row)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (generation == entryGeneration && query == LocalGalleryQuery(profile, album, kind, unbacked) &&
                    (select || (previewRequest == previewGeneration && !selecting))) notice = e.message ?: "读取失败"
            }
        }
    }
    fun submitBackup() {
        if (needsPairing) { onLogin(); return }
        if (selection.isEmpty()) { selecting = true; return }
        val selectedRows = selection.values.toList()
        busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) { LocalCatalog.persist(TransferStore.open(context, profile).handle, selectedRows) }
                BackupScheduler.enqueueNow(context, config)
                resetSelection(); onSubmitted()
            } catch (e: Exception) { notice = e.message ?: "提交失败" }
            finally {
                busy = false
                if (queuedRefresh) {
                    val pendingScan = queuedScan
                    queuedRefresh = false; queuedScan = false; load(scan = pendingScan)
                }
            }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { load(scan = true) }
    val refresh by rememberUpdatedState(newValue = { load(scan = true) })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, profile) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer)
        var refreshJob: Job? = null
        val mediaObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                refreshJob?.cancel()
                refreshJob = scope.launch { delay(300); refresh() }
            }
        }
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, mediaObserver)
        onDispose {
            lifecycle.removeObserver(observer)
            context.contentResolver.unregisterContentObserver(mediaObserver)
            refreshJob?.cancel()
        }
    }
    LaunchedEffect(profile) {
        val permissionsState = context.getSharedPreferences("photo_access_ui", Context.MODE_PRIVATE)
        if (!LocalCatalog.hasAccess(context) && !permissionsState.getBoolean("requested", false)) {
            permissionsState.edit().putBoolean("requested", true).apply()
            permission.launch(LocalCatalog.permissions())
        } else load(scan = true)
    }
    LaunchedEffect(directory, gridState, thumbnailSize) {
        val effectDirectory = directory
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.mapNotNull { effectDirectory.positions[it.key] } }
            .distinctUntilChanged().collectLatest { visible ->
                if (effectDirectory.entries.isEmpty()) return@collectLatest
                val first = visible.minOrNull() ?: 0
                val last = visible.maxOrNull() ?: minOf(23, effectDirectory.entries.lastIndex)
                details.warm(context, profile, effectDirectory, first, last)
                val nearby = effectDirectory.entries.subList((first - 12).coerceAtLeast(0), (last + 37).coerceAtMost(effectDirectory.entries.size))
                LocalThumbnailCache.prefetch(context, nearby, thumbnailSize)
            }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(GridCells.Fixed(gridColumns), Modifier.fillMaxSize().testTag("gallery.grid").galleryGridPinch(gridColumns) { columns ->
                if (columns != gridColumns) { gridColumns = columns; galleryPreferences.edit().putInt("columns", columns).apply() }
            },
            state = gridState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 64.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("共 ${directory.entries.size} 项", Modifier.testTag("gallery.loaded-count"), style = MaterialTheme.typography.titleSmall)
                    if (selecting) Text("已选 ${selection.size} 项", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (hasFilters) GalleryInlineButton("清除筛选", onClick = { album = null; kind = null; unbacked = false; load() })
                }
            }
            if (notice.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                Text(notice, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (directory.entries.isEmpty() && !busy) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    GalleryEmptyState(if (hasFilters) "没有符合条件的照片" else "这里还没有照片",
                        if (hasFilters) "清除筛选后查看全部可访问的媒体。" else "允许访问选定照片或全部照片后，在这里浏览和备份。", R.drawable.ic_photo_library)
                    if (!hasFilters) Text("请在“设置 → 照片权限设置”中授权访问照片。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (hasFilters) TextButton(shape = AppShapes.control, onClick = { album = null; kind = null; unbacked = false; load() }) { Text("清除筛选") }
                }
            }
            items(directory.grid, key = { it.key }, span = { if (it is LocalGalleryGridItem.Day) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                contentType = { if (it is LocalGalleryGridItem.Day) "day" else "media" }) { item ->
                when (item) {
                    is LocalGalleryGridItem.Day -> Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(item.day, Modifier.testTag("gallery.date.${item.day}"), style = MaterialTheme.typography.titleSmall)
                        if (selecting) GalleryInlineButton("全选", enabled = !busy, onClick = { selectScope(item.day) })
                    }
                    is LocalGalleryGridItem.Media -> {
                        val entry = item.entry
                        val row = details.rows[entry.id]
                        val checked = entry.id in selection
                        val status = row?.optString("backup_state") ?: "unknown"
                        val badge = when (status) { "complete" -> "✓"; "queued", "uploading" -> "↑"; "failed", "original_complete" -> "!"; else -> null }
                        Box(Modifier.fillMaxWidth().testTag("gallery.photo.${entry.id}").aspectRatio(1f).clip(RoundedCornerShape(8.dp))
                            .border(if (checked) 2.dp else 0.dp, if (checked) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(8.dp))
                            .combinedClickable(onClick = { openEntry(entry) }, onLongClick = { if (!checked) openEntry(entry, select = true) else selecting = true })
                            .semantics { contentDescription = "${if (entry.kind == "video") "视频" else "照片"}，${entry.name}" }) {
                            LocalMediaImage(context, entry.id, entry.name, entry.kind, entry.modified, false, Modifier.fillMaxSize(), thumbnailSize)
                            if (entry.kind == "video") Text("▶", Modifier.align(Alignment.BottomStart).padding(5.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
                                color = Color.White, style = MaterialTheme.typography.labelSmall)
                            if (badge != null) Text(badge, Modifier.align(Alignment.BottomEnd).padding(5.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp)
                                .semantics { contentDescription = LocalCatalog.status(status) }, color = Color.White, style = MaterialTheme.typography.labelSmall)
                            if (row?.optBoolean("excluded") == true) Text("⊘", Modifier.align(Alignment.TopStart).padding(5.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp)
                                .semantics { contentDescription = "自动备份已排除" }, color = Color.White, style = MaterialTheme.typography.labelSmall)
                            if (selecting) GallerySelectionButton(checked, { openEntry(entry, select = true) }, Modifier.align(Alignment.TopEnd)
                                .semantics { contentDescription = "选择 ${entry.name}" }, size = if (gridColumns >= 6) 24.dp else 44.dp)
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                GalleryToolbarButton("筛选", enabled = !busy, onClick = { menu = true }, modifier = Modifier.testTag("gallery.filter"))
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("全部相册") }, onClick = { album = null; menu = false; load() })
                    albums.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { album = id; menu = false; load() }) }
                    HorizontalDivider()
                    listOf(null to "照片和视频", "photo" to "仅照片", "video" to "仅视频").forEach { (value, title) ->
                        DropdownMenuItem(text = { Text(title) }, trailingIcon = { if (kind == value) Text("✓") },
                            onClick = { kind = value; menu = false; load() })
                    }
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("仅未备份 / 待确认") }, trailingIcon = { if (unbacked) Text("✓") },
                        onClick = { unbacked = !unbacked; menu = false; load() })
                }
            }
            Spacer(Modifier.weight(1f))
            GalleryToolbarButton("备份", onClick = ::submitBackup, enabled = !busy, modifier = Modifier.testTag("gallery.backup"))
            if (selecting) {
                GalleryToolbarButton("全选", onClick = { selectScope() }, enabled = !busy, modifier = Modifier.testTag("gallery.select-all"))
                GalleryToolbarButton("取消", onClick = { resetSelection() }, modifier = Modifier.testTag("gallery.cancel"))
            } else GalleryToolbarButton("选择", onClick = { selecting = true }, modifier = Modifier.testTag("gallery.select"))
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter)
            .padding(start = 16.dp, end = 16.dp, top = 56.dp).testTag("gallery.loading"))
    }

    preview?.let { row ->
        if (row.getString("media_kind") == "video") FullScreenPhotoDialog(onClose = { setPreview(null) }) {
            LocalVideo(Uri.parse(row.getString("source_id")), Modifier.fillMaxSize(),
                onClose = { setPreview(null) }, backupSelected = row.getString("source_id") in selection,
                onBackup = { selecting = true; toggle(row) })
        } else {
            var previewMenu by remember(row.getString("source_id")) { mutableStateOf(false) }
            FullScreenPhotoDialog(onClose = { setPreview(null) }) {
                Box(Modifier.fillMaxSize()) {
                    ZoomablePhotoFrame(onTap = { setPreview(null) }, onLongPress = { previewMenu = true }) { imageModifier ->
                        LocalImage(context, row, true, imageModifier)
                    }
                    DropdownMenu(expanded = previewMenu, onDismissRequest = { previewMenu = false }) {
                        DropdownMenuItem(text = { Text(if (row.getString("source_id") in selection) "取消选择" else "选择备份") }, onClick = {
                            previewMenu = false; selecting = true; toggle(row); setPreview(null)
                        })
                        DropdownMenuItem(text = { Text(if (row.getBoolean("excluded")) "恢复自动备份" else "不再自动备份此项目") }, onClick = {
                            previewMenu = false
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { TransferStore.gallery(TransferStore.open(context, profile).handle, "exclude", JSONObject()
                                        .put("source_id", row.getString("source_id")).put("excluded", !row.getBoolean("excluded"))) }
                                    setPreview(null); load()
                                } catch (e: Exception) { notice = e.message ?: "操作失败" }
                            }
                        })
                    }
                }
            }
        }
    }
}
@Composable
internal fun LocalImage(context: Context, row: JSONObject, preview: Boolean, modifier: Modifier, thumbnailSize: Int = 256) {
    LocalMediaImage(context, row.getString("source_id"), row.getString("name"), row.optString("media_kind", "photo"),
        row.optLong("modified_ms"), preview, modifier, thumbnailSize)
}
