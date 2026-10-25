package com.afeicn.aiplayer.ui

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import com.afeicn.aiplayer.AggregatedVod
import com.afeicn.aiplayer.AppContainer
import com.afeicn.aiplayer.EpisodeSheetViewModel
import com.afeicn.aiplayer.HomeViewModel
import com.afeicn.aiplayer.MediaEntry
import com.afeicn.aiplayer.MediaKind
import com.afeicn.aiplayer.OnlineVideoSource
import com.afeicn.aiplayer.PlayerEngine
import com.afeicn.aiplayer.PlayerViewModel
import com.afeicn.aiplayer.SourceHit
import com.afeicn.aiplayer.VodItem
import com.afeicn.aiplayer.VodPlayGroup
import com.afeicn.aiplayer.VodPlayLine
import java.io.File

/**
 * 仅 debug 包可用的截图画廊：用 ScreenPreviews 的假数据把各页面独立渲染出来。
 *
 * 新模拟器上没有离线数据，真机走不到「有卡片 / 有结果 / 有下载状态」的界面；
 * 这个 Activity 让每个页面都能直接截图逐页核对深色模式，不参与 release 构建。
 *
 * 用法：adb shell am start -n com.afeicn.aiplayer/.ui.PreviewGalleryActivity --es screen home
 */
class PreviewGalleryActivity : ComponentActivity() {

    /** 复现「上一集/下一集」状态用：和 MainActivity 一样持有真实的 PlayerViewModel。 */
    private val playerVm: PlayerViewModel by viewModels()

    /** 进度持久化联调：用真实的首页/选集 ViewModel 读持久化结果，避免另写一套读取逻辑。 */
    private val homeVm: HomeViewModel by viewModels()
    private val sheetVm: EpisodeSheetViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val screen = intent.getStringExtra("screen").orEmpty()
        // 可选：传入本地视频地址，播放页就能拿到真实 duration（拖动预览需要）。
        val media = intent.getStringExtra("media")
        // 播放页必须横屏；其余保持竖屏。
        if (screen == "player" || screen == "player-queue") {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        if (screen == "player-queue") {
            val start = intent.getIntExtra("start", 0)
            // 音频用于验证「听音频不该常亮」：同一播放页只换 MediaKind，其余链路不变。
            val kind = if (intent.getBooleanExtra("audio", false)) MediaKind.AUDIO else MediaKind.VIDEO
            // 与 MainActivity.openPlayer 的离线剧分支一致：播过就进「最近浏览」，
            // 这样杀进程重进后，首页大卡/最近列表才有可核对的条目。
            AppContainer.playbackProgress.recordPlay(
                "队列测试", "", "", System.currentTimeMillis()
            )
            playerVm.playQueue(demoQueue(kind), startIndex = start)
        }
        if (screen == "sheet-real") {
            sheetVm.load(demoProgressVod())
        }
        setContent {
            AIPlayerTheme {
                GalleryScreen(screen, media, playerVm, homeVm, sheetVm)
            }
        }
    }

    /** 三条同源但标题不同的队列，仅用于验证切集后的按钮/标题是否刷新。 */
    private fun demoQueue(kind: MediaKind = MediaKind.VIDEO): List<MediaEntry> {
        val uri = Uri.fromFile(File(filesDir, "sample.mp4"))
        return (1..3).map { i ->
            MediaEntry(
                uri = uri, name = "队列测试", kind = kind,
                folder = "本地", extension = "mp4", title = "第 $i 集", episodeNumber = i
            )
        }
    }

    /** 在线直链演示条目：选集面板走真实 load 分支（不联网），读到持久化的续播集号。 */
    private fun demoProgressVod(): AggregatedVod {
        val source = OnlineVideoSource(id = "demo", name = "演示源", base = "https://example.invalid")
        val lines = (1..15).map { VodPlayLine("第 $it 集", "https://example.invalid/$it.m3u8") }
        val item = VodItem(vodName = "队列测试", playGroups = listOf(VodPlayGroup("正片", lines)))
        return AggregatedVod(
            name = "队列测试", pic = "", year = "", area = "", remarks = "",
            hits = listOf(SourceHit(source, item))
        )
    }
}

@Composable
private fun GalleryScreen(
    screen: String,
    media: String?,
    playerVm: PlayerViewModel,
    homeVm: HomeViewModel,
    sheetVm: EpisodeSheetViewModel
) {
    when (screen) {
        "home" -> SoftPage {
            HomeScreen(previewHomeState, onSearch = {}, onDownloads = {}, onSources = {},
                downloadCount = 2, onOpenSeries = {}, onPlay = {}, onPlayRecent = {}, onOpenRecent = {})
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(bottom = 9.dp)
            ) { CapsuleNavigation("home", {}, {}) }
        }

        "discover" -> SoftPage {
            DiscoverScreen(previewSearchState, query = "三体", onQuery = {}, onSearch = {},
                onRemoveHistory = {}, onClearHistory = {}, onOpenResult = {}, onSources = {})
        }

        "discover-intro" -> SoftPage {
            DiscoverScreen(previewIntroState, query = "", onQuery = {}, onSearch = {},
                onRemoveHistory = {}, onClearHistory = {}, onOpenResult = {}, onSources = {})
        }

        "discover-empty" -> SoftPage {
            DiscoverScreen(previewNoResultState, query = "一个没有的关键词", onQuery = {}, onSearch = {},
                onRemoveHistory = {}, onClearHistory = {}, onOpenResult = {}, onSources = {})
        }

        "detail" -> DetailScreen(previewSeries("三体", 6),
            onBack = {}, onPlay = {}, onEpisodePlay = {}, onSelectEpisodes = {})

        "detail-online" -> DetailScreen(previewOnlineVod,
            onBack = {}, onPlay = {}, onSelectEpisodes = {})

        "sheet-download" -> EpisodeSheet(previewSheetState, mode = EpisodeSheetMode.DOWNLOAD,
            onDismiss = {}, onPlay = {}, onDownload = {})

        "sheet-play" -> EpisodeSheet(previewSheetState.copy(resumeEpisode = 4),
            mode = EpisodeSheetMode.PLAY, onDismiss = {}, onPlay = {}, onDownload = {})

        "downloads" -> DownloadsScreen(previewDownloads, pendingRemoval = emptySet(),
            cacheSizeText = "4.6 GB", onBack = {}, onSettings = {}, onPause = {}, onResume = {},
            onRetry = {}, onDelete = {})

        "sources" -> SourcesScreen(previewSourcesState, allowMobileData = false, onSetAllowMobileData = {},
            onBack = {}, onAdd = {}, onToggle = { _, _ -> },
            onLongPressRow = {}, onToggleSelect = {}, onToggleSelectAll = {},
            onExitManage = {}, onRequestDeleteSelected = {},
            onReprobe = {}, onSaveAi = { _, _, _ -> }, onDismissProbe = {})

        "dialog", "dialog-disabled" -> {
            SoftPage { /* 只提供主题底色，对话框自己开窗口 */ }
            SoftDialog(
                onDismissRequest = {},
                title = "添加订阅源",
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("粘贴 MacCMS 站点地址，应用会自动探测可用接口。",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        OutlinedTextField(
                            value = "https://example.org/api.php/provide/vod",
                            onValueChange = {}, singleLine = true,
                            label = { Text("站点地址") }
                        )
                    }
                },
                confirmText = "保存",
                onConfirm = {},
                confirmEnabled = screen != "dialog-disabled",
                dismissText = "取消"
            )
        }

        "player" -> PlayerGalleryScreen(media)

        // 回归用：验证切集后标题与「上一集/下一集」是否随真实游标刷新。
        "player-queue" -> {
            val cursor by playerVm.cursor.collectAsStateWithLifecycle()
            val entry = cursor.currentEntry
            if (entry != null) {
                VideoPlayerScreen(
                    entry = entry,
                    hasPrev = cursor.hasPrev,
                    hasNext = cursor.hasNext,
                    onBack = {}, onPrev = { playerVm.prev() }, onNext = { playerVm.next() },
                    onEpisodes = {}, onSaveProgress = {}
                )
            }
        }

        // 进度持久化联调：真实 HomeViewModel 从 SharedPreferences 读，验证重启后首页两处文案。
        "home-real" -> {
            val state by homeVm.state.collectAsStateWithLifecycle()
            SoftPage {
                HomeScreen(state, onSearch = {}, onDownloads = {}, onSources = {},
                    downloadCount = state.series.size, onOpenSeries = {}, onPlay = {},
                    onPlayRecent = {}, onOpenRecent = {})
            }
        }

        // 进度持久化联调：真实 EpisodeSheetViewModel.load 读同一份集号，验证续播按钮与高亮。
        "sheet-real" -> {
            val state by sheetVm.state.collectAsStateWithLifecycle()
            SoftPage {
                EpisodeSheet(state, mode = EpisodeSheetMode.PLAY,
                    onDismiss = {}, onPlay = {}, onDownload = {})
            }
        }

        else -> SoftPage {
            Text("未知页面：$screen", modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * 播放页截图专用：给 ExoPlayer 真正 prepare 一段公开测试流，
 * 这样进度条有 duration，横向拖动才会出现快进预览浮层（生产代码不参与）。
 */
@Composable
private fun PlayerGalleryScreen(media: String?) {
    val context = LocalContext.current
    // 没有本地样品时退回公开测试流的地址（只影响截图，不影响生产播放链路）。
    val entry = remember(media) {
        MediaEntry(
            uri = Uri.parse(media ?: "https://storage.googleapis.com/exoplayer-test-media-0/playlist.m3u8"),
            name = "庆余年 第二季", kind = MediaKind.VIDEO,
            folder = "在线播放", extension = "m3u8", title = "第 8 集"
        )
    }
    LaunchedEffect(Unit) {
        PlayerEngine.player(context).apply {
            setMediaItem(MediaItem.fromUri(entry.uri))
            prepare()
        }
    }
    VideoPlayerScreen(
        entry = entry,
        hasPrev = true, hasNext = true,
        onBack = {}, onPrev = {}, onNext = {}, onEpisodes = {}, onSaveProgress = {}
    )
}
