# 参与贡献

感谢你对 AI Player 的兴趣！

## 开始之前

- 本项目使用 **AGPL-3.0**，任何衍生分发必须同样开源。
- 提交 PR 前请先跑通 `./gradlew :app:assembleDebug :app:testDebugUnitTest`，新逻辑请附带单元测试（核心解析/聚合/下载状态机逻辑都设计为不依赖设备可测）。

## 提交 bug

使用 issue 模板，附上：复现步骤、App 版本（设置页底部）、Android 版本、日志（`adb logcat | grep aiplayer`，注意自行抹除订阅源地址等隐私信息）。

## 提交 PR

- 一个 PR 做一件事，保持提交历史干净。
- UI 改动请遵循 `ui/Motion.kt` 的动效 Token（时长/曲线统一收口，尊重系统「移除动画」）。
- 解析器改动请为新的站点结构补充测试夹具（`app/src/test/resources/`，夹具请使用合成数据，不要提交真实站点抓取的页面）。

## 代码结构速览

| 位置 | 职责 |
|---|---|
| `SubscriptionPipeline` / `SearchRuleDetector` | 订阅源搜索规则探测与抓取 |
| `CrossSourceSearch` / `VodAggregator` | 跨源并行搜索与同名聚合 |
| `OfflineDownloadManager` / `EpisodeBatchDownloader` | 离线下载队列与批量选集 |
| `NetworkMediaSourceFactory` / `PlayerEngine` | 播放缓存与 ExoPlayer 单例 |
| `ui/Motion.kt` | 全局动效 Token |
