package com.afeicn.aiplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 投屏面板的状态快照。 */
internal data class CastUiState(
    val discovering: Boolean = false,
    val devices: List<DlnaDevice> = emptyList(),
    /** 是否已经搜过一轮（用来区分「还没搜」和「搜了但没有」）。 */
    val searched: Boolean = false,
    /** 正在连接/起播的设备名；非空时面板显示加载态。 */
    val connectingDeviceName: String? = null,
    val activeDevice: DlnaDevice? = null,
    /** 电视端是否处于暂停。 */
    val paused: Boolean = false,
    /** 当前已投出去的媒体地址（switch 集数时用来判断是否需要重投）。 */
    val castingUri: String? = null,
    val message: String? = null
) {
    val casting: Boolean get() = activeDevice != null
}

/**
 * 无线投屏：SSDP 找 DLNA 电视 → 手机起局域网代理 → 电视从代理拉流。
 *
 * 为什么中间要架代理：
 * - 源站大多校验 Referer / UA / Cookie，电视直接拉会 403；
 * - HLS 播放列表里的分片是相对地址，由手机重写成代理地址后电视才能逐片拉取。
 *
 * 投屏期间手机本地播放器会暂停；停止投屏不会自动恢复本地播放，位置保持不动。
 */
internal class CastViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CastUiState())
    val state: StateFlow<CastUiState> = _state.asStateFlow()

    private var proxy: CastProxyServer? = null
    private var work: Job? = null

    /** 搜索同一 Wi-Fi 下的 DLNA 电视。 */
    fun discover() {
        if (_state.value.discovering) return
        val context = getApplication<Application>()
        if (CastNetwork.localIpv4(context) == null) {
            _state.update {
                it.copy(
                    discovering = false, searched = true,
                    message = "手机没连 Wi-Fi，投屏需要和电视在同一个局域网"
                )
            }
            return
        }
        _state.update { it.copy(discovering = true, searched = true, message = null) }
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.IO) {
            val devices = runCatching { DlnaDiscovery.discover(context) }.getOrDefault(emptyList())
            _state.update {
                it.copy(
                    discovering = false,
                    devices = devices,
                    message = if (devices.isEmpty()) {
                        "没搜到电视。确认电视和手机连同一个 Wi-Fi，并在电视上打开投屏/DLNA"
                    } else null
                )
            }
        }
    }

    /**
     * 把 [entry] 投到 [device]。
     * 只支持在线 http(s) 流；离线缓存文件电视拉不到，直接提示。
     */
    fun cast(device: DlnaDevice, entry: MediaEntry, startPositionMs: Long = 0L) {
        if (!entry.isNetwork) {
            _state.update { it.copy(message = "离线缓存不能投屏，请播在线线路") }
            return
        }
        val context = getApplication<Application>()
        val host = CastNetwork.localIpv4(context)
        if (host == null) {
            _state.update { it.copy(message = "手机没连 Wi-Fi，投屏需要和电视在同一个局域网") }
            return
        }
        _state.update {
            it.copy(connectingDeviceName = device.friendlyName, message = null)
        }
        work?.cancel()
        work = viewModelScope.launch(Dispatchers.IO) {
            val oldDevice = _state.value.activeDevice
            if (oldDevice != null) runCatching { DlnaController.stop(oldDevice) }
            proxy?.stop()
            proxy = null
            try {
                val server = CastProxyServer(
                    host = host,
                    sessionHeaders = entry.networkHeaders,
                    token = UUID.randomUUID().toString().replace("-", "")
                )
                server.start()
                proxy = server
                val mediaUrl = server.urlFor(entry.uri.toString())
                DlnaController.setUriAndPlay(
                    device = device,
                    mediaUrl = mediaUrl,
                    metadata = DlnaProtocol.metadata(
                        title = entry.displayTitle,
                        mediaUrl = mediaUrl,
                        sourceUrl = entry.uri.toString(),
                        kind = entry.kind
                    )
                )
                // 从手机当前进度续播。个别电视不支持 REL_TIME 时忽略，不影响投屏本身。
                if (startPositionMs > 0) {
                    runCatching { DlnaController.seek(device, startPositionMs) }
                }
                // 用户在起播过程中关闭了投屏/离开页面：别把已经停掉的会话又「复活」。
                if (!isActive) {
                    server.stop()
                    if (proxy === server) proxy = null
                    return@launch
                }
                _state.update {
                    it.copy(
                        connectingDeviceName = null,
                        activeDevice = device,
                        paused = false,
                        castingUri = entry.uri.toString(),
                        message = "已投屏到 ${device.friendlyName}"
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                proxy?.stop()
                proxy = null
                _state.update {
                    it.copy(
                        connectingDeviceName = null,
                        activeDevice = null,
                        castingUri = null,
                        paused = false,
                        message = "投屏失败：${t.message ?: "电视没有响应"}"
                    )
                }
            }
        }
    }

    /** 电视端播放/暂停。 */
    fun togglePlay() {
        val device = _state.value.activeDevice ?: return
        val paused = _state.value.paused
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                if (paused) DlnaController.play(device) else DlnaController.pause(device)
            }.fold(
                onSuccess = { _state.update { it.copy(paused = !paused) } },
                onFailure = { t -> _state.update { it.copy(message = "电视操作失败：${t.message ?: "无响应"}") } }
            )
        }
    }

    /** 手机进度条拖动时同步电视端（REL_TIME）。 */
    fun seekTo(positionMs: Long) {
        val device = _state.value.activeDevice ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { DlnaController.seek(device, positionMs) }
        }
    }

    /** 停止投屏并关掉代理端口；本地播放器保持暂停。 */
    fun stopCasting() {
        val device = _state.value.activeDevice
        work?.cancel()
        work = null
        proxy?.stop()
        proxy = null
        _state.update {
            it.copy(
                activeDevice = null, connectingDeviceName = null, castingUri = null,
                paused = false, message = null
            )
        }
        if (device != null) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { DlnaController.stop(device) } }
        }
    }

    fun consumeMessage() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        work?.cancel()
        proxy?.stop()
        proxy = null
        super.onCleared()
    }
}
