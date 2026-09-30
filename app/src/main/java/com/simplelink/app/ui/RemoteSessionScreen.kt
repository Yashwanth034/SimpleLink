package com.simplelink.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.simplelink.app.control.RemoteControlCommand
import com.simplelink.app.session.Role
import com.simplelink.app.webrtc.RemoteInputState
import com.simplelink.app.webrtc.ScreenSize
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun RemoteSessionScreen(
    role: Role,
    status: String,
    videoTrack: VideoTrack?,
    screenSize: ScreenSize,
    inputState: RemoteInputState,
    eglContext: EglBase.Context,
    onControl: (RemoteControlCommand) -> Unit,
    onDisconnect: () -> Unit
) {
    if (role == Role.HOST) {
        HostConnectedScreen(status, onDisconnect)
    } else {
        ViewerScreen(videoTrack, screenSize, eglContext, onControl)
    }
}

@Composable
private fun HostConnectedScreen(status: String, onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(status, fontSize = 28.sp)
        Spacer(Modifier.size(12.dp))
        Text(
            "This phone is being shared. A system notification stays visible while the session is active.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(28.dp))
        GlassActionButton("End Session", onDisconnect)
    }
}

@Composable
private fun ViewerScreen(
    videoTrack: VideoTrack?,
    screenSize: ScreenSize,
    eglContext: EglBase.Context,
    onControl: (RemoteControlCommand) -> Unit
) {
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (videoTrack != null) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val remoteAspect = if (screenSize.width > 0 && screenSize.height > 0) {
                    screenSize.width / screenSize.height.toFloat()
                } else {
                    9f / 16f
                }
                val viewportAspect = maxWidth.value / maxHeight.value
                val fitted = if (viewportAspect > remoteAspect) {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(remoteAspect, matchHeightConstraintsFirst = true)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(remoteAspect)
                }
                WebRtcRenderer(videoTrack, eglContext, fitted)
            }
        } else {
            Text(
                "Connecting…",
                color = Color.White.copy(alpha = .72f),
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(videoTrack, screenSize, viewSize) {
                    detectTapGestures(
                        onTap = { offset ->
                            mapPoint(offset, viewSize, screenSize)?.let { point ->
                                onControl(RemoteControlCommand.Tap(point.x, point.y))
                            }
                        },
                        onLongPress = { offset ->
                            mapPoint(offset, viewSize, screenSize)?.let { point ->
                                onControl(RemoteControlCommand.LongPress(point.x, point.y))
                            }
                        }
                    )
                }
                .pointerInput(videoTrack, screenSize, viewSize) {
                    detectDragGestures(
                        onDragStart = { start ->
                            dragStart = start
                            dragEnd = start
                        },
                        onDrag = { change, _ ->
                            dragEnd = change.position
                        },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            if (start != null && end != null) {
                                val dx = end.x - start.x
                                val dy = end.y - start.y
                                if (dx * dx + dy * dy >= 16f) {
                                    sendMappedSwipe(
                                        start,
                                        end,
                                        viewSize,
                                        screenSize,
                                        durationFor(start, end),
                                        onControl
                                    )
                                }
                            }
                            dragStart = null
                            dragEnd = null
                        },
                        onDragCancel = {
                            dragStart = null
                            dragEnd = null
                        }
                    )
                }
        )
    }
}

private fun sendMappedSwipe(
    start: Offset,
    end: Offset,
    viewSize: IntSize,
    screenSize: ScreenSize,
    durationMs: Long,
    onControl: (RemoteControlCommand) -> Unit
) {
    val a = mapPointClamped(start, viewSize, screenSize) ?: return
    val b = mapPointClamped(end, viewSize, screenSize) ?: return
    onControl(RemoteControlCommand.Swipe(a.x, a.y, b.x, b.y, durationMs))
}

private fun durationFor(start: Offset, end: Offset): Long {
    val distance = (end - start).getDistance()
    return (distance * 0.75f).toInt().coerceIn(90, 420).toLong()
}

@Composable
private fun WebRtcRenderer(
    track: VideoTrack,
    eglContext: EglBase.Context,
    modifier: Modifier
) {
    val context = LocalContext.current
    val renderer = remember(track, eglContext) {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    }
    DisposableEffect(track) {
        track.addSink(renderer)
        onDispose {
            track.removeSink(renderer)
            renderer.release()
        }
    }
    AndroidView(
        factory = { renderer },
        update = {
            it.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        },
        modifier = modifier
    )
}

private fun mapPoint(offset: Offset, view: IntSize, remote: ScreenSize): Offset? {
    if (view.width <= 0 || view.height <= 0 || remote.width <= 0 || remote.height <= 0) return null
    val scale = minOf(view.width / remote.width.toFloat(), view.height / remote.height.toFloat())
    val contentWidth = remote.width * scale
    val contentHeight = remote.height * scale
    val left = (view.width - contentWidth) / 2f
    val top = (view.height - contentHeight) / 2f
    if (
        offset.x < left || offset.x > left + contentWidth ||
        offset.y < top || offset.y > top + contentHeight
    ) return null
    return Offset(
        ((offset.x - left) / contentWidth).coerceIn(0f, 1f),
        ((offset.y - top) / contentHeight).coerceIn(0f, 1f)
    )
}

private fun mapPointClamped(offset: Offset, view: IntSize, remote: ScreenSize): Offset? {
    if (view.width <= 0 || view.height <= 0 || remote.width <= 0 || remote.height <= 0) return null
    val scale = minOf(view.width / remote.width.toFloat(), view.height / remote.height.toFloat())
    val contentWidth = remote.width * scale
    val contentHeight = remote.height * scale
    val left = (view.width - contentWidth) / 2f
    val top = (view.height - contentHeight) / 2f
    return Offset(
        ((offset.x.coerceIn(left, left + contentWidth) - left) / contentWidth).coerceIn(0f, 1f),
        ((offset.y.coerceIn(top, top + contentHeight) - top) / contentHeight).coerceIn(0f, 1f)
    )
}
