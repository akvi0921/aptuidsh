package com.aptuidsh.kui.env;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * APTUIDSH 内置 proroot 运行环境的路径与常量中心。
 *
 * <h3>环境构成</h3>
 * <ul>
 *   <li><b>proroot</b>：rootless Linux 运行时（5 个 .so，随 APK 的 jniLibs 分发，
 *       安装后落在 {@code nativeLibraryDir}，这是 Android 10+ 唯一允许 exec 的目录）。</li>
 *   <li><b>rootfs</b>：Ubuntu 24.04.5 arm64 base + Node.js 22.22.2 + dsh 本体，
 *       以 {@code assets/rootfs.tar.xz} 打包，首次启动由 {@link RootfsInstaller} 解压到
 *       {@code filesDir/rootfs}。</li>
 * </ul>
 *
 * <h3>关键约束（实测得出，勿改）</h3>
 * <ol>
 *   <li>启动 proroot 必须使用<b>干净环境变量</b>：宿主环境里的 {@code PREFIX}/{@code DSH_HOME}
 *       等会透传进 guest，导致 npm/工具链路径错乱。</li>
 *   <li>guest 子进程的 PATH 由 shell 默认值决定，因此 Node 必须装在 {@code /usr/local}
 *       （默认 PATH 内），不能放 {@code /usr/local/node/bin} 这类自定义目录。</li>
 *   <li>Android 文件系统禁止硬链接，因此 rootfs 镜像打包时用 {@code tar --hard-dereference}
 *       把硬链接降级为副本。</li>
 *   <li>dsh ≥ 0.1.5 的 web 后端要求 cookie 鉴权，见 {@link DshAuth}。</li>
 * </ol>
 */
public final class ProrootEnv {

    private ProrootEnv() {
    }

    /** APP 内 dsh 后端监听端口（与 Termux 环境的 3080 隔离，避免冲突）。 */
    public static final int DSH_PORT = 3081;
    /** dsh 后端绑定地址（仅本机回环）。 */
    public static final String DSH_HOST = "127.0.0.1";
    /** 前端使用的后端基址。 */
    public static final String BASE_URL = "http://" + DSH_HOST + ":" + DSH_PORT;

    /** 打包进 assets 的 rootfs 镜像文件名。 */
    public static final String ROOTFS_ASSET = "rootfs.tar.xz";
    /** 镜像自带的版本标记文件（相对 rootfs 根）。 */
    public static final String IMAGE_MARKER = ".aptuidsh-image";
    /** 安装完成标记（放在 rootfs 之外，避免被镜像覆盖）。 */
    public static final String INSTALL_MARKER = ".aptuidsh-installed";

    /** guest 内 dsh 的家目录。 */
    public static final String GUEST_HOME = "/root";
    /** guest 内 dsh 的 DSH_HOME。 */
    public static final String GUEST_DSH_HOME = "/root/.dsh";
    /** guest 内默认工作区（绑定手机存储后即为 /sdcard/APTUIDSH，见 {@link #GUEST_SDCARD}）。 */
    public static final String GUEST_SDCARD = "/sdcard";
    /** 手机共享存储挂载点（host 侧）。 */
    public static final String HOST_SDCARD = "/storage/emulated/0";
    /** 默认工作区（guest 侧）。 */
    public static final String GUEST_WORKSPACE = GUEST_SDCARD + "/APTUIDSH";

    /** proroot 启动器文件名。 */
    public static final String PROROOT_LAUNCHER = "libproroot.so";
    /** proroot 运行时需要的全部 .so（必须与启动器同目录，启动器靠 /proc/self/exe 自动发现）。 */
    public static final String[] PROROOT_LIBS = {
            "libproroot.so",
            "libproroot-runtime.so",
            "libproroot-linker.so",
            "libproroot-bridge.so",
            "libproroot-stub-loader.so",
    };

    /** guest 内 PATH（与 Ubuntu 登录默认值一致；Node 装在 /usr/local/bin 因而可达）。 */
    public static final String GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";

    // ------------------------------------------------------------------ 路径

    /** proroot 启动器绝对路径（nativeLibraryDir 内，唯一可 exec 的位置）。 */
    public static String launcherPath(Context ctx) {
        return new File(ctx.getApplicationInfo().nativeLibraryDir, PROROOT_LAUNCHER).getAbsolutePath();
    }

    /** rootfs 根目录。 */
    public static File rootfsDir(Context ctx) {
        return new File(ctx.getFilesDir(), "rootfs");
    }

    /** proroot 运行时临时目录（必须可写，PROROOT_TMP_DIR）。 */
    public static File tmpDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "proroot-tmp");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** 日志目录。 */
    public static File logsDir(Context ctx) {
        File d = new File(ctx.getFilesDir(), "logs");
        if (!d.exists()) {
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
        }
        return d;
    }

    /** dsh 后端运行日志文件。 */
    public static File backendLogFile(Context ctx) {
        return new File(logsDir(ctx), "dsh.log");
    }

    /** dsh 子进程 PID 文件（guest 路径）。 */
    public static final String GUEST_PID_FILE = "/root/.aptuidsh/dsh.pid";

    /** 安装完成标记文件。 */
    public static File installMarker(Context ctx) {
        return new File(ctx.getFilesDir(), INSTALL_MARKER);
    }

    /** 镜像是否已解压安装。 */
    public static boolean isInstalled(Context ctx) {
        if (!installMarker(ctx).exists()) return false;
        File root = rootfsDir(ctx);
        return new File(root, "bin/sh").exists()
                && new File(root, "usr/local/bin/node").exists()
                && new File(root, "usr/local/bin/dsh").exists();
    }

    // ------------------------------------------------------------------ DNS

    /**
     * 把 Android 当前网络的 DNS 服务器写入 guest 的 /etc/resolv.conf。
     *
     * <p>guest 与宿主共用网络栈但有自己的 /etc，因此必须显式写入可用的 DNS；
     * 取不到时退回公共 DNS。
     */
    public static void syncResolvConf(Context ctx) {
        List<String> servers = new ArrayList<>();
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network active = cm.getActiveNetwork();
                LinkProperties lp = active != null ? cm.getLinkProperties(active) : null;
                if (lp != null) {
                    for (InetAddress addr : lp.getDnsServers()) {
                        String host = addr.getHostAddress();
                        if (host != null && !host.isEmpty() && !host.contains(":")) {
                            servers.add(host);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 无网络或权限不足：走公共 DNS 兜底
        }
        if (servers.isEmpty()) {
            servers.add("223.5.5.5");
            servers.add("114.114.114.114");
        }

        StringBuilder sb = new StringBuilder();
        for (String s : servers) {
            sb.append("nameserver ").append(s).append('\n');
        }
        sb.append("options timeout:2 attempts:3\n");

        File etc = new File(rootfsDir(ctx), "etc");
        //noinspection ResultOfMethodCallIgnored
        etc.mkdirs();
        File resolv = new File(etc, "resolv.conf");
        try (FileOutputStream out = new FileOutputStream(resolv, false)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // 写失败不致命：guest 内已有兜底 resolv.conf
        }
    }

    /** 人类可读的镜像版本信息（读 rootfs/.aptuidsh-image，读不到返回 null）。 */
    public static String imageInfo(Context ctx) {
        File f = new File(rootfsDir(ctx), IMAGE_MARKER);
        if (!f.exists()) return null;
        try {
            byte[] buf = new byte[(int) Math.min(f.length(), 4096)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                return new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            }
        } catch (IOException e) {
            return null;
        }
    }
}
