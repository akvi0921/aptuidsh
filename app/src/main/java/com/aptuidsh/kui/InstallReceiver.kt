package com.aptuidsh.kui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

/** PackageInstaller 会话结果广播:安装成功 / 等待系统确认 / 失败原因 → Toast。 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE
        )
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val legacy = intent.getIntExtra("android.content.pm.extra.LEGACY_STATUS", 0)
        val text = when {
            status == PackageInstaller.STATUS_SUCCESS -> "安装成功"
            status == PackageInstaller.STATUS_PENDING_USER_ACTION -> "等待系统安装确认…"
            else -> "安装失败(错误码 $legacy): ${
                if (message.isNullOrEmpty()) "请查看日志" else message
            }"
        }
        Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    }
}
