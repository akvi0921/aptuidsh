package com.aptuidsh.kui

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 应用内状态横条(替换系统 Toast 的场景,如 APK 唤起提示):
 * 灰色背景、无圆角边框、宽度随文字自适应,**居中悬浮在内容视口中心**;
 * 2.6s 后自动移除。非 Activity 上下文时回退系统 Toast。
 */
object AppBanner {
    private val handler = Handler(Looper.getMainLooper())

    fun show(ctx: Context, text: String) {
        val activity = ctx as? Activity
        if (activity == null || activity.isFinishing) {
            Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
            return
        }
        handler.post { attach(activity, text) }
    }

    private fun attach(activity: Activity, text: String) {
        try {
            val root = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
                ?: return fallback(activity, text)
            val dark = (activity.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val banner = TextView(activity).apply {
                this.text = text
                setBackgroundColor(if (dark) Color.rgb(56, 58, 60) else Color.rgb(226, 226, 226))
                setTextColor(if (dark) Color.rgb(235, 235, 235) else Color.rgb(50, 50, 50))
                textSize = 13f
                setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10))
                gravity = Gravity.CENTER
            }
            // 内容视口中心悬浮,宽度只包住文字(不占全屏)
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.CENTER
            root.addView(banner, lp)
            handler.postDelayed({ runCatching { root.removeView(banner) } }, 2600)
        } catch (_: Exception) {
            fallback(activity, text)
        }
    }

    private fun fallback(ctx: Context, text: String) {
        Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
    }

    private fun dp(ctx: Context, v: Int): Int =
        Math.round(v * ctx.resources.displayMetrics.density)
}
