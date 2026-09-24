package com.aptuidsh.kui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aptuidsh.kui.ui.HomeScreen
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme

/**
 * 首屏：内置环境控制台。
 *
 * <p>自研原生前端已整体移除（对话 / 会话列表 / 设置 / 文件浏览 / 播放器 / 协议层），
 * 前端只剩**官方 Web UI** 一套。原生侧因此只做两件事：
 * <ol>
 *   <li><b>把内置 Linux 环境（proroot + Ubuntu + Node + dsh）管起来</b> —— 装、启、停、重启、更新；</li>
 *   <li><b>提供进入官方界面的入口</b> —— {@link WebUiActivity}；排障另有 {@link EnvConsoleActivity}。</li>
 * </ol>
 *
 * <p>不再持有任何 dsh RPC 网关：本页不跟后端通信，状态全部来自
 * {@link com.aptuidsh.kui.env.DshBackend} 的本地状态（进程 + 端口），
 * 因此即使后端没起来，首屏也能如实显示原因，不会卡在「连接失败」。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val ctx: Context = this
        setContent {
            APTUIDSHTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    HomeScreen(
                        onOpenConsole = {
                            ctx.startActivity(Intent(ctx, EnvConsoleActivity::class.java))
                        },
                        onOpenWebUi = {
                            ctx.startActivity(Intent(ctx, WebUiActivity::class.java))
                        },
                    )
                }
            }
        }
    }
}
