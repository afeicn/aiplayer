package com.afeicn.aiplayer.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.net.Uri
import android.content.Intent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afeicn.aiplayer.*
import com.afeicn.aiplayer.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val ScreenPadding = 18.dp
private val ListBottomSpace = 120.dp

// ==================== 首页（离线库） ====================

@Composable
fun HomeScreen(
    state: HomeUiState,
    // 滚动状态由上层传入（提升到 PlayerApp），切页再回来不丢滚动位置。
    listState: LazyListState = rememberLazyListState(),
    onSearch: () -> Unit,
    onDownloads: () -> Unit,
    onSources: () -> Unit,
    downloadCount: Int,
    onOpenSeries: (DownloadedSeries) -> Unit,
    onPlay: (DownloadedSeries) -> Unit,
    onPlayRecent: (RecentPlay) -> Unit,
    onOpenRecent: (RecentPlay) -> Unit
) {
    // 大卡取最近浏览里「播过」的那部（有续播地址、有观看进度或已下载）；
    // 只浏览没播放不算正在播放，兜底最新完成下载的一部。
    val hero = state.recent.firstOrNull { r ->
        r.playUrl.isNotBlank() || r.lastEpisode > 0 ||
            state.series.any { OfflineLibrary.sameTitle(it.title, r.title) }
    }
    val heroSeries = hero?.let { r -> state.series.firstOrNull { OfflineLibrary.sameTitle(it.title, r.title) } }
    val fallback = if (hero == null) state.series.maxByOrNull { it.newestCompletedAt } else null
    // 大卡与横滑小卡可能同一部作品：共享元素 key 同页只能出现一次，重复的那张让给大卡。
    val heroTitle = hero?.title ?: fallback?.title
    // 一部剧只有一个条目：已下载的剧不再出现在「最近浏览」，「已下载」卡就是它的入口。
    val recentRow = state.recent.filter { r -> state.series.none { OfflineLibrary.sameTitle(it.title, r.title) } }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("AI Player", onSearch, onDownloads, onSources, downloadCount = downloadCount,
            logoMark = true)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = ListBottomSpace),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            if (hero != null) {
                item {
                    HeroCard(
                        label = "正在播放",
                        title = hero.title,
                        subtitle = heroSeries?.countLabel
                            ?: if (hero.lastEpisode > 0) "上次看到第 ${hero.lastEpisode} 集" else "最近播放",
                        // 最近浏览里海报为空（老记录没存图）时退回下载记录里的海报。
                        cover = hero.cover.ifBlank { heroSeries?.cover.orEmpty() },
                        onDetail = { heroSeries?.let(onOpenSeries) ?: onOpenRecent(hero) },
                        onPlay = { heroSeries?.let(onPlay) ?: onPlayRecent(hero) }
                    )
                }
            } else if (fallback != null) {
                item {
                    HeroCard("最近下载", fallback.title, fallback.countLabel, fallback.cover,
                        onDetail = { onOpenSeries(fallback) }, onPlay = { onPlay(fallback) })
                }
            } else {
                // 既没播过也没下载过：空态插画占住大卡位置，首页顶部不至于空一块。
                item {
                    RevealOnEnter {
                    Column(
                        Modifier.fillMaxWidth().clip(heroShape)
                            .background(MaterialTheme.colorScheme.surface),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.empty_home_library),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(240.dp).padding(top = 20.dp)
                        )
                        Text("还没看过什么好片", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface)
                        Text("去「发现」搜一部，点进详情页就会出现在下面", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                    }
                    }
                }
            }
            item { HomeSectionHeader("已下载",
                if (state.cacheSizeText.isBlank()) "${state.series.size} 部"
                else "${state.series.size} 部 · ${state.cacheSizeText}") }
            if (state.series.isEmpty()) {
                item {
                    Text("去「发现」搜索作品，选集下载后放这里", fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.series.isNotEmpty()) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.series) { series ->
                            MiniSeriesCard(series.title, series.cover, series.countLabel,
                                onClick = { onOpenSeries(series) },
                                sharedKey = if (heroTitle != null && OfflineLibrary.sameTitle(series.title, heroTitle))
                                    null else posterKey(series.title))
                        }
                    }
                }
            }
            if (recentRow.isNotEmpty()) {
                item { HomeSectionHeader("最近浏览") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(recentRow) { r ->
                            MiniSeriesCard(r.title, r.cover,
                                if (r.lastEpisode > 0) "看到第 ${r.lastEpisode} 集" else "最近浏览",
                                onClick = { onOpenRecent(r) },
                                sharedKey = if (heroTitle != null && OfflineLibrary.sameTitle(r.title, heroTitle))
                                    null else posterKey(r.title))
                        }
                    }
                }
            } else if (state.recent.isEmpty()) {
                item { HomeSectionHeader("最近浏览") }
                item {
                    Text("点进过详情页的作品都会出现在这里", fontSize = 12.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 12.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** 首页栏目标题：主色细条 + 加粗标题，右侧可挂计数胶囊，两栏区共用一套节奏。 */
@Composable
private fun HomeSectionHeader(title: String, countLabel: String? = null) {
    val palette = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(4.dp).height(16.dp).clip(CircleShape).background(palette.primary))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = (-.3).sp)
        if (countLabel != null) {
            Spacer(Modifier.width(7.dp))
            Surface(shape = CircleShape, color = palette.surfaceVariant) {
                Text(countLabel, fontSize = 11.sp, color = palette.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
    }
}

// ==================== 发现（跨源搜索） ====================
/** 热搜榜名次配色：前三名给暖色，其余走中性色，深浅主题都不刺眼。 */
@Composable
private fun hotRankColor(rank: Int): Color = when (rank) {
    1 -> Color(0xFFE5484D)
    2 -> Color(0xFFF76808)
    3 -> Color(0xFFEBB11E)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
fun DiscoverScreen(
    state: SearchUiState,
    // 滚动状态由上层传入（提升到 PlayerApp），切页再回来不丢滚动位置。
    listState: LazyListState = rememberLazyListState(),
    query: String,
    onQuery: (String) -> Unit,
    onSearch: (String) -> Unit,
    onRemoveHistory: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenResult: (AggregatedVod) -> Unit,
    onSources: () -> Unit
) {
    val focus = LocalFocusManager.current
    // 只看关键词是否为空：清空输入框就回到推荐首屏，不用管上一次搜索的残留结果。
    val showIntro = query.isBlank() && !state.searching
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("发现", onSources = onSources)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = ScreenPadding, end = ScreenPadding, bottom = ListBottomSpace),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                // 紧凑搜索条：默认 OutlinedTextField 有 56dp 高，这一页整体要收紧，手搭一条单行输入。
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 5.dp, bottom = 5.dp)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        BasicTextField(
                            value = query,
                            onValueChange = onQuery,
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus(); onSearch(query) }),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { inner ->
                                Box {
                                    if (query.isEmpty()) {
                                        Text("搜索影视作品…",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    inner()
                                }
                            },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
                        )
                        if (query.isNotEmpty()) IconButton(onClick = { onQuery("") },
                            modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, "清空搜索", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
            if (showIntro) {
                if (SearchHistoryRules.isVisible(state.history)) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("搜索历史", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.weight(1f))
                                // 不用 TextButton：48dp 最小触控区会把标题行撑高。
                                Text("清空", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clip(CircleShape)
                                        .clickable(onClick = onClearHistory)
                                        .padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                            state.history.chunked(3).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { entry ->
                                        // 同热搜词条：可点击 Surface 的 48dp 触控区会在行间塞进隐形边距。
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surfaceVariant,
                                            modifier = Modifier.clickable { onQuery(entry.keyword); onSearch(entry.keyword) }
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 4.dp, bottom = 4.dp)) {
                                                Text(entry.keyword, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                                                    modifier = Modifier.widthIn(max = 96.dp))
                                                IconButton(onClick = { onRemoveHistory(entry.keyword) },
                                                    modifier = Modifier.size(22.dp)) {
                                                    Icon(Icons.Default.Close, "删除历史：${entry.keyword}",
                                                        modifier = Modifier.size(13.dp),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("热门搜索", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        HotSearchCatalog.display().mapIndexed { index, keyword -> (index + 1) to keyword }
                            .chunked(2).forEach { row ->
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    row.forEach { (rank, keyword) ->
                                        // 不用 Surface(onClick=)：可点击 Surface 会强制 48dp 最小触控区，
                                        // 把 26dp 的词条撑进透明框，行间凭空多出约 22dp 空隙。
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f),
                                            modifier = Modifier.weight(1f).clickable { onQuery(keyword); onSearch(keyword) }
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                            ) {
                                                // 不设 lineHeight 会落到 bodyLarge 的 24sp，词条被撑高、行距显松。
                                                Text("$rank", fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold,
                                                    color = hotRankColor(rank), modifier = Modifier.width(13.dp))
                                                Spacer(Modifier.width(5.dp))
                                                Text(keyword, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                            }
                    }
                }
                if (state.noEnabledSources) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Image(
                                painter = painterResource(R.drawable.empty_discover_sources),
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(170.dp)
                            )
                            Text("连上订阅源，好片马上到～", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    // 与设置页「添加订阅源」同款：紫色主按钮、半屏宽、居中。
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            PrimaryPill("添加订阅源", Icons.Default.Add, onSources, Modifier.fillMaxWidth(0.5f))
                        }
                    }
                } else {
                    recommendationItems(state, emptyHint = "订阅源这次没返回推荐，直接搜一个关键词试试",
                        onQuery = onQuery, onOpenResult = onOpenResult)
                }
                item { Spacer(Modifier.height(6.dp)) }
            } else {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("搜索结果", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        if (state.searching) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("正在搜索…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        } else if (state.results.isNotEmpty()) {
                            Text("${state.results.size} 部作品", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                    }
                }
                if (state.noEnabledSources) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("还没有启用中的订阅源，去设置里添加订阅源。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                                PrimaryPill("添加订阅源", Icons.Default.Add, onSources, Modifier.fillMaxWidth(0.5f))
                            }
                        }
                    }
                }
                if (state.error != null) {
                    item { Text(state.error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
                if (state.failedSources > 0 && state.results.isNotEmpty()) {
                    item {
                        Text("${state.failedSources} 个源本次搜索失败，结果可能不全",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                if (!state.searching && state.results.isEmpty() && state.error == null && !state.noEnabledSources) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Image(
                                painter = painterResource(R.drawable.noresult),
                                contentDescription = "没有搜索结果",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(180.dp)
                            )
                            Text("这个关键词没搜到作品", fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface)
                            Text("换个说法再试，或者检查订阅源是否可用", fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // 没搜到不等于没内容：下面照样给推荐，用户不至于撞墙退出。
                    recommendationItems(state, emptyHint = null, onQuery = onQuery, onOpenResult = onOpenResult)
                }
                items(state.results) { vod ->
                    VodRow(vod, onClick = { onQuery(vod.name); onOpenResult(vod) })
                }
            }
        }
    }
}

/**
 * 「为你推荐」这一段，首屏和「没搜到」两处共用。
 *
 * [emptyHint] 传 null 表示「推荐为空就整段不出现」——在「没搜到」下面再补一句「也没推荐」等于劝退。
 */
private fun LazyListScope.recommendationItems(
    state: SearchUiState,
    emptyHint: String?,
    onQuery: (String) -> Unit,
    onOpenResult: (AggregatedVod) -> Unit
) {
    if (state.noEnabledSources) return
    val nothingToShow = state.recommendations.isEmpty() && !state.recommendationsLoading
    if (nothingToShow && emptyHint == null) return
    item {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("为你推荐", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (state.recommendationsLoading) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在挑选…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    items(state.recommendations) { vod ->
        VodRow(vod, onClick = { onQuery(vod.name); onOpenResult(vod) })
    }
    val hint = emptyHint
    if (nothingToShow && hint != null) {
        item { Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ==================== 详情：在线结果 ====================

@Composable
fun DetailScreen(
    vod: AggregatedVod,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onSelectEpisodes: () -> Unit
) {
    // 简介：MacCMS 源聚合时就带着；HTML 搜索命中没有，进页面后去详情页抓一次 meta/简介段落。
    var blurb by remember(vod.name) { mutableStateOf(vod.blurb) }
    var blurbLoading by remember(vod.name) {
        mutableStateOf(vod.blurb.isBlank() && vod.detailUrls.isNotEmpty())
    }
    var blurbExpanded by remember(vod.name) { mutableStateOf(false) }
    LaunchedEffect(vod.name) {
        if (blurb.isBlank()) {
            val url = vod.detailUrls.firstOrNull()
            val referer = vod.hits.firstOrNull()?.source?.referer?.takeIf { it.isNotBlank() }
            if (url != null) {
                blurb = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        VodBlurbParser.extract(HttpPageFetcher().fetch(url, referer).body)
                    }.getOrDefault("")
                }
            }
            blurbLoading = false
        }
    }
    LazyColumn(
        // 文案在转场后段浮现：海报先飞到位（360ms），标题按钮在 180ms 后淡入补齐。
        Modifier.fillMaxSize().revealDelayed(Motion.DETAIL_CONTENT_DELAY, Motion.DETAIL_ENTER - Motion.DETAIL_CONTENT_DELAY),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { DetailHeader(vod.pic, vod.name, onBack) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(vod.name, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    vod.year.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                    vod.area.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                    vod.remarks.takeIf { it.isNotBlank() }?.let { MetaChip(it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 播放模式会展开选集列表逐集解析，没有直链但有详情页也能播。
                    PrimaryPill("立即播放", Icons.Default.PlayArrow, onPlay, Modifier.weight(1f),
                        enabled = vod.hasPlayableLines || vod.detailUrls.isNotEmpty())
                    SecondaryPill("选集下载", Icons.Default.FileDownload, onSelectEpisodes, Modifier.weight(1f))
                }
                if (!vod.hasPlayableLines) {
                    Text("这条结果需要先打开详情页抓集数，选集后可以在线播放或下载。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
                }
                if (blurbLoading) {
                    Spacer(Modifier.height(6.dp))
                    Text("正在获取简介…", fontSize = 12.sp, lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else if (blurb.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    BlurbCard(blurb, blurbExpanded) { blurbExpanded = !blurbExpanded }
                }
            }
        }
    }
}

// ==================== 详情：已下载剧集 ====================

@Composable
fun DetailScreen(
    series: DownloadedSeries,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onEpisodePlay: (Int) -> Unit,
    onSelectEpisodes: () -> Unit
) {
    // 简介：优先读下载记录落库的 blurb（下载/自愈时提取）；没有才凭详情页地址懒抓一次，失败/为空整块不出现。
    var blurb by remember(series.title) { mutableStateOf(series.blurb) }
    var blurbExpanded by remember(series.title) { mutableStateOf(false) }
    LaunchedEffect(series.title) {
        val url = series.detailUrl
        if (blurb.isBlank() && url.isNotBlank()) {
            // 带来源请求头里的 Referer：不少站点无 Referer 直接 403，懒抓永远失败。
            val referer = series.sourceItem?.headers?.get("Referer")?.takeIf { it.isNotBlank() }
            blurb = withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { VodBlurbParser.extract(HttpPageFetcher().fetch(url, referer).body) }.getOrDefault("")
            }
        }
    }
    LazyColumn(
        // 文案在转场后段浮现：海报先飞到位（360ms），标题按钮在 180ms 后淡入补齐。
        Modifier.fillMaxSize().revealDelayed(Motion.DETAIL_CONTENT_DELAY, Motion.DETAIL_ENTER - Motion.DETAIL_CONTENT_DELAY),
        contentPadding = PaddingValues(bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { DetailHeader(series.cover, series.title, onBack) }
        item {
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(series.title, fontSize = 29.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryPill("继续播放", Icons.Default.PlayArrow, onPlay, Modifier.weight(1f))
                    SecondaryPill(ContinueDownloadRules.LABEL, Icons.Default.FileDownload, onSelectEpisodes, Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
                Text("已下载剧集", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                series.episodes.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { episode ->
                            val position = series.episodes.indexOf(episode)
                            Surface(
                                onClick = { onEpisodePlay(position) }, modifier = Modifier.weight(1f).height(42.dp),
                                shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        episode.episodeNumber?.toString() ?: episode.episodeName.ifBlank { "·" },
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                        repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        if (blurb.isNotBlank()) item {
            Box(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                BlurbCard(blurb, blurbExpanded) { blurbExpanded = !blurbExpanded }
            }
        }
    }
}

/** 详情页「剧情简介」卡片：在线与离线详情页共用同一款（紫条标题 + 4 行折叠 + 展开/收起）。 */
@Composable
private fun BlurbCard(blurb: String, expanded: Boolean, onToggle: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth(),
        shape = cardShape, color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(16.dp).clip(CircleShape).background(Ink.Violet))
                Spacer(Modifier.width(8.dp))
                Text("剧情简介", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Text(blurb, fontSize = 13.sp, lineHeight = 21.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis)
            // 超过 4 行才出「展开」，短简介不打扰。
            if (blurb.length > 120) {
                TextButton(onClick = onToggle,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)) {
                    Text(if (expanded) "收起" else "展开", fontSize = 13.sp, color = Ink.Violet)
                }
            }
        }
    }
}

@Composable
private fun DetailHeader(cover: String, title: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(330.dp)) {
        // 与卡片端同一个 key：海报和底部羽化蒙版包在同一个共享元素里，
        // 转场时蒙版随海报一起形变，而不是海报落地后蒙版再后贴上去。
        // 两端各自声明 clip 形状（卡片端圆角、这里直角），转场中形状连续插值，
        // 返回时不会直角飞回、落地突然变圆。
        Box(Modifier.fillMaxSize().posterSharedElement(posterKey(title)).clip(RectangleShape)) {
            CoverImage(cover, "${title}剧集海报", modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x4D091025), Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        Surface(
            onClick = onBack,
            color = Color.Black.copy(alpha = .35f), shape = CircleShape,
            modifier = Modifier.statusBarsPadding().padding(start = 16.dp, top = 12.dp).size(44.dp)
        ) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Default.ArrowBack, "返回", tint = Color.White) } }
    }
}

@Composable
private fun MetaChip(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Text(text, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ==================== 选集面板 ====================

/** 选集面板两种模式：PLAY=点一集直接播（带「继续上次播放」）；DOWNLOAD=多选入队下载，面板内不设播放入口。 */
enum class EpisodeSheetMode { PLAY, DOWNLOAD }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSheet(
    state: EpisodeSheetUiState,
    mode: EpisodeSheetMode,
    onDismiss: () -> Unit,
    onPlay: (EpisodeLink) -> Unit,
    onDownload: (Set<Int>) -> Unit
) {
    val total = state.episodes.size
    val motionOn = rememberMotionEnabled()
    val available = remember(state.episodes, state.statuses) {
        EpisodeDownloadRules.availableIndices(total, state.statuses)
    }
    var selection by remember(state.title, state.episodes) { mutableStateOf(setOf<Int>()) }
    val downloadedCount = state.statuses.values.count { it == DownloadTaskState.COMPLETED }
    val canResume = state.resumeEpisode in 1..total
    // 每页 30 集：超过 30 集出分页签，页签只在多页时显示；选择集跨页保留。
    val pageSize = 30
    val pageCount = ((total + pageSize - 1) / pageSize).coerceAtLeast(1)
    var page by remember(state.title, state.episodes) { mutableStateOf(0) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface,
        sheetMaxWidth = 620.dp, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 18.dp)
                // 数据到达换内容时面板高度平滑过渡，不在起弹动画中途硬跳一截。
                .animateContentSize(motionTween(motionOn, Motion.SHEET_OPEN)),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(state.title, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                    if (state.episodes.isNotEmpty()) {
                        Text("共 $total 集 · 已下载 $downloadedCount 集", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            when {
                state.locating -> EpisodeGridSkeleton(ContinueDownloadRules.LOOKUP_LABEL)
                state.loading -> EpisodeGridSkeleton("正在获取集数…")
                state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    lineHeight = 20.sp)
                state.episodes.isEmpty() -> Text("没有解析到集数", color = MaterialTheme.colorScheme.onSurfaceVariant)
                mode == EpisodeSheetMode.PLAY -> {
                    // 播放模式：有续播记录先给「继续上次播放」，其余点哪集播哪集，不涉及下载选择。
                    if (canResume) {
                        PrimaryPill("继续上次播放 · 第 ${state.resumeEpisode} 集", Icons.Default.PlayArrow,
                            onClick = {
                                state.episodes.firstOrNull { it.index == state.resumeEpisode }?.let(onPlay)
                            })
                    }
                    Text(if (canResume) "点一集换着播" else "点一集开始播放", fontWeight = FontWeight.Bold)
                    if (pageCount > 1) EpisodePageTabs(total, page) { page = it }
                    // 分页签切页：格子整体 220ms 交叉淡化，不重放整个面板。
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            fadeIn(tween(motionDuration(motionOn, Motion.TAB_SWITCH))) togetherWith
                                fadeOut(tween(motionDuration(motionOn, Motion.TAB_SWITCH)))
                        },
                        label = "episodePage"
                    ) { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ((p * pageSize + 1)..minOf(total, (p + 1) * pageSize)).chunked(6).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                group.forEach { n ->
                                    EpisodeCell(n, EpisodeDownloadRules.statusOf(state.statuses[n]),
                                        selected = false,
                                        // 每集都可点播（与下载状态无关）；上次播过的那格高亮。
                                        enabled = true,
                                        marked = n == state.resumeEpisode,
                                        resolving = state.resolvingEpisode == n,
                                        onClick = {
                                            state.episodes.firstOrNull { it.index == n }?.let(onPlay)
                                        })
                                }
                            }
                        }
                    }
                    }
                }
                else -> {
                    // 下载模式：只管选集入队，播放入口在详情页，不在这里混。
                    Text("选择下载的剧集", fontWeight = FontWeight.Bold)
                    if (pageCount > 1) EpisodePageTabs(total, page) { page = it }
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            fadeIn(tween(motionDuration(motionOn, Motion.TAB_SWITCH))) togetherWith
                                fadeOut(tween(motionDuration(motionOn, Motion.TAB_SWITCH)))
                        },
                        label = "episodeDownloadPage"
                    ) { p ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ((p * pageSize + 1)..minOf(total, (p + 1) * pageSize)).chunked(6).forEach { group ->
                            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                group.forEach { n ->
                                    EpisodeCell(n, EpisodeDownloadRules.statusOf(state.statuses[n]),
                                        selected = n in selection,
                                        onClick = {
                                            if (n in available) {
                                                selection = EpisodeSelection.toggle(selection, n)
                                            }
                                        })
                                }
                            }
                        }
                    }
                    }
                    // 快捷选择：「下10集」以当前选中的最大集为基准往后推，连点 前10集 → 下10集 就是从头分段选；
                    // 「全部未看」= 当前播放集之后所有还没下载、也没在排队下载的集（没播放记录就是全剧）；
                    // 取消选择直接在网格里点回那一集，不再单设「清空」。
                    // 整部不足 10 集时全选就是全部，快捷区没有存在意义，直接不显示。
                    if (total >= 10) {
                        Text("快捷选择", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickTag("全选") { selection = available }
                            QuickTag("前10集") { selection = available intersect EpisodeSelection.firstN(total, 10) }
                            QuickTag("下10集") {
                                val base = selection.maxOrNull() ?: 10
                                selection = available intersect EpisodeSelection.range(total, base + 1, base + 10)
                            }
                            QuickTag("全部未看") {
                                // 上次播到第 N 集，就选 N+1 往后所有可下载集；没有播放记录则从第 1 集起。
                                val from = state.resumeEpisode.coerceIn(0, total) + 1
                                selection = available intersect EpisodeSelection.range(total, from, total)
                            }
                        }
                    }
                    PrimaryPill("下载 ${selection.size} 集", Icons.Default.FileDownload,
                        { onDownload(selection) }, Modifier.fillMaxWidth(), enabled = selection.isNotEmpty())
                }
            }
        }
    }
}

/** 选集分页签：每 30 集一页，标签写集数区间；选中页用主色实底，与快捷标签同一套胶囊语言。 */
@Composable
private fun EpisodePageTabs(total: Int, page: Int, onSelect: (Int) -> Unit) {
    val motionOn = rememberMotionEnabled()
    val palette = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat((total + 29) / 30) { i ->
            val current = i == page
            val tabBg by animateColorAsState(
                if (current) palette.primary else palette.surfaceVariant,
                motionTween(motionOn, Motion.TAB_SWITCH), label = "pageTabBg"
            )
            val tabFg by animateColorAsState(
                if (current) Color.White else palette.onSurface,
                motionTween(motionOn, Motion.TAB_SWITCH), label = "pageTabFg"
            )
            Surface(
                onClick = { onSelect(i) }, shape = CircleShape,
                color = tabBg,
                contentColor = tabFg
            ) {
                Text("${i * 30 + 1}-${minOf((i + 1) * 30, total)}集", fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

/** 快捷选择标签：圆形胶囊、浅色实底，比标准 chip 轻，不跟下载按钮抢视觉。 */
@Composable
private fun QuickTag(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick, shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Text(text, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
    }
}

/**
 * 加载占位：与加载后的选集网格同构（5 行 × 6 格、同尺寸同间距），
 * 面板起弹过程中数据到达时高度几乎不变，不会中途跳一下。
 */
@Composable
private fun EpisodeGridSkeleton(label: String) {
    val palette = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(label, color = palette.onSurfaceVariant)
        }
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            repeat(5) {
                Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    repeat(6) {
                        Box(Modifier.width(48.dp).height(32.dp).clip(CircleShape)
                            .background(palette.surfaceVariant.copy(alpha = .45f)))
                    }
                }
            }
        }
    }
}

/**
 * 选集网格单格：固定大小的圆形胶囊（48x32），不随集数位数或所在行拉伸；
 * 状态角标收在数字旁边，避免被胶囊圆角裁掉。
 * 下载模式不可选的集不响应点选，播放模式每集都可点。
 */
@Composable
private fun EpisodeCell(
    index: Int,
    status: EpisodeGridStatus,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = status == EpisodeGridStatus.AVAILABLE,
    marked: Boolean = false,
    resolving: Boolean = false
) {
    val palette = MaterialTheme.colorScheme
    val motionOn = rememberMotionEnabled()
    // 选中/高亮变色 150ms 过渡；快捷选择批量改选中态时所有格子同一动画节奏，不逐个跳动。
    val cellBackground by animateColorAsState(
        when {
            selected || marked -> palette.primary
            status == EpisodeGridStatus.DOWNLOADED -> palette.primaryContainer
            else -> palette.surfaceVariant
        },
        motionTween(motionOn, Motion.ICON_STATE), label = "cellBg"
    )
    val cellContentColor by animateColorAsState(
        when {
            selected || marked -> Color.White
            status == EpisodeGridStatus.DOWNLOADED -> palette.primary
            else -> palette.onSurface
        },
        motionTween(motionOn, Motion.ICON_STATE), label = "cellFg"
    )
    Surface(
        onClick = onClick, enabled = enabled,
        shape = CircleShape, modifier = Modifier.width(48.dp).height(32.dp),
        color = cellBackground
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (resolving) {
                // 详情页模式正在解析这一集的播放地址，格内转圈。
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = cellContentColor)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("$index", fontSize = 12.sp, color = cellContentColor)
                    when (status) {
                        EpisodeGridStatus.DOWNLOADED -> Icon(Icons.Default.Check, "已下载",
                            modifier = Modifier.size(10.dp),
                            tint = if (marked) Color.White else palette.primary)
                        EpisodeGridStatus.RESOLVING -> Text("⋯", fontSize = 9.sp, color = palette.onSurfaceVariant)
                        EpisodeGridStatus.ACTIVE -> Icon(Icons.Default.Download, "下载中",
                            modifier = Modifier.size(10.dp), tint = palette.onSurfaceVariant)
                        EpisodeGridStatus.AVAILABLE -> Unit
                    }
                }
            }
        }
    }
}

// ==================== 下载管理 ====================

private val ACTIVE_STATES = setOf(
    DownloadTaskState.RESOLVING, DownloadTaskState.QUEUED, DownloadTaskState.DOWNLOADING,
    DownloadTaskState.PAUSED, DownloadTaskState.WAITING_NETWORK, DownloadTaskState.WAITING_WIFI
)

@Composable
fun DownloadsScreen(
    downloads: List<OfflineDownload>,
    pendingRemoval: Set<String>,
    cacheSizeText: String,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onRetry: (OfflineDownload) -> Unit,
    onDelete: (String) -> Unit
) {
    var filter by remember { mutableIntStateOf(0) }
    val visibleAll = downloads.filterNot { it.item.id in pendingRemoval }
    val counts = listOf(
        visibleAll.count { it.state in ACTIVE_STATES },
        visibleAll.count { it.state == DownloadTaskState.COMPLETED },
        visibleAll.count { it.state == DownloadTaskState.FAILED }
    )
    val visible = visibleAll.filter {
        when (filter) {
            0 -> it.state in ACTIVE_STATES
            1 -> it.state == DownloadTaskState.COMPLETED
            else -> it.state == DownloadTaskState.FAILED
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("下载管理", onSettings = onSettings, onBack = onBack)
        Row(Modifier.fillMaxWidth().padding(horizontal = ScreenPadding), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("下载中", "已完成", "失败").forEachIndexed { index, label ->
                FilterChip(selected = filter == index, onClick = { filter = index },
                    label = { Text("$label (${counts[index]})", fontSize = 12.sp) })
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (visible.isEmpty()) item {
                Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    RevealOnEnter {
                        Text("暂无任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(visible, key = { it.item.id }) { task ->
                DownloadRow(task,
                    onPause = { onPause(task.item.id) },
                    onResume = { onResume(task.item.id) },
                    onRetry = { onRetry(task) },
                    onDelete = { onDelete(task.item.id) })
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surface, shape = cardShape, tonalElevation = 2.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("缓存占用 $cacheSizeText", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LazyItemScope.DownloadRow(task: OfflineDownload, onPause: () -> Unit, onResume: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val motionOn = rememberMotionEnabled()
    val item = task.item
    // 进度连续化：800ms 轮询喂进来的是台阶值，900ms Linear 动画把它抹平成连续爬行。
    val animatedPercent by animateFloatAsState(
        targetValue = (task.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(motionDuration(motionOn, Motion.PROGRESS), easing = LinearEasing),
        label = "downloadProgress"
    )
    val title = OfflineLibrary.displayTitle(item.title, item.episodeName, item.episodeNumber, item.kind)
    val stateColor = when (task.state) {
        DownloadTaskState.FAILED -> palette.error
        DownloadTaskState.COMPLETED -> Ink.Success
        DownloadTaskState.DOWNLOADING -> palette.primary
        else -> palette.onSurfaceVariant
    }
    // animateItem：删除/完成时行收缩移出、下方补位，不瞬间消失。
    Surface(modifier = Modifier.animateItem(), color = palette.surface, shape = cardShape, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val detail = buildString {
                        append(task.state.label)
                        if (task.failure != OfflineFailureReason.NONE) append(" · ${task.failure.label}")
                        if (task.state == DownloadTaskState.DOWNLOADING && task.totalBytes > 0) {
                            append(" · ${OfflineDownloadRules.formatBytes(task.downloadedBytes)} / ${OfflineDownloadRules.formatBytes(task.totalBytes)}")
                        }
                    }
                    Text(detail, fontSize = 12.sp, color = stateColor)
                }
                val actions = task.state.allowedActions()
                if (OfflineAction.PAUSE in actions) {
                    IconButton(onClick = onPause) { Icon(Icons.Default.Pause, "暂停", tint = palette.primary) }
                }
                if (OfflineAction.RESUME in actions) {
                    IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, "继续", tint = palette.primary) }
                }
                if (OfflineAction.RETRY in actions) {
                    IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "重试", tint = palette.primary) }
                }
                if (OfflineAction.DELETE in actions) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.DeleteOutline, "删除任务", modifier = Modifier.size(18.dp),
                            tint = palette.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(9.dp))
            when (task.state) {
                DownloadTaskState.RESOLVING -> LinearProgressIndicator(
                    Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = palette.primary, trackColor = palette.surfaceVariant
                )
                DownloadTaskState.COMPLETED -> LinearProgressIndicator(
                    progress = { 1f }, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = Ink.Success, trackColor = palette.surfaceVariant,
                    gapSize = 0.dp, drawStopIndicator = {}
                )
                else -> LinearProgressIndicator(
                    progress = { animatedPercent },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                    color = if (task.state == DownloadTaskState.FAILED) palette.error else palette.primary,
                    trackColor = palette.surfaceVariant,
                    gapSize = 0.dp, drawStopIndicator = {}
                )
            }
        }
    }
}

// ==================== 设置（订阅源管理 + AI 智能解析） ====================

@Composable
fun SourcesScreen(
    state: SourcesUiState,
    allowMobileData: Boolean,
    onSetAllowMobileData: (Boolean) -> Unit,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onLongPressRow: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onToggleSelectAll: () -> Unit,
    onExitManage: () -> Unit,
    onRequestDeleteSelected: () -> Unit,
    onReprobe: (String) -> Unit,
    onSaveAi: (Boolean, String, String) -> Unit,
    onDismissProbe: () -> Unit
) {
    var howToAddExpanded by remember { mutableStateOf(true) }
    var moreSettingsExpanded by remember { mutableStateOf(false) }
    val motionOn = rememberMotionEnabled()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenHeader("设置", onBack = if (state.managing) onExitManage else onBack)
        if (state.managing) {
            Row(
                Modifier.padding(horizontal = ScreenPadding).padding(top = 2.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("已选 ${state.selection.count} 个", fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onToggleSelectAll, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)) {
                    Text(
                        if (state.selection.isAllSelected(state.rows.map { it.source.id })) "取消全选" else "全选",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                SecondaryPill("完成", onClick = onExitManage)
                PrimaryPill(
                    text = if (state.selection.count > 0) "删除选中 (${state.selection.count})" else "删除选中",
                    onClick = onRequestDeleteSelected,
                    enabled = state.selection.count > 0
                )
            }
            Text("长按源行也可以多选；删除前会再确认一次。",
                Modifier.padding(horizontal = ScreenPadding),
                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            // 键盘弹出时列表视口跟着缩，编辑框出现后主动滚进可见区，保存按钮不会被键盘压住。
            modifier = Modifier.imePadding()
        ) {
            // 添加按钮和说明卡都进列表：整页一起滚，编辑 AI 卡时上方内容能被完全滚走。
            if (!state.managing) {
                item { HowToAddCard(expanded = howToAddExpanded, onToggle = { howToAddExpanded = !howToAddExpanded }) }
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // 半屏宽、居中的紫色主按钮，放在说明卡下方：先看懂怎么加，再按按钮。
                        PrimaryPill("添加订阅源", Icons.Default.Add, onAdd, Modifier.fillMaxWidth(0.5f))
                    }
                }
            }
            if (state.probing != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在探测「${state.probing}」的搜索入口…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            if (state.rows.isEmpty() && state.probing == null) {
                item {
                    Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) {
                        RevealOnEnter {
                            Text("还没有订阅源，点上面「添加订阅源」开始", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (state.rows.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.Bottom) {
                        Text("我的订阅源", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.weight(1f))
                        Text("共 ${state.rows.size} 个站点", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            items(state.rows, key = { it.source.id }) { row ->
                SourceRow(row,
                    managing = state.managing,
                    selected = state.selection.isSelected(row.source.id),
                    onToggle = { onToggle(row.source.id, it) },
                    onLongPress = { onLongPressRow(row.source.id) },
                    onClickWhenManaging = { onToggleSelect(row.source.id) },
                    onReprobe = { onReprobe(row.source.id) })
            }
            // 订阅源区到此结束：结束线 + 「更多设置」折叠入口，设置类卡片默认收起，点开才见。
            item {
                HorizontalDivider(Modifier.padding(top = 8.dp),
                    color = MaterialTheme.colorScheme.outlineVariant)
            }
            item {
                val arrowRotation by animateFloatAsState(
                    if (moreSettingsExpanded) 180f else 0f,
                    motionTween(motionOn, Motion.ICON_STATE), label = "moreSettingsArrow"
                )
                Row(
                    Modifier.fillMaxWidth().clip(cardShape)
                        .clickable { moreSettingsExpanded = !moreSettingsExpanded }
                        .padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("更多设置", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                        modifier = Modifier.weight(1f))
                    Text(if (moreSettingsExpanded) "收起" else "展开", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Icon(Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.graphicsLayer { rotationZ = arrowRotation })
                }
            }
            item {
                // 展开/收起 240ms 高度动画，下方内容随之平滑移动，不跳位。
                AnimatedVisibility(
                    visible = moreSettingsExpanded,
                    enter = expandVertically(motionTween(motionOn, Motion.EXPAND)) +
                        fadeIn(tween(motionDuration(motionOn, Motion.EXPAND))),
                    exit = shrinkVertically(motionTween(motionOn, Motion.EXPAND, Motion.EmphasizedAccelerate)) +
                        fadeOut(tween(motionDuration(motionOn, Motion.EXPAND))),
                    label = "moreSettings"
                ) {
                    // 设置卡片区从下载偏好开始，AI 智能解析在后，后续更多设置卡片依次排在这里。
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(color = MaterialTheme.colorScheme.surface, shape = cardShape, tonalElevation = 2.dp) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("允许移动数据下载", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text("开启后没有 Wi-Fi 也会继续下载，可能产生流量费用。",
                                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(checked = allowMobileData, onCheckedChange = onSetAllowMobileData,
                                    modifier = Modifier.height(28.dp))
                            }
                        }
                        AiSettingsCard(state.ai, onSaveAi, listState)
                    }
                }
            }
            item {
                // 页脚：版本号取自安装包 versionName，版权署名固定；GitHub 图标跳开源仓库。
                val context = LocalContext.current
                val version = remember(context) {
                    runCatching {
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull().orEmpty()
                }
                Column(
                    Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(if (version.isNotEmpty()) "AI Player v$version" else "AI Player",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                // 仓库地址集中在这里；开源后改这一处即可。
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW,
                                            Uri.parse("https://github.com/afeicn/aiplayer"))
                                    )
                                }
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(painterResource(R.drawable.ic_github), "开源仓库",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("© 2026 afeicn · 供AI爱好者研究交流学习使用",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    // 探测会话流：像 agent 一样一步步直播探测过程，结束给「已启用 / 不可用」结论。
    state.probeSession?.let { session -> ProbeFlowSheet(session, onDismiss = onDismissProbe) }
}

/** 探测过程底部会话流面板：事件逐条出现，进行中转圈，通过打勾，不通过打叉。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProbeFlowSheet(session: ProbeSession, onDismiss: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    LaunchedEffect(session.events.size) {
        if (session.events.isNotEmpty()) listState.animateScrollToItem(session.events.size - 1)
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = palette.surface,
        sheetMaxWidth = 620.dp, dragHandle = { BottomSheetDefaults.DragHandle() }) {
        // 底部留白按 Material 3 规范：内容区 16dp 边距 + 手势条 inset（navigationBarsPadding）。
        // 事件列表限高，保证整栏内容不超面板最大高度——超了会把底部边距裁掉，按钮就贴底了。
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("探测「${session.sourceName}」", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f))
                if (!session.done) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(9.dp),
                modifier = Modifier.heightIn(max = 220.dp)) {
                items(session.events) { event -> ProbeEventRow(event) }
            }
            if (session.done) {
                Surface(
                    color = if (session.success) palette.primaryContainer else palette.errorContainer,
                    shape = cardShape, modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            if (session.success) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                            null, tint = if (session.success) palette.primary else palette.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(session.conclusion, fontSize = 13.sp, lineHeight = 20.sp,
                            color = if (session.success) palette.onPrimaryContainer else palette.onErrorContainer)
                    }
                }
                PrimaryPill(if (session.success) "完成" else "知道了", onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ProbeEventRow(event: ProbeEvent) {
    val palette = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (event.status) {
            ProbeStepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
            ProbeStepStatus.OK -> Icon(Icons.Default.CheckCircle, null, tint = palette.primary,
                modifier = Modifier.size(17.dp))
            ProbeStepStatus.FAIL -> Icon(Icons.Default.Cancel, null,
                tint = palette.onSurfaceVariant.copy(alpha = .7f), modifier = Modifier.size(17.dp))
        }
        Text(event.label, fontSize = 13.sp, lineHeight = 19.sp,
            color = if (event.status == ProbeStepStatus.FAIL) palette.onSurfaceVariant else palette.onSurface)
    }
}

/** 「如何添加站点」可折叠说明卡：正文 240ms 高度动画，箭头 180° 旋转。 */
@Composable
private fun HowToAddCard(expanded: Boolean, onToggle: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val motionOn = rememberMotionEnabled()
    val arrowRotation by animateFloatAsState(
        if (expanded) 180f else 0f, motionTween(motionOn, Motion.ICON_STATE), label = "howToAddArrow"
    )
    Surface(color = palette.primaryContainer.copy(alpha = 0.35f), shape = cardShape,
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.clip(cardShape).clickable(onClick = onToggle).padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(color = palette.primaryContainer, shape = CircleShape) {
                    Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Lightbulb, null, tint = palette.primary, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text("如何添加订阅源", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, if (expanded) "收起" else "展开",
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = arrowRotation },
                    tint = palette.onSurfaceVariant)
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(motionTween(motionOn, Motion.EXPAND)) +
                    fadeIn(tween(motionDuration(motionOn, Motion.EXPAND))),
                exit = shrinkVertically(motionTween(motionOn, Motion.EXPAND, Motion.EmphasizedAccelerate)) +
                    fadeOut(tween(motionDuration(motionOn, Motion.EXPAND))),
                label = "howToAddBody"
            ) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    Text("• 粘贴站点首页、列表页或播放页链接即可自动识别，按站点根保存。",
                        fontSize = 12.sp, lineHeight = 19.sp, color = palette.onSurfaceVariant)
                    Text("• 添加时自动检测连通性并探测搜索入口，开了 AI 还会顺手认出站点名称（认不出退回页面标题或域名）。",
                        fontSize = 12.sp, lineHeight = 19.sp, color = palette.onSurfaceVariant)
                    Text("• 探测没通过也会存为未启用源，之后点「重新探测」再试即可。",
                        fontSize = 12.sp, lineHeight = 19.sp, color = palette.onSurfaceVariant)
                    Text("• 长按源行进入管理模式，可多选删除。",
                        fontSize = 12.sp, lineHeight = 19.sp, color = palette.onSurfaceVariant)
                }
            }
        }
    }
}

/**
 * AI 智能解析设置卡（暂时支持 DeepSeek），按设计图不折叠：
 * 标题行带开关（即时生效）；API Key 与模型在子卡里各占一行，点行就地编辑，收起键盘即保存。
 *
 * 关掉或 Key 留空时，探测和搜索都不会调模型，主路行为不变；
 * 开启后模型也只提候选——搜索入口要实测通过才会启用源。
 */
@Composable
private fun AiSettingsCard(ai: AiSettingsUi, onSave: (Boolean, String, String) -> Unit,
                           listState: LazyListState) {
    val palette = MaterialTheme.colorScheme
    var enabled by remember { mutableStateOf(ai.enabled) }
    var key by remember { mutableStateOf(ai.apiKey) }
    var model by remember { mutableStateOf(ai.model) }
    var editingKey by remember { mutableStateOf(false) }
    var editingModel by remember { mutableStateOf(false) }
    // 编辑框改的是草稿，点「保存」才写回 key/model 并落库；「取消」直接丢弃草稿。
    var draftKey by remember { mutableStateOf("") }
    var draftModel by remember { mutableStateOf("") }
    // 就地编辑框出现即抢焦点，等键盘动画落定后把编辑框滚进可见区。
    val keyFocus = remember { FocusRequester() }
    val modelFocus = remember { FocusRequester() }
    val keyReveal = remember { BringIntoViewRequester() }
    val modelReveal = remember { BringIntoViewRequester() }
    // 滚到按钮行为止：按钮在编辑框下方，按钮露出则编辑框必然也在键盘之上。
    val buttonsReveal = remember { BringIntoViewRequester() }
    // 进入编辑态就把 AI 卡（列表最后一项）整卡顶到列表视口顶部，
    // 否则只滚到按钮行，卡片上半截会被上方说明卡裁掉，看着像被遮挡。
    suspend fun liftCard() {
        delay(300)
        listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        buttonsReveal.bringIntoView()
    }
    LaunchedEffect(editingKey) {
        if (editingKey) { keyFocus.requestFocus(); liftCard() }
    }
    LaunchedEffect(editingModel) {
        if (editingModel) { modelFocus.requestFocus(); liftCard() }
    }
    // 开关切换即时落库；Key/模型走草稿 + 保存按钮。
    fun persist(nextEnabled: Boolean, nextKey: String, nextModel: String) {
        enabled = nextEnabled; key = nextKey; model = nextModel
        onSave(nextEnabled, nextKey, nextModel)
    }
    fun saveEdits() {
        persist(enabled, if (editingKey) draftKey else key, if (editingModel) draftModel else model)
        editingKey = false; editingModel = false
    }

    Surface(color = palette.surface, shape = cardShape, tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 2.dp)) {
                Surface(color = palette.primaryContainer, shape = CircleShape) {
                    Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.SmartToy, null, tint = palette.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI 智能解析", fontWeight = FontWeight.SemiBold)
                    Text("用于搜索入口异常时辅助解析 · 暂时支持 DeepSeek",
                        fontSize = 11.sp, color = palette.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { persist(it, key, model) },
                    modifier = Modifier.height(28.dp))
            }
            // 子卡：API Key 与模型各一行，点行就地编辑；显示时只给掩码，不露明文。
            Surface(color = palette.surfaceVariant.copy(alpha = .45f), shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 10.dp)) {
                if (editingKey) {
                    OutlinedTextField(value = draftKey, onValueChange = { draftKey = it }, singleLine = true,
                        label = { Text("API Key") }, placeholder = { Text("sk-…") },
                        visualTransformation = PasswordVisualTransformation(),
                        trailingIcon = {
                            if (draftKey.isNotEmpty()) IconButton(onClick = { draftKey = "" },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "清空 API Key", modifier = Modifier.size(18.dp))
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        keyboardActions = KeyboardActions(onDone = { saveEdits() }),
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().focusRequester(keyFocus).bringIntoViewRequester(keyReveal))
                } else {
                    SettingValueRow(Icons.Default.VpnKey, "API Key",
                        if (key.isBlank()) "未填写" else "••••••••••••••",
                        valueIsPlaceholder = key.isBlank(),
                        onClick = { draftKey = key; editingKey = true })
                }
                HorizontalDivider(color = palette.outlineVariant)
                if (editingModel) {
                    OutlinedTextField(value = draftModel, onValueChange = { draftModel = it }, singleLine = true,
                        label = { Text("模型") },
                        trailingIcon = {
                            if (draftModel.isNotEmpty()) IconButton(onClick = { draftModel = "" },
                                modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "清空模型", modifier = Modifier.size(18.dp))
                            }
                        },
                        keyboardActions = KeyboardActions(onDone = { saveEdits() }),
                        shape = CircleShape,
                        modifier = Modifier.fillMaxWidth().focusRequester(modelFocus).bringIntoViewRequester(modelReveal))
                } else {
                    SettingValueRow(Icons.Default.ViewInAr, "模型", model.ifBlank { "未填写" },
                        valueIsPlaceholder = model.isBlank(),
                        onClick = { draftModel = model; editingModel = true })
                }
                }
            }
            // 编辑态出现时才显示保存/取消，非编辑态卡片保持干净。
            if (editingKey || editingModel) {
                Row(Modifier.fillMaxWidth().bringIntoViewRequester(buttonsReveal),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryPill("取消", onClick = { editingKey = false; editingModel = false },
                        modifier = Modifier.weight(1f))
                    PrimaryPill("保存", onClick = { saveEdits() }, modifier = Modifier.weight(1f))
                }
            }
            Text("Key 明文存本机，只在解析触发时发往 api.deepseek.com。",
                fontSize = 11.sp, lineHeight = 16.sp, color = palette.onSurfaceVariant)
        }
    }
}

/** 设置卡里的「图标 + 名称 + 值 + 箭头」行，点击就地编辑。 */
@Composable
private fun SettingValueRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String,
                            valueIsPlaceholder: Boolean, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(cardShape).clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = palette.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (valueIsPlaceholder) palette.onSurfaceVariant else palette.onSurface)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Default.KeyboardArrowRight, null, modifier = Modifier.size(18.dp),
            tint = palette.onSurfaceVariant)
    }
}

@Composable
private fun LazyItemScope.SourceRow(
    row: SourceRowUi,
    managing: Boolean,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    onLongPress: () -> Unit,
    onClickWhenManaging: () -> Unit,
    onReprobe: () -> Unit
) {
    val palette = MaterialTheme.colorScheme
    val motionOn = rememberMotionEnabled()
    val healthyNormal = row.healthy && row.healthText == "已启用 · 运行正常"
    val healthColor by animateColorAsState(
        when {
            healthyNormal -> Ink.Success
            row.source.enabled && !row.healthy -> palette.error
            else -> palette.onSurfaceVariant
        },
        motionTween(motionOn, Motion.ICON_STATE), label = "healthColor"
    )
    // animateItem：删除源时卡片收缩移出、下方补位。
    Surface(modifier = Modifier.animateItem(), color = palette.surface, shape = cardShape, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth()
                .clip(cardShape)
                .combinedClickable(
                    onClick = { if (managing) onClickWhenManaging() },
                    onLongClick = onLongPress
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 管理模式进出：行首复选框 240ms 横向展开/收起，卡片内容随之平滑移动。
            AnimatedVisibility(
                visible = managing,
                enter = expandHorizontally(motionTween(motionOn, Motion.EXPAND)) +
                    fadeIn(tween(motionDuration(motionOn, Motion.EXPAND))),
                exit = shrinkHorizontally(motionTween(motionOn, Motion.EXPAND, Motion.EmphasizedAccelerate)) +
                    fadeOut(tween(motionDuration(motionOn, Motion.EXPAND))),
                label = "manageCheckbox"
            ) {
                Row {
                    Checkbox(checked = selected, onCheckedChange = { onClickWhenManaging() })
                    Spacer(Modifier.width(6.dp))
                }
            }
            Surface(color = palette.primaryContainer, shape = RoundedCornerShape(14.dp)) {
                Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Language, null, tint = palette.primary)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(row.source.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(row.source.base, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = 11.sp, color = palette.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(healthColor))
                    Spacer(Modifier.width(6.dp))
                    // 探测结果变化时状态文字原位置淡入切换，列表不整体进 loading。
                    Crossfade(targetState = row.healthText,
                        animationSpec = tween(motionDuration(motionOn, Motion.ICON_STATE)),
                        label = "healthText") { healthText ->
                        Text(healthText, color = healthColor, fontSize = 11.sp)
                    }
                }
            }
            if (!managing) {
                // 每行都给重新探测的机会：没搜索规则的补探测，冷却中的探测通过即恢复。
                Spacer(Modifier.width(8.dp))
                MiniPill("重新探测", Icons.Default.Refresh, onReprobe)
                Spacer(Modifier.width(6.dp))
                Switch(checked = row.source.enabled, onCheckedChange = onToggle, modifier = Modifier.height(28.dp))
            }
        }
    }
}

/** 源行右侧的小胶囊操作钮（重新探测）。 */
@Composable
private fun MiniPill(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    Surface(color = palette.primaryContainer.copy(alpha = 0.5f), shape = CircleShape,
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = palette.primary, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            Text(text, fontSize = 11.sp, color = palette.primary, fontWeight = FontWeight.Medium)
        }
    }
}
