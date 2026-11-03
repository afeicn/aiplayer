# 无线投屏（DLNA）

播放页支持把当前在线视频投到家中的电视 / 盒子 / 投影仪。入口是播放页右上角方向切换按钮**左侧**的投屏图标（竖屏、全屏都有）；投屏后画面顶部居中会出现「已投屏：电视名」的状态条，点它可以暂停、继续或停止投屏。

## 为什么是 DLNA，不是 Google Cast / Miracast

| 方案 | 能不能做 | 原因 |
| --- | --- | --- |
| DLNA（UPnP AVTransport） | 能 | 局域网标准协议，电视作为 MediaRenderer 接收 URL，不需要账号和接收端注册 |
| Google Cast | 不能直接做 | 需要 Google Cast SDK + 接收端注册（Developer Console）且依赖 GMS；国内电视普遍没有 |
| Miracast（Wi-Fi Display） | 不能由 App 发起 | 系统级屏幕镜像，第三方没有发起接口；需要用户在系统「投屏」里操作 |

因此本功能只覆盖**支持 DLNA 的智能电视 / 盒子 / 投影仪**。电视上一般叫「多屏互动」「投屏」「DLNA」。

## 链路

```
SSDP M-SEARCH (239.255.255.250:1900)
  → 电视响应 LOCATION
  → 拉设备描述 XML，找 AVTransport 服务
  → 手机开局域网代理（随机端口 + 会话令牌）
  → SOAP SetAVTransportURI(代理地址) + Play
  → 电视向 http://手机IP:端口/stream?t=令牌&u=原地址 拉流
  → 手机带 Referer / UA / Cookie 转发给源站
```

代码分层：

- `DlnaProtocol.kt`：SSDP 报文、设备描述解析、SOAP 动作、DIDL-Lite 元数据（纯 JVM，可单测）
- `DlnaDiscovery.kt`：组播搜索 + 拉取设备描述（搜索期间持 `MulticastLock`）
- `DlnaController.kt`：AVTransport 的 HTTP POST 控制端
- `CastProxyProtocol.kt`：代理 URL、HLS 播放列表重写规则（纯 JVM，可单测）
- `CastProxyServer.kt`：手机侧 HTTP 代理，透传 Range
- `CastNetwork.kt`：取 Wi-Fi / 以太网的 IPv4 地址
- `CastViewModel.kt`：投屏会话状态
- `ui/CastSheet.kt`：设备选择与投屏中控制面板

### 为什么必须架手机代理

1. 很多源的直链要求 Referer / User-Agent / Cookie，电视自己拉会 403；
2. HLS 的 m3u8 里分片是相对地址，电视只会向代理要 `seg-1.ts`，必须把播放列表里的每个 URI（包括 `#EXT-X-KEY:URI="..."`、`#EXT-X-MAP:URI="..."`）都改写成代理地址；
3. 代理端口每次随机会话重新分配，令牌只防同局域网的其他设备借用，不承担加密职责。

## 已知限制

- **HLS 的电视兼容性不保证**：部分电视的 DLNA 只认 MP4 直链，m3u8 会起播失败（面板会提示失败原因）。这是电视端能力问题，App 侧无法补齐。
- **离线缓存不能投屏**：缓存是手机本地文件，电视拉不到；按钮对离线条目禁用。
- **电视端进度不同步回手机**：电视遥控器拖动进度后，手机进度条不会跟着变；手机拖动会同步给电视，两边位置保持一致。
- **投屏中切换集数**：会停掉旧地址、把新一集重新投到同一台电视，代理令牌同时换新。
- **停止投屏**：不会自动恢复手机本地播放，本地保持暂停、位置不变。
- **局域网限制**：手机必须和电视在同一 Wi-Fi / 有线局域网；开了 AP 隔离或访客网络时搜不到也拉不到流。
- **搜索时间**：SSDP 默认等 3.5 秒；部分电视响应慢，可以点「重新搜索」。
- **cleartext HTTP**：DLNA 是明文 HTTP，`AndroidManifest.xml` 开了 `usesCleartextTraffic="true"`。
- **组播锁**：搜索期间申请 `CHANGE_WIFI_MULTICAST_STATE` 权限对应的 `MulticastLock`，搜索结束立即释放，不会长期影响待机功耗。

## 只能真机验证的项

以下都无法在 JVM 单测里覆盖，需要真电视 / 真网络：

1. **能否搜到电视**：同 Wi-Fi 下点投屏，列表是否出现客厅电视；不同品牌 ROM（小米 / 华为 / OPPO）的组播过滤是否影响搜索。
2. **起播**：MP4 直链、m3u8 点播、m3u8 直播三类片源分别能不能在电视上起播。
3. **代理透传**：需要 Referer / UA 才能拉的源，电视端是否正常播放（看手机代理有没有正常转发）。
4. **HLS 相对地址**：多码率 master playlist 能不能切到子列表并正常拉分片。
5. **控制**：暂停 / 继续 / 拖动进度 / 停止投屏是否作用到电视；投屏中切下一集是否重投成功。
6. **异常路径**：电视中途断电 / 退出投屏 App / 手机切走 Wi-Fi 时的提示是否合理，代理端口是否释放。
7. **多设备**：同一局域网有两台以上电视时，选中的设备是否就是实际起播的那台。
