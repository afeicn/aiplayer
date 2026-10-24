package com.afeicn.aiplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.PlatformScheduler
import androidx.media3.exoplayer.scheduler.Scheduler

/**
 * 前台下载服务：带进度通知，App 退到后台或被杀后由系统保持任务运行，
 * 未完成任务在服务重启后由 DownloadManager（SQLite 索引）自动恢复。
 */
@OptIn(UnstableApi::class)
class OfflineDownloadService : DownloadService(
    FOREGROUND_NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.offline_download_channel_name,
    R.string.offline_download_channel_description
) {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun getDownloadManager(): DownloadManager = OfflineDownloadManager.manager(this)

    override fun getScheduler(): Scheduler = PlatformScheduler(this, JOB_ID)

    override fun getForegroundNotification(downloads: List<Download>, notMetRequirements: Int): Notification {
        val helper = DownloadNotificationHelper(this, CHANNEL_ID)
        return helper.buildProgressNotification(
            this,
            R.drawable.ic_stat_player,
            null,
            getString(R.string.offline_download_notification_text),
            downloads,
            notMetRequirements
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.offline_download_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.offline_download_channel_description)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private companion object {
        const val CHANNEL_ID = "offline_downloads"
        const val JOB_ID = 0x5A11
        const val FOREGROUND_NOTIFICATION_ID = 0x5A12
    }
}
