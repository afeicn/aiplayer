package com.afeicn.aiplayer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.afeicn.aiplayer.CastUiState
import com.afeicn.aiplayer.DlnaDevice

/**
 * 投屏面板：未投屏时列出同一 Wi-Fi 下的 DLNA 电视；投屏中显示当前设备与暂停/停止。
 *
 * 只做 DLNA（智能电视 / 盒子 / 投影普遍支持）。Google Cast 需要接收端注册与 GMS，
 * Miracast 没有第三方发起接口，都不在本面板里。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CastSheet(
    state: CastUiState,
    onDismiss: () -> Unit,
    onDiscover: () -> Unit,
    onCast: (DlnaDevice) -> Unit,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetMaxWidth = 620.dp,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("投屏到电视", fontSize = 18.sp, fontWeight = FontWeight.Bold)

            val active = state.activeDevice
            when {
                active != null -> {
                    CastActiveCard(
                        deviceName = active.friendlyName,
                        paused = state.paused,
                        onTogglePlay = onTogglePlay,
                        onStop = onStop
                    )
                    Text(
                        "电视正在从手机拉流，手机端已暂停。停止投屏后可以继续在手机上看。",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                state.connectingDeviceName != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("正在投到「${state.connectingDeviceName}」…", fontSize = 14.sp)
                    }
                }
                else -> {
                    when {
                        state.discovering -> Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("正在搜索同一 Wi-Fi 下的电视…", fontSize = 14.sp)
                        }
                        state.devices.isEmpty() -> Text(
                            if (state.searched) "没有发现可投屏的设备"
                            else "点下面的按钮搜索电视",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.devices.forEach { device ->
                                DeviceRow(device = device, onClick = { onCast(device) })
                            }
                        }
                    }
                    if (state.searched && !state.discovering &&
                        state.devices.isEmpty() && state.message == null
                    ) {
                        Text(
                            "确认电视和手机连同一个 Wi-Fi，并在电视上打开投屏 / DLNA。",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = onDiscover, enabled = !state.discovering) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (state.searched) "重新搜索" else "搜索电视")
                    }
                    Text(
                        "支持 DLNA 的智能电视、盒子、投影仪可以被搜索到；离线缓存的视频不能投屏。",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.message?.let { message ->
                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DlnaDevice, onClick: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Tv, null, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(device.friendlyName, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (device.modelName.isNotBlank() && device.modelName != device.friendlyName) {
                    Text(device.modelName, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(Icons.Default.Cast, null,
                tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun CastActiveCard(
    deviceName: String,
    paused: Boolean,
    onTogglePlay: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CastConnected, null, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("正在投屏", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    Text(deviceName, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onTogglePlay) {
                    Icon(
                        if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        null, modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (paused) "继续播放" else "暂停")
                }
                OutlinedButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("停止投屏")
                }
            }
        }
    }
}

/**
 * 播放页常驻的投屏状态条：显示投到哪台电视、电视端是否暂停，点一下打开面板。
 * 不放在右上角（那里留给横竖屏切换），由播放页放在画面顶部居中。
 */
@Composable
internal fun CastingBanner(
    deviceName: String,
    paused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MediaOverlayColors.Scrim,
        shape = CircleShape,
        onClick = onClick,
        modifier = modifier
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(Icons.Default.CastConnected, null,
                tint = MediaOverlayColors.TextPrimary, modifier = Modifier.size(16.dp))
            Text(
                if (paused) "已投屏：$deviceName · 已暂停" else "已投屏：$deviceName",
                color = MediaOverlayColors.TextPrimary, fontSize = 12.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
