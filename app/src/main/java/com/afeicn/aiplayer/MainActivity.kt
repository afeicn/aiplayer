package com.afeicn.aiplayer

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.afeicn.aiplayer.ui.*
import kotlinx.coroutines.launch

private enum class Page { HOME, DISCOVER, DETAIL, DOWNLOADS, SOURCES, PLAYER }

/** 详情页目标：在线搜索结果卡 / 已下载剧集卡。 */
sealed class DetailTarget {
    data class Online(val vod: AggregatedVod) : DetailTarget()
    data class Offline(val series: DownloadedSeries) : DetailTarget()
}

/** 选集面板目标：在线结果（直链或详情页） / 离线剧继续下载。 */
sealed class SheetTarget {
    data class Online(val vod: AggregatedVod) : SheetTarget()
    data class Continue(val series: DownloadedSeries) : SheetTarget()
}

class MainActivity : ComponentActivity() {

    /** Compose 要跟着 PiP 状态切换渲染（小窗里只画视频）。 */
    var isInPip by mutableStateOf(false)
        private set

    /** 从 PiP 双击/点全屏回到 App 时置真，由 Compose 消费后进入全屏。 */
    var pipFullscreenRequested by mutableStateOf(false)
        private set

    private var pipEligible = false
    private var pipPlaying = false
    private var pipAspect = 16 to 9
    private var pipFeature = false
    private var wasInPip = false

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pipFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        handleIntent(intent)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent { AIPlayerTheme { PlayerApp(this) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_PIP_FULLSCREEN, false) == true) {
            pipFullscreenRequested = true
            intent.removeExtra(EXTRA_PIP_FULLSCREEN)
        }
    }

    fun consumePipFullscreenRequest(): Boolean {
        val requested = pipFullscreenRequested
        pipFullscreenRequested = false
        return requested
    }

    /**
     * 由 Compose 在播放状态变化时调用：
     * eligibility 为真时 Android 12+ 打开自动进入 PiP，同时保留 onUserLeaveHint 兜底（8–11）。
     */
    fun updatePipEligibility(eligible: Boolean, aspect: Pair<Int, Int>, playing: Boolean) {
        pipEligible = eligible
        pipPlaying = playing
        if (aspect.first > 0 && aspect.second > 0) pipAspect = aspect
        if (!pipFeature) return
        runCatching { setPictureInPictureParams(buildPipParams(autoEnter = eligible)) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!pipEligible || !pipFeature || isInPictureInPictureMode) return
        if (Build.VERSION.SDK_INT < PipRules.MIN_SDK) return
        runCatching { enterPictureInPictureMode(buildPipParams(autoEnter = false)) }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        if (PipRules.requestFullscreenOnExit(wasInPip, isInPictureInPictureMode)) {
            // 从 PiP 回到 App：按飞哥要求直达全屏。
            pipFullscreenRequested = true
        }
        wasInPip = isInPictureInPictureMode
        isInPip = isInPictureInPictureMode
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    private fun buildPipParams(autoEnter: Boolean): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(pipAspect.first, pipAspect.second))
            .setActions(pipActions())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(autoEnter)
        }
        return builder.build()
    }

    private fun pipActions(): List<RemoteAction> {
        val toggle = RemoteAction(
            Icon.createWithResource(
                this,
                if (pipPlaying) R.drawable.ic_pip_pause else R.drawable.ic_pip_play
            ),
            if (pipPlaying) "暂停" else "播放",
            if (pipPlaying) "暂停" else "播放",
            broadcast(PipRequestCode.TOGGLE, PlaybackActionReceiver.ACTION_TOGGLE_PLAY)
        )
        val fullscreen = RemoteAction(
            Icon.createWithResource(this, R.drawable.ic_pip_fullscreen),
            "全屏",
            "全屏",
            broadcast(PipRequestCode.FULLSCREEN, PlaybackActionReceiver.ACTION_FULLSCREEN)
        )
        return listOf(toggle, fullscreen)
    }

    private fun broadcast(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(this, PlaybackActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private object PipRequestCode {
        const val TOGGLE = 1
        const val FULLSCREEN = 2
    }

    companion object {
        const val EXTRA_PIP_FULLSCREEN = "com.afeicn.aiplayer.extra.PIP_FULLSCREEN"
    }
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class,
      androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun PlayerApp(activity: MainActivity) {
    val homeVm: HomeViewModel = viewModel()
    val searchVm: SearchViewModel = viewModel()
    val downloadsVm: DownloadsViewModel = viewModel()
    val sourcesVm: SourcesViewModel = viewModel()
    val playerVm: PlayerViewModel = viewModel()
    val sheetVm: EpisodeSheetViewModel = viewModel()

    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var page by remember { mutableStateOf(Page.HOME) }
    var detailOrigin by remember { mutableStateOf(Page.HOME) }
    var playerOrigin by remember { mutableStateOf(Page.HOME) }
    var detailTarget by remember { mutableStateOf<DetailTarget?>(null) }
    var sheetTarget by remember { mutableStateOf<SheetTarget?>(null) }
    // 播放页里点「选集」用的上下文（当前播放会话属于哪部作品），返回后仍可继续选集。
    var playSheet by remember { mutableStateOf<SheetTarget?>(null) }
    var showAddSource by remember { mutableStateOf(false) }
    // 选集面板模式：立即播放走 PLAY（点集播集、可续播），选集下载走 DOWNLOAD（只入队下载）。
    var sheetMode by remember { mutableStateOf(EpisodeSheetMode.DOWNLOAD) }
    // 管理模式「删除选中」按下后置真，弹批量删除确认；确认才调 deleteSelected。
    var pendingSourceDeleteConfirm by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // 全局动效开关（系统动画缩放）；首页/发现页滚动状态提升到这一层，切页不丢滚动位置。
    val motionOn = rememberMotionEnabled()
    val slidePx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.dp.toPx().toInt() }
    val homeListState = rememberLazyListState()
    val discoverListState = rememberLazyListState()

    // 退到后台/锁屏不再暂停：播放器由 PlaybackService 持有，锁屏、PiP、切应用都继续播。
    // 这里只负责在网络状态变化时估一次下载，以及在可能被系统杀掉前同步落一次进度。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                // 前台补一次网络重估：连上 WiFi 后等待中的下载立刻续传。
                androidx.lifecycle.Lifecycle.Event.ON_START -> downloadsVm.onAppForeground()
                // 进最近任务 / 切走时先同步落盘：部分 ROM 在划掉任务时不再补发 onStop，
                // 强杀后就会回到更早的断点。真正的写盘在服务端。
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE ->
                    playerVm.saveProgress(synchronous = true)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val homeState by homeVm.state.collectAsStateWithLifecycle()
    val searchState by searchVm.state.collectAsStateWithLifecycle()
    val downloads by downloadsVm.downloads.collectAsStateWithLifecycle()
    val pendingRemoval by downloadsVm.pendingRemoval.collectAsStateWithLifecycle()
    val allowMobileData by downloadsVm.allowMobileData.collectAsStateWithLifecycle()
    val sourcesState by sourcesVm.state.collectAsStateWithLifecycle()
    val sheetState by sheetVm.state.collectAsStateWithLifecycle()
    val playerCursor by playerVm.cursor.collectAsStateWithLifecycle()
    val playerController by playerVm.controllerFlow.collectAsStateWithLifecycle()
    val playback by playerVm.playback.collectAsStateWithLifecycle()

    // ===== 播放页形态：竖屏默认，系统转横屏或点手动全屏按钮进全屏 =====
    val configuration = LocalConfiguration.current
    val systemLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playerKind = playerCursor.currentEntry?.kind
    var manualFullscreen by remember { mutableStateOf(false) }
    // 退出全屏后先锁竖屏，等系统真转回竖屏再恢复自动旋转，避免立刻又被转回横屏。
    var restoreAutoRotate by remember { mutableStateOf(false) }
    val isFullscreen = PlayerDisplayRules.isFullscreen(playerKind, manualFullscreen, systemLandscape)

    // 最近浏览一出现就静默预取作品卡入缓存：点大卡/最近条目时秒进详情页，不等搜索。
    LaunchedEffect(homeState.recent) { homeVm.prefetchRecentVods(homeState.recent) }

    val inFlight = downloads.count {
        it.state in setOf(
            DownloadTaskState.RESOLVING, DownloadTaskState.QUEUED, DownloadTaskState.DOWNLOADING,
            DownloadTaskState.PAUSED, DownloadTaskState.WAITING_NETWORK, DownloadTaskState.WAITING_WIFI
        )
    }

    LaunchedEffect(Unit) {
        homeVm.startPolling()
        downloadsVm.startPolling()
    }

    // 选集面板 / 源管理的提示统一走 Snackbar，消费后清掉防止重进页面重复弹。
    LaunchedEffect(sheetState.notice) {
        sheetState.notice?.let {
            snackbar.showSnackbar(it)
            sheetVm.consumeNotice()
        }
    }
    LaunchedEffect(sourcesState.notice) {
        sourcesState.notice?.let {
            snackbar.showSnackbar(it)
            sourcesVm.consumeNotice()
        }
    }
    // 删除任务进入 5 秒撤销窗口时给一条可撤销的 Snackbar。
    LaunchedEffect(pendingRemoval) {
        pendingRemoval.forEach { id ->
            val result = snackbar.showSnackbar("已删除该下载任务", "撤销")
            if (result == SnackbarResult.ActionPerformed) downloadsVm.undoRemove(id)
        }
    }
    // 选集面板打开期间跟着下载进度刷新每集角标。
    LaunchedEffect(sheetTarget, downloads) {
        if (sheetTarget != null) sheetVm.refreshStatuses(sheetState.title)
    }
    // 进发现页时预取推荐流，首屏不至于空着。
    LaunchedEffect(page) {
        if (page == Page.DISCOVER) searchVm.loadRecommendations()
    }

    fun openDetails(target: DetailTarget) {
        // 点进过详情页，作品就进首页「最近浏览」；在线结果顺带记上海报，离线剧记封面。
        when (target) {
            is DetailTarget.Online ->
                AppContainer.playbackProgress.recordView(
                    target.vod.name, target.vod.pic, System.currentTimeMillis(),
                    target.vod.detailUrls.firstOrNull().orEmpty()
                )
            is DetailTarget.Offline ->
                AppContainer.playbackProgress.recordView(
                    target.series.title, target.series.cover, System.currentTimeMillis(),
                    target.series.detailUrl
                )
        }
        detailTarget = target
        detailOrigin = page
        page = Page.DETAIL
    }
    fun openSheet(target: SheetTarget, mode: EpisodeSheetMode) {
        sheetTarget = target
        sheetMode = mode
        when (target) {
            is SheetTarget.Online -> sheetVm.load(target.vod)
            is SheetTarget.Continue -> sheetVm.loadForSeries(target.series)
        }
    }
    /** 起播一个队列（离线整剧 / 在线一条线路），并记住选集上下文。 */
    fun openPlayer(entries: List<MediaEntry>, startIndex: Int, sheet: SheetTarget?, playUrl: String = "") {
        if (entries.isEmpty()) return
        // 播过一集，作品就进首页「最近浏览」；在线作品顺带记播放页地址，供大卡续播重新解析。
        when (sheet) {
            is SheetTarget.Online ->
                AppContainer.playbackProgress.recordPlay(
                    sheet.vod.name, sheet.vod.pic, playUrl, System.currentTimeMillis(),
                    sheet.vod.detailUrls.firstOrNull().orEmpty()
                )
            is SheetTarget.Continue ->
                AppContainer.playbackProgress.recordPlay(
                    sheet.series.title, sheet.series.cover, "", System.currentTimeMillis(),
                    sheet.series.detailUrl
                )
            null -> Unit
        }
        playerVm.playQueue(entries, startIndex)
        playSheet = sheet
        if (page != Page.PLAYER) playerOrigin = page
        page = Page.PLAYER
        sheetTarget = null
    }
    /** 起播单条（选集面板里在线解析出来的流）。 */
    fun openPlayer(entry: MediaEntry, sheet: SheetTarget?, playUrl: String = "") {
        openPlayer(listOf(entry), 0, sheet, playUrl)
    }
    /** 离线剧的播放队列：全部已下载集；起点按「上次播到第几集」记录，其次断点记录，否则第一集。 */
    fun offlineQueue(series: DownloadedSeries): Pair<List<MediaEntry>, Int> {
        val entries = series.episodes.map { it.mediaEntry() }
        val store = AppContainer.playbackProgress
        val start = PlaybackProgressRules.offlineResumeIndex(
            episodeNumbers = entries.map { it.episodeNumber },
            lastEpisode = store.validatedLastEpisodeOf(series.title, series.totalEpisodes),
            positions = entries.map { store.positionOf(it.uri.toString()) }
        )
        return entries to start
    }
    fun goBack() {
        when (page) {
            Page.DETAIL -> page = detailOrigin
            Page.PLAYER -> {
                // 返回 = 停止播放并清掉媒体通知（不从后台继续，也不做「划掉还播」）。
                playerVm.stopPlayback()
                manualFullscreen = false
                restoreAutoRotate = false
                playSheet = null
                page = playerOrigin
            }
            Page.DOWNLOADS, Page.SOURCES -> page = Page.HOME
            Page.DISCOVER -> page = Page.HOME
            Page.HOME -> Unit
        }
    }

    fun toggleFullscreen() {
        val next = PlayerDisplayRules.fullscreenToggleState(isFullscreen)
        manualFullscreen = next.manualFullscreen
        restoreAutoRotate = next.restoreAutoRotate
    }

    val isPlayer = page == Page.PLAYER
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    // 离开播放页时清掉手动全屏状态。
    LaunchedEffect(isPlayer) {
        if (!isPlayer) {
            manualFullscreen = false
            restoreAutoRotate = false
        }
    }

    // 方向请求：竖屏默认 + 系统自动旋转；音频锁竖屏；PiP 里完全交给系统。
    LaunchedEffect(isPlayer, playerKind, manualFullscreen, restoreAutoRotate, systemLandscape, activity.isInPip) {
        val base = PlayerDisplayRules.orientationRequest(
            isPlayerPage = isPlayer,
            kind = playerKind,
            manualFullscreen = manualFullscreen,
            isInPip = activity.isInPip
        )
        activity.requestedOrientation = when {
            base == PlayerOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            base == PlayerOrientation.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            restoreAutoRotate && isPlayer && systemLandscape -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        if (restoreAutoRotate && isPlayer && !systemLandscape) restoreAutoRotate = false
    }

    // 全屏才隐藏系统栏；竖屏播放页保留状态栏/导航栏，和普通页面一致。
    LaunchedEffect(isPlayer, isFullscreen, activity.isInPip) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (isPlayer && isFullscreen && !activity.isInPip) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        val lightBars = !isDark && !(isPlayer && isFullscreen)
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }

    // Android 13+ 媒体通知需要运行时权限；第一次进播放页时问。
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(isPlayer) {
        if (isPlayer && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                activity, android.Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // PiP 资格：播放页 + 视频 + 正在播放，三者同时成立才允许按 Home 自动进小窗。
    val hasPipFeature = remember(activity) {
        activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    LaunchedEffect(
        isPlayer, playerKind, playback.isPlaying,
        playback.videoWidth, playback.videoHeight, activity.isInPip
    ) {
        val eligible = PipRules.eligible(
            isPlayerPage = isPlayer,
            kind = playerKind,
            isPlaying = playback.isPlaying,
            sdkInt = Build.VERSION.SDK_INT,
            hasPipFeature = hasPipFeature
        )
        activity.updatePipEligibility(
            eligible = eligible,
            aspect = PipRules.aspectRatio(playback.videoWidth, playback.videoHeight),
            playing = playback.isPlaying
        )
    }
    DisposableEffect(activity) {
        onDispose { activity.updatePipEligibility(false, 16 to 9, false) }
    }

    // 从 PiP 回到 App 时按飞哥要求直达全屏（双击或小窗全屏按钮都会走到这里）。
    LaunchedEffect(activity.pipFullscreenRequested) {
        if (activity.consumePipFullscreenRequest()) {
            if (isPlayer && playerKind == MediaKind.VIDEO) {
                manualFullscreen = true
                restoreAutoRotate = false
            }
        }
    }
    BackHandler(page != Page.HOME || sheetTarget != null || showAddSource || pendingSourceDeleteConfirm) {
        when {
            showAddSource -> showAddSource = false
            pendingSourceDeleteConfirm -> pendingSourceDeleteConfirm = false
            page == Page.SOURCES && sourcesState.managing -> sourcesVm.exitManage()
            sheetTarget != null -> sheetTarget = null
            else -> goBack()
        }
    }

    SoftPage {
        // 共享元素转场容器：卡片海报与详情海报在页面切换间配对飞移。
        SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this) {
        // 页面转场：首页↔发现只交叉淡化；更深层级右移 24dp 进出；播放器进出只淡入淡出
        // （方向切换本身已是强变化，不再叠加位移）。内容按 target 渲染，旧页在转场期间保持原样。
        AnimatedContent(
            targetState = page,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                val sameLevel = (initialState == Page.HOME && targetState == Page.DISCOVER) ||
                    (initialState == Page.DISCOVER && targetState == Page.HOME)
                val playerSide = targetState == Page.PLAYER || initialState == Page.PLAYER
                val detailSide = targetState == Page.DETAIL || initialState == Page.DETAIL
                when {
                    playerSide -> fadeIn(motionTween(motionOn, Motion.CONTROLS)) with
                        fadeOut(motionTween(motionOn, Motion.CONTROLS))
                    sameLevel -> fadeIn(motionTween(motionOn, Motion.TAB_SWITCH)) with
                        fadeOut(motionTween(motionOn, Motion.TAB_SWITCH))
                    // 详情页进出：不滑只淡化，位移让给海报共享元素（360ms 从小变大）。
                    detailSide -> fadeIn(motionTween(motionOn, Motion.DETAIL_ENTER)) with
                        fadeOut(motionTween(motionOn, Motion.DETAIL_ENTER))
                    targetState.ordinal > initialState.ordinal ->
                        (fadeIn(motionTween(motionOn, Motion.PAGE_ENTER)) +
                            slideInHorizontally(motionTween(motionOn, Motion.PAGE_ENTER)) { slidePx }) with
                        fadeOut(motionTween(motionOn, Motion.PAGE_EXIT))
                    else ->
                        fadeIn(motionTween(motionOn, Motion.PAGE_ENTER)) with
                        (fadeOut(motionTween(motionOn, Motion.PAGE_EXIT)) +
                            slideOutHorizontally(motionTween(motionOn, Motion.PAGE_EXIT)) { slidePx })
                }
            },
            label = "page"
        ) { target ->
        // 把本页的可见性作用域交给卡片：共享元素两端各自持有进出页作用域才能配对。
        CompositionLocalProvider(LocalPageTransitionScope provides this) {
        when (target) {
            Page.HOME -> HomeScreen(
                state = homeState,
                listState = homeListState,
                onSearch = { page = Page.DISCOVER },
                onDownloads = { page = Page.DOWNLOADS },
                onSources = { page = Page.SOURCES },
                downloadCount = inFlight,
                // 「已下载」卡片点击进详情页（看集数/海报/管理），播放走卡上的播放按钮。
                onOpenSeries = { series -> openDetails(DetailTarget.Offline(series)) },
                onPlay = { series ->
                    val (entries, start) = offlineQueue(series)
                    openPlayer(entries, start, SheetTarget.Continue(series))
                },
                // 大卡续播：已下载走离线队列；在线先凭记录的播放页地址重新解析，
                // 没地址或解析失败就凭缓存/搜索定位作品、直达上次看的那一集（进度自动续），
                // 连作品都定位不到才退回发现页。全程静默，不弹提示。
                onPlayRecent = { r ->
                    val series = homeState.series.firstOrNull { OfflineLibrary.sameTitle(it.title, r.title) }
                    if (series != null) {
                        val (entries, start) = offlineQueue(series)
                        openPlayer(entries, start, SheetTarget.Continue(series))
                    } else {
                        fun resumeBySearch() {
                            homeVm.resumeRecentBySearch(r) { resume ->
                                if (resume != null) {
                                    // 带上作品卡作选集上下文：播放页里还能选集；播放页地址回写记录，下次走快路径。
                                    openPlayer(resume.entry, SheetTarget.Online(resume.vod), resume.playUrl)
                                } else {
                                    // 作品定位到了但这一集解析不出可播流：进详情页让用户自己选集/换源。
                                    homeVm.findRecentVod(r.title) { vod ->
                                        if (vod != null) openDetails(DetailTarget.Online(vod))
                                        else { query = r.title; searchVm.search(r.title); page = Page.DISCOVER }
                                    }
                                }
                            }
                        }
                        if (r.playUrl.isNotBlank()) {
                            homeVm.resolveRecent(r) { entry ->
                                if (entry != null) openPlayer(entry, null)
                                else resumeBySearch()
                            }
                        } else resumeBySearch()
                    }
                },
                // 点大卡主体 / 最近浏览条目：一律进详情页（在线吃预取缓存秒进，缓存没有才后台搜）。
                // 直接起播只走卡上的播放按钮（onPlay / onPlayRecent）。
                onOpenRecent = { r ->
                    val series = homeState.series.firstOrNull { OfflineLibrary.sameTitle(it.title, r.title) }
                    if (series != null) openDetails(DetailTarget.Offline(series))
                    else {
                        // 记录里有详情页地址就直接构造详情卡，跳过按标题搜索
                        // （「我不是大师[全集]」这类带标记标题常在源上搜不到，搜到也常是无关作品）。
                        val direct = RecentPlayLog.directVod(r, AppContainer.sourceStore.sources())
                        if (direct != null) openDetails(DetailTarget.Online(direct))
                        else {
                            homeVm.findRecentVod(r.title) { vod ->
                                if (vod != null) openDetails(DetailTarget.Online(vod))
                                // 缓存和搜索都定位不到：留在首页提示，给「去搜索」按钮由用户自己发起搜索。
                                else scope.launch {
                                    val result = snackbar.showSnackbar("找不到「${r.title}」，可能已下架", "去搜索")
                                    if (result == SnackbarResult.ActionPerformed) {
                                        query = r.title
                                        searchVm.search(r.title)
                                        page = Page.DISCOVER
                                    }
                                }
                            }
                        }
                    }
                }
            )
            Page.DISCOVER -> DiscoverScreen(
                state = searchState,
                listState = discoverListState,
                query = query,
                onQuery = { query = it },
                onSearch = { searchVm.search(it) },
                onRemoveHistory = { searchVm.removeHistory(it) },
                onClearHistory = { searchVm.clearHistory() },
                onOpenResult = { vod -> openDetails(DetailTarget.Online(vod)) },
                onSources = { page = Page.SOURCES }
            )
            Page.DETAIL -> when (val target = detailTarget) {
                is DetailTarget.Online -> DetailScreen(
                    vod = target.vod,
                    onBack = ::goBack,
                    // 立即播放不再直接起播第一条线路，而是展开选集列表：点一集播一集，有记录则先续播。
                    onPlay = { openSheet(SheetTarget.Online(target.vod), EpisodeSheetMode.PLAY) },
                    onSelectEpisodes = { openSheet(SheetTarget.Online(target.vod), EpisodeSheetMode.DOWNLOAD) }
                )
                is DetailTarget.Offline -> DetailScreen(
                    series = target.series,
                    onBack = ::goBack,
                    // 与「继续播放」同源：被记录的总集数校验过，避免脏集号标错集。
                    lastEpisode = AppContainer.playbackProgress
                        .validatedLastEpisodeOf(target.series.title, target.series.totalEpisodes),
                    onPlay = {
                        val (entries, start) = offlineQueue(target.series)
                        openPlayer(entries, start, SheetTarget.Continue(target.series))
                    },
                    onEpisodePlay = { index ->
                        val (entries, _) = offlineQueue(target.series)
                        openPlayer(entries, index, SheetTarget.Continue(target.series))
                    },
                    onSelectEpisodes = { openSheet(SheetTarget.Continue(target.series), EpisodeSheetMode.DOWNLOAD) }
                )
                null -> page = Page.HOME
            }
            Page.DOWNLOADS -> DownloadsScreen(
                downloads = downloads,
                pendingRemoval = pendingRemoval,
                cacheSizeText = downloadsVm.cacheSizeText(),
                onBack = ::goBack,
                onSettings = { page = Page.SOURCES },
                onPause = { downloadsVm.pause(it) },
                onResume = { downloadsVm.resume(it) },
                onRetry = { downloadsVm.retry(it) },
                onDelete = { downloadsVm.remove(it) },
                // 点已完成任务：队列取同剧全部已下载集，从这一集起播；起播会顺带记下「上次看到第几集」。
                onOpen = { task ->
                    OfflineLibrary.playbackQueueFor(downloads, task.item.id)?.let { (series, index) ->
                        val (entries, _) = offlineQueue(series)
                        openPlayer(entries, index, SheetTarget.Continue(series))
                    }
                }
            )
            Page.SOURCES -> SourcesScreen(
                state = sourcesState,
                allowMobileData = allowMobileData,
                onSetAllowMobileData = { downloadsVm.setAllowMobileData(it) },
                onBack = ::goBack,
                onAdd = { showAddSource = true },
                onToggle = { id, enabled -> sourcesVm.setEnabled(id, enabled) },
                onLongPressRow = { sourcesVm.enterManage(it) },
                onToggleSelect = { sourcesVm.toggleSelect(it) },
                onToggleSelectAll = { sourcesVm.toggleSelectAll() },
                onExitManage = { sourcesVm.exitManage() },
                onRequestDeleteSelected = { pendingSourceDeleteConfirm = true },
                onReprobe = { id -> sourcesVm.reprobe(id) },
                onSaveAi = { enabled, key, model -> sourcesVm.saveAiSettings(enabled, key, model) },
                onDismissProbe = { sourcesVm.dismissProbe() }
            )
            Page.PLAYER -> {
                val entry = playerCursor.currentEntry
                if (entry != null) {
                    // 竖屏详情区的作品名/简介：在线取聚合卡，离线取剧卡；都没有就用条目本身。
                    val (workTitle, workBlurb) = when (val sheet = playSheet) {
                        is SheetTarget.Online -> sheet.vod.name to sheet.vod.blurb
                        is SheetTarget.Continue -> sheet.series.title to sheet.series.blurb
                        null -> entry.name to ""
                    }
                    VideoPlayerScreen(
                        entry = entry,
                        player = playerController,
                        isFullscreen = isFullscreen,
                        isInPip = activity.isInPip,
                        workTitle = workTitle,
                        blurb = workBlurb,
                        hasEpisodePicker = playSheet != null,
                        hasPrev = playerCursor.hasPrev,
                        hasNext = playerCursor.hasNext,
                        nextEntryName = playerCursor.nextEntryName,
                        onBack = ::goBack,
                        onPrev = { playerVm.prev() },
                        onNext = { playerVm.next() },
                        onEpisodes = { playSheet?.let { openSheet(it, EpisodeSheetMode.PLAY) } },
                        onToggleFullscreen = { toggleFullscreen() },
                        onSaveProgress = { playerVm.saveProgress() }
                    )
                }
            }
        }
        }
        }
        }
        }
        if (page in setOf(Page.HOME, Page.DISCOVER)) {
            Box(
                Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                    .navigationBarsPadding().padding(bottom = 9.dp)
            ) {
                CapsuleNavigation(
                    selected = if (page == Page.HOME) "home" else "discover",
                    onHome = { page = Page.HOME },
                    onDiscover = { page = Page.DISCOVER }
                )
            }
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                .navigationBarsPadding().padding(bottom = if (page in setOf(Page.HOME, Page.DISCOVER)) 82.dp else 12.dp)
        ) { data -> ThemedSnackbar(data) }
    }

    if (sheetTarget != null) {
        EpisodeSheet(
            state = sheetState,
            mode = sheetMode,
            onDismiss = { sheetTarget = null },
            onPlay = { episode ->
                val title = sheetState.title
                val sheet = sheetTarget
                sheetVm.play(title, episode) { entry ->
                    if (entry != null) {
                        sheetTarget = null
                        // 带上这一集的播放页地址：首页大卡续播凭它重新解析流。
                        openPlayer(entry, sheet, episode.url)
                    } else {
                        scope.launch { snackbar.showSnackbar("这一集解析不到播放地址，换一集或换个源试试") }
                    }
                }
            },
            onDownload = { selected ->
                sheetVm.download(sheetState.title, selected)
                sheetTarget = null
            }
        )
    }

    if (pendingSourceDeleteConfirm) {
        val deleteCount = sourcesState.selection.count
        SoftDialog(
            onDismissRequest = { pendingSourceDeleteConfirm = false },
            title = DestructiveCopy.deleteSourcesTitle(deleteCount),
            text = { Text(DestructiveCopy.deleteSourcesBody()) },
            confirmText = "删除",
            onConfirm = {
                sourcesVm.deleteSelected()
                pendingSourceDeleteConfirm = false
            },
            dismissText = "取消"
        )
    }
    if (showAddSource) {
        AddSourceDialog(
            onDismiss = { showAddSource = false },
            onSave = { address ->
                sourcesVm.add(address)
                showAddSource = false
            }
        )
    }
}

@Composable
private fun AddSourceDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var address by remember { mutableStateOf("") }
    SoftDialog(
        onDismissRequest = onDismiss,
        title = "添加订阅源",
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text("站点地址（首页/列表页/播放页都行）") }, placeholder = { Text("https://…") },
                    shape = CircleShape)
                Text("添加时自动检测连通性、探测搜索入口，开了 AI 还会顺手认出站点名称。" +
                    "探测没通过也会先存为未启用源，之后可以随时重新探测。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        // 不带 http:// 的地址也收：siteBase 会补上协议，用户粘的常常是复制来的裸域名。
        confirmText = "添加",
        confirmEnabled = address.isNotBlank(),
        onConfirm = { onSave(address) },
        dismissText = "取消"
    )
}
