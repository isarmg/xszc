package org.sarmg.ana

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CloudVideo(api: BackupApi, resource: RemoteResource, active: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val player = remember(api, resource.cacheIdentity) {
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(api.videoFactory(resource))).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setMediaItem(MediaItem.fromUri(api.resolve(resource.contentPath)))
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    VideoPlayback(player, active, modifier)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun LocalVideo(uri: Uri, modifier: Modifier, onClose: () -> Unit, backupSelected: Boolean, onBackup: () -> Unit) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setAudioAttributes(AudioAttributes.DEFAULT, true)
            setMediaItem(MediaItem.fromUri(uri))
            playWhenReady = false
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    VideoPlayback(player, true, modifier, onClose, backupSelected, onBackup)
}

private val videoAccent = Color(0xFF72B7FF)
private val videoPanel = Color(0xFF20242B).copy(alpha = 0.86f)
private val videoSpeeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
private fun videoTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}
private fun speedLabel(speed: Float) = "${speed.toString().removeSuffix(".0")}×"

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoPlayback(
    player: ExoPlayer, active: Boolean, modifier: Modifier,
    onClose: (() -> Unit)? = null, backupSelected: Boolean = false, onBackup: (() -> Unit)? = null,
) {
    var playing by remember(player) { mutableStateOf(player.playWhenReady) }
    var ready by remember(player) { mutableStateOf(player.playbackState == Player.STATE_READY) }
    var buffering by remember(player) { mutableStateOf(false) }
    var failed by remember(player) { mutableStateOf(false) }
    var position by remember(player) { mutableLongStateOf(0) }
    var duration by remember(player) { mutableLongStateOf(0) }
    var buffered by remember(player) { mutableLongStateOf(0) }
    var speed by remember(player) { mutableFloatStateOf(1f) }
    var controls by remember(player) { mutableStateOf(true) }
    var speedMenu by remember(player) { mutableStateOf(false) }
    var seeking by remember(player) { mutableStateOf(false) }
    var seekPosition by remember(player) { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(player, lifecycle) {
        fun update() {
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED
            ready = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
            buffering = player.playbackState == Player.STATE_BUFFERING
            failed = player.playerError != null
            speed = player.playbackParameters.speed
            if (!playing) controls = true
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { update() }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        player.addListener(listener); lifecycle.addObserver(observer); update()
        onDispose { player.removeListener(listener); lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(player, active) {
        if (!active) player.pause()
        while (isActive && active) {
            position = player.currentPosition.coerceAtLeast(0)
            duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0
            buffered = player.bufferedPosition.coerceAtLeast(0)
            delay(250)
        }
    }
    LaunchedEffect(playing, controls, seeking, speedMenu) {
        if (playing && controls && !seeking && !speedMenu) { delay(3500); controls = false }
    }
    fun togglePlayback() {
        controls = true
        if (playing) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = videoAccent, surface = videoPanel, onSurface = Color.White)) {
        Box(modifier.background(Color.Black).testTag("video.preview")) {
            AndroidView(factory = { context -> PlayerView(context).apply {
                this.player = player
                useController = false
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                isClickable = false; isFocusable = false
            } }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
            // Keep the video surface separate from accessible buttons and slider gestures.
            Box(Modifier.fillMaxSize().clickable(onClickLabel = "显示或隐藏播放控件") { controls = !controls })
            AnimatedVisibility(controls, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    if (onClose != null || onBackup != null) Row(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter)
                            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent)))
                            .safeDrawingPadding().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (onClose != null) VideoIconButton(R.drawable.ic_video_close, "关闭视频", onClose, Modifier.testTag("video.close"))
                        Spacer(Modifier.weight(1f))
                        if (onBackup != null) Surface(shape = CircleShape, color = if (backupSelected) videoAccent.copy(alpha = 0.22f) else videoPanel) {
                            TextButton(onClick = onBackup, modifier = Modifier.testTag("video.backup").semantics {
                                contentDescription = if (backupSelected) "取消选择备份" else "选择备份"
                            },
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                                Icon(painterResource(if (backupSelected) R.drawable.ic_check else R.drawable.ic_cloud), null, Modifier.size(20.dp), tint = videoAccent)
                                Spacer(Modifier.width(8.dp))
                                Text(if (backupSelected) "已选择" else "备份", color = Color.White)
                            }
                        }
                    }
                    // Seeking temporarily returns ExoPlayer to BUFFERING. Keep
                    // transport controls available once the duration is known.
                    if ((ready || duration > 0) && !failed) VideoIconButton(
                        if (playing) R.drawable.ic_video_pause else R.drawable.ic_video_play,
                        if (playing) "暂停" else "播放", ::togglePlayback,
                        Modifier.align(Alignment.Center).size(76.dp).testTag("video.play"),
                    )
                    Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                        .safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp)) {
                        val maximum = duration.toFloat().coerceAtLeast(1f)
                        Slider(value = (if (seeking) seekPosition else position.toFloat()).coerceIn(0f, maximum),
                            onValueChange = { seeking = true; seekPosition = it },
                            onValueChangeFinished = { player.seekTo(seekPosition.toLong()); position = seekPosition.toLong(); seeking = false },
                            valueRange = 0f..maximum, enabled = duration > 0 && !failed,
                            modifier = Modifier.fillMaxWidth().testTag("video.progress").semantics { contentDescription = "播放进度" },
                            thumb = { Box(Modifier.size(12.dp).background(Color.White, CircleShape)) },
                            track = { state ->
                                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                                    val middle = size.height / 2f
                                    fun line(fraction: Float, color: Color) {
                                        drawLine(color, Offset(0f, middle), Offset(size.width * fraction.coerceIn(0f, 1f), middle), size.height, StrokeCap.Round)
                                    }
                                    line(1f, Color.White.copy(alpha = 0.18f))
                                    line(buffered.toFloat() / maximum, Color.White.copy(alpha = 0.3f))
                                    line(state.value / maximum, videoAccent)
                                }
                            })
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(videoTime(if (seeking) seekPosition.toLong() else position), color = Color.White,
                                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            Text(" / ${videoTime(duration)}", color = Color.White.copy(alpha = 0.55f),
                                style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.weight(1f))
                            Box {
                                Surface(shape = CircleShape, color = videoPanel) {
                                    TextButton(onClick = { speedMenu = true }, modifier = Modifier.testTag("video.speed")
                                        .semantics { contentDescription = "播放倍速" }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                                        Text(speedLabel(speed), color = Color.White)
                                    }
                                }
                                DropdownMenu(speedMenu, { speedMenu = false }, shape = AppShapes.group, containerColor = Color(0xFF20242B)) {
                                    videoSpeeds.forEach { value ->
                                        DropdownMenuItem(text = { Text(speedLabel(value)) },
                                            trailingIcon = { if (speed == value) Icon(painterResource(R.drawable.ic_check), null, Modifier.size(18.dp), tint = videoAccent) },
                                            onClick = { player.setPlaybackSpeed(value); speed = value; speedMenu = false })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (buffering && !failed) CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp), color = Color.White, strokeWidth = 2.dp)
            if (failed) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("视频暂时无法播放", color = Color.White)
                TextButton(onClick = { player.prepare() }) { Text("重试", color = videoAccent) }
            }
        }
    }
}

@Composable
private fun VideoIconButton(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, modifier = modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
        .semantics { contentDescription = label }, shape = CircleShape, color = videoPanel) {
        Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(if (label == "关闭视频") 22.dp else 30.dp), tint = Color.White)
        }
    }
}
