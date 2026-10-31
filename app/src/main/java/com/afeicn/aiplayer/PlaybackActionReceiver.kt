package com.afeicn.aiplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * PiP 窗口里的 RemoteAction 落点：
 * - 播放/暂停直接转发给已在运行的前台服务（不经过 Activity，PiP 里也能操作）；
 * - 全屏按钮把 App 拉回前台，并带上「回到全屏」标记，由 MainActivity 消费。
 */
class PlaybackActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE_PLAY -> PlaybackService.togglePlayPause()
            ACTION_FULLSCREEN -> {
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra(MainActivity.EXTRA_PIP_FULLSCREEN, true)
                }
                context.startActivity(open)
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE_PLAY = "com.afeicn.aiplayer.action.PIP_TOGGLE_PLAY"
        const val ACTION_FULLSCREEN = "com.afeicn.aiplayer.action.PIP_FULLSCREEN"
    }
}
