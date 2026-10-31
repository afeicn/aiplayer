package com.afeicn.aiplayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * 后台播放服务：ExoPlayer 的真正宿主。
 *
 * 原来播放器跟着播放页生命周期走，Activity 一进后台/锁屏就暂停；现在：
 * 1. ExoPlayer 由前台服务持有，配好音频焦点、耳机拔出、CPU 唤醒锁；
 * 2. MediaSession 暴露给系统（锁屏、蓝牙、车机媒体键）；
 * 3. 整个队列一次性交给 Media3（setMediaItems），上一集/下一集与自动连播都由它管；
 * 4. 进度保存搬到这里：周期性兜底 + 切集/暂停/停止/划掉任务时落盘。
 *
 * UI 不再直接持有 ExoPlayer，只通过 MediaController 发自定义命令（见 [PlaybackCommands]）。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    /** 当前队列：与 Media3 播放列表下标一一对应，用于存断点和集号。 */
    private var queue: List<MediaEntry> = emptyList()

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 播放中每 5 秒兜底存一次：进程被强杀时最多丢这几秒。 */
    private val progressTicker = object : Runnable {
        override fun run() {
            if (mediaSession?.player?.isPlaying == true) {
                saveProgress(synchronous = false)
                mainHandler.postDelayed(this, ProgressSaveRules.INTERVAL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            publishCurrentEntry()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            saveProgress(synchronous = !isPlaying)
            syncProgressTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) saveProgress(synchronous = true)
            if (playbackState == Player.STATE_IDLE) mainHandler.removeCallbacks(progressTicker)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            // 自动连播前先把上一集的最后一小段位置落盘（周期存档可能刚好错过）。
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                savePositionAt(oldPosition.mediaItemIndex, oldPosition.positionMs, synchronous = false)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.playback_channel_name)
                .setNotificationId(NOTIFICATION_ID)
                .build()
                .apply { setSmallIcon(R.drawable.ic_stat_player) }
        )

        val player = PlayerEngine.player(this).apply {
            // handleAudioFocus = true：来电话/其他 App 抢占时自动让音，结束后恢复。
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            setHandleAudioBecomingNoisy(true)
            // 锁屏 / 关屏继续解码：没有唤醒锁时 CPU 会睡，声音会断。
            setWakeMode(C.WAKE_MODE_NETWORK)
            addListener(playerListener)
        }

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(openAppPendingIntent())
            .setCallback(SessionCallback())
            .build()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** 从最近任务划掉 = 停止播放，不做音乐 App 那种划掉还继续。 */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlaybackInternal()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        saveProgress(synchronous = true)
        mainHandler.removeCallbacksAndMessages(null)
        instance = null
        mediaSession?.run {
            player.removeListener(playerListener)
            release()
        }
        mediaSession = null
        PlayerEngine.release()
        super.onDestroy()
    }

    // ==================== 播放控制 ====================

    private fun startQueue(start: PlaybackCommands.QueueStart) {
        if (start.entries.isEmpty()) return
        queue = start.entries
        val player = mediaSession?.player ?: return
        val index = PlaybackQueueRules.startIndex(start.startIndex, queue.size)
        val entry = queue[index]
        // 续播位置只在起播那一集生效；后续集从 0 开始（与原来的播放页行为一致）。
        val position = AppContainer.playbackProgress.positionOf(entry.uri.toString())
        player.setMediaItems(queue.map { it.toMediaItem() }, index, position.coerceAtLeast(0L))
        player.prepare()
        player.playWhenReady = true
        publishCurrentEntry()
        syncProgressTicker()
    }

    private fun stopPlaybackInternal() {
        saveProgress(synchronous = true)
        mainHandler.removeCallbacks(progressTicker)
        val player = mediaSession?.player
        player?.stop()
        player?.clearMediaItems()
        queue = emptyList()
        stopSelf()
    }

    private fun saveProgress(synchronous: Boolean) {
        val player = mediaSession?.player ?: return
        if (player.mediaItemCount == 0) return
        // 周期存档只写断点；剧名/集号在切集和起播时写，避免每 5 秒重复写两份 SharedPreferences。
        savePositionAt(player.currentMediaItemIndex, player.currentPosition, synchronous)
    }

    private fun savePositionAt(index: Int, positionMs: Long, synchronous: Boolean) {
        val entry = entryAt(index) ?: return
        AppContainer.playbackProgress.savePosition(entry.uri.toString(), positionMs, synchronous)
    }

    /** 记剧名 + 集号：首页「最近播放」和续播定位都靠它。 */
    private fun publishCurrentEntry() {
        val player = mediaSession?.player ?: return
        val index = player.currentMediaItemIndex
        val entry = entryAt(index) ?: return
        if (entry.kind == MediaKind.VIDEO) AppContainer.playbackProgress.saveLastSeries(entry.name)
        val number = PlaybackQueueRules.episodeNumberOf(queue, index)
            ?: entry.episodeNumber?.takeIf { it > 0 }
            ?: return
        AppContainer.playbackProgress.saveLastEpisode(entry.name, number)
    }

    /** 队列优先；服务被重建导致队列丢失时从 MediaItem extras 还原最小信息。 */
    private fun entryAt(index: Int): MediaEntry? =
        queue.getOrNull(index) ?: mediaSession?.player?.getMediaItemAt(index)?.toEntryOrNull()

    private fun syncProgressTicker() {
        mainHandler.removeCallbacks(progressTicker)
        if (mediaSession?.player?.isPlaying == true) {
            mainHandler.postDelayed(progressTicker, ProgressSaveRules.INTERVAL_MS)
        }
    }

    private fun success(): ListenableFuture<SessionResult> =
        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))

    private fun openAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private inner class SessionCallback : MediaSession.Callback {

        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            // 自定义命令必须在这里显式放行，MediaController 才能 sendCustomCommand。
            val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                .add(SessionCommand(PlaybackCommands.SET_QUEUE, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.SAVE_PROGRESS, Bundle.EMPTY))
                .add(SessionCommand(PlaybackCommands.STOP, Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(commands)
                .build()
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                PlaybackCommands.SET_QUEUE -> {
                    val start = PlaybackCommands.decodeQueue(args)
                        ?: return Futures.immediateFuture(
                            SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE)
                        )
                    startQueue(start)
                }
                PlaybackCommands.SAVE_PROGRESS -> {
                    saveProgress(synchronous = args.getBoolean(PlaybackCommands.KEY_SYNCHRONOUS))
                }
                PlaybackCommands.STOP -> stopPlaybackInternal()
            }
            return success()
        }
    }

    companion object {
        private const val CHANNEL_ID = "aiplayer_playback"
        private const val NOTIFICATION_ID = 0x50A1

        @Volatile
        private var instance: PlaybackService? = null

        /**
         * PiP 窗口里的播放/暂停按钮走广播进来（服务已在播，属于用户主动操作）。
         * 返回 false 表示服务已停止，调用方不用重试。
         */
        fun togglePlayPause(): Boolean {
            val player = instance?.mediaSession?.player ?: return false
            if (player.isPlaying) {
                player.pause()
            } else {
                player.playWhenReady = true
                player.play()
            }
            return true
        }
    }
}
