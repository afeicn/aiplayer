package com.afeicn.aiplayer.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.afeicn.aiplayer.*

// 预览用的假状态：全部走真实数据类，不依赖任何演示模型。

private val previewSource = OnlineVideoSource(id = "p1", name = "示例站", base = "https://example.org")

private fun previewSeries(title: String, downloaded: Int) = DownloadedSeries(
    title = title,
    episodes = (1..downloaded).map { index ->
        DownloadedEpisode(
            id = "$title-$index", title = title, episodeName = "第${index}集", episodeNumber = index,
            sizeBytes = 400L * 1024 * 1024, completedAt = index * 1000L, cover = "",
            item = OfflineItem(id = "$title-$index", title = title, url = "https://example.org/$index.m3u8"),
            totalEpisodes = 12
        )
    },
    cover = "", detailUrl = "https://example.org/vodplay/1.html", sourceName = "示例站"
)

private val previewHomeState = HomeUiState(
    series = listOf(previewSeries("三体", 6), previewSeries("在暴雪时分", 8), previewSeries("长相思", 3)),
    lastSeriesTitle = "三体",
    cacheSizeText = "4.6 GB",
    recent = listOf(
        RecentPlay("庆余年 第二季", "", 200L, "https://example.org/vodplay/9.html", lastEpisode = 9),
        RecentPlay("三体", "", 100L, lastEpisode = 6)
    )
)

private val previewSearchState = SearchUiState(
    history = listOf(SearchHistoryEntry("三体", 0L), SearchHistoryEntry("庆余年", 0L)),
    results = listOf(
        AggregatedVod(
            name = "三体", pic = "", year = "2023", area = "大陆", remarks = "全26集",
            hits = listOf(SourceHit(previewSource, VodItem("三体", vodYear = "2023", vodArea = "大陆")))
        )
    )
)

private val previewSheetState = EpisodeSheetUiState(
    title = "三体",
    episodes = (1..12).map { index -> EpisodeLink(index, "第${index}集", "https://example.org/play/$index") },
    statuses = mapOf(
        1 to DownloadTaskState.COMPLETED,
        2 to DownloadTaskState.DOWNLOADING,
        3 to DownloadTaskState.RESOLVING
    )
)

private val previewDownloads = listOf(
    OfflineDownload(
        item = OfflineItem(id = "d1", title = "三体", url = "https://example.org/8.m3u8", episodeName = "第8集", episodeNumber = 8),
        state = DownloadTaskState.DOWNLOADING, downloadedBytes = 120L * 1024 * 1024,
        totalBytes = 400L * 1024 * 1024, percent = 30f, failure = OfflineFailureReason.NONE
    ),
    OfflineDownload(
        item = OfflineItem(id = "d2", title = "三体", url = "https://example.org/9.m3u8", episodeName = "第9集", episodeNumber = 9),
        state = DownloadTaskState.FAILED, downloadedBytes = 0L, totalBytes = 0L,
        percent = 0f, failure = OfflineFailureReason.NETWORK
    ),
    OfflineDownload(
        item = OfflineItem(id = "d3", title = "长相思", url = "https://example.org/4.m3u8", episodeName = "第4集", episodeNumber = 4),
        state = DownloadTaskState.COMPLETED, downloadedBytes = 380L * 1024 * 1024,
        totalBytes = 380L * 1024 * 1024, percent = 100f, failure = OfflineFailureReason.NONE
    )
)

private val previewSourcesState = SourcesUiState(
    rows = listOf(
        SourceRowUi(previewSource, healthy = true, healthText = "已启用 · 运行正常"),
        SourceRowUi(previewSource.copy(id = "p2", name = "冷却站", enabled = false), healthy = false, healthText = "已停用")
    )
)

@Preview(name = "首页 · 浅色", showBackground = true, widthDp = 390, heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "首页 · 深色", showBackground = true, widthDp = 390, heightDp = 844,
    uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun HomeScreenPreview() {
    AIPlayerTheme {
        SoftPage {
            HomeScreen(previewHomeState, onSearch = {}, onDownloads = {}, onSources = {},
                downloadCount = 2, onOpenSeries = {}, onPlay = {}, onPlayRecent = {}, onOpenRecent = {})
            Box(
                modifier = Modifier.align(Alignment.BottomCenter)
                    .navigationBarsPadding().padding(bottom = 9.dp)
            ) { CapsuleNavigation("home", {}, {}) }
        }
    }
}

@Preview(name = "发现 · 搜索结果", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun DiscoverScreenPreview() {
    AIPlayerTheme {
        SoftPage {
            DiscoverScreen(previewSearchState, query = "三体", onQuery = {}, onSearch = {},
                onRemoveHistory = {}, onClearHistory = {}, onOpenResult = {}, onSources = {})
        }
    }
}

@Preview(name = "发现 · 首屏推荐", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun DiscoverIntroPreview() {
    val state = SearchUiState(
        history = listOf(SearchHistoryEntry("三体", 0L), SearchHistoryEntry("庆余年", 0L)),
        recommendations = listOf(
            AggregatedVod("三体", "", "2023", "大陆", "全26集",
                hits = (1..3).map { SourceHit(OnlineVideoSource("s$it", "源$it", "http://s$it.example.com"), VodItem("三体")) }),
            AggregatedVod("庆余年 第二季", "", "2024", "大陆", "更新至20集",
                hits = (1..5).map { SourceHit(OnlineVideoSource("s$it", "源$it", "http://s$it.example.com"), VodItem("庆余年 第二季")) })
        )
    )
    AIPlayerTheme {
        SoftPage {
            DiscoverScreen(state, query = "", onQuery = {}, onSearch = {},
                onRemoveHistory = {}, onClearHistory = {}, onOpenResult = {}, onSources = {})
        }
    }
}

@Preview(name = "发现 · 没搜到（下面仍有推荐）", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun DiscoverNoResultPreview() {
    val state = SearchUiState(
        results = emptyList(),
        recommendations = listOf(
            AggregatedVod("三体", "", "2023", "大陆", "全26集",
                hits = (1..3).map { SourceHit(OnlineVideoSource("s$it", "源$it", "http://s$it.example.com"), VodItem("三体")) })
        )
    )
    AIPlayerTheme {
        SoftPage {
            DiscoverScreen(state, query = "一个没有的关键词", onQuery = {}, onSearch = {},
                onRemoveHistory = {}, onClearHistory = {}, onOpenResult = {}, onSources = {})
        }
    }
}

@Preview(name = "详情 · 已下载剧集", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun OfflineDetailPreview() {
    AIPlayerTheme {
        DetailScreen(previewSeries("三体", 6), onBack = {}, onPlay = {}, onEpisodePlay = {}, onSelectEpisodes = {})
    }
}

@Preview(name = "选集面板 · 下载", showBackground = true, widthDp = 390, heightDp = 620)
@Composable
fun EpisodeSheetPreview() {
    AIPlayerTheme {
        EpisodeSheet(previewSheetState, mode = EpisodeSheetMode.DOWNLOAD, onDismiss = {}, onPlay = {}, onDownload = {})
    }
}

@Preview(name = "选集面板 · 播放", showBackground = true, widthDp = 390, heightDp = 620)
@Composable
fun EpisodeSheetPlayPreview() {
    AIPlayerTheme {
        EpisodeSheet(previewSheetState.copy(resumeEpisode = 4), mode = EpisodeSheetMode.PLAY,
            onDismiss = {}, onPlay = {}, onDownload = {})
    }
}

@Preview(name = "下载管理", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun DownloadsScreenPreview() {
    AIPlayerTheme {
        DownloadsScreen(previewDownloads, pendingRemoval = emptySet(),
            cacheSizeText = "4.6 GB", onBack = {}, onSettings = {}, onPause = {}, onResume = {},
            onRetry = {}, onDelete = {})
    }
}

@Preview(name = "设置", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SourcesScreenPreview() {
    AIPlayerTheme {
        SourcesScreen(previewSourcesState, allowMobileData = false, onSetAllowMobileData = {},
            onBack = {}, onAdd = {}, onToggle = { _, _ -> },
            onLongPressRow = {}, onToggleSelect = {}, onToggleSelectAll = {},
            onExitManage = {}, onRequestDeleteSelected = {},
            onReprobe = {}, onSaveAi = { _, _, _ -> }, onDismissProbe = {})
    }
}
