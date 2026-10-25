package com.afeicn.aiplayer.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil3.compose.SubcomposeAsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import com.afeicn.aiplayer.AggregatedVod
import com.afeicn.aiplayer.DownloadedSeries
import com.afeicn.aiplayer.R

val cardShape = RoundedCornerShape(18.dp)
val heroShape = RoundedCornerShape(26.dp)

/**
 * 主题化 Snackbar：替代 Material 默认深色条，与底部胶囊导航同一套语言——
 * 白色悬浮胶囊、细描边、紫点标记、紫色动作按钮。所有 Snackbar 统一走这里。
 */
@Composable
fun ThemedSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.padding(horizontal = 24.dp),
        shape = RoundedCornerShape(100.dp),
        color = palette.surface.copy(alpha = .97f),
        contentColor = palette.onSurface,
        border = BorderStroke(.5.dp, palette.outline.copy(alpha = .4f)),
        shadowElevation = 10.dp,
        tonalElevation = 2.dp
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 10.dp, bottom = 10.dp,
                end = if (data.visuals.actionLabel != null) 4.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(Ink.Violet))
            Text(
                data.visuals.message,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(
                    onClick = { data.performAction() },
                    colors = ButtonDefaults.textButtonColors(contentColor = palette.primary),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun SoftPage(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // 纯色底：此前底部渐变 + 漂移光晕在浅紫底上叠出灰紫色雾块，白色卡片像蒙了层脏底，已整体移除。
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        content()
    }
}

/** 影视图床常直接拒绝非浏览器 UA 的请求（浏览器能开、App 打不开），图片请求统一伪装成浏览器。 */
private const val IMAGE_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/**
 * 封面图：URL 有效走 Coil 加载（搜索结果 vod_pic / 离线条目 cover），
 * 为空或加载失败退回渐变占位块，绝不留空洞。
 */
@Composable
fun CoverImage(url: String, description: String, modifier: Modifier = Modifier) {
    if (url.isBlank()) {
        CoverPlaceholder(modifier)
    } else {
        val context = androidx.compose.ui.platform.LocalContext.current
        val request = remember(url) {
            coil3.request.ImageRequest.Builder(context)
                .data(url)
                .httpHeaders(NetworkHeaders.Builder().add("User-Agent", IMAGE_UA).build())
                // 解码尺寸不写死：Coil 按组件实际显示约束自动降采样，
                // 小卡片省流量、详情页大海报保清晰，两头都照顾到。
                .build()
        }
        SubcomposeAsyncImage(
            model = request,
            contentDescription = description,
            contentScale = ContentScale.Crop,
            modifier = modifier,
            // 图床挂了/超时不再留白，退回占位块。
            error = { CoverPlaceholder(Modifier.fillMaxSize()) }
        )
    }
}

@Composable
private fun CoverPlaceholder(modifier: Modifier = Modifier) {
    val palette = MaterialTheme.colorScheme
    Box(
        modifier.fillMaxWidth()
            .background(Brush.linearGradient(listOf(palette.surfaceVariant, palette.primaryContainer.copy(alpha = .55f)))),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.Movie, null,
            tint = palette.onSurfaceVariant.copy(alpha = .6f),
            modifier = Modifier.size(34.dp)
        )
    }
}

@Composable
fun ToolbarButton(image: ImageVector, description: String, onClick: () -> Unit, count: Int = 0) {
    Box {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.surface,
            shape = CircleShape,
            shadowElevation = 2.dp,
            modifier = Modifier.size(38.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(image, contentDescription = description, modifier = Modifier.size(19.dp))
            }
        }
        if (count > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).size(16.dp).background(Ink.Violet, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // 数字要在圆圈正中：行高贴字号 + 去字体内边距，否则基线留白让数字看着偏上。
                Text(count.toString(),
                    style = TextStyle(color = Color.White, fontSize = 9.sp, lineHeight = 9.sp,
                        fontWeight = FontWeight.Bold,
                        platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
fun ScreenHeader(
    title: String,
    onSearch: (() -> Unit)? = null,
    onDownloads: (() -> Unit)? = null,
    onSources: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    downloadCount: Int = 0,
    logoMark: Boolean = false
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
        }
        // 首页标题带品牌标记：logo 文字部分深色主题下看不清，只放紫色三角标记。
        if (logoMark) {
            Image(painterResource(R.drawable.app_mark), null,
                modifier = Modifier.height(26.dp))
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-.5).sp),
            maxLines = 1
        )
        if (onSearch != null) ToolbarButton(Icons.Default.Search, "搜索", onSearch)
        if (onDownloads != null) ToolbarButton(Icons.Default.FileDownload, "下载管理", onDownloads, downloadCount)
        // 设置入口全局同一个图标：首页/发现页与下载管理页都跳同一个设置页。
        if (onSources != null) ToolbarButton(Icons.Default.Settings, "设置", onSources)
        if (onSettings != null) ToolbarButton(Icons.Default.Settings, "设置", onSettings)
    }
}

@Composable
fun CapsuleNavigation(selected: String, onHome: () -> Unit, onDiscover: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = .98f),
        shadowElevation = 14.dp,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
            .height(64.dp)
    ) {
        Row(
            Modifier.fillMaxSize().padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            NavItem("首页", Icons.Default.Home, selected == "home", onHome, Modifier.weight(1f))
            NavItem("发现", Icons.Default.Explore, selected == "discover", onDiscover, Modifier.weight(1f))
        }
    }
}

@Composable
private fun NavItem(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    // 选中态不跳变：两个等宽槽位各自 220ms 变色，背景块不跨槽平移（平移易抖，变色视觉等效）。
    val motionOn = rememberMotionEnabled()
    val palette = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) palette.primaryContainer else Color.Transparent,
        motionTween(motionOn, Motion.TAB_SWITCH), label = "navContainer"
    )
    val accent by animateColorAsState(
        if (selected) palette.primary else palette.onSurfaceVariant,
        motionTween(motionOn, Motion.TAB_SWITCH), label = "navAccent"
    )
    Row(
        modifier = modifier.fillMaxHeight()
            .clip(RoundedCornerShape(100.dp))
            .background(container)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp, color = accent)
    }
}

/**
 * 统一对话框：保持 M3 居中样式（简单表单居中更合理），补 220ms 淡入 + 0.92→1 缩放进场。
 * 添加订阅源、删除源确认都走它，替代裸 AlertDialog。
 */
@Composable
fun SoftDialog(
    onDismissRequest: () -> Unit,
    title: String,
    text: @Composable ColumnScope.() -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean = true,
    dismissText: String? = null
) {
    val motionOn = rememberMotionEnabled()
    Dialog(onDismissRequest = onDismissRequest) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { shown = true }
        val scale by animateFloatAsState(if (shown) 1f else 0.92f,
            motionTween(motionOn, Motion.DIALOG), label = "dialogScale")
        val alpha by animateFloatAsState(if (shown) 1f else 0f,
            tween(motionDuration(motionOn, Motion.DIALOG)), label = "dialogAlpha")
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                text()
                Row(Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (dismissText != null) {
                        TextButton(onClick = onDismissRequest) { Text(dismissText) }
                    }
                    TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                        Text(confirmText, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

/** 首页大卡：正在播放（或兜底的最近下载）的一部作品；点主体进详情，点圆钮直接续播。 */
@Composable
fun HeroCard(label: String, title: String, subtitle: String, cover: String,
             onDetail: () -> Unit, onPlay: () -> Unit) {
    // 播放钮的呼吸光晕：2.6 秒一呼一吸，暗示「可以点」。
    val pulseTransition = rememberInfiniteTransition(label = "fab")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600), RepeatMode.Reverse),
        label = "breathe"
    )
    Box(
        modifier = Modifier.fillMaxWidth().height(350.dp)
            .clip(heroShape).clickable(onClick = onDetail)
    ) {
        // 共享元素两端各自声明形状：卡片端 26dp 圆角、详情页直角，转场中连续插值。
        CoverImage(cover, "${title}海报",
            modifier = Modifier.fillMaxSize().posterSharedElement(posterKey(title)).clip(heroShape))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xD90C0C20)))))
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 20.dp, bottom = 22.dp, end = 88.dp)
        ) {
            Text(label, color = Color.White.copy(alpha = .86f), fontSize = 12.sp)
            Spacer(Modifier.height(5.dp))
            Text(title, color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Color.White.copy(alpha = .95f), fontSize = 13.sp)
        }
        // 呼吸光晕：光圈从按钮边缘向外扩散并淡出，与按钮同心（中心同为距边 46/52dp）。
        Box(
            Modifier.align(Alignment.BottomEnd).padding(end = 4.dp, bottom = 10.dp).size(84.dp)
                .scale(0.72f + 0.28f * pulse)
                .background(
                    Brush.radialGradient(
                        listOf(Ink.Violet.copy(alpha = 0.4f * (1f - pulse)), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 24.dp).size(56.dp)
                .shadow(12.dp, CircleShape),
            shape = CircleShape,
            color = Ink.Violet,
            onClick = onPlay
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, contentDescription = "直接续播", tint = Color.White, modifier = Modifier.size(31.dp))
            }
        }
    }
}

/** 首页横滑小卡：与「为你推荐」同款结构缩小一档——海报在左、标题副题在右。
 * sharedKey 传 null 表示这张与首页大卡是同一部作品，共享元素让给大卡，避免同页重复 key。 */
@Composable
fun MiniSeriesCard(title: String, cover: String, subtitle: String, onClick: () -> Unit,
                   modifier: Modifier = Modifier,
                   sharedKey: String? = posterKey(title)) {
    val palette = MaterialTheme.colorScheme
    Surface(
        onClick = onClick, modifier = modifier.width(186.dp), shape = cardShape,
        color = palette.surface, shadowElevation = 3.dp,
        border = BorderStroke(.5.dp, palette.outline.copy(alpha = .14f))
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val coverModifier = if (sharedKey != null)
                Modifier.width(56.dp).height(74.dp).posterSharedElement(sharedKey)
            else Modifier.width(56.dp).height(74.dp)
            CoverImage(cover, "${title}封面", coverModifier)
            Column(Modifier.weight(1f).padding(start = 10.dp, end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = palette.onSurfaceVariant, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 搜索结果 / 推荐行：一张同名聚合卡。海报贴住卡片上、左、下三条边（出血式），文字在右。 */
@Composable
fun VodRow(vod: AggregatedVod, onClick: () -> Unit) {
    val palette = MaterialTheme.colorScheme
    val subtitle = listOf(vod.year, vod.area, vod.remarks).filter { it.isNotBlank() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().clip(cardShape)
            .background(palette.surface)
            .clickable(onClick = onClick),
        // 海报是最高元素、撑起卡片；文字列与「N 个源」胶囊都垂直居中，不再顶在上沿。
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 出血海报：64x84 小尺寸，卡片高度由它撑起、不被顶高；
        // Crop 只显示海报上段，贴住上、左、下三条边不留空隙，圆角随卡片裁切。
        CoverImage(vod.pic, "${vod.name}海报",
            Modifier.width(64.dp).height(84.dp).posterSharedElement(posterKey(vod.name)))
        Column(
            Modifier.weight(1f).padding(start = 12.dp, end = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // 标题单行：保证文字列不会高过海报，海报始终贴住上下边。
            Text(vod.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = palette.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
            }
        }
        Box(Modifier.padding(end = 12.dp), contentAlignment = Alignment.Center) {
            Surface(color = palette.primaryContainer, shape = CircleShape) {
                Text("${vod.sourceCount} 个源", color = palette.primary, fontSize = 11.sp,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
fun PrimaryPill(text: String, icon: ImageVector? = null, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    // 呼吸光晕收在胶囊边界内：2.8 秒一呼一吸，底色微微变亮再暗回去，
    // 顶部再压一层 clip 到胶囊形的高光，光不越出按钮之外；禁用态不呼吸。
    val pulseTransition = rememberInfiniteTransition(label = "pillGlow")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2800), RepeatMode.Reverse),
        label = "breathe"
    )
    val primary = MaterialTheme.colorScheme.primary
    // 按钮与高光层包进同一个 Box：否则高光层会变成外层 Row 的第二个子节点，
    // 它的 fillMaxWidth 把 weight 平分出来的按钮宽度抢光、按钮塌成 0 宽（详情页双胶囊实测踩坑）。
    Box(modifier) {
        Button(
            onClick = onClick, enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(44.dp), shape = CircleShape,
            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) lerp(primary, Color.White, 0.16f * pulse) else primary
            )
        ) {
            if (icon != null) {
                Icon(icon, null, modifier = Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
        if (enabled) {
            // 高光层 matchParentSize 贴着按钮尺寸，只提亮胶囊内部上缘，不参与测量、不挡点击。
            Box(
                Modifier.matchParentSize().clip(CircleShape)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.White.copy(alpha = 0.10f + 0.16f * pulse),
                                Color.Transparent,
                                Color.White.copy(alpha = 0.04f + 0.08f * pulse)
                            )
                        )
                    )
            )
        }
    }
}

@Composable
fun SecondaryPill(text: String, icon: ImageVector? = null, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick, modifier = modifier.height(44.dp), shape = CircleShape,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp)
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(17.dp)); Spacer(Modifier.width(5.dp)) }
        Text(text, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    }
}
