package com.afeicn.aiplayer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.afeicn.aiplayer.MediaEntry
import com.afeicn.aiplayer.PlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 真正的横屏播放器：Media3 PlayerView 画布（Fit 保持原画比例）+ Compose 控制层。
 * 进度每 500ms 轮询一次；退出时由调用方保存断点（[onSaveProgress]）。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    entry: MediaEntry,
    hasPrev: Boolean,
    hasNext: Boolean,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onEpisodes: () -> Unit,
    onSaveProgress: () -> Unit
) {
    val context = LocalContext.current
    val player = remember { PlayerEngine.player(context) }
    val motionOn = rememberMotionEnabled()

    var playing by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(player.currentPosition) }
    var duration by remember { mutableLongStateOf(player.duration.coerceAtLeast(0L)) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPosition by remember { mutableLongStateOf(0L) }
    var speedIndex by remember { mutableIntStateOf(0) }
    val speeds = listOf(1.0f, 1.25f, 1.5f, 2.0f)
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(player, entry) {
        while (isActive) {
            if (!scrubbing) position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            playing = player.isPlaying
            delay(500)
        }
    }
    LaunchedEffect(playing, controlsVisible) {
        // 控制层显示 3 秒后自动隐藏（暂停态保持可见）。
        if (playing && controlsVisible) { delay(3000); controlsVisible = false }
    }
    DisposableEffect(entry) {
        onDispose { onSaveProgress() }
    }
    DisposableEffect(Unit) {
        onDispose {
            // ExoPlayer 是全局单例，不随页面销毁：离开播放页必须先存进度再立即 stop，
            // 否则返回后视频在后台继续出声。先存后停，stop 会把位置清零。
            onSaveProgress()
            player.stop()
        }
    }

    val shownPosition = if (scrubbing) scrubPosition else position
    val sliderValue = if (duration > 0) shownPosition.toFloat() / duration else 0f

    Box(Modifier.fillMaxSize().background(Color.Black).clickable { controlsVisible = !controlsVisible }) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setPlayer(player)
                }
            },
            onRelease = { view -> view.setPlayer(null as Player?) },
            modifier = Modifier.fillMaxSize()
        )
        // 控制层整体淡入淡出 180ms，不再布尔硬切；内层 Box 承载原来的对齐布局。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(motionDuration(motionOn, Motion.CONTROLS))),
            exit = fadeOut(tween(motionDuration(motionOn, Motion.CONTROLS))),
            label = "playerControls"
        ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xA3000000), Color.Transparent, Color(0xBC000000)))
            ))
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 25.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PlayerIcon(Icons.Default.ArrowBack, "返回", onBack)
                Column(Modifier.weight(1f)) {
                    Text(entry.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1)
                    if (entry.title.isNotBlank()) {
                        Text(entry.title, color = Color.White.copy(alpha = .75f), fontSize = 12.sp, maxLines = 1)
                    }
                }
                if (entry.offlineOnly) {
                    Text("离线", fontSize = 10.sp, color = Color.White.copy(alpha = .65f))
                }
                Surface(
                    color = Color.White.copy(alpha = .15f), shape = CircleShape,
                    onClick = {
                        speedIndex = (speedIndex + 1) % speeds.size
                        player.setPlaybackSpeed(speeds[speedIndex])
                    }
                ) {
                    Text("${speeds[speedIndex]}x".removeSuffix(".0"), color = Color.White, fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
                }
            }
            Row(
                Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(65.dp)
            ) {
                PlayerIcon(Icons.Default.Replay10, "快退10秒",
                    { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)) }, 34.dp)
                Surface(
                    color = Color.Black.copy(alpha = .35f), shape = CircleShape, modifier = Modifier.size(74.dp),
                    onClick = {
                        if (player.isPlaying) player.pause() else {
                            player.playWhenReady = true
                            player.play()
                        }
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Crossfade(targetState = playing,
                            animationSpec = tween(motionDuration(motionOn, Motion.ICON_STATE)),
                            label = "playPause") { isPlaying ->
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "暂停或播放",
                                tint = Color.White, modifier = Modifier.size(43.dp))
                        }
                    }
                }
                PlayerIcon(Icons.Default.Forward10, "快进10秒",
                    { player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration)) }, 34.dp)
            }
            Column(
                Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(formatTime(shownPosition), color = Color.White, fontSize = 12.sp)
                    Slider(
                        value = sliderValue,
                        onValueChange = {
                            scrubbing = true
                            scrubPosition = (it * duration).toLong()
                        },
                        onValueChangeFinished = {
                            player.seekTo(scrubPosition)
                            position = scrubPosition
                            scrubbing = false
                        },
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(32.dp),
                        colors = SliderDefaults.colors(
                            thumbColor = Color(0xFFBB9AFE), activeTrackColor = Color(0xFFBB9AFE),
                            inactiveTrackColor = Color.White.copy(alpha = .45f)
                        )
                    )
                    Text(formatTime(duration), color = Color.White, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    PlayerAction(Icons.Default.SkipPrevious, "上一集", enabled = hasPrev, onClick = onPrev)
                    Spacer(Modifier.width(20.dp))
                    PlayerAction(Icons.Default.List, "选集", onClick = onEpisodes)
                    Spacer(Modifier.weight(1f))
                    PlayerAction(Icons.Default.SkipNext, "下一集", enabled = hasNext, onClick = onNext)
                    Spacer(Modifier.width(20.dp))
                    PlayerAction(Icons.Default.MoreHoriz, "倍速",
                        onClick = {
                            speedIndex = (speedIndex + 1) % speeds.size
                            player.setPlaybackSpeed(speeds[speedIndex])
                        })
                }
            }
        }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun PlayerIcon(image: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, size: androidx.compose.ui.unit.Dp = 24.dp) {
    IconButton(onClick = onClick, modifier = Modifier.size(43.dp)) {
        Icon(image, contentDescription = label, tint = Color.White, modifier = Modifier.size(size))
    }
}

@Composable
private fun PlayerAction(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = if (enabled) Color.White else Color.White.copy(alpha = .35f)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(RoundedCornerShape(15.dp))
            .clickable(enabled = enabled, onClick = onClick).padding(7.dp)
    ) {
        Icon(image, null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp, color = tint)
    }
}
