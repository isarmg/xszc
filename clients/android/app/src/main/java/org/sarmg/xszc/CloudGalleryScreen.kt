package org.sarmg.xszc

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/** UI and page selection use the same filter snapshot, including pages not visible yet. */
internal interface CloudGallerySource {
    val api: BackupApi? get() = null
    suspend fun page(cursor: String?, trash: Boolean, favorite: Boolean, album: String?, filters: CloudFilters): RemoteLibrary.Page
    suspend fun albums(): List<Pair<String, String>> = emptyList()
    suspend fun devices(): List<Pair<String, String>> = emptyList()
    suspend fun synchronize(): Boolean = false
}

private class AccountCloudGallerySource(val context: Context, val config: SecureConfig, val profile: String) : CloudGallerySource {
    private val credentials = config.connection()
    override val api = BackupApi(credentials.server, credentials.token)
    override suspend fun page(cursor: String?, trash: Boolean, favorite: Boolean, album: String?, filters: CloudFilters) = withContext(Dispatchers.IO) {
        check(config.isLoggedIn && config.connection() == credentials) { "账户已退出或切换" }
        val handle = TransferStore.open(context, profile).handle
        TransferStore.command(handle, "bind", org.json.JSONObject().put("server", credentials.server)
            .put("account_id", credentials.accountId).put("device_id", credentials.deviceId))
        GalleryCache.page(context, profile, api, cursor, trash, favorite, album, filters)
    }
    override suspend fun albums() = withContext(Dispatchers.IO) {
        val rows = api.listAlbums()
        (0 until rows.length()).map { rows.getJSONObject(it).let { r -> r.getString("album_id") to r.getString("name") } }
    }
    override suspend fun devices() = withContext(Dispatchers.IO) {
        val rows = api.devices()
        (0 until rows.length()).map { rows.getJSONObject(it).let { r -> r.getString("device_id") to r.getString("name") } }
    }
    override suspend fun synchronize() = withContext(Dispatchers.IO) { GalleryCache.synchronize(context, profile, api) }
}

// Material date pickers represent calendar days at UTC midnight. Server filters use local days.
internal fun cloudDateBoundary(pickerMillis: Long, endInclusive: Boolean = false): Long {
    val day = Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()
    return (if (endInclusive) day.plusDays(1) else day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
private fun pickerDate(boundary: Long?, exclusiveEnd: Boolean = false): Long? = boundary?.let {
    val day = Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()
    (if (exclusiveEnd) day.minusDays(1) else day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun CloudGalleryScreen(context: Context, config: SecureConfig, profile: String,
    sourceOverride: CloudGallerySource? = null, onDownload: ((List<RemoteAsset>) -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val source = remember(profile, sourceOverride) { sourceOverride ?: AccountCloudGallerySource(context, config, profile) }
    val api = source.api
    var assets by remember(profile) { mutableStateOf<List<RemoteAsset>>(emptyList()) }
    var cursor by remember(profile) { mutableStateOf<String?>(null) }
    var started by remember(profile) { mutableStateOf(false) }
    var loading by remember(profile) { mutableStateOf(false) }
    var notice by remember(profile) { mutableStateOf("") }
    var loadError by remember(profile) { mutableStateOf<String?>(null) }
    var trash by remember(profile) { mutableStateOf(false) }
    var favorites by remember(profile) { mutableStateOf(false) }
    var albums by remember(profile) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var albumId by remember(profile) { mutableStateOf<String?>(null) }
    var filterMenu by remember { mutableStateOf<String?>(null) }
    var optionsMenu by remember { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("gallery_ui", Context.MODE_PRIVATE) }
    var gridColumns by rememberSaveable(profile) { mutableIntStateOf(preferences.getInt("columns", 3).coerceIn(1, 8)) }
    var filters by remember(profile) { mutableStateOf(CloudFilters()) }
    var devices by remember(profile) { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var dateRange by remember { mutableStateOf(false) }
    var generation by remember(profile) { mutableIntStateOf(0) }
    var selectionGeneration by remember(profile) { mutableIntStateOf(0) }
    var selecting by remember(profile) { mutableStateOf(false) }
    var selectingAll by remember(profile) { mutableStateOf(false) }
    var selection by remember(profile) { mutableStateOf<Set<String>>(emptySet()) }
    var selectAllJob by remember(profile) { mutableStateOf<Job?>(null) }
    var selected by remember(profile) { mutableStateOf<Int?>(null) }
    var duplicateGroups by remember(profile) { mutableStateOf<org.json.JSONArray?>(null) }
    var downloadNotice by remember(profile) { mutableStateOf("") }
    val hasFilters = favorites || albumId != null || filters != CloudFilters()
    val monthFormat = remember(Locale.getDefault(), ZoneId.systemDefault()) { SimpleDateFormat("yyyy年M月", Locale.getDefault()) }
    fun monthLabel(asset: RemoteAsset) = monthFormat.format(Date(asset.createdAtMs))
    fun resetSelection(end: Boolean = false) {
        selectionGeneration++; selectAllJob?.cancel(); selectingAll = false; selection = emptySet()
        if (end) selecting = false
    }
    fun toggle(asset: RemoteAsset) { if (selectingAll) return; selection = if (asset.id in selection) selection - asset.id else selection + asset.id }
    val seen = remember(profile) { mutableSetOf<String>() }
    suspend fun loadPage(reset: Boolean) {
        if (!reset && (loading || (started && cursor == null))) return
        if (reset) { seen.clear(); generation++; assets = emptyList(); cursor = null; started = false; loadError = null }
        val requestGeneration = generation
        val requestCursor = cursor
        val requestTrash = trash; val requestFavorites = favorites; val requestAlbum = albumId; val requestFilters = filters
        loading = true
        try {
            val page = source.page(requestCursor, requestTrash, requestFavorites, requestAlbum, requestFilters)
            if (requestGeneration != generation) return
            check(page.nextCursor == null || (page.nextCursor != requestCursor && page.nextCursor !in seen)) { "分页游标重复，请重试" }
            page.nextCursor?.let { seen.add(it) }
            assets = (assets + page.items).distinctBy { it.id }
            cursor = page.nextCursor; started = true; loadError = null
            notice = if (page.cached) "正在显示离线缓存" else ""
            if (reset) scope.launch {
                // Optional filter metadata must not delay paging or turn a good page into an error.
                try {
                    val latest = source.albums()
                    if (requestGeneration != generation) return@launch
                    albums = latest
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Keep the last known album options while offline. */ }
                if (requestGeneration != generation) return@launch
                try {
                    val latest = source.devices()
                    if (requestGeneration != generation) return@launch
                    devices = latest
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* Album and device metadata recover independently. */ }
            }
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            if (requestGeneration == generation) { loadError = error.message ?: "加载失败"; notice = loadError.orEmpty() }
        } finally { if (requestGeneration == generation) loading = false }
    }
    fun load(reset: Boolean) {
        scope.launch { loadPage(reset) }
    }
    fun applyFilter() { filterMenu = null; resetSelection(); load(true) }
    fun selectAll(month: String? = null) {
        if (selectingAll) return
        val stamp = selectionGeneration
        selecting = true; selectingAll = true
        selectAllJob = scope.launch {
            try {
                while (loading) delay(100)
                if (stamp != selectionGeneration) return@launch
                if (!started) { seen.clear(); loadPage(true) }
                check(loadError == null) { loadError.orEmpty() }
                while (cursor != null) {
                    if (stamp != selectionGeneration) return@launch
                    val previous = cursor
                    loadPage(false)
                    check(loadError == null && cursor != previous) { loadError ?: "分页游标未前进" }
                }
                if (stamp == selectionGeneration) selection = selection + assets.filter { month == null || monthLabel(it) == month }.map { it.id }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (stamp == selectionGeneration) notice = error.message ?: "全选失败" }
            finally { if (stamp == selectionGeneration) selectingAll = false }
        }
    }
    fun enqueue(values: List<RemoteAsset>) {
        val added = if (onDownload != null) { onDownload(values); values.distinctBy { it.id }.size }
            else DownloadQueue.enqueue(context, config, values)
        if (added > 0) downloadNotice = "已加入下载：$added 项"
    }
    fun download(values: List<RemoteAsset>) {
        if (values.isEmpty()) return
        enqueue(values)
    }
    LaunchedEffect(source, profile) {
        seen.clear(); loadPage(true)
    }
    var foreground by remember { mutableStateOf(true) }
    var refreshPending by remember(profile) { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, source) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                foreground = true
                if (started && !loading && selected == null && !selecting) load(true)
            } else if (event == Lifecycle.Event.ON_PAUSE) foreground = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Synchronization can finish after a preview/selection opens. Preserve the
    // change until the gallery is idle instead of clearing an active pager.
    LaunchedEffect(refreshPending, foreground, loading, selected, selecting) {
        if (refreshPending && foreground && !loading && selected == null && !selecting) {
            refreshPending = false
            load(true)
        }
    }
    LaunchedEffect(source, profile) {
        while (true) {
            delay(30_000)
            if (foreground && !loading && selected == null && !selecting) {
                try { if (source.synchronize()) refreshPending = true }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { notice = "图库缓存待同步：${error.message}" }
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        PullToRefreshBox(isRefreshing = loading && assets.isEmpty(), onRefresh = { if (!selecting) load(true) }) {
            LazyVerticalGrid(columns = GridCells.Fixed(gridColumns), modifier = Modifier.fillMaxSize().testTag("cloud.grid")
                .galleryGridPinch(gridColumns) { gridColumns = it; preferences.edit().putInt("columns", it).apply() },
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 64.dp, bottom = if (selecting) 56.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已载入 ${assets.size} 项", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        if (downloadNotice.isNotBlank()) Text(downloadNotice, Modifier.testTag("cloud.download-notice"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (hasFilters) TextButton(shape = AppShapes.control, onClick = { favorites = false; albumId = null; filters = CloudFilters(); resetSelection(); load(true) }) { Text("清除筛选") }
                    }
                }
                if (trash || notice.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(if (notice.isNotEmpty()) notice else "回收站", style = MaterialTheme.typography.bodySmall)
                }
                if (assets.isEmpty() && !loading) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (loadError != null) "云端图库加载失败" else if (trash) "回收站为空" else if (hasFilters) "没有符合条件的照片" else "云端还没有照片", style = MaterialTheme.typography.titleMedium)
                        if (loadError != null || trash || hasFilters) Button(shape = AppShapes.control, onClick = {
                            if (loadError == null) { trash = false; favorites = false; albumId = null; filters = CloudFilters(); resetSelection() }
                            load(true)
                        }) { Text(if (loadError != null) "重试" else "返回全部照片") }
                    }
                }
                assets.withIndex().groupBy { monthLabel(it.value) }.entries.sortedByDescending { group ->
                    group.value.maxOf { it.value.createdAtMs }
                }.forEach { (month, group) ->
                    item(key = "month-$month", span = { GridItemSpan(maxLineSpan) }) {
                        Row(Modifier.fillMaxWidth().testTag("cloud.month.$month"), verticalAlignment = Alignment.CenterVertically) {
                            Text(month, Modifier.weight(1f).padding(vertical = 10.dp), style = MaterialTheme.typography.titleSmall)
                            if (selecting) GalleryInlineButton("全选", onClick = { selectAll(month) }, enabled = !selectingAll,
                                modifier = Modifier.testTag("cloud.month-all.$month"))
                        }
                    }
                    items(group, key = { it.value.id }) { indexed ->
                        val asset = indexed.value; val checked = asset.id in selection
                        Box(Modifier.fillMaxWidth().aspectRatio(1f)
                            .testTag("cloud.photo.${asset.id}")
                            .combinedClickable(onClick = { if (selecting) toggle(asset) else selected = indexed.index },
                                onLongClick = { selecting = true; if (!checked) toggle(asset) })
                            .semantics { contentDescription = "${if (asset.mediaKind == "video") "视频" else "照片"}，${asset.resources.firstOrNull { it.role == "primary" }?.filename ?: "未命名媒体"}" }) {
                            // Clipping and selection outline share the image layer, below its badges.
                            RemoteImage(context, api, profile, asset, false, Modifier.fillMaxSize().clip(AppShapes.photo)
                                .then(if (checked) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, AppShapes.photo) else Modifier))
                            if (asset.mediaKind == "video") Text("▶", Modifier.align(Alignment.BottomStart).padding(5.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
                                color = Color.White, style = MaterialTheme.typography.labelSmall)
                            if (selecting) GallerySelectionButton(checked, { toggle(asset) }, enabled = !selectingAll,
                                modifier = Modifier.align(Alignment.TopEnd), size = if (gridColumns >= 6) 24.dp else 44.dp)
                        }
                    }
                }
                if (cursor != null) item(span = { GridItemSpan(maxLineSpan) }) {
                    LaunchedEffect(cursor, selectingAll) { if (!selectingAll && !loading) load(false) }
                    TextButton(shape = AppShapes.control, onClick = { load(false) }, enabled = !loading && !selectingAll) { Text("加载更多") }
                }
            }
        }
        Row(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                GalleryToolbarButton("筛选", onClick = { filterMenu = "root" }, enabled = !selectingAll, modifier = Modifier.testTag("cloud.filter"))
                DropdownMenu(filterMenu != null, { filterMenu = null }, shape = AppShapes.group) {
                    if (filterMenu != "root") DropdownMenuItem(text = { Text("‹ 返回") }, onClick = { filterMenu = "root" })
                    when (filterMenu) {
                        "root" -> {
                            listOf("albums" to "相册", "types" to "类型", "devices" to "设备", "date" to "日期").forEach { (key, label) ->
                                DropdownMenuItem(text = { Text(label) }, trailingIcon = { Text("›") }, onClick = { filterMenu = key })
                            }
                            DropdownMenuItem(text = { Text("仅收藏") }, trailingIcon = { if (favorites) Text("✓") }, onClick = { favorites = !favorites; applyFilter() })
                            if (hasFilters) DropdownMenuItem(text = { Text("清除筛选") }, onClick = { favorites = false; albumId = null; filters = CloudFilters(); applyFilter() })
                        }
                        "albums" -> (listOf(null to "全部相册") + albums).forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name) }, trailingIcon = { if (albumId == id) Text("✓") }, onClick = { albumId = id; applyFilter() })
                        }
                        "types" -> listOf(null to "照片和视频", "photo" to "照片", "video" to "视频").forEach { (kind, label) ->
                            DropdownMenuItem(text = { Text(label) }, trailingIcon = { if (filters.kind == kind) Text("✓") }, onClick = { filters = filters.copy(kind = kind); applyFilter() })
                        }
                        "devices" -> (listOf(null to "全部设备") + devices).forEach { (id, name) ->
                            DropdownMenuItem(text = { Text(name) }, trailingIcon = { if (filters.device == id) Text("✓") }, onClick = { filters = filters.copy(device = id); applyFilter() })
                        }
                        "date" -> {
                            DropdownMenuItem(text = { Text("全部日期") }, onClick = { filters = filters.copy(from = null, to = null); applyFilter() })
                            DropdownMenuItem(text = { Text("自定日期范围…") }, onClick = { filterMenu = null; dateRange = true })
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            GalleryToolbarButton("下载", onClick = {
                if (!selecting) selecting = true
                else if (selection.isNotEmpty()) { download(assets.filter { it.id in selection }); resetSelection(end = true) }
            }, enabled = assets.isNotEmpty() && !selectingAll && (!selecting || selection.isNotEmpty()), modifier = Modifier.testTag("cloud.download"))
            if (selecting) {
                GalleryToolbarButton("全选", onClick = { selectAll() }, enabled = !selectingAll, modifier = Modifier.testTag("cloud.select-all"))
                GalleryToolbarButton("取消", onClick = { resetSelection(end = true) }, modifier = Modifier.testTag("cloud.cancel"))
            } else {
                Box {
                    GalleryToolbarButton("更多", onClick = { optionsMenu = true })
                    DropdownMenu(optionsMenu, { optionsMenu = false }, shape = AppShapes.group) {
                        DropdownMenuItem(text = { Text(if (trash) "返回云端" else "打开回收站") }, onClick = { optionsMenu = false; trash = !trash; resetSelection(end = true); load(true) })
                        DropdownMenuItem(text = { Text("查看重复项") }, onClick = {
                            optionsMenu = false
                            if (api != null) scope.launch {
                                try { duplicateGroups = withContext(Dispatchers.IO) { api.duplicateGroups() } }
                                catch (error: CancellationException) { throw error }
                                catch (error: Exception) { notice = error.message ?: "重复项加载失败" }
                            }
                        })
                    }
                }
                GalleryToolbarButton("选择", onClick = { selecting = true }, enabled = assets.isNotEmpty(), modifier = Modifier.testTag("cloud.select"))
            }
        }
        if (loading || selectingAll) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = 56.dp))
        if (selecting) Row(Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text("已选 ${selection.size} 项", modifier = Modifier.testTag("cloud.selection-count"), style = MaterialTheme.typography.titleSmall)
            if (selection.isNotEmpty()) GalleryInlineButton("清空选择", onClick = { resetSelection() })
        }
    }
    if (dateRange) {
        val state = rememberDateRangePickerState(initialSelectedStartDateMillis = pickerDate(filters.from),
            initialSelectedEndDateMillis = pickerDate(filters.to, exclusiveEnd = true))
        DatePickerDialog(onDismissRequest = { dateRange = false }, shape = AppShapes.dialog, confirmButton = {
            TextButton(shape = AppShapes.control, enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null, onClick = {
                filters = filters.copy(from = cloudDateBoundary(state.selectedStartDateMillis!!),
                    to = cloudDateBoundary(state.selectedEndDateMillis!!, endInclusive = true))
                dateRange = false; resetSelection(); load(true)
            }) { Text("应用") }
        }, dismissButton = { TextButton(shape = AppShapes.control, onClick = { dateRange = false }) { Text("取消") } }) {
            DateRangePicker(state, modifier = Modifier.heightIn(max = 520.dp), title = { Text("日期范围", Modifier.padding(24.dp)) }, showModeToggle = false)
        }
    }
    selected?.let { index ->
        api?.let { connection ->
            PhotoViewerScreen(context, connection, profile, assets, index, onClose = { selected = null },
                onSave = { asset -> download(listOf(asset)) },
                onFavorite = { asset -> scope.launch {
                    try { withContext(Dispatchers.IO) { connection.updateAsset(asset.id, favorite = !asset.favorite) }; selected = null; load(true) }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice = e.message ?: "更新失败" }
                } }, onAction = { asset, operation, tag -> scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            when (operation) {
                                "archive" -> connection.updateAsset(asset.id, archived = !asset.archived)
                                "trash" -> if (asset.trashed) connection.restoreAsset(asset.id) else connection.trashAsset(asset.id)
                                "tag" -> { val created = connection.createTag(tag); connection.addTagAsset(created.getString("tag_id"), asset.id) }
                                "remove-tag" -> {
                                    val tags = connection.listTags()
                                    val existing = (0 until tags.length()).map(tags::getJSONObject)
                                        .firstOrNull { it.getString("name") == tag }
                                        ?: error("标签不存在")
                                    connection.removeTagAsset(existing.getString("tag_id"), asset.id)
                                }
                                "delete" -> connection.deleteAssetPermanently(asset.id)
                            }
                        }
                        selected = null; load(true)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { notice = e.message ?: "更新失败" }
                } })
        }
    }
    duplicateGroups?.let { groups ->
        ModalBottomSheet(onDismissRequest = { duplicateGroups = null }, shape = AppShapes.sheet) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("重复项", style = MaterialTheme.typography.titleLarge)
                if (groups.length() == 0) Text("没有检测到内容相同的媒体。")
                for (index in 0 until groups.length()) {
                    val group = groups.getJSONObject(index)
                    val members = group.getJSONArray("assets")
                    ElevatedCard(Modifier.fillMaxWidth(), shape = AppShapes.group) {
                        Column(Modifier.padding(12.dp)) {
                            Text("第 ${index + 1} 组 · ${members.length()} 项", style = MaterialTheme.typography.titleMedium)
                            Text("${group.getLong("content_size")} 字节", style = MaterialTheme.typography.bodySmall)
                            for (member in 0 until members.length()) {
                                val asset = members.getJSONObject(member)
                                Text(asset.optString("source_asset_id", asset.getString("asset_id")), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                Button(shape = AppShapes.control, onClick = { duplicateGroups = null }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            }
        }
    }
}
@Composable
internal fun RemoteImage(context: Context, api: BackupApi?, profile: String, asset: RemoteAsset, preview: Boolean, modifier: Modifier, showPlaceholderMessage: Boolean = true) {
    var bitmap by remember(profile, asset.id, preview) { mutableStateOf<Bitmap?>(null) }
    var error by remember(profile, asset.id, preview) { mutableStateOf("") }
    LaunchedEffect(profile, asset.id, preview, api) {
        val resource = if (preview && asset.mediaKind != "video") asset.resources.firstOrNull { it.role == "primary" }
            ?: asset.resources.firstOrNull { it.role != "thumbnail" } else asset.resources.firstOrNull { it.role == "thumbnail" }
        if (resource != null && api != null) {
            try {
                if (preview && asset.mediaKind != "video") asset.resources.firstOrNull { it.role == "thumbnail" }?.let {
                    // A thumbnail is only a progressive placeholder. Its failure
                    // must not prevent loading the independently stored preview.
                    try { bitmap = RemoteImageCache.load(context, api, profile, it, false) }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { }
                }
                bitmap = RemoteImageCache.load(context, api, profile, resource, preview)
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "图片加载失败" }
        }
    }
    Box(modifier) {
        bitmap?.let { Image(it.asImageBitmap(), asset.resources.firstOrNull()?.filename,
            Modifier.fillMaxSize(), contentScale = if (preview) ContentScale.Fit else ContentScale.Crop) }
        if (bitmap == null && !preview) {
            if (showPlaceholderMessage) Text(if (error.isNotBlank()) error else if (asset.mediaKind == "video") "视频封面" else "暂无预览")
            else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.ic_photo_library), contentDescription = "暂无预览", modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
internal fun PhotoViewerScreen(context: Context, api: BackupApi, profile: String, assets: List<RemoteAsset>, index: Int,
    onClose: () -> Unit, onSave: (RemoteAsset) -> Unit, onFavorite: (RemoteAsset) -> Unit,
    onAction: (RemoteAsset, String, String) -> Unit) {
    FullScreenPhotoDialog(onClose = onClose) {
        val pager = rememberPagerState(initialPage = index, pageCount = { assets.size })
        var menu by remember { mutableStateOf(false) }
        var addingTag by remember { mutableStateOf(false) }
        var confirmingDelete by remember { mutableStateOf(false) }
        var tag by remember { mutableStateOf("") }
        val current = assets[pager.currentPage]
        Box(Modifier.fillMaxSize()) {
            HorizontalPager(pager, Modifier.fillMaxSize(), key = { assets[it].id }) { page ->
                val asset = assets[page]
                val primary = asset.resources.firstOrNull { it.role == "primary" }
                if (asset.mediaKind == "video" && primary != null) {
                    CloudVideo(api, primary, pager.currentPage == page, Modifier.fillMaxSize())
                } else {
                    ZoomablePhotoFrame(onTap = onClose, onLongPress = { menu = true }) { imageModifier ->
                        RemoteImage(context, api, profile, asset, true, imageModifier)
                    }
                }
            }
            if (current.mediaKind == "video") {
                Row(Modifier.fillMaxWidth().align(androidx.compose.ui.Alignment.TopCenter), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(shape = AppShapes.control, onClick = onClose) { Text("关闭") }
                    TextButton(shape = AppShapes.control, onClick = { menu = true }) { Text("更多") }
                }
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = AppShapes.group) {
                DropdownMenuItem(text = { Text("保存到手机") }, onClick = { menu = false; onSave(current) })
                DropdownMenuItem(text = { Text(if (current.favorite) "取消收藏" else "收藏") }, onClick = { menu = false; onFavorite(current) })
                DropdownMenuItem(text = { Text(if (current.archived) "取消归档" else "归档") }, onClick = { menu = false; onAction(current, "archive", "") })
                DropdownMenuItem(text = { Text("添加标签") }, onClick = { menu = false; tag = ""; addingTag = true })
                current.tagNames.forEach { existing ->
                    DropdownMenuItem(text = { Text("移除标签：$existing") }, onClick = { menu = false; onAction(current, "remove-tag", existing) })
                }
                DropdownMenuItem(text = { Text(if (current.trashed) "恢复照片" else "移入回收站") }, onClick = { menu = false; onAction(current, "trash", "") })
                if (current.trashed) DropdownMenuItem(text = { Text("永久删除") }, onClick = { menu = false; confirmingDelete = true })
            }
            if (addingTag) AlertDialog(onDismissRequest = { addingTag = false }, shape = AppShapes.dialog, title = { Text("添加标签") },
                text = { OutlinedTextField(tag, { tag = it }, label = { Text("标签名称") }, singleLine = true) },
                confirmButton = { TextButton(shape = AppShapes.control, enabled = tag.isNotBlank(), onClick = { addingTag = false; onAction(current, "tag", tag.trim()) }) { Text("添加") } },
                dismissButton = { TextButton(shape = AppShapes.control, onClick = { addingTag = false }) { Text("取消") } })
            if (confirmingDelete) AlertDialog(onDismissRequest = { confirmingDelete = false }, shape = AppShapes.dialog, title = { Text("永久删除照片？") },
                text = { Text("此操作无法撤销。服务器可能在后台继续回收未引用的文件。") },
                confirmButton = { TextButton(shape = AppShapes.control, onClick = { confirmingDelete = false; onAction(current, "delete", "") }) { Text("永久删除") } },
                dismissButton = { TextButton(shape = AppShapes.control, onClick = { confirmingDelete = false }) { Text("取消") } })
        }
    }
}
