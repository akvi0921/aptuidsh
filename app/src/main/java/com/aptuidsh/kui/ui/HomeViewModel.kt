package com.aptuidsh.kui.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aptuidsh.kui.net.DshClient
import com.aptuidsh.kui.net.DshGateway
import com.aptuidsh.kui.net.EventStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** host.describe 返回的后端信息(字段缺失时展示 "—")。 */
data class BackendInfo(
    val version: String,
    val provider: String,
    val model: String,
    val cwd: String,
    val home: String,
    val attachedSessions: String,
) {
    companion object {
        fun from(value: JSONObject?): BackendInfo {
            fun opt(key: String): String {
                if (value == null || !value.has(key)) return "—"
                val v = value.optString(key, "—")
                return if (v.isNullOrEmpty()) "—" else v
            }
            val sessions = if (value != null && value.has("attachedSessions")) {
                value.optInt("attachedSessions", 0).toString() + " 个"
            } else {
                "—"
            }
            return BackendInfo(
                version = opt("version"),
                provider = opt("provider"),
                model = opt("model"),
                cwd = opt("cwd"),
                home = opt("home"),
                attachedSessions = sessions,
            )
        }
    }
}

/** 首屏三态:连接中 / 已连接(含后端信息与基址) / 失败(含错误详情)。 */
sealed interface HomeUiState {
    data object Connecting : HomeUiState
    data class Connected(val info: BackendInfo, val baseUrl: String) : HomeUiState
    data class Failed(val detail: String) : HomeUiState
}

/**
 * 首屏 ViewModel:持有 DshGateway,启动即调 host.describe;
 * 同时订阅事件通道(host/mux)连接状态供底部状态行展示。
 * 标题图片为内置资源(drawable-nodpi),无需运行时加载。
 *
 * <p>线程模型:底层 DshClient 回调在守护线程池执行;StateFlow 线程安全,
 * Compose collectAsState 自动在主线程重组。
 */
class HomeViewModel(private val gateway: DshGateway) : ViewModel() {

    private val _uiState = MutableStateFlow<HomeUiState>(HomeUiState.Connecting)
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private val _channelHost = MutableStateFlow("重连中")
    val channelHost: StateFlow<String> = _channelHost.asStateFlow()

    private val _channelMux = MutableStateFlow("重连中")
    val channelMux: StateFlow<String> = _channelMux.asStateFlow()

    init {
        gateway.setConnectionListener(object : EventStream.ConnectionListener {
            override fun onConnected(channel: String) {
                when (channel) {
                    EventStream.CH_HOST -> _channelHost.value = "已连接"
                    EventStream.CH_MUX -> _channelMux.value = "已连接"
                }
            }

            override fun onDisconnected(channel: String, reason: String?) {
                when (channel) {
                    EventStream.CH_HOST -> _channelHost.value = "重连中"
                    EventStream.CH_MUX -> _channelMux.value = "重连中"
                }
            }
        })
        refresh()
    }

    /** 发起 host.describe(连接中 → 成功/失败)。 */
    fun refresh() {
        _uiState.value = HomeUiState.Connecting
        gateway.client().hostDescribe(object : DshClient.DshCallback {
            override fun onResult(value: JSONObject?) {
                _uiState.value = HomeUiState.Connected(
                    info = BackendInfo.from(value),
                    baseUrl = gateway.baseUrl,
                )
            }

            override fun onError(code: Int, message: String?, details: JSONObject?) {
                _uiState.value = HomeUiState.Failed(formatError(code, message, details))
            }
        })
    }

    private fun formatError(code: Int, message: String?, details: JSONObject?): String {
        val sb = StringBuilder()
        if (!message.isNullOrEmpty()) sb.append(message)
        sb.append("\n错误码: ").append(code)
        if (details != null) {
            val raw = details.optString("rawCode", "")
            if (raw.isNotEmpty()) sb.append(" (").append(raw).append(")")
            val http = details.optInt("httpStatus", 0)
            if (http > 0) sb.append(" · HTTP ").append(http)
        }
        return sb.toString()
    }

    /** ViewModel 工厂:注入 DshGateway(MainActivity 持有)。 */
    class Factory(private val gateway: DshGateway) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = HomeViewModel(gateway) as T
    }
}
