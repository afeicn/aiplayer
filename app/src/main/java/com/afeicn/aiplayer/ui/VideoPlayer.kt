package com.afeicn.aiplayer.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.afeicn.aiplayer.AcceleratedSeek
import com.afeicn.aiplayer.MediaEntry
import com.afeicn.aiplayer.PlayerEngine
import com.afeicn.aiplayer.PlayerGestureRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    // ==================== 手势层状态（行为对齐 104vplayer GesturePlayerView） ====================
    val activity = remember(context) { context.findActivity() }
    val audioManager = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }
    val uiScope = rememberCoroutineScope()
    var speedOverlay by remember { mutableStateOf<Float?>(null) }
    var seekPreview by remember { mutableStateOf<SeekPreview?>(null) }
    var levelOverlay by remember { mutableStateOf<LevelOverlay?>(null) }
    var gestureHint by remember { mutableStateOf<String?>(null) }
    // 长按倍速是临时改速，松手恢复到按下时的倍速。
    var gestureBaseSpeed by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(gestureHint) {
        if (gestureHint != null) { delay(900); gestureHint = null }
    }
    LaunchedEffect(levelOverlay) {
        if (levelOverlay != null) { delay(900); levelOverlay = null }
    }

    fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            player.playWhenReady = true
            player.play()
        }
        playing = player.isPlaying
    }
    fun seekBy(deltaMs: Long) {
        val total = player.duration.coerceAtLeast(0L)
        val target = (player.currentPosition + deltaMs).coerceIn(0L, if (total > 0) total else Long.MAX_VALUE)
        player.seekTo(target)
        position = target
    }

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
            // 手势调过的亮度只作用于播放窗口，退出播放页恢复系统亮度。
            restoreSystemBrightness(activity)
        }
    }

    val shownPosition = if (scrubbing) scrubPosition else position
    val sliderValue = if (duration > 0) shownPosition.toFloat() / duration else 0f

    Box(Modifier.fillMaxSize().background(MediaOverlayColors.Canvas)) {
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
        // 手势层：盖在画面上、垫在控件下面——落在按钮/滑块上的点击先被控件自己吃掉，
        // 空白区域的按下才进这里。手势判定顺序对齐 104vplayer 的 GesturePlayerView：
        // 先按 touchSlop 定方向（横向快进快退 / 纵向亮度音量），按住超过长按时长则进倍速模式且只认竖向。
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                val slop = viewConfiguration.touchSlop
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                val doubleTapSlop = 64.dp.toPx()
                var lastTapUpTime = 0L
                var lastTapPos = Offset.Zero
                var tapSeq = 0

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 不抢按下事件：若上层控件（按钮/滑块）先接走了这次按下，整个手势让给它。
                    if (down.isConsumed) return@awaitEachGesture
                    val downPos = down.position
                    val startTime = down.uptimeMillis
                    var axis = PlayerGestureRules.Axis.NONE
                    var longPress = false
                    var claimed = false
                    var cancelled = false
                    var total = Offset.Zero
                    var lastPos = downPos
                    var upPos = downPos
                    var upTime = startTime

                    // 横向拖动：加速快进快退（拖动只更新预览，松手才真正 seek）。
                    var accelerated: AcceleratedSeek? = null
                    var seekStartMs = 0L
                    var seekDurationMs = 0L
                    var seekTargetMs = 0L
                    // 竖向拖动：左半屏调亮度、右半屏调音量。
                    var levelSide: PlayerGestureRules.Side? = null
                    var levelStart = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            upPos = change.position
                            upTime = change.uptimeMillis
                            if (claimed) change.consume() else if (change.isConsumed) cancelled = true
                            break
                        }
                        if (!claimed && change.isConsumed) { cancelled = true; break }
                        total += change.position - lastPos
                        lastPos = change.position

                        if (longPress) {
                            // 长按倍速和横向拖动互斥：激活后只认竖向位移。
                            change.consume()
                            val speed = PlayerGestureRules.longPressSpeed(total.y / density)
                            if (speed != null && speed != speedOverlay) {
                                player.setPlaybackSpeed(speed)
                                speedOverlay = speed
                            }
                            continue
                        }

                        if (!claimed) {
                            // 按住超过长按时长才开始动：算长按倍速，不按方向走普通滑动。
                            if (change.uptimeMillis - startTime >= longPressTimeout) {
                                longPress = true
                                claimed = true
                                change.consume()
                                gestureBaseSpeed = player.playbackParameters.speed
                                val speed = PlayerGestureRules.longPressSpeed(total.y / density) ?: 1.5f
                                player.setPlaybackSpeed(speed)
                                speedOverlay = speed
                                continue
                            }
                            axis = PlayerGestureRules.axisOf(total.x, total.y, slop)
                            if (axis == PlayerGestureRules.Axis.NONE) continue
                            claimed = true
                            change.consume()
                            when (axis) {
                                PlayerGestureRules.Axis.HORIZONTAL -> {
                                    val durationMs = player.duration.coerceAtLeast(0L)
                                    if (durationMs > 0) {
                                        seekDurationMs = durationMs
                                        seekStartMs = player.currentPosition.coerceIn(0L, durationMs)
                                        seekTargetMs = seekStartMs
                                        accelerated = AcceleratedSeek(seekStartMs, durationMs)
                                        seekPreview = SeekPreview(seekStartMs, seekTargetMs, seekDurationMs)
                                    }
                                }
                                PlayerGestureRules.Axis.VERTICAL -> {
                                    levelSide = PlayerGestureRules.sideOf(downPos.x, size.width.toFloat())
                                    levelStart = when (levelSide) {
                                        PlayerGestureRules.Side.BRIGHTNESS -> currentWindowBrightness(activity)
                                        else -> currentVolume(audioManager)
                                    }
                                }
                            }
                        } else {
                            change.consume()
                        }

                        when (axis) {
                            PlayerGestureRules.Axis.HORIZONTAL -> accelerated?.let { seek ->
                                seekTargetMs = seek.move(total.x / density)
                                seekPreview = SeekPreview(seekStartMs, seekTargetMs, seekDurationMs)
                            }
                            PlayerGestureRules.Axis.VERTICAL -> levelSide?.let { side ->
                                val fraction = -total.y / size.height.coerceAtLeast(1)
                                when (side) {
                                    PlayerGestureRules.Side.BRIGHTNESS -> {
                                        val level = PlayerGestureRules.adjustLevel(levelStart, fraction, min = 0.05f)
                                        setWindowBrightness(activity, level)
                                        levelOverlay = LevelOverlay(side, level)
                                    }
                                    PlayerGestureRules.Side.VOLUME -> {
                                        val level = PlayerGestureRules.adjustLevel(levelStart, fraction)
                                        applyVolume(audioManager, level)
                                        levelOverlay = LevelOverlay(side, level)
                                    }
                                }
                            }
                            else -> Unit
                        }
                    }

                    // 手势收尾：恢复倍速 / 松手才 seek / 收起预览 / 判定单击双击。
                    when {
                        longPress -> {
                            player.setPlaybackSpeed(gestureBaseSpeed)
                            speedOverlay = null
                        }
                        axis == PlayerGestureRules.Axis.HORIZONTAL -> {
                            if (!cancelled && accelerated != null && seekDurationMs > 0) {
                                player.seekTo(seekTargetMs)
                                position = seekTargetMs
                            }
                            seekPreview = null
                        }
                        axis == PlayerGestureRules.Axis.VERTICAL -> Unit
                        !claimed && !cancelled -> {
                            if (upTime - startTime >= longPressTimeout) {
                                // 长按到底没滑就松手：既不调速也不当单击。
                            } else {
                                val isDouble = upTime - lastTapUpTime in 1..doubleTapTimeout &&
                                    (upPos - lastTapPos).getDistance() <= doubleTapSlop
                                if (isDouble) {
                                    lastTapUpTime = 0L
                                    tapSeq++
                                    when (PlayerGestureRules.doubleTapZone(upPos.x, size.width.toFloat())) {
                                        PlayerGestureRules.DoubleTapZone.BACK -> {
                                            seekBy(-PlayerGestureRules.EDGE_SEEK_MS)
                                            gestureHint = "后退 10 秒"
                                        }
                                        PlayerGestureRules.DoubleTapZone.FORWARD -> {
                                            seekBy(PlayerGestureRules.EDGE_SEEK_MS)
                                            gestureHint = "前进 10 秒"
                                        }
                                        PlayerGestureRules.DoubleTapZone.CENTER -> togglePlay()
                                    }
                                } else {
                                    lastTapUpTime = upTime
                                    lastTapPos = upPos
                                    val seq = ++tapSeq
                                    uiScope.launch {
                                        delay(doubleTapTimeout + 60L)
                                        if (seq == tapSeq) controlsVisible = !controlsVisible
                                    }
                                }
                            }
                        }
                    }
                }
            }
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
                Brush.verticalGradient(MediaOverlayColors.ControlGradient)
            ))
            Row(
                Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 25.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PlayerIcon(Icons.Default.ArrowBack, "返回", onBack)
                Column(Modifier.weight(1f)) {
                    Text(entry.name, color = MediaOverlayColors.TextPrimary, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (entry.title.isNotBlank()) {
                        Text(entry.title, color = MediaOverlayColors.TextSecondary, fontSize = 12.sp, maxLines = 1)
                    }
                }
                if (entry.offlineOnly) {
                    Text("离线", fontSize = 10.sp, color = MediaOverlayColors.TextSecondary)
                }
                Surface(
                    color = MediaOverlayColors.ButtonTint, shape = CircleShape,
                    onClick = {
                        speedIndex = (speedIndex + 1) % speeds.size
                        player.setPlaybackSpeed(speeds[speedIndex])
                    }
                ) {
                    Text("${speeds[speedIndex]}x".removeSuffix(".0"), color = MediaOverlayColors.TextPrimary,
                        fontSize = 12.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
                }
            }
            Row(
                Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(65.dp)
            ) {
                PlayerIcon(Icons.Default.Replay10, "快退10秒",
                    { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)) }, 34.dp)
                Surface(
                    color = MediaOverlayColors.ControlCircle, shape = CircleShape, modifier = Modifier.size(74.dp),
                    onClick = { togglePlay() }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Crossfade(targetState = playing,
                            animationSpec = tween(motionDuration(motionOn, Motion.ICON_STATE)),
                            label = "playPause") { isPlaying ->
                            Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "暂停或播放",
                                tint = MediaOverlayColors.TextPrimary, modifier = Modifier.size(43.dp))
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
                    Text(formatTime(shownPosition), color = MediaOverlayColors.TextPrimary, fontSize = 12.sp)
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
                            thumbColor = MediaOverlayColors.Accent,
                            activeTrackColor = MediaOverlayColors.Accent,
                            inactiveTrackColor = MediaOverlayColors.TrackInactive
                        )
                    )
                    Text(formatTime(duration), color = MediaOverlayColors.TextPrimary, fontSize = 12.sp)
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

        // ==================== 手势浮层（无点击处理，不拦截触摸） ====================
        speedOverlay?.let { speed ->
            Surface(
                color = MediaOverlayColors.Scrim, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 16.dp)
            ) {
                Text(
                    "${speed}x", color = MediaOverlayColors.TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
                )
            }
        }
        seekPreview?.let { preview ->
            val delta = preview.targetMs - preview.startMs
            Surface(
                color = MediaOverlayColors.Scrim, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                ) {
                    Text(
                        "${formatTime(preview.targetMs)} / ${formatTime(preview.durationMs)}",
                        color = MediaOverlayColors.TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                    Text(
                        when {
                            delta > 0L -> "快进 +${formatTime(delta)}"
                            delta < 0L -> "快退 -${formatTime(-delta)}"
                            else -> "当前位置"
                        },
                        color = MediaOverlayColors.TextSecondary, fontSize = 12.sp
                    )
                }
            }
        }
        gestureHint?.let { hint ->
            Surface(
                color = MediaOverlayColors.Scrim, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Text(
                    hint, color = MediaOverlayColors.TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
        }
        levelOverlay?.let { level ->
            val isVolume = level.side == PlayerGestureRules.Side.VOLUME
            Surface(
                color = MediaOverlayColors.Scrim, shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .align(if (isVolume) Alignment.CenterEnd else Alignment.CenterStart)
                    .padding(horizontal = 24.dp)
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp).width(150.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isVolume) Icons.Default.VolumeUp else Icons.Default.Brightness6,
                            null, tint = MediaOverlayColors.TextPrimary, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (isVolume) "音量" else "亮度",
                            color = MediaOverlayColors.TextPrimary, fontSize = 13.sp)
                        Spacer(Modifier.weight(1f))
                        Text("${(level.level * 100).roundToInt()}%",
                            color = MediaOverlayColors.TextPrimary, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { level.level },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                        color = MediaOverlayColors.Accent, trackColor = MediaOverlayColors.TrackOnScrim,
                        gapSize = 0.dp, drawStopIndicator = {}
                    )
                }
            }
        }
    }
}

/** 横向拖动时的预览状态：起拖位置、当前预览位置与总时长。 */
private data class SeekPreview(val startMs: Long, val targetMs: Long, val durationMs: Long)

/** 亮度/音量浮层：调的是哪一侧、当前值（0-1）。 */
private data class LevelOverlay(val side: PlayerGestureRules.Side, val level: Float)

/** 从 Context 找宿主 Activity（窗口亮度要改它）。 */
private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** 播放页调亮度：只改当前窗口亮度（不动系统设置），退出播放页时恢复系统亮度。 */
private fun setWindowBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.05f, 1f) }
}

/** 当前窗口亮度：没单独设过（跟随系统）时读系统亮度（0-255 → 0-1）。 */
private fun currentWindowBrightness(activity: Activity?): Float {
    val override = activity?.window?.attributes?.screenBrightness ?: -1f
    if (override in 0f..1f) return override
    val raw = runCatching {
        Settings.System.getInt(activity?.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrDefault(128)
    return (raw / 255f).coerceIn(0.05f, 1f)
}

/** 退出播放页恢复系统亮度（手势调过的窗口亮度不留在别的页面）。 */
private fun restoreSystemBrightness(activity: Activity?) {
    val window = activity?.window ?: return
    window.attributes = window.attributes.apply {
        screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    }
}

private fun currentVolume(audio: AudioManager?): Float {
    if (audio == null) return 0f
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    return audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
}

private fun applyVolume(audio: AudioManager?, level: Float) {
    if (audio == null) return
    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    runCatching {
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level * max).roundToInt().coerceIn(0, max), 0)
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
        Icon(image, contentDescription = label, tint = MediaOverlayColors.TextPrimary,
            modifier = Modifier.size(size))
    }
}

@Composable
private fun PlayerAction(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tint = if (enabled) MediaOverlayColors.TextPrimary else MediaOverlayColors.Disabled
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
