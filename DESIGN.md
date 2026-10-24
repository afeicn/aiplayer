# AI Player v1 整体设计

> 从 104vplayer（观己 Player）中抽取「在线搜索 + 离线播放」两条链路，做成独立的新 App。
> 原则：第一版只做「搜到 → 下下来 → 播得了」，一切与这条链路无关的功能全部舍弃。

## 一、定位

一个轻量的安卓影音 App：

- 配置若干影视/音乐站点（订阅源）；
- 跨站搜索，选中作品后确认集数范围，批量下载到本地；
- 离线库统一管理已下载内容，随时播放（含续播、后台媒体控制）。

不做：本地媒体库扫描、歌词、封面、歌单/收藏、均衡器、语音助手、AI 整理、投屏、云同步。

## 二、核心流程（v1 唯一主链路）

```
订阅源管理（添加站点 → 自动探测搜索规则）
      ↓
在线搜索（跨源并行、同名聚合、搜索历史）
      ↓
选作品 → 抓详情页集数 → 确认范围（全部 / 3,5 / 2-8 / 前10）
      ↓
批量下载（HLS 缓存 / 音频直链，2 并发、失败重试、移动数据闸门）
      ↓
离线库（按作品分组、共 N 集已下 M 集、继续下载）
      ↓
播放（视频/音频、断点续播、通知栏媒体控制）
```

附带一条低成本支线：搜索结果里「有可播线路」的条目可直接在线播放（复用下载链路里本来就有的 `WebLinkResolver` 解析，不另建旁路）。

## 三、功能取舍

### 保留（全部来自原项目已验证的实现）

| 模块 | 内容 | 来源 |
| --- | --- | --- |
| 订阅源管理 | 添加站点（首页表单自动探测）、帝国CMS/MacCMS/WordPress 三类解析、手填 `{kw}` 模板、启停/删除、失败源 30min/24h 健康冷却 | `OnlineVideo` `SearchRule` `SearchRuleDetector` `SourceProber` `SourceHealthPolicy` |
| 跨源搜索 | 并行搜所有启用源、同名合并标「N 个源」、区分「没结果」与「网络失败」 | `CrossSourceSearchEngine` `VodAggregator` |
| 搜索历史 | 最多 10 条、去重置顶、单删可撤销、批量清理 | `SearchHistory` |
| 集数确认 | 详情页解析集数、范围语法（全部/逗号/区间/前N）、选集面板 | `EpisodeListParser` `EpisodeRangeParser` `SubscriptionPipeline` |
| 批量下载 | 2 并发 + 集间间隔、失败重试 2 次后汇总、前台下载服务（进度通知、杀进程后恢复）、WiFi/移动数据闸门 | `EpisodeBatchDownloader` `OfflineDownloadManager` `OfflineDownloadService` `DownloadNetworkGate` |
| 音频源分流 | AUDIO 类型源解析 .mp3/.m4a/.flac 直链，走普通文件下载不走 HLS | `AudioDirectLink` `ProgressiveDownloader` |
| 继续下载 | 已下几集后补下剩余集；老数据无 detailUrl 时自动回源定位 | `ContinueDownload` `ContinueSourceLocator` |
| 离线库 | 按剧聚合卡片、HTML 残渣标题清洗、判重、存储占用 | `OfflineLibrary` `MediaTitle` `OfflineStore` |
| 播放 | ExoPlayer（视频+音频）、断点续播、后台/通知栏媒体控制、倍速 | `PlaybackService`（需简化重写） |
| 在线直播（支线） | 有线路的结果直接播 m3u8/直链 | `WebLinkResolver` `HiddenWebViewResolver` `M3uParser` |

### 舍弃（明确不做）

- 本地文件夹扫描、音乐库、标签读取、CUE/APE/FLAC 无损（`libraries/ape`、`libraries/ffmpeg` 两个自定义库整个不带）
- 歌词（本地 LRC、AI 歌词）、封面（AI 封面、iTunes 匹配）、AI 歌单、智能队列
- 收藏、歌单、播放队列管理、睡眠定时、A-B 循环、均衡器、淡入淡出
- 小观语音助手（Vosk + JNA 依赖移除）
- DeepSeek 集成（资料补充、剧集分析）
- 剧集片头片尾跳过、进度手势、投屏、网络地址手动添加（`NetworkAddressDialog`，v1 不做）
- 订阅源 JSON 导入/导出（v1 不做，需要时再加）

## 四、技术方案

### 新项目骨架

- 位置：仓库根目录（单模块 Gradle 工程）
- 单模块 Gradle 工程，`applicationId = com.afeicn.aiplayer`，minSdk 26 / targetSdk 36
- 依赖（全部沿用原项目已验证版本）：
  - Compose + Material3（compose-bom 2026.04.01）
  - Media3 1.11.1：`exoplayer`、`exoplayer-hls`、`datasource`、`session`、`ui`、`common`
  - `androidx.work`（下载调度兜底）、`core-ktx`、`lifecycle-viewmodel`
- 移除的依赖：vosk-android、jna、opencc4j、documentfile、media3-database、`:libraries:ape`、`:libraries:ffmpeg`
- 网络：不引 OkHttp，沿用原项目的 `SearchTransport` 抽象（HttpSearchTransport + HttpURLConnection），单测注入假 transport，纯 JVM 不联网。

### 包结构（原项目是 98 文件平铺单包，新项目分层）

```
com.afeicn.aiplayer
├── source/     订阅源：OnlineVideoSource、SearchRule、OnlineSourceStore、
│               SourceProber、SearchRuleDetector、SourceHealthPolicy
├── search/     搜索：OnlineSearchEngine、CrossSourceSearchEngine、VodAggregator、
│               SearchResultsParser、MediaTitle、SearchHistory
├── episode/    选集：EpisodeListParser、EpisodeRangeParser、EpisodeNaming、
│               EpisodeSelection、EpisodeSession、SubscriptionPipeline、
│               EpisodeBatchDownloader、AudioDirectLink
├── download/   下载：OfflineDownloadManager、OfflineDownloadService、OfflineStore、
│               OfflineDownloadRules、OfflineLibrary、ContinueDownload、
│               ContinueSourceLocator、DownloadNetworkGate、NetworkStatusMonitor
├── resolve/    取址：WebLinkResolver、HiddenWebViewResolver、M3uParser、
│               NetworkStream、NetworkMediaSourceFactory
├── player/     播放：PlaybackService（简化重写）、PlayerViewModel、
│               PlaybackProgressRules、PlaybackErrorMessages
└── ui/         界面：离线库(首页)、搜索页、下载管理页、源管理页、播放页、
                选集面板、搜索历史区
```

### 数据与存储（沿用原方案，不引入 Room）

| 存储 | 内容 | 机制 |
| --- | --- | --- |
| Media3 `DefaultDownloadIndex` | 下载任务 url/进度/流（SQLite，自动恢复） | 原样 |
| `OfflineStore`（SharedPreferences + JSON） | 离线条目展示元数据（片名、headers、集数、detailUrl、kind） | 原样 |
| `OnlineSourceStore` | 订阅源及搜索规则、健康状态 | 原样 |
| `SearchHistoryStore` | 搜索历史 10 条 | 原样 |
| 播放进度 | 按条目 id 记录续播位置 | 简化重写 |

下载文件位置沿用 Media3 缓存目录（App 私有外部存储），v1 不做自定义目录选择。

### UI（4 页 + 播放器）

1. **离线库（首页）**：按作品分组卡片（封面占位、共 N 集已下 M 集）、点集直接播；顶部入口：搜索、下载中(N)、源管理。
2. **在线搜索页**：输入框 + 历史标签 → 跨源结果列表（标「N 个源」）→ 点开进选集面板（下载为主，有线路时提供「直接播放」）。
3. **下载管理页**：进行中队列（进度、暂停/继续、删除）、失败汇总重试。
4. **源管理页**：源列表（启停、健康状态、删除）、添加站点（输入域名 → 自动探测 → 失败引导手填模板）。
5. **播放页**：全屏视频 / 简易音频；进度条、倍速、上一集/下一集（按剧内集序）。

原 `MainActivity.kt`（6667 行）不迁移，重写为约 300 行的导航壳。

## 五、迁移清单

### 直接带走（约 30 个主源文件，改包名 + 删本地库引用）

`OnlineVideo` `SearchRule` `SearchRuleDetector` `CrossSourceSearch`（含 SourceProber/健康策略）`OnlineSearchEngine` `SearchResultsParser` `MediaTitle` `SearchHistory` `OnlineSearchEntry` `EpisodeList` `EpisodeNaming` `EpisodeSelection` `EpisodeSession` `EpisodeRange` `EpisodeDownloadStatus` `SubscriptionPipeline` `EpisodeBatchDownloader` `AudioDirectLink` `OfflineDownloadManager` `OfflineDownloadRules` `OfflineDownloadService` `OfflineStore` `OfflineLibrary` `ContinueDownload` `ContinueSourceLocator` `DownloadNetworkGate` `NetworkStatusMonitor` `WebLinkResolver` `HiddenWebViewResolver` `M3uParser` `NetworkStream` `NetworkMediaSourceFactory` `PlaybackErrorMessages` `PlaybackProgressRules` `DestructiveActions`

### 简化重写（3 个）

- `PlaybackService`：去掉 MusicLibrary 元数据增强、音频队列持久化、stopAfterTrack、睡眠定时；只留 ExoPlayer + MediaSession + 按条目续播。
- `MainActivity` → 新导航壳。
- UI 文件（`OnlineSearchUi` 1349 行、`OfflineDownloadsUi` 556 行、`EpisodeSelectionSheetUi` 410 行、`ContinueDownloadUi`）：迁入时剥离本地媒体库跳转，只保留这条链路的交互。

### 需要处理的耦合点

- `OfflineItem.toMediaEntry()` 依赖本地媒体库的 `MediaEntry` 模型 → 新 App 里离线条目即一等公民，直接构造 Media3 `MediaItem`，删掉转换层（或带一个 20 行的精简 `MediaEntry`）。
- `OfflineLibrary` 与 `MediaLibrary`/`LibraryMerge` 的合并逻辑 → 整段丢弃，离线库独立。
- 语音工具注册（`SEARCH_ALL_SOURCES`/`PICK_RESULT`/`DOWNLOAD_EPISODES`）→ 丢弃，`SubscriptionPipeline` 本身与语音无关，可直接用。

### 测试带走（约 24 个测试文件）

原项目这条链路有完整单测（纯 JVM、假 transport、真实站点 HTML 样本）：`CrossSourceSearchTest` `SourceProberTest` `SearchRuleDetectorTest` `SearchResultsParserTest` `MediaTitleTest` `SearchHistoryTest` `EpisodeListTest` `EpisodeRangeParserTest` `EpisodeSelectionTest` `EpisodeNamingTest` `EpisodeBatchDownloaderTest` `SubscriptionPipelineTest` `AudioDirectLinkTest` `OfflineDownloadRulesTest` `OfflineLibraryTest` `ContinueDownloadTest` `ContinueSourceLocatorTest` `DownloadNetworkGateTest` `WebLinkResolverTest` `M3uParserTest` `OnlineSearchIntegrationTest` `OnlineSearchEntryTest` `SourceHealthPolicyTest` `EpisodeDownloadRulesTest`。
丢弃：`VoiceOnlineTest` `EpisodeSkipRulesTest` `CoverImageSourcesTest`。

## 六、实施顺序建议

1. 新工程骨架 + 依赖 + 空导航壳（半天）
2. 迁移 source/search/episode 纯逻辑层 + 对应单测跑绿（1 天）
3. 迁移 download 层 + OfflineStore/OfflineLibrary + 单测（1 天）
4. 简化 PlaybackService + 播放页（1 天）
5. 四个 UI 页面接线（1–2 天）
6. 模拟器端到端验收：加订阅源 → 搜「三体」→ 下 2 集 → 离线播 → 杀进程续传续播

## 七、待确认的决策点

1. **在线直播**要不要进 v1？（推荐保留：解析链路本来就要带，边际成本一个按钮）
2. **音频源**（搜歌下载听）要不要进 v1？（推荐保留：分流逻辑已在链路里）
3. **WebView 兜底取址**（`HiddenWebViewResolver`，381 行）要不要带？（推荐带：没有它部分站点解析不出真实地址）
4. 应用名与图标：「AI Player」的中文名、包名 `com.afeicn.aiplayer` 是否 OK？
