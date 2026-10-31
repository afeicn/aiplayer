# 小米超级岛 / 焦点通知接入说明

> 适用版本：HyperOS 2（焦点通知，协议 2）与 HyperOS 3（超级岛，协议 3）。
> 实现方式：本地客户端构造原生通知 + `miui.focus.param`，**不引入 MiPush SDK**。

## 1. 代码结构

| 文件 | 职责 |
| --- | --- |
| `MiuiFocusPayload.kt` | 纯 JVM：协议判定、模板选择、`param_v2` JSON、cancel JSON、3072 字节上限 |
| `MiuiIsland.kt` | Android 层：能力查询（反射 / Settings / ContentResolver）、固定通知 id、sequence、节流发送、结束岛 |
| `DownloadActionReceiver.kt` | 岛上的暂停 / 继续 / 取消按钮，转发到 `OfflineDownloadManager` |
| `DownloadSpeedTracker.kt` | 纯 JVM：相邻采样差分 + 指数滑动平均测速 |
| `OfflineDownloadManager.kt` | `doRefresh()` 末尾把主下载任务同步到岛；无任务时结束岛 |
| `ui/Screens.kt` | 设置页「更多设置」里的 debug-only 调试卡片 |
| `res/drawable/ic_download_notification.xml` | 通知小图标 |

## 2. 协议与模板选择

| 系统能力 | 下发结构 |
| --- | --- |
| 非小米 / OS1 / 无焦点权限 | 不额外发通知，保留 Media3 前台下载通知兜底 |
| OS2（`notification_focus_protocol=2`） | 焦点通知模板：`baseInfo` + `progressInfo` + `actions`，**不带** `param_island` |
| OS3（协议 3 且 `persist.sys.feature.island=true`） | 超级岛模板：`baseInfo` + `multiProgressInfo` + `param_island`（大岛 / 小岛）+ `actions` |
| OS3 但岛特性关闭 | 退回 OS2 焦点通知模板 |

关键字段：

- `filterWhenNoPermission=false`：焦点权限被关时通知正常显示，不被过滤；
- `isShowNotification=false`：通知栏本体由 Media3 前台通知承担，避免重复；岛仍展示；
- `updatable=true` + 固定 `notifyId` + 递增 `sequence`：同一任务持续更新、避免乱序；
- `reopen=reopen`：用户划掉后，下一次进度更新重新展示；
- `timeout=720`（分钟）、`islandTimeout=43200`（秒）：与小米「单次任务不超过 12 小时」的准入要求一致；
- 完成 / 失败 / 取消：同 id 下发 `cancel=true`，再延迟 `cancel()` 清掉通知栏残留。

## 3. 权限申请（必须由小米侧开通）

代码只能完成客户端链路，**正式上岛还需要小米开通**：

1. 发邮件到 `mipush-permission@xiaomi.com` 申请焦点通知 / 超级岛权限；
2. 提交上岛场景预审（本应用场景：离线下载进度；有明确开始/结束、通常不超过 12 小时）；
3. 按小米要求做上线验证；
4. 联调期可申请「设备白名单」，有效期约 30 天，把测试机 IMEI / 序列号报给小米；
5. 开通后把 `AndroidManifest.xml` 里的 `com.xiaomi.xms.APP_ID` 从包名占位替换为小米分配的正式 APP_ID；
6. 与小米确认 `business` 的正式取值（当前代码为 `download`）。

## 4. 调试入口

debug 包进入「设置 → 更多设置」，最下方有「小米超级岛（调试）」卡片：

- 显示：设备是否小米系、协议版本、岛特性、焦点权限、最终下发模板；
- 点击「发送测试超级岛」：发一条 42% 的假下载任务，12 秒后自动结束；
- release 包通过 `FLAG_DEBUGGABLE` 判断隐藏该卡片。

## 5. 只能在小米真机上验证的项

1. 系统属性 `persist.sys.feature.island` 是否可读、返回值是否符合预期；
2. `content://miui.statusbar.notification.public` 的 `canShowFocus` 返回值；
3. 焦点通知在状态栏 / 通知中心 / 锁屏 / 息屏的实际样式；
4. 超级岛摘要态、展开态、小岛进度环的布局与截断；
5. 暂停 / 继续 / 取消三个按钮的点击回调；
6. 完成、失败、取消后岛是否按 `cancel=true` 收起；
7. 多任务同时下载时 `activeCount` 与主任务切换是否符合预期；
8. 无焦点权限时是否只显示普通下载通知（不吞通知）；
9. 小米设备白名单过期后，系统是否自动退回普通通知。

## 6. 锁屏小组件的结论

**不能做第三方锁屏小组件。**

依据：

- AOSP 虽然有 `AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD` 常量，但锁屏上的 AppWidget 宿主由系统 SystemUI 控制，公共 API 没有给第三方应用注册 keyguard host 的能力；
- 小米开发者文档没有提供第三方锁屏小组件 SDK / 上架入口，HyperOS 的锁屏小组件目前以小米自家生态为主；
- 强行声明 keyguard category 也不会被系统锁屏宿主绑定，属于写了也用不上的代码。

替代方案（均已具备或已实现）：

1. **锁屏媒体卡片**：现有 `PlaybackService`（Media3 `MediaSessionService`）已提供锁屏 / 通知栏播放控制；
2. **媒体通知自动上岛**：小米官方文档第六节说明媒体通知接入后可自动上岛，无需额外写岛模板；
3. **画中画**：上一轮已实现的 PiP，适合看视频时离开应用；
4. **下载超级岛**：本轮实现，覆盖「下载进度 + 暂停/继续/取消」这一有明确生命周期的场景。
