# 音频路由变化暂停（耳机拔出 / 蓝牙断开）

> 适用版本：`PlaybackService`（Media3 `MediaSessionService`）持有播放器的版本。
> 目标：有线耳机拔出、蓝牙耳机断开、输出路由切走时暂停播放，但**前台服务和媒体通知保留**。

## 1. 已实现的行为

| 事件 | 处理 | 说明 |
| --- | --- | --- |
| `AudioManager.ACTION_AUDIO_BECOMING_NOISY` | `playWhenReady == true` 时 `player.pause()` | 有线拔出、蓝牙断开、输出从耳机切到扬声器时由系统发出，无需权限 |
| `BluetoothDevice.ACTION_ACL_DISCONNECTED` | `playWhenReady == true` 时 `player.pause()` | 蓝牙 ACL 真正断开的兜底；部分 ROM 不发 becoming-noisy |
| 缓冲中（`isPlaying == false`，`playWhenReady == true`） | 同样暂停 | 只看 `isPlaying` 会漏掉，缓冲完后会直接在扬声器上响 |
| 已经暂停 / 空闲 | 不处理 | `playWhenReady == false`，避免路由抖动把用户手动暂停的播放重新拉起 |

暂停只调 `player.pause()`，**不调用 `stopSelf()`**：

- 前台服务继续存活，媒体通知保留，通知栏 / 锁屏 / 耳机都能继续播放；
- 暂停会触发 `Player.Listener.onIsPlayingChanged(false)`，进度按现有逻辑同步落盘；
- 只有「返回播放页」「从最近任务划掉」等显式停止路径才会停服务、清通知。

## 2. 代码结构

| 文件 | 职责 |
| --- | --- |
| `AudioRouteRules.kt` | 纯 JVM：广播 action 判定、`isPlaying` 守卫 |
| `PlaybackService.kt` | 动态注册两个广播；收到后只暂停，不杀服务 |
| `AndroidManifest.xml` | 蓝牙 ACL 兜底所需的 `BLUETOOTH`（≤30）/ `BLUETOOTH_CONNECT` 声明 |
| `AudioRouteRulesTest.kt` | 单测：两类广播、无关广播、暂停态、空 action |

`ExoPlayer.setHandleAudioBecomingNoisy(true)` 仍然保留（Media3 自带实现）。
我们显式再听一遍 becoming-noisy，是为了把两类事件收进同一个可测策略；
两条路径同时到达时，第二次因 `isPlaying == false` 直接跳过，暂停是幂等的。

## 3. AVRCP PAUSE 链路确认

结论：**没有被自己注册的 `MediaButtonReceiver` 抢掉，AVRCP PAUSE 走 Media3 默认链路。**

依据：

1. `AndroidManifest.xml` 只声明了 `PlaybackService`，intent-filter 是
   `androidx.media3.session.MediaSessionService`，`exported="true"`——这是 Media3 接收系统媒体键 /
   AVRCP 的入口；
2. 全仓没有 `MediaButtonReceiver` 声明，也没有 `android.intent.action.MEDIA_BUTTON` 的 receiver；
3. `PlaybackActionReceiver` 是 `exported="false"` 的内部广播，只处理 PiP 的「播放/暂停」「回全屏」
   两个自定义 action，不碰媒体键；
4. `PlaybackService` 里的 `MediaSession.Callback` 只追加了自定义命令（设队列 / 存进度 / 停止），
   没有覆写 `onMediaButtonEvent`，所以 PAUSE / PLAY / NEXT / PREVIOUS 由 Media3 默认处理；
5. 播放器由 `PlaybackService` 持有，`MediaSession` 的 session player 就是它，媒体键最终落到
   `player.pause()` / `player.play()`。

因此：**耳机按暂停（AVRCP PAUSE）能暂停；真机需确认耳机本身是否发送该命令。**

## 4. 边界：Android 没有通用的「耳机摘下」API

这一条必须写清楚，避免把「摘下不暂停」当 bug 追：

1. **系统只提供「输出路由变化」事件**：`ACTION_AUDIO_BECOMING_NOISY`、ACL 断开、音频设备增删。
   Android 公共 API 没有「耳机被摘下 / 入耳状态变化」的通用广播。
2. **摘下会不会暂停，取决于耳机固件**：
   - 支持入耳检测并主动发 AVRCP PAUSE 的耳机：App 通过 MediaSession 收到暂停，正常暂停；
   - 不发的耳机：App 侧拿不到任何信号，**无法区分「摘下」和「戴着但没声音」**；
   - 部分耳机摘下只停音乐、不断蓝牙，也不会触发 becoming-noisy。
3. **入耳状态只有厂商 SDK 能读**：小米、华为、Apple 等入耳检测 / 佩戴状态属于私有协议或厂商
   SDK（如小米耳机 SDK），不是 Android 公共 API。未接厂商 SDK 时，App 不能假装能做到。
4. 因此本功能的承诺是：**路由变化 / 断开一定暂停；摘下是否暂停由耳机决定**。

## 5. 权限与版本差异

- `ACTION_AUDIO_BECOMING_NOISY`：无需权限，是主路径。
- `ACTION_ACL_DISCONNECTED`：
  - Android 11 及以下：依赖普通 `BLUETOOTH` 权限（Manifest 已声明，`maxSdkVersion=30`）；
  - Android 12+：需要运行时权限 `BLUETOOTH_CONNECT`。本应用**不主动弹窗申请**
    （为一个兜底路径弹「附近设备」权限不划算），未授权时该广播收不到；
  - 所以 ACL 只是「能收到就多一层兜底」，不能当作所有机型都生效的保证。
- 不同 ROM 对 becoming-noisy 的发送时机有差异（尤其蓝牙断连、切换输出设备），需要真机覆盖。

## 6. 真机验证清单

### 6.1 路由变化暂停

- [ ] 有线耳机播放中直接拔出：应暂停，通知保留，点通知可继续播；
- [ ] 有线耳机播放中先按暂停，再拔出：仍保持暂停，不能自动播放；
- [ ] 蓝牙耳机播放中在系统设置里断开 / 关掉耳机电源：应暂停；
- [ ] 蓝牙耳机播放中切到手机扬声器（或切到另一副耳机）：应暂停；
- [ ] 没有连接任何蓝牙 / 耳机时正常起播、暂停、切集：不崩、不误暂停；
- [ ] 暂停后从最近任务切回 App：播放页状态与通知一致，进度没有回跳。

### 6.2 AVRCP / 媒体键

- [ ] 蓝牙耳机按「暂停」：App 暂停；
- [ ] 蓝牙耳机按「播放」：App 继续；
- [ ] 蓝牙耳机上一首 / 下一首：按现有队列逻辑切集（不是被别的 App 抢走）；
- [ ] 锁屏媒体卡片、通知栏的播放/暂停与耳机按键状态同步。

### 6.3 「摘下耳机」边界（按耳机型号记录，不作为 bug）

- [ ] 支持入耳检测且会发 AVRCP PAUSE 的耳机：摘下暂停；
- [ ] 不支持入耳检测 / 不发 AVRCP PAUSE 的耳机：摘下不暂停——**预期行为**，App 侧无信号；
- [ ] 摘下但不断蓝牙：通常不会触发 becoming-noisy，记录耳机型号与表现即可。

### 6.4 不同 ROM 的差异

- [ ] MIUI / HyperOS：蓝牙断开是否发送 becoming-noisy；
- [ ] 原生 Android / 其他 ROM：同上；
- [ ] Android 12+ 未授予 `BLUETOOTH_CONNECT` 时，ACL 兜底不生效，但 becoming-noisy 仍应工作。
