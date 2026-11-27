package org.sarmg.ana

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.roundToInt
import androidx.compose.ui.unit.IntSize
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

@Composable
internal fun FullScreenPhotoDialog(onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val view = LocalView.current
        DisposableEffect(view) {
            val window = (view.parent as? DialogWindowProvider)?.window
            window?.setWindowAnimations(0)
            val controller = window?.let { WindowCompat.getInsetsController(it, view) }
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black)) { content() }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ZoomablePhotoFrame(
    modifier: Modifier = Modifier,
    onTap: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    content: @Composable (Modifier) -> Unit,
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    Box(modifier.fillMaxSize().background(Color.Black)
        .onSizeChanged { viewport = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                var active = true
                while (active) {
                    val event = awaitPointerEvent()
                    if (event.changes.count { it.pressed } >= 2) {
                        zoom = (zoom * event.calculateZoom()).coerceIn(1f, 5f)
                        val pan = event.calculatePan()
                        val maxX = viewport.width * (zoom - 1f) / 2f
                        val maxY = viewport.height * (zoom - 1f) / 2f
                        offsetX = (offsetX + pan.x).coerceIn(-maxX, maxX)
                        offsetY = (offsetY + pan.y).coerceIn(-maxY, maxY)
                        if (zoom == 1f) { offsetX = 0f; offsetY = 0f }
                        event.changes.forEach { it.consume() }
                    }
                    active = event.changes.any { it.pressed }
                }
            }
        }
        .combinedClickable(onClick = onTap, onLongClick = onLongPress)
        .semantics { contentDescription = "照片预览，点按关闭，双指缩放" }) {
        content(Modifier.fillMaxSize().graphicsLayer(scaleX = zoom, scaleY = zoom,
            translationX = offsetX, translationY = offsetY))
    }
}

@Composable
internal fun Modifier.galleryGridPinch(columns: Int, onColumnsChanged: (Int) -> Unit): Modifier {
    val updateColumns by rememberUpdatedState(onColumnsChanged)
    val currentColumns by rememberUpdatedState(columns)
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var scale = 1f
            var baseColumns: Int? = null
            var active = true
            while (active) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    if (baseColumns == null) baseColumns = currentColumns
                    scale *= event.calculateZoom()
                    updateColumns((baseColumns!! / scale).roundToInt().coerceIn(1, 8))
                    event.changes.forEach { it.consume() }
                }
                active = event.changes.any { it.pressed }
            }
        }
    }
}
