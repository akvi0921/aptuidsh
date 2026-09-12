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

    // ------------------------------------------------------------------ 事实核查

    /**
     * 一次性列出环境的关键事实，供控制台直接展示。
     *
     * <p>「点了没反应」这类问题最难查的地方在于：用户和开发者都看不到任何中间状态。
     * 这里把最关键的几项直接摆出来——启动器在不在、能不能执行、rootfs 解没解开、
     * 磁盘够不够——不需要任何点击，打开控制台就能判断卡在哪一步。
     */
    public static java.util.List<String> diagnose(Context ctx) {
        java.util.List<String> out = new java.util.ArrayList<>();
        File launcher = new File(launcherPath(ctx));
        out.add("nativeLibraryDir = " + ctx.getApplicationInfo().nativeLibraryDir);
        out.add("启动器存在 = " + launcher.exists()
                + "，可读 = " + launcher.canRead()
                + "，可执行 = " + launcher.canExecute()
                + "，大小 = " + launcher.length());
        File libDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        String[] libs = libDir.list();
        int libCount = libs == null ? -1 : libs.length;
        out.add("nativeLibraryDir 条目数 = " + libCount);
        for (String lib : PROROOT_LIBS) {
            File f = new File(libDir, lib);
            out.add("  " + lib + " 存在=" + f.exists() + " 可执行=" + f.canExecute());
        }
        File root = rootfsDir(ctx);
        out.add("rootfs 目录 = " + root.getAbsolutePath() + "，存在 = " + root.exists());
        out.add("安装标记 = " + installMarker(ctx).exists());
        out.add("关键文件: bin/sh=" + new File(root, "bin/sh").exists()
                + " node=" + new File(root, "usr/local/bin/node").exists()
                + " dsh=" + new File(root, "usr/local/bin/dsh").exists());
        long free = ctx.getFilesDir().getUsableSpace();
        out.add("filesDir 可用空间 = " + (free >> 20) + "MB（安装约需 670MB）");
        out.add("需安装 = " + !isInstalled(ctx));
        return out;
    }

    // ------------------------------------------------------------------ 自检

    /** 自检结果。 */
    public static final class Smoke {
        /** 是否通过。 */
        public final boolean ok;
        /** 面向用户的结论。 */
        public final String summary;
        /** 原始输出（诊断用）。 */
        public final String output;

        Smoke(boolean ok, String summary, String output) {
            this.ok = ok;
            this.summary = summary;
            this.output = output;
        }
    }

    /**
     * 启动器自检：只 exec {@code libproroot.so}（不带参数，它会打印用法后退出）。
     *
     * <p>这是整个方案里唯一无法在开发机上验证的环节——<b>Android 10+ 只允许 exec
     * nativeLibraryDir 里的文件</b>（应用数据目录被 SELinux 禁止 exec）。带参数跑一次
     * 自检就能在解压 585MB 之前就把这类环境问题暴露出来，而不是等安装完才发现启动失败。
     */
    public static Smoke smokeTestLauncher(Context ctx) {
        File launcher = new File(launcherPath(ctx));
        if (!launcher.exists()) {
            EnvLog.e("启动器不存在: " + launcher, null);
            return new Smoke(false, "缺少启动器 " + launcher.getAbsolutePath(), "");
        }
        EnvLog.i("启动器: " + launcher + "，可读=" + launcher.canRead()
                + " 可执行=" + launcher.canExecute() + " 大小=" + launcher.length());
        if (!launcher.canExecute()) {
            EnvLog.e("启动器不可执行（Android 10+ 必须从 nativeLibraryDir 执行）: " + launcher, null);
            return new Smoke(false, "启动器不可执行（权限位未置位）: " + launcher, "");
        }
        return runCaptured(new String[]{launcher.getAbsolutePath()}, null, 12_000L,
                "启动器可执行", "启动器无法执行");
    }

    /**
     * Guest 自检：真正进 rootfs 跑一条命令，验证「proroot + rootfs + 动态链接」整条链。
     *
     * <p>等价于 {@code libproroot.so -r <rootfs> -0 --link2symlink /bin/sh -c '…'}。
     */
    public static Smoke smokeTestGuest(Context ctx) {
        if (!isInstalled(ctx)) {
            EnvLog.w("guest 自检跳过：尚未安装");
            return new Smoke(false, "尚未安装内置环境", "");
        }
        File launcher = new File(launcherPath(ctx));
        String[] cmd = {
                launcher.getAbsolutePath(),
                "-r", rootfsDir(ctx).getAbsolutePath(),
                "-0", "--link2symlink",
                "-w", GUEST_HOME,
                "/bin/sh", "-c",
                "echo APTUIDSH-SMOKE-OK; uname -m; /usr/local/bin/node -v; /usr/local/bin/dsh --version",
        };
        return runCaptured(cmd, ctx, 30_000L, "guest 自检通过", "guest 自检失败");
    }

    /** 以干净环境执行命令并捕获输出（与后端启动使用同一套环境规则）。 */
    private static Smoke runCaptured(String[] cmd, Context ctx, long timeoutMs,
                                     String okSummary, String failSummary) {
        Process p = null;
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().put("HOME", GUEST_HOME);
        pb.environment().put("PATH", GUEST_PATH);
        pb.environment().put("TERM", "xterm");
        pb.environment().put("LANG", "C.UTF-8");
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("ANDROID_ROOT", "/system");
        pb.environment().put("ANDROID_DATA", "/data");
        if (ctx != null) {
            pb.environment().put("PROROOT_TMP_DIR", tmpDir(ctx).getAbsolutePath());
            pb.directory(rootfsDir(ctx));
        }
        pb.redirectErrorStream(true);
        try {
            p = pb.start();
            final Process proc = p;
            StringBuilder sb = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (sb.length() < 8192) sb.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                }
            }, "proroot-smoke");
            reader.setDaemon(true);
            reader.start();

            // 注意：Process.waitFor(long, TimeUnit) 需要 API 26，而本 APP 的 minSdk 是 24，
            // 因此这里用轮询实现，避免在旧设备上 NoSuchMethodError。
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try {
                    p.exitValue();
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    Thread.sleep(120);
                }
            }
            boolean finished;
            try {
                p.exitValue();
                finished = true;
            } catch (IllegalThreadStateException stillRunning) {
                finished = false;
            }
            if (!finished) {
                p.destroyForcibly();
                EnvLog.w("启动器超时无响应: " + java.util.Arrays.toString(cmd));
                return new Smoke(false, "启动器超时无响应（被系统拦截？）", sb.toString());
            }
            reader.join(1500);
            int code = p.exitValue();
            String out = sb.toString();
            boolean ok = code == 0 || out.contains("APTUIDSH-SMOKE-OK")
                    || out.contains("Usage:");
            return new Smoke(ok, (ok ? okSummary : failSummary) + "（exit=" + code + "）", out);
        } catch (IOException e) {
            // 这一步最典型的失败是 EACCES/EPERM：Android 10+ 禁止在应用数据目录 exec 文件
            String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Smoke(false, "无法执行 proroot 启动器：" + msg, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Smoke(false, "自检被中断", "");
        } finally {
            if (p != null) p.destroy();
        }
    }
}
