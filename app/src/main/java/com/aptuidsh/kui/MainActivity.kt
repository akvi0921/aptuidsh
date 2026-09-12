package com.aptuidsh.kui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aptuidsh.kui.net.DshGateway
import com.aptuidsh.kui.ui.HomeScreen
import com.aptuidsh.kui.ui.HomeViewModel
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme

/**
 * APTUIDSH 主界面(首屏):连接状态 + 后端信息(host.describe 真实数据)。
 *
 * <p>生命周期:onCreate 创建网关(固定基址 http://127.0.0.1:3081)并 start()
 * (启动 host/mux 双事件通道),绑定 AppRuntime;onDestroy 幂等停机并解绑。
 *
 * <p>线程模型:网关内部 RPC/事件通道均在守护线程;UI 由 Compose 主线程渲染。
 */
class MainActivity : ComponentActivity() {

    private var gateway: DshGateway? = null

    private val homeViewModel: HomeViewModel by viewModels {
        HomeViewModel.Factory(requireNotNull(gateway))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val g = DshGateway() // 默认基址 http://127.0.0.1:3081
        g.start()
        gateway = g
        AppRuntime.setGateway(g)

        setContent {
            APTUIDSHTheme {
                HomeScreen(homeViewModel)
            }
        }
    }

    override fun onDestroy() {
        gateway?.shutdown()
        gateway = null
        AppRuntime.clearGateway()
        super.onDestroy()
    }
}
