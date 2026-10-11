package org.sarmg.xszc

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Context
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.rotate
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        BackupScheduler.syncAutomatic(this, SecureConfig(this))
        setContent {
            AppTheme { AppNavigation(this) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNavigation(context: Context) {
    val config = remember { SecureConfig(context) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var profile by remember { mutableStateOf(config.profile) }
    var account by remember { mutableStateOf(false) }
    var accountRevision by remember { mutableIntStateOf(0) }
    Scaffold(containerColor = if (tab == 3) settingsBackgroundColor() else MaterialTheme.colorScheme.background, bottomBar = {
        AppTabBar(tab) { tab = it }
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            key(profile, accountRevision) {
                when (tab) {
                    0 -> LocalGalleryScreen(context, config, profile, onSubmitted = { tab = 2 }, onLogin = { account = true })
                    1 -> if (config.serverUrl.isBlank() || config.authorizationCode.isBlank()) {
                        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                            GalleryEmptyState("登录后查看云端照片", "随时浏览、收藏和下载已备份的媒体。", R.drawable.ic_cloud)
                            Button(shape = AppShapes.control, onClick = { account = true }) { Text("登录账户") }
                        }
                    } else CloudGalleryScreen(context, config, profile)
                    2 -> TransfersScreen(context, config, profile)
                    else -> SettingsScreen(context, config, onLogin = { account = true }, onLoggedOut = { accountRevision++ })
                }
            }
        }
    }
    if (account) AccountDialog(context, config, onDismiss = { account = false }, onLoggedIn = {
        DownloadQueue.cancelPending()
        TransferTelemetry.reset()
        profile = config.profile; accountRevision++; account = false
    })

}

@Composable
private fun SettingsScreen(context: Context, config: SecureConfig, onLogin: () -> Unit, onLoggedOut: () -> Unit) {
    val scope = rememberCoroutineScope()
    var auto by remember { mutableStateOf(config.autoBackup) }
    var wifi by remember { mutableStateOf(config.wifiOnly) }
    var charging by remember { mutableStateOf(config.chargingOnly) }
    var photos by remember { mutableStateOf(config.backupPhotos) }
    var videos by remember { mutableStateOf(config.backupVideos) }
    var camera by remember { mutableStateOf(config.cameraOnly) }
    var albums by remember { mutableStateOf<List<DeviceAlbum>>(emptyList()) }
    var selected by remember { mutableStateOf(config.selectedAlbumIds) }
    var notice by remember { mutableStateOf("") }
    var albumsExpanded by remember { mutableStateOf(false) }
    var cacheLimit by remember { mutableIntStateOf(RemoteImageCache.limit(context)) }
    fun loadAlbums() { scope.launch { albums = withContext(Dispatchers.IO) { if (LocalCatalog.hasAccess(context)) runCatching { DeviceAlbums.list(context) }.getOrDefault(emptyList()) else emptyList() } } }
    fun applyPreferences() {
        val networkChanged = wifi != config.wifiOnly || charging != config.chargingOnly
        config.autoBackup = auto; config.wifiOnly = wifi; config.chargingOnly = charging
        config.backupPhotos = photos; config.backupVideos = videos; config.cameraOnly = camera
        config.selectedAlbumIds = selected
        if (networkChanged) BackupScheduler.stopCurrent(context, config)
        BackupScheduler.syncAutomatic(context, config)
        notice = when {
            auto && !photos && !videos -> "自动备份已开启，请至少选择一种媒体类型"
            auto && !camera && selected.isEmpty() -> "自动备份已开启，请选择自动备份相册"
            else -> "备份偏好已生效"
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) loadAlbums() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { loadAlbums() }
    LazyColumn(Modifier.fillMaxSize().background(settingsBackgroundColor()).testTag("settings.list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)) {
        item {
            SettingsGroup("账户") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 60.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).clickable(onClick = onLogin).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(painterResource(R.drawable.ic_account), null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(if (!config.isLoggedIn) "尚未登录" else "备份实例已登录", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            if (config.serverUrl.isNotEmpty()) Text(config.serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (config.isLoggedIn) TextButton(shape = AppShapes.control, onClick = {
                        scope.launch {
                            config.logout()
                            DownloadQueue.cancelPending()
                            TransferTelemetry.reset()
                            withContext(Dispatchers.IO) {
                                androidx.work.WorkManager.getInstance(context).cancelAllWorkByTag(BackupScheduler.TAG).result.get()
                            }
                            onLoggedOut()
                        }
                    }) { Text("退出", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
        item {
            SettingsGroup("照片访问") {
                SettingsAction("照片权限设置") {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                }
            }
        }
        item {
            SettingsGroup("备份偏好") {
                SettingToggle("自动备份", auto) { auto = it; applyPreferences() }; SettingsDivider()
                SettingToggle("仅 Wi-Fi 上传", wifi) { wifi = it; applyPreferences() }; SettingsDivider()
                SettingToggle("后台仅充电时运行", charging) { charging = it; applyPreferences() }; SettingsDivider()
                SettingToggle("自动备份照片", photos) { photos = it; applyPreferences() }; SettingsDivider()
                SettingToggle("自动备份视频", videos) { videos = it; applyPreferences() }
            }
        }
        item {
            SettingsGroup {
                Row(Modifier.fillMaxWidth().clickable { albumsExpanded = !albumsExpanded }.padding(horizontal = 16.dp).heightIn(min = 52.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("自动备份相册", Modifier.weight(1f), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(20.dp).rotate(if (albumsExpanded) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (albumsExpanded) {
                    SettingsDivider()
                    SettingsAction("选择可访问的相册") { loadAlbums() }; SettingsDivider()
                    SettingToggle("仅相机目录", camera) { camera = it; applyPreferences() }
                    if (!camera) albums.forEach { album ->
                        SettingsDivider()
                        SettingToggle("${album.name}（${album.count} 项）", album.id in selected) { enabled ->
                            selected = if (enabled) selected + album.id else selected - album.id; applyPreferences()
                        }
                    }
                }
            }
        }
        if (notice.isNotEmpty() && notice != "备份偏好已生效") item { Text(notice, Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall) }
        item {
            SettingsGroup("浏览缓存") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("磁盘缓存：$cacheLimit MiB", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Surface(shape = AppShapes.compact, color = MaterialTheme.colorScheme.secondaryContainer) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(enabled = cacheLimit > 64, onClick = { cacheLimit -= 64; RemoteImageCache.setLimit(context, cacheLimit) }, modifier = Modifier.size(40.dp).testTag("settings.cache.decrease")) { Text("−", style = MaterialTheme.typography.titleLarge) }
                            Box(Modifier.width(1.dp).height(18.dp).background(MaterialTheme.colorScheme.outlineVariant))
                            IconButton(enabled = cacheLimit < 1024, onClick = { cacheLimit += 64; RemoteImageCache.setLimit(context, cacheLimit) }, modifier = Modifier.size(40.dp).testTag("settings.cache.increase")) { Text("+", style = MaterialTheme.typography.titleLarge) }
                        }
                    }
                }
                SettingsDivider()
                SettingsAction("清空浏览缓存") { scope.launch { withContext(Dispatchers.IO) { RemoteImageCache.clear(context) }; notice = "浏览图片缓存已清空" } }
            }
        }
    }
}

@Composable
private fun SettingToggle(label: String, value: Boolean, changed: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value = value, role = Role.Switch, onValueChange = changed).padding(horizontal = 16.dp).heightIn(min = 52.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(value, onCheckedChange = null, thumbContent = { Spacer(Modifier.size(24.dp)) }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White,
            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant, uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = Color.White))
    }
}

@Composable
private fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) Text(title, Modifier.padding(start = 16.dp, bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = AppShapes.group, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsDivider() { HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant) }

@Composable
private fun SettingsAction(label: String, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
    }
}
