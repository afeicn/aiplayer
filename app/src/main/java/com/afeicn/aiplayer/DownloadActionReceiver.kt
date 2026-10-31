package com.afeicn.aiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 超级岛 / 焦点通知上的暂停、继续、取消按钮。
 *
 * 只做转发，不自己起播放器、不持有下载状态：所有动作都回到 [OfflineDownloadManager]，
 * 由 DownloadManager 的监听器再触发 [OfflineDownloadManager.refresh] 和岛状态同步。
 */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID) ?: return
        when (intent.action) {
            ACTION_PAUSE -> OfflineDownloadManager.pause(context, downloadId)
            ACTION_RESUME -> OfflineDownloadManager.resume(context, downloadId)
            ACTION_CANCEL -> OfflineDownloadManager.remove(context, downloadId)
            else -> return
        }
        // 部分状态下 Media3 不会立刻回调 onDownloadChanged（例如暂停后服务进入 idle），
        // 主动刷新一次，让岛上的按钮/进度及时变化。
        OfflineDownloadManager.refresh()
    }

    companion object {
        const val ACTION_PAUSE = "com.afeicn.aiplayer.action.FOCUS_PAUSE"
        const val ACTION_RESUME = "com.afeicn.aiplayer.action.FOCUS_RESUME"
        const val ACTION_CANCEL = "com.afeicn.aiplayer.action.FOCUS_CANCEL"
        const val EXTRA_DOWNLOAD_ID = "com.afeicn.aiplayer.extra.FOCUS_DOWNLOAD_ID"
    }
}
