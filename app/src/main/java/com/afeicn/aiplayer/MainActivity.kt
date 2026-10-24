package com.afeicn.aiplayer

import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        setContent { AIPlayerTheme { PlayerApp(this) } }
    }
}

@OptIn(androidx.compose.animation.ExperimentalAnimationApi::class,
      androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun PlayerApp(activity: ComponentActivity) {
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

    // 退到后台（Home 键/切应用/锁屏）立即存进度并暂停：v1 不做后台播放，
    // 声音只属于前台播放页；播放页内返回的停止在 VideoPlayerScreen 的 onDispose 里。
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP && PlayerEngine.isActive()) {
                playerVm.pause()
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
                AppContainer.playbackProgress.recordView(target.vod.name, target.vod.pic, System.currentTimeMillis())
            is DetailTarget.Offline ->
                AppContainer.playbackProgress.recordView(target.series.title, target.series.cover, System.currentTimeMillis())
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
                AppContainer.playbackProgress.recordPlay(sheet.vod.name, sheet.vod.pic, playUrl, System.currentTimeMillis())
            is SheetTarget.Continue ->
                AppContainer.playbackProgress.recordPlay(sheet.series.title, sheet.series.cover, "", System.currentTimeMillis())
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
    /** 离线剧的播放队列：全部已下载集；起点取有断点记录的那一集，否则第一集。 */
    fun offlineQueue(series: DownloadedSeries): Pair<List<MediaEntry>, Int> {
        val entries = series.episodes.map { it.mediaEntry() }
        val start = entries.indexOfFirst { AppContainer.playbackProgress.positionOf(it.uri.toString()) > 0 }
            .coerceAtLeast(0)
        return entries to start
    }
    fun goBack() {
        when (page) {
            Page.DETAIL -> page = detailOrigin
            Page.PLAYER -> {
                playerVm.saveProgress()
                page = playerOrigin
            }
            Page.DOWNLOADS, Page.SOURCES -> page = Page.HOME
            Page.DISCOVER -> page = Page.HOME
            Page.HOME -> Unit
        }
    }

    val isPlayer = page == Page.PLAYER
    val isDark = androidx.compose.foundation.isSystemInDarkTheme()

    @Suppress("DEPRECATION")
    LaunchedEffect(isPlayer) {
        activity.requestedOrientation =
            if (isPlayer) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        if (isPlayer) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = !isDark && !isPlayer
        controller.isAppearanceLightNavigationBars = !isDark && !isPlayer
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
                // 「已下载」卡片点击直接进播放页（离线队列，起点续上次进度），不再绕详情页。
                onOpenSeries = { series ->
                    val (entries, start) = offlineQueue(series)
                    openPlayer(entries, start, SheetTarget.Continue(series))
                },
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
                // 点大卡主体 / 最近浏览条目：已下载直接进播放页（与「已下载」卡同一行为）；
                // 在线吃预取缓存秒进详情页，缓存没有才后台搜。
                onOpenRecent = { r ->
                    val series = homeState.series.firstOrNull { OfflineLibrary.sameTitle(it.title, r.title) }
                    if (series != null) {
                        val (entries, start) = offlineQueue(series)
                        openPlayer(entries, start, SheetTarget.Continue(series))
                    }
                    else {
                        homeVm.findRecentVod(r.title) { vod ->
                            if (vod != null) openDetails(DetailTarget.Online(vod))
                            else { query = r.title; searchVm.search(r.title); page = Page.DISCOVER }
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
                onDelete = { downloadsVm.remove(it) }
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
                val entry = playerVm.currentEntry()
                if (entry != null) {
                    VideoPlayerScreen(
                        entry = entry,
                        hasPrev = playerVm.hasPrev(),
                        hasNext = playerVm.hasNext(),
                        onBack = ::goBack,
                        onPrev = { playerVm.prev() },
                        onNext = { playerVm.next() },
                        onEpisodes = { playSheet?.let { openSheet(it, EpisodeSheetMode.PLAY) } },
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
        )
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
