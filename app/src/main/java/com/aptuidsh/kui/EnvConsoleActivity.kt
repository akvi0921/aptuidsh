package com.aptuidsh.kui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.aptuidsh.kui.ui.EnvConsoleScreen
import com.aptuidsh.kui.ui.theme.APTUIDSHTheme

/** 内置环境控制台（只读状态 + 启停/重装 + 原始运行日志）。 */
class EnvConsoleActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            APTUIDSHTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .systemBarsPadding(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    EnvConsoleScreen(onBack = { finish() })
                }
            }
        }
    }
}
