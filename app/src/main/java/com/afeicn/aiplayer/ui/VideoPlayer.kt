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
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.afeicn.aiplayer.AcceleratedSeek
import com.afeicn.aiplayer.MediaEntry
import com.afeicn.aiplayer.MediaKind
import com.afeicn.aiplayer.PlayerGestureRules
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 播放页：竖屏是默认形态，横屏/手动全屏切到全屏控制层。
 *
 * - 竖屏：上方 16:9 视频区（FIT 不裁切），下方集名、进度、上一/下一集、选集、简介；
 * - 全屏：沿用原来的手势 + 控制层，整屏显示；
 * - PiP：只保留视频画面（详情区不缩进小窗）；
 * - 音频条目：竖屏显示音频卡片，不再转横屏黑屏。
 *
 * 播放器来自 [androidx.media3.session.MediaController]（后台播放服务），
 * 页面销毁不再 stop/release 播放器。
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    entry: MediaEntry,
    player: Player?,
    isFullscreen: Boolean,
    isInPip: Boolean,
    workTitle: String,
    blurb: String,
    hasEpisodePicker: Boolean,
    hasPrev: Boolean,
    hasNext: Boolean,
    nextEntryName: String?,
    onBack: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onEpisodes: () -> Unit,
    onToggleFullscreen: () -> Unit,
    onSaveProgress: () -> Unit
) {
    val context = LocalContext.current
    val motionOn = rememberMotionEnabled()

    if (player == null) {
        ConnectingPlayer()
        return
    }

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
        // MediaController 的播放状态经 Binder 异步回传，这里先乐观更新按钮图标。
        if (player.isPlaying) {
            player.pause()
            playing = false
        } else {
            player.playWhenReady = true
            player.play()
            playing = true
        }
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
    // 进度保存已经搬进 PlaybackService（周期兜底 + 切集/暂停/停止落盘）。
    // 这里只在离开播放页时补一次「立即存」命令，不直接读写播放器。
    DisposableEffect(Unit) {
        onDispose { onSaveProgress() }
    }

    // 常亮与亮度恢复成对处理：视频播放页在前台就常亮（暂停也常亮，方便看画面找东西），
    // 音频不常亮（听书/听歌一般锁屏后台听）。页面不可见（ON_PAUSE/ON_STOP）立即清除 flag。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, activity, player, entry.kind) {
        val window = activity?.window
        fun syncKeepScreenOn() {
            val keep = entry.kind == MediaKind.VIDEO &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
            if (keep) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME, Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> syncKeepScreenOn()
                else -> Unit
            }
        }
        val playerListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) = syncKeepScreenOn()
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        player.addListener(playerListener)
        syncKeepScreenOn()
        onDispose {
            player.removeListener(playerListener)
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            // 手势调过的亮度只作用于播放窗口，退出播放页恢复系统亮度。
            restoreSystemBrightness(activity)
        }
    }

    val shownPosition = if (scrubbing) scrubPosition else position
    val sliderValue = if (duration > 0) shownPosition.toFloat() / duration else 0f
    val isVideo = entry.kind == MediaKind.VIDEO

    /** 视频画面 / 音频卡片。 */
    @Composable
    fun ContentCanvas(modifier: Modifier) {
        if (isVideo) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setPlayer(player)
                    }
                },
                update = { it.setPlayer(player) },
                onRelease = { view -> view.setPlayer(null as Player?) },
                modifier = modifier
            )
        } else {
            AudioCanvas(
                modifier = modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { controlsVisible = !controlsVisible }
            )
        }
    }

    /** 手势 + 控制层 + 浮层，竖屏/全屏共用；compact 只影响间距与底部按钮行。 */
    @Composable
    fun PlayerStage(modifier: Modifier, compact: Boolean) {
        Box(modifier) {
            ContentCanvas(Modifier.fillMaxSize())
            if (isVideo) {
                // 手势层：盖在画面上、垫在控件下面——落在按钮/滑块上的点击先被控件自己吃掉。
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

                            var accelerated: AcceleratedSeek? = null
                            var seekStartMs = 0L
                            var seekDurationMs = 0L
                            var seekTargetMs = 0L
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
                                    change.consume()
                                    val speed = PlayerGestureRules.longPressSpeed(total.y / density)
                                    if (speed != null && speed != speedOverlay) {
                                        player.setPlaybackSpeed(speed)
                                        speedOverlay = speed
                                    }
                                    continue
                                }

                                if (!claimed) {
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
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(motionDuration(motionOn, Motion.CONTROLS))),
                exit = fadeOut(tween(motionDuration(motionOn, Motion.CONTROLS))),
                label = "playerControls"
            ) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(MediaOverlayColors.ControlGradient)
                        )
                    )
                    Row(
                        Modifier.fillMaxWidth().align(Alignment.TopCenter)
                            .padding(horizontal = if (compact) 14.dp else 25.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 12.dp)
                    ) {
                        PlayerIcon(Icons.Default.ArrowBack, "返回", onBack)
                        Column(Modifier.weight(1f)) {
                            Text(entry.name, color = MediaOverlayColors.TextPrimary,
                                fontSize = if (compact) 13.sp else 15.sp,
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
                                fontSize = 12.sp, modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp))
                        }
                        if (isVideo) {
                            PlayerIcon(
                                if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                if (isFullscreen) "退出全屏" else "全屏",
                                onToggleFullscreen
                            )
                        }
                    }
                    Row(
                        Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 30.dp else 65.dp)
                    ) {
                        PlayerIcon(Icons.Default.Replay10, "快退10秒",
                            { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)) },
                            if (compact) 28.dp else 34.dp)
                        Surface(
                            color = MediaOverlayColors.ControlCircle, shape = CircleShape,
                            modifier = Modifier.size(if (compact) 58.dp else 74.dp),
                            onClick = { togglePlay() }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Crossfade(targetState = playing,
                                    animationSpec = tween(motionDuration(motionOn, Motion.ICON_STATE)),
                                    label = "playPause") { isPlaying ->
                                    Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "暂停或播放",
                                        tint = MediaOverlayColors.TextPrimary,
                                        modifier = Modifier.size(if (compact) 34.dp else 43.dp))
                                }
                            }
                        }
                        PlayerIcon(Icons.Default.Forward10, "快进10秒",
                            { player.seekTo((player.currentPosition + 10_000).coerceAtMost(duration)) },
                            if (compact) 28.dp else 34.dp)
                    }
                    Column(
                        Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                            .padding(horizontal = if (compact) 14.dp else 28.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
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
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).height(30.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = MediaOverlayColors.Accent,
                                    activeTrackColor = MediaOverlayColors.Accent,
                                    inactiveTrackColor = MediaOverlayColors.TrackInactive
                                )
                            )
                            Text(formatTime(duration), color = MediaOverlayColors.TextPrimary, fontSize = 12.sp)
                        }
                        if (!compact) {
                            // 全屏才有底部操作行；竖屏的操作在视频下方的详情区，不重复塞两排。
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                PlayerAction(Icons.Default.SkipPrevious, "上一集", enabled = hasPrev, onClick = onPrev)
                                Spacer(Modifier.width(20.dp))
                                PlayerAction(Icons.Default.List, "选集", onClick = onEpisodes)
                                Spacer(Modifier.weight(1f))
                                PlayerAction(Icons.Default.SkipNext, "下一集", enabled = hasNext, onClick = onNext)
                                Spacer(Modifier.width(20.dp))
                                PlayerAction(Icons.Default.FullscreenExit, "退出全屏", onClick = onToggleFullscreen)
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

            // ==================== 手势浮层（无点击处理，不拦截触摸） ====================
            speedOverlay?.let { speed ->
                Surface(
                    color = MediaOverlayColors.Scrim, shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 66.dp, end = 16.dp)
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

    // PiP：只画视频，不把详情区缩进小窗。
    if (isInPip) {
        ContentCanvas(Modifier.fillMaxSize().background(MediaOverlayColors.Canvas))
        return
    }

    if (isFullscreen) {
        PlayerStage(Modifier.fillMaxSize(), compact = false)
    } else {
        Column(
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                .statusBarsPadding()
        ) {
            // 顶部视频区铺满宽度，FIT 不裁切；16:9 是竖屏最自然的占位。
            PlayerStage(Modifier.fillMaxWidth().aspectRatio(16f / 9f), compact = true)
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, maxLines = 2)
                    val subtitle = listOfNotNull(
                        entry.title.takeIf { it.isNotBlank() },
                        entry.episodeNumber?.let { "第 $it 集" }
                    ).joinToString(" · ")
                    if (subtitle.isNotBlank()) {
                        Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (workTitle.isNotBlank() && workTitle != entry.name) {
                        Text(workTitle, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(formatTime(shownPosition), style = MaterialTheme.typography.labelMedium)
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
                            enabled = duration > 0,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            colors = SliderDefaults.colors(
                                thumbColor = MaterialTheme.colorScheme.primary,
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                        Text(formatTime(duration), style = MaterialTheme.typography.labelMedium)
                    }
                    if (hasNext) {
                        Text(
                            "下一集：${nextEntryName ?: "—"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InfoButton(Icons.Default.SkipPrevious, "上一集", hasPrev, onPrev)
                    InfoButton(Icons.Default.SkipNext, "下一集", hasNext, onNext)
                    if (hasEpisodePicker) InfoButton(Icons.Default.List, "选集", true, onEpisodes)
                    Spacer(Modifier.weight(1f))
                    // 音频没有横屏形态，不显示无意义的全屏按钮。
                    if (isVideo) InfoButton(Icons.Default.Fullscreen, "全屏", true, onToggleFullscreen)
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("简介", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        blurb.ifBlank { "暂无简介" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectingPlayer() {
    Box(
        Modifier.fillMaxSize().background(MediaOverlayColors.Canvas),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = MediaOverlayColors.Accent)
    }
}

/** 音频条目：竖屏也不再是一块黑屏，给一个稳定的音频卡片。 */
@Composable
private fun AudioCanvas(modifier: Modifier) {
    Box(
        modifier.background(
            Brush.verticalGradient(listOf(Color(0xFF1B2338), Color(0xFF0C1325)))
        ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Default.MusicNote,
                contentDescription = null,
                tint = MediaOverlayColors.TextPrimary,
                modifier = Modifier.size(52.dp)
            )
            Spacer(Modifier.height(10.dp))
            Text("音频播放中", color = MediaOverlayColors.TextSecondary, fontSize = 13.sp)
        }
    }
}

/** 竖屏详情区的按钮：跟随 Material 主题（不是视频浮层）。 */
@Composable
private fun InfoButton(
    image: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Icon(image, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 12.sp)
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
