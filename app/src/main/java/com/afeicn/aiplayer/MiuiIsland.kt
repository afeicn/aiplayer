package com.afeicn.aiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/**
 * 小米超级岛 / 焦点通知的 Android 接入层。
 *
 * 设计约束（对应小米官方《开发指南》）：
 * - 只走本地客户端实现，不引入 MiPush SDK；
 * - 能力查询（OS 版本 / 岛特性 / 焦点权限）全部反射或容错包起来，非小米机型直接 [MiuiFocusTemplate.NONE]；
 * - 无权限时不下发 `miui.focus.param`，由原有 Media3 前台下载通知兜底，绝不吞掉下载通知；
 * - 整段 JSON 由 [MiuiFocusPayload] 生成，本类只负责 Notification / PendingIntent / Bundle 拼装；
 * - 固定 notification id + 递增 sequence，保证同一任务的多次更新不会乱序。
 */
object MiuiIsland {

    /** 与 OfflineDownloadService 的前台通知（0x5A12）分开，避免互相覆盖。 */
    const val NOTIFICATION_ID = 0x5A13
    const val CHANNEL_ID = "offline_downloads_focus"

    private const val KEY_PARAM = "miui.focus.param"
    private const val KEY_PICS = "miui.focus.pics"
    private const val KEY_ACTIONS = "miui.focus.actions"

    private const val REQUEST_OPEN = 0x5A14
    private const val MIN_UPDATE_INTERVAL_MS = 800L
    private const val CAPABILITY_TTL_MS = 60_000L
    private const val CANCEL_DELAY_MS = 800L

    data class Capability(
        val isXiaomi: Boolean,
        val protocol: Int,
        val islandFeature: Boolean,
        val canShowFocus: Boolean
    ) {
        val template: MiuiFocusTemplate
            get() = MiuiFocusRules.resolve(isXiaomi, protocol, islandFeature, canShowFocus)
    }

    private var cached: Capability? = null
    private var cachedAt = 0L

    private var lastPosted = false
    private var lastPostAt = 0L
    private var lastSignature: String? = null
    private var lastStatus: String? = null
    /** 每次真正发通知 +1；用于让「结束岛的延迟 cancel」不误伤新任务。 */
    private var generation = 0
    private var sequence = SystemClock.elapsedRealtime()

    private var appIcon: Icon? = null

    /**
     * 下载状态同步入口：null 表示没有活动任务，结束岛。
     *
     * 调用方（[OfflineDownloadManager.doRefresh]）已经在主线程，且每秒最多回调数次；
     * 这里再做一次 800ms 节流，避免高频进度回调刷爆系统通知服务。
     */
    @Synchronized
    fun sync(context: Context, state: MiuiIslandState?) {
        runCatching {
            val ctx = context.applicationContext
            val capability = capability(ctx)
            if (state == null || capability.template == MiuiFocusTemplate.NONE) {
                if (lastPosted) finishInternal(ctx, capability)
                return
            }
            post(ctx, capability, state.copy(notificationId = NOTIFICATION_ID, sequence = nextSequence()), force = false)
        }
    }

    /** 显式结束（下载全部完成 / 失败 / 取消后调用）。 */
    @Synchronized
    fun finish(context: Context) {
        runCatching {
            if (!lastPosted) return
            val ctx = context.applicationContext
            finishInternal(ctx, capability(ctx))
        }
    }

    /** 设置页调试卡片用：返回当前设备能力，不发送。 */
    fun debugStatus(context: Context): String {
        val capability = runCatching { capability(context.applicationContext, force = true) }
            .getOrDefault(Capability(false, 0, false, false))
        return buildString {
            append("设备：").append(if (capability.isXiaomi) "小米 / Redmi / POCO" else "非小米")
            append(" · 协议：").append(protocolLabel(capability.protocol))
            append("\n超级岛特性：").append(if (capability.islandFeature) "支持" else "不支持")
            append(" · 焦点权限：").append(if (capability.canShowFocus) "已开启" else "未开启/查询失败")
            append("\n实际下发：").append(templateLabel(capability.template))
        }
    }

    /**
     * 设置页调试卡片用：发一条 42% 的假下载岛，12 秒后自动结束。
     *
     * 只用于小米设备白名单联调；无权限/非小米时返回原因，不打扰用户。
     */
    @Synchronized
    fun sendDebugDemo(context: Context): String {
        val ctx = context.applicationContext
        return runCatching {
            val capability = capability(ctx, force = true)
            if (capability.template == MiuiFocusTemplate.NONE) {
                return "未发送：${templateLabel(capability.template)}（${capability.reason()}）"
            }
            val state = MiuiIslandState(
                downloadId = "debug-focus-demo",
                title = "测试剧集",
                episode = "第 1 集",
                status = "下载中",
                percent = 42,
                speedText = "3.2 MB/s",
                remainingText = "剩 2 分 10 秒",
                downloadedText = "84.0 MB / 200.0 MB",
                activeCount = 1,
                paused = false,
                notificationId = NOTIFICATION_ID,
                sequence = nextSequence()
            )
            if (!post(ctx, capability, state, force = true)) {
                return "未发送：payload 生成失败"
            }
            val sentGeneration = generation
            Handler(Looper.getMainLooper()).postDelayed({
                synchronized(this) {
                    if (generation == sentGeneration) finishInternal(ctx, capability(ctx))
                }
            }, 12_000L)
            if (capability.template == MiuiFocusTemplate.ISLAND_OS3) {
                "已发送测试超级岛，12 秒后自动结束"
            } else {
                "已发送测试焦点通知，12 秒后自动结束"
            }
        }.getOrElse { "发送失败：${it.message ?: it.javaClass.simpleName}" }
    }

    // ---------------------------------------------------------------- 能力查询

    private fun capability(context: Context, force: Boolean = false): Capability {
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            cached?.let { if (now - cachedAt < CAPABILITY_TTL_MS) return it }
        }
        val isXiaomi = MiuiFocusRules.isXiaomiDevice(
            runCatching { android.os.Build.MANUFACTURER }.getOrDefault(""),
            runCatching { android.os.Build.BRAND }.getOrDefault("")
        )
        val protocol = if (isXiaomi) {
            runCatching {
                Settings.System.getInt(context.contentResolver, "notification_focus_protocol", 0)
            }.getOrDefault(0)
        } else 0
        val islandFeature = if (isXiaomi) runCatching { readIslandFeature() }.getOrDefault(false) else false
        // OS1 及以下没有焦点通知权限概念，查询也没有意义。
        val canShowFocus = if (isXiaomi && protocol >= MiuiFocusRules.PROTOCOL_OS2) {
            runCatching { canShowFocus(context) }.getOrDefault(false)
        } else false
        return Capability(isXiaomi, protocol, islandFeature, canShowFocus).also {
            cached = it
            cachedAt = now
        }
    }

    /** 官方推荐：反射读 `persist.sys.feature.island`，非小米/被限制时返回 false。 */
    private fun readIslandFeature(): Boolean {
        val clazz = Class.forName("android.os.SystemProperties")
        val method = clazz.getDeclaredMethod("getBoolean", String::class.java, java.lang.Boolean.TYPE)
        method.isAccessible = true
        val value = method.invoke(null, "persist.sys.feature.island", false)
        return value as? Boolean ?: false
    }

    /** 官方推荐：ContentResolver.call 查询本应用焦点通知权限。 */
    private fun canShowFocus(context: Context): Boolean {
        val uri = Uri.parse("content://miui.statusbar.notification.public")
        val extras = Bundle().apply { putString("package", context.packageName) }
        val bundle = context.contentResolver.call(uri, "canShowFocus", null, extras) ?: return false
        return bundle.getBoolean("canShowFocus", false)
    }

    // ---------------------------------------------------------------- 发送 / 结束

    /** @return true 表示已调用 notify。 */
    private fun post(
        context: Context,
        capability: Capability,
        state: MiuiIslandState,
        force: Boolean
    ): Boolean {
        val now = SystemClock.elapsedRealtime()
        val signature = listOf(
            state.downloadId, state.percent, state.status, state.paused,
            state.activeCount, state.speedText, state.remainingText
        ).joinToString("|")
        val critical = state.paused || state.status != lastStatus || state.percent >= 100
        if (!force && lastPosted) {
            if (now - lastPostAt < MIN_UPDATE_INTERVAL_MS && !critical) return false
            if (signature == lastSignature) return false
        }
        val payload = MiuiFocusPayload.build(state, capability.template) ?: return false
        val notification = buildNotification(context, state)
        attachFocusExtras(context, notification, payload, state)
        notificationManager(context).notify(NOTIFICATION_ID, notification)
        lastPosted = true
        lastPostAt = now
        lastSignature = signature
        lastStatus = state.status
        generation++
        return true
    }

    private fun finishInternal(context: Context, capability: Capability) {
        lastPosted = false
        lastPostAt = 0L
        lastSignature = null
        lastStatus = null
        val manager = notificationManager(context)
        val payload = MiuiFocusPayload.cancel(NOTIFICATION_ID, nextSequence(), capability.template)
        if (payload == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle("下载已结束")
            .setContentText("进度岛已收起")
            .setOngoing(false)
            .setAutoCancel(true)
            .setShowWhen(false)
            .build()
        notification.extras.putString(KEY_PARAM, payload)
        manager.notify(NOTIFICATION_ID, notification)
        val finishedGeneration = generation
        // cancel=true 让系统收起岛；再 cancel 通知本体清掉通知栏残留。
        // 若期间又开始了新下载（generation 变化），这次延迟 cancel 必须跳过。
        Handler(Looper.getMainLooper()).postDelayed({
            synchronized(this) {
                if (generation == finishedGeneration) manager.cancel(NOTIFICATION_ID)
            }
        }, CANCEL_DELAY_MS)
    }

    private fun buildNotification(context: Context, state: MiuiIslandState): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_notification)
            .setContentTitle(state.taskName)
            .setContentText(state.statusLine)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setProgress(100, state.percent, false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setShowWhen(false)
            .build()
    }

    private fun attachFocusExtras(
        context: Context,
        notification: Notification,
        payload: String,
        state: MiuiIslandState
    ) {
        val extras = Bundle()
        extras.putString(KEY_PARAM, payload)
        extras.putBundle(KEY_PICS, Bundle().apply {
            putParcelable(MiuiFocusPayload.PICTURE_APP, loadAppIcon(context))
        })
        extras.putBundle(KEY_ACTIONS, buildActions(context, state))
        notification.extras.putAll(extras)
    }

    private fun buildActions(context: Context, state: MiuiIslandState): Bundle {
        val actions = Bundle()
        val toggleAction = if (state.paused) DownloadActionReceiver.ACTION_RESUME else DownloadActionReceiver.ACTION_PAUSE
        val togglePending = downloadPendingIntent(context, toggleAction, state.downloadId)
        actions.putParcelable(
            state.pauseActionKey,
            Notification.Action.Builder(
                Icon.createWithResource(
                    context,
                    if (state.paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
                ),
                if (state.paused) "继续" else "暂停",
                togglePending
            ).build()
        )
        actions.putParcelable(
            MiuiFocusPayload.ACTION_CANCEL_KEY,
            Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_close_clear_cancel),
                "取消",
                downloadPendingIntent(context, DownloadActionReceiver.ACTION_CANCEL, state.downloadId)
            ).build()
        )
        return actions
    }

    private fun downloadPendingIntent(context: Context, action: String, downloadId: String): PendingIntent {
        val intent = Intent(context, DownloadActionReceiver::class.java).apply {
            this.action = action
            putExtra(DownloadActionReceiver.EXTRA_DOWNLOAD_ID, downloadId)
            // 官方要求：触发广播的 PendingIntent 必须带 FLAG_RECEIVER_FOREGROUND。
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode(action, downloadId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun requestCode(action: String, downloadId: String): Int =
        (action.hashCode() * 31 + downloadId.hashCode()) and 0x7FFFFFFF

    private fun loadAppIcon(context: Context): Icon {
        appIcon?.let { return it }
        val loaded = runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                ).also { target ->
                    val canvas = Canvas(target)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            }
            Icon.createWithBitmap(bitmap)
        }.getOrElse {
            Icon.createWithResource(context, R.mipmap.ic_launcher)
        }
        appIcon = loaded
        return loaded
    }

    private fun notificationManager(context: Context): NotificationManager {
        ensureChannel(context)
        return context.getSystemService(NotificationManager::class.java)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载超级岛",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "下载进度在小米超级岛 / 焦点通知上的展示"
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun nextSequence(): Long {
        sequence += 1
        return sequence
    }

    private fun protocolLabel(protocol: Int): String = when {
        protocol >= MiuiFocusRules.PROTOCOL_OS3 -> "OS3+（$protocol）"
        protocol == MiuiFocusRules.PROTOCOL_OS2 -> "OS2（$protocol）"
        protocol == MiuiFocusRules.PROTOCOL_OS1 -> "OS1（$protocol）"
        else -> "未知（$protocol）"
    }

    private fun templateLabel(template: MiuiFocusTemplate): String = when (template) {
        MiuiFocusTemplate.ISLAND_OS3 -> "超级岛"
        MiuiFocusTemplate.FOCUS_OS2 -> "OS2 焦点通知"
        MiuiFocusTemplate.NONE -> "不发送（普通通知兜底）"
    }

    private fun Capability.reason(): String = when {
        !isXiaomi -> "非小米设备"
        protocol < MiuiFocusRules.PROTOCOL_OS2 -> "系统不支持焦点通知"
        !canShowFocus -> "应用未获得焦点通知权限"
        else -> "超级岛特性未开启"
    }
}
