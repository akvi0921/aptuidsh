package com.aptuidsh.kui;

import com.aptuidsh.kui.net.DshGateway;

/**
 * 应用级运行期持有器（devcrew 步骤 9 · features 角色）。
 *
 * <p>跨 Activity 共享 {@link DshGateway}（RPC 客户端 + host/mux 双事件通道）：
 * MainActivity 持有网关生命周期（onCreate 创建/启动，onDestroy 停机），
 * SettingsActivity 等二级页面经 {@link #gateway()} 取同一实例（主界面在
 * 二级页之上存活，进程内引用始终有效）。
 *
 * <p>线程模型：volatile 读写，任意线程可访问；网关内部自身线程安全。
 */
public final class AppRuntime {

    private static volatile DshGateway gateway;

    private AppRuntime() {
    }

    /** 绑定网关（MainActivity.onCreate 调用）。 */
    public static void setGateway(DshGateway g) {
        gateway = g;
    }

    /** 取网关；未绑定（进程冷启边缘）返回 null，调用方自行降级。 */
    public static DshGateway gateway() {
        return gateway;
    }

    /** 解绑（MainActivity.onDestroy 调用，防泄漏）。 */
    public static void clearGateway() {
        gateway = null;
    }
}
