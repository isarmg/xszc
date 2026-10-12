package org.sarmg.xszc

import android.content.Context
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

private data class BatchAction(val id: String, val count: Int, val retry: Boolean)

private data class TransferPreview(val id: String, val name: String, val uri: String? = null, val asset: RemoteAsset? = null)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TransfersScreen(context: Context, config: SecureConfig, profile: String) {
    val scope = rememberCoroutineScope()
    val downloads by DownloadTransfers.rows.collectAsState()
    val currentDownloads = downloads.filter { it.profile == profile }
    var batches by remember(profile) { mutableStateOf<List<Pair<JSONObject, List<JSONObject>>>>(emptyList()) }
    var automatic by remember(profile) { mutableStateOf<List<UploadPhoto>>(emptyList()) }
    var notice by remember(profile) { mutableStateOf("") }
    var uploadsExpanded by rememberSaveable(profile) { mutableStateOf(false) }
    var downloadsExpanded by rememberSaveable(profile) { mutableStateOf(false) }
    val preferences = remember { context.getSharedPreferences("transfer_ui", Context.MODE_PRIVATE) }
    var columns by rememberSaveable { mutableIntStateOf(preferences.getInt("columns", 3).coerceIn(1, 8)) }
    var preview by remember(profile) { mutableStateOf<TransferPreview?>(null) }
    val uploads by TransferTelemetry.uploads.states.collectAsState()
    val downloadStates by TransferTelemetry.downloads.states.collectAsState()
    val uploadState = uploads[profile]; val downloadState = downloadStates[profile]
    val now by produceState(TransferRate.now()) { while (true) { delay(1000); value = TransferRate.now() } }
    var uploadMenu by remember { mutableStateOf(false) }
    var downloadMenu by remember { mutableStateOf(false) }
    var batchAction by remember(profile) { mutableStateOf<BatchAction?>(null) }
    var cancelledBatchesVisible by remember(profile) { mutableStateOf(false) }
    var clearHistory by remember(profile) { mutableStateOf<String?>(null) }
    val api = remember(profile) { config.connection().let { if (config.isLoggedIn) BackupApi(it.server, it.token) else null } }
    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            val handle = TransferStore.open(context, profile).handle
            val rows = TransferStore.batches(handle)
            val batchRows = (0 until rows.length()).reversed().map { i ->
                val batch = rows.getJSONObject(i)
                val items = TransferStore.items(handle, batch.getString("id"))
                batch to (0 until items.length()).map(items::getJSONObject)
            }
            val autoRows = TransferStore.command(handle, "automatic_uploads") as org.json.JSONArray
            batchRows to (0 until autoRows.length()).map { i ->
                val row = autoRows.getJSONObject(i)
                UploadPhoto.fromItem("automatic.${row.getString("asset")}.${row.getLong("modified_ms")}", null, row, automatic = true)
            }
        }
        batches = result.first; automatic = result.second
    }
    fun changeBatch(id: String, retry: Boolean) { scope.launch {
        try {
            withContext(Dispatchers.IO) { TransferStore.command(TransferStore.open(context, profile).handle,
                if (retry) "retry_batch" else "cancel_batch", JSONObject().put("batch_id", id)) }
            if (retry) BackupScheduler.enqueueNow(context, config)
            refresh(); notice = ""
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) { notice = error.message ?: "操作失败" }
    } }
    fun requestBatch(id: String, retry: Boolean) {
        val batch = batches.firstOrNull { it.first.getString("id") == id }?.first ?: return
        batchAction = BatchAction(id, batch.getInt("items"), retry)
    }
    LaunchedEffect(profile) {
        while (true) {
            try { refresh() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { notice = error.message ?: "读取任务失败" }
            delay(1500)
        }
    }
    val photos = automatic + batches.filter { !it.first.getBoolean("cancelled") }.flatMap { (batch, items) ->
        items.map { UploadPhoto.fromItem("${batch.getString("id")}.${it.getString("id")}", batch.getString("id"), it) }
    }
    val pendingUploads = photos.filterNot { it.isCompleted }
    val pendingDownloads = currentDownloads.filterNot { it.isCompleted }
    val active = uploadState?.asset?.let { mapOf(it to uploadState.progress) } ?: emptyMap()
    LazyVerticalGrid(GridCells.Fixed(columns), Modifier.fillMaxSize().testTag("transfers.grid")
        .galleryGridPinch(columns) { if (uploadsExpanded || downloadsExpanded) {
            columns = it; preferences.edit().putInt("columns", it).apply()
        } }, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        item(key = "upload-header", span = { GridItemSpan(maxLineSpan) }) {
            Box {
                TransferHeader("上传", uploadProgress(photos, active), TransferRate.formatted(uploadState?.rate?.bytesPerSecond(now) ?: 0.0),
                    uploadsExpanded, "transfers.upload", onLongClick = { uploadMenu = true }) { uploadsExpanded = !uploadsExpanded }
                DropdownMenu(uploadMenu, { uploadMenu = false }, shape = AppShapes.group) {
                    DropdownMenuItem(text = { Text("继续上传") }, enabled = config.isLoggedIn && uploadState == null, onClick = {
                        uploadMenu = false; BackupScheduler.enqueueNow(context, config, automatic = config.autoBackup)
                    })
                    DropdownMenuItem(text = { Text("已取消批次") }, onClick = {
                        uploadMenu = false; cancelledBatchesVisible = true
                    })
                    DropdownMenuItem(text = { Text("清除已完成手动批次记录") }, onClick = {
                        uploadMenu = false; clearHistory = "upload"
                    })
                }
            }
        }
        if (uploadsExpanded) items(pendingUploads, key = { "upload.${it.id}" }) { photo ->
            var menu by remember(photo.id) { mutableStateOf(false) }
            Box {
                TransferPhotoTile(photo.name, photo.status, "upload.${photo.id}", onClick = {
                    if (photo.uri != null) preview = TransferPreview(photo.id, photo.name, photo.uri)
                }, onLongClick = { menu = true }) {
                    TransferImage(context, api, profile, TransferPreview(photo.id, photo.name, photo.uri), false, it)
                }
                DropdownMenu(menu, { menu = false }, shape = AppShapes.group) {
                    DropdownMenuItem(text = { Text(photo.status) }, enabled = false, onClick = {})
                    photo.batchId?.let { id ->
                            val count = batches.firstOrNull { it.first.getString("id") == id }?.first?.getInt("items") ?: 0
                            DropdownMenuItem(text = { Text("重试整个批次（${count}项）") }, onClick = { menu = false; requestBatch(id, true) })
                            DropdownMenuItem(text = { Text("取消整个批次（${count}项）") }, onClick = { menu = false; requestBatch(id, false) })
                    }
                }
            }
        }
        item(key = "download-header", span = { GridItemSpan(maxLineSpan) }) {
            Box {
                TransferHeader("下载", downloadProgress(currentDownloads), TransferRate.formatted(downloadState?.rate?.bytesPerSecond(now) ?: 0.0),
                    downloadsExpanded, "transfers.download", onLongClick = { downloadMenu = true }) { downloadsExpanded = !downloadsExpanded }
                DropdownMenu(downloadMenu, { downloadMenu = false }, shape = AppShapes.group) {
                    DropdownMenuItem(text = { Text("清除已完成下载记录") }, onClick = {
                        downloadMenu = false; clearHistory = "download"
                    })
                }
            }
        }
        if (downloadsExpanded) items(pendingDownloads, key = { "download.${it.id}" }) { download ->
            var menu by remember(download.id) { mutableStateOf(false) }
            Box {
                TransferPhotoTile(download.name, download.phase, "download.${download.id}", onClick = {
                    download.asset?.let { preview = TransferPreview(download.id, download.name, asset = it) }
                }, onLongClick = { menu = true }) {
                    TransferImage(context, api, profile, TransferPreview(download.id, download.name, asset = download.asset), false, it)
                }
                DropdownMenu(menu, { menu = false }, shape = AppShapes.group) {
                    DropdownMenuItem(text = { Text(download.phase) }, enabled = false, onClick = {})

                }
            }
        }
        if (notice.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text(notice, Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
    batchAction?.let { action ->
        AlertDialog(onDismissRequest = { batchAction = null },
            title = { Text("${if (action.retry) "重试" else "取消"}整个批次（${action.count}项）？") },
            text = { Text(if (action.retry) "恢复此批次中未完成的项目；已完成项目不会重复上传。"
                else "此操作影响整个批次，不只这张照片。已完成的云端文件会保留；被其他批次或自动备份使用的资源仍可继续上传。取消后可从“已取消批次”恢复。") },
            confirmButton = { TextButton(onClick = { batchAction = null; changeBatch(action.id, action.retry) }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { batchAction = null }) { Text("返回") } })
    }
    if (cancelledBatchesVisible) AlertDialog(onDismissRequest = { cancelledBatchesVisible = false },
        title = { Text("已取消批次") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val cancelled = batches.filter { it.first.getBoolean("cancelled") }
            if (cancelled.isEmpty()) Text("没有已取消批次")
            cancelled.forEach { (batch, _) ->
                TextButton(onClick = { cancelledBatchesVisible = false; requestBatch(batch.getString("id"), true) }) {
                    Text("恢复整个批次（${batch.getInt("items")}项，${batch.getInt("complete")}项已完成）")
                }
            }
        } }, confirmButton = { TextButton(onClick = { cancelledBatchesVisible = false }) { Text("关闭") } })
    clearHistory?.let { kind ->
        AlertDialog(onDismissRequest = { clearHistory = null }, title = { Text(if (kind == "upload") "清除已完成手动批次记录？" else "清除已完成下载记录？") },
            text = { Text(if (kind == "upload") "只清除传输列表中已完成的手动批次；自动备份状态、手机和云端文件及上传回执均保留。"
                else "只清除传输列表中的完成记录；手机和云端文件均保留。") },
            confirmButton = { TextButton(onClick = {
                clearHistory = null
                if (kind == "download") DownloadTransfers.clearAllCompleted(profile)
                else scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val handle = TransferStore.open(context, profile).handle
                            batches.filter { it.first.getInt("items") > 0 && it.first.getInt("items") == it.first.getInt("complete") }
                                .forEach { TransferStore.clearCompletedBatch(handle, it.first.getString("id")) }
                        }
                        refresh()
                    } catch (error: CancellationException) { throw error }
                    catch (error: Exception) { notice = error.message ?: "清除记录失败" }
                }
            }) { Text("清除记录") } }, dismissButton = { TextButton(onClick = { clearHistory = null }) { Text("返回") } })
    }
    preview?.let { photo ->
        FullScreenPhotoDialog(onClose = { preview = null }) {
            ZoomablePhotoFrame(modifier = Modifier.testTag("transfers.preview"), onTap = { preview = null }) {
                TransferImage(context, api, profile, photo, true, it)
            }
        }
    }
}
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferHeader(title: String, progress: Float, speed: String, expanded: Boolean, tag: String,
    onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Surface(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(56.dp).testTag(tag).clip(AppShapes.control)
        .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick)
        .semantics { stateDescription = "${if (expanded) "已展开" else "已收起"}，${(progress * 100).toInt()}%，$speed" },
        shape = AppShapes.control, color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f).height(4.dp).testTag("$tag.progress"),
                trackColor = MaterialTheme.colorScheme.surfaceVariant, gapSize = 0.dp, drawStopIndicator = {})
            Text(speed, Modifier.width(78.dp).testTag("$tag.speed"), maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TransferPhotoTile(name: String, status: String, id: String, onClick: () -> Unit, onLongClick: () -> Unit,
    image: @Composable (Modifier) -> Unit) {
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(AppShapes.photo).testTag("transfers.photo.$id")
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        .semantics(mergeDescendants = true) { contentDescription = name; stateDescription = status }) { image(Modifier.fillMaxSize()) }
}

@Composable
private fun TransferImage(context: Context, api: BackupApi?, profile: String, photo: TransferPreview, preview: Boolean, modifier: Modifier) {
    if (photo.uri != null) LocalImage(context, JSONObject().put("source_id", photo.uri).put("name", photo.name), preview, modifier, thumbnailSize = 1024)
    else if (photo.asset != null) RemoteImage(context, api, profile, photo.asset, preview, modifier, showPlaceholderMessage = false)
    else Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(painterResource(R.drawable.ic_photo_library), contentDescription = "暂无预览", modifier = Modifier.size(24.dp)) }
}
