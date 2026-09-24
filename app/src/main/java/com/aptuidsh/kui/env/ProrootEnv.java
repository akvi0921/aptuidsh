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
    public static final String ROOTFS_ASSET = "rootfs.img";
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

    /**
     * 当前 APK 里**内置镜像**的指纹（不解压就能算出来）。
     *
     * <p>取 APK zip 表里 {@code assets/rootfs.img} 的「未压缩长度 + CRC32」——
     * 这两项都在 zip 中央目录里，读一次即可，**不需要解压那 117MB**。
     * 镜像内容一变指纹就变，因此可以用来判断「设备上已解压的环境是不是当前 APK 这一份」。
     *
     * @return 形如 {@code 122211356-7031cecb}；取不到返回 null（调用方按「无法判断」宽松处理）
     */
    public static String bundledImageFingerprint(Context ctx) {
        String cached = cachedFingerprint;
        if (cached != null) return cached.isEmpty() ? null : cached;
        String fp = null;
        try {
            String apk = ctx.getApplicationInfo().sourceDir;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
                java.util.zip.ZipEntry e = zip.getEntry("assets/" + ROOTFS_ASSET);
                if (e != null && e.getSize() > 0) {
                    fp = e.getSize() + "-" + Long.toHexString(e.getCrc());
                }
            }
        } catch (Throwable t) {
            EnvLog.w("读取 APK 内镜像指纹失败（将退回长度判断）: " + t);
        }
        if (fp == null) {
            // 后备：拿得到长度也比完全没有判断强（CRC 拿不到）
            try (android.content.res.AssetFileDescriptor fd =
                         ctx.getAssets().openFd(ROOTFS_ASSET)) {
                if (fd != null && fd.getLength() > 0) fp = fd.getLength() + "-len";
            } catch (Throwable ignored) {
            }
        }
        // 空串表示「已经算过但是拿不到」，避免每次状态轮询都重试
        cachedFingerprint = fp == null ? "" : fp;
        return fp;
    }

    private static volatile String cachedFingerprint = null;

    /** 设备上已解压环境的指纹（安装时写进安装标记）；老版本安装的标记没有这段 → 返回 null。 */
    public static String installedImageFingerprint(Context ctx) {
        File m = installMarker(ctx);
        if (!m.exists()) return null;
        try {
            byte[] buf = new byte[(int) Math.min(m.length(), 4096)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(m)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                for (String line : new String(buf, 0, n, StandardCharsets.UTF_8).split("\n")) {
                    String s = line.trim();
                    if (s.startsWith(FINGERPRINT_PREFIX)) {
                        return s.substring(FINGERPRINT_PREFIX.length()).trim();
                    }
                }
            }
        } catch (IOException e) {
            EnvLog.w("读取安装标记失败: " + e);
        }
        return null;
    }

    /** 安装标记里指纹那一行的前缀。 */
    public static final String FINGERPRINT_PREFIX = "image=";

    /**
     * 设备上「物理上装了一份环境」——只看标记与关键文件在不在，**不看指纹**。
     *
     * <p>与 {@link #isInstalled} 的分工：本方法回答「有没有」，{@code isInstalled} 回答
     * 「有、而且是当前 APK 这一份」。升级场景下两者会分叉（有，但是旧的），
     * 界面要能把这个中间态讲清楚。
     */
    public static boolean isPhysicallyInstalled(Context ctx) {
        if (!installMarker(ctx).exists()) return false;
        File root = rootfsDir(ctx);
        return new File(root, "bin/sh").exists()
                && new File(root, "usr/local/bin/node").exists()
                && new File(root, "usr/local/bin/dsh").exists();
    }

    /**
     * 设备上已安装的环境是否**落后于当前 APK 内置的镜像**（即需要重装环境）。
     *
     * <p>为什么必须有这个判断：覆盖安装 APK 时，Android 只替换 APK，不会碰
     * {@code filesDir} 里已解压的那份 rootfs。原先的 {@code isInstalled} 只看
     * 「标记文件在不在 + 几个关键文件在不在」，于是**升级内置 dsh 后环境永远不更新**，
     * 界面上一直显示旧版本（实测踩到：APK 已内置 0.1.7-rc.1，界面仍显示 0.1.5-rc.1）。
     *
     * <p>宽松策略：算不出当前指纹时不下「需要更新」的结论，避免误删好环境；
     * 老版本装的环境没有指纹行 → 视为需要更新（这正是升级路径）。
     */
    public static boolean needsImageUpdate(Context ctx) {
        if (!isPhysicallyInstalled(ctx)) return false;    // 没装就是「要装」，不是「要更新」
        String bundled = bundledImageFingerprint(ctx);
        if (bundled == null) return false;                // 判断不了就别乱动
        String installed = installedImageFingerprint(ctx);
        return !bundled.equals(installed);
    }

    /**
     * 镜像是否已解压安装，且**是当前 APK 内置的那一份**。
     *
     * <p>加入指纹比对之后，覆盖安装新 APK 会自然走到「未安装 → 重新解压」的老路径上，
     * 无需用户手动点「重装环境」。
     */
    public static boolean isInstalled(Context ctx) {
        if (!isPhysicallyInstalled(ctx)) return false;
        String bundled = bundledImageFingerprint(ctx);
        if (bundled == null) return true;                 // 判断不了就沿用老口径，别误删
        String installed = installedImageFingerprint(ctx);
        if (installed == null) return false;              // 老标记没有指纹 → 需要重装一次
        return bundled.equals(installed);
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

    /** 内置 dsh 版本标记 asset（由 tools/build-rootfs.sh 一并产出）。 */
    public static final String VERSION_ASSET = "image-version.txt";

    /**
     * 当前 APK **内置镜像里的 dsh 版本**（读 assets/image-version.txt）。
     *
     * <p>镜像是 xz 压缩的，不解压就读不到里面的 {@code .aptuidsh-image}，
     * 所以构建脚本额外产出一个纯文本版本标记随包携带 —— 界面因此能直接显示
     * 「已装 0.1.5-rc.1 → 内置 0.1.7-rc.1」，而不是让人对着一个没变过的版本号发懵。
     *
     * @return 形如 {@code 0.1.7-rc.1}；读不到返回 null
     */
    public static String bundledDshVersion(Context ctx) {
        try (java.io.InputStream in = ctx.getAssets().open(VERSION_ASSET)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            if (n <= 0) return null;
            String s = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
            return s.isEmpty() ? null : s;
        } catch (IOException e) {
            return null;
        }
    }

    /** 已安装镜像里的 dsh 版本（解析 {@code .aptuidsh-image} 的 {@code dsh=} 行）。 */
    public static String installedDshVersion(Context ctx) {
        String info = imageInfo(ctx);
        if (info == null) return null;
        for (String line : info.split("\n")) {
            String s = line.trim();
            if (s.startsWith("dsh=")) return s.substring(4).trim();
        }
        return null;
    }

    // ------------------------------------------------------------------ 启动方式

    /**
     * 是否改用 {@code /system/bin/linker64} 加载启动器。
     *
     * <h3>为什么需要这条后备路径</h3>
     * Android 10+ 默认禁止应用执行自己数据目录里的文件；常规做法是把 proroot 放进
     * {@code jniLibs} 由系统解包到 {@code nativeLibraryDir}（唯一允许 exec 的位置）。
     * 但部分 ROM（实测关注华为 EMUI）对该路径也做了限制，一旦被拒，整个方案就死了。
     *
     * <p>后备思路：<b>不去 execve 我们自己的文件</b>，而是执行系统二进制
     * {@code /system/bin/linker64}，把 proroot 启动器当作参数交给它加载。
     * 内核只对 linker64 做 execve（系统路径恒定允许），我们的文件只是被
     * {@code open()+mmap()} 读取——只要可读即可，不需要可执行。
     *
     * <p>实测（本机验证）：链路完全可用，guest 能正常跑起来并输出 dsh 版本。
     * 唯一前提是显式给出 proroot 的运行时库路径——启动器本来靠 {@code /proc/self/exe}
     * 的同级目录自动发现，而经 linker64 启动时那个位置指向 linker 自己，
     * 必须用 {@code PROROOT_*} 环境变量覆盖。
     */
    private static volatile boolean useLinker64;

    /** 是否处于 linker64 后备模式。 */
    public static boolean isLinkerMode() {
        return useLinker64;
    }

    /** linker64 后备模式下启动器与运行时库的存放目录（只要可读）。 */
    public static File linkerLibDir(Context ctx) {
        return new File(ctx.getFilesDir(), "proroot-libs");
    }

    private static String linkerPath() {
        for (String p : new String[]{"/system/bin/linker64",
                "/apex/com.android.runtime/bin/linker64"}) {
            if (new File(p).exists()) return p;
        }
        return "/system/bin/linker64";
    }

    /** 组装启动器命令行（自动带上当前模式的正确前缀）。 */
    public static String[] launcherArgv(Context ctx) {
        if (useLinker64) {
            return new String[]{linkerPath(),
                    new File(linkerLibDir(ctx), PROROOT_LAUNCHER).getAbsolutePath()};
        }
        return new String[]{launcherPath(ctx)};
    }

    /** 把 5 个 .so 从 nativeLibraryDir 复制到可读目录（linker 模式只需可读）。 */
    private static void stageLinkerLibs(Context ctx) throws IOException {
        File dir = linkerLibDir(ctx);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File srcDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        for (String lib : PROROOT_LIBS) {
            File src = new File(srcDir, lib);
            File dst = new File(dir, lib);
            if (dst.exists() && dst.length() == src.length()) continue;
            if (!src.exists()) throw new IOException("缺少 " + lib);
            try (java.io.FileInputStream in = new java.io.FileInputStream(src);
                 java.io.FileOutputStream out = new java.io.FileOutputStream(dst, false)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }
        EnvLog.i("已暂存 proroot 运行时库到 " + dir.getAbsolutePath());
    }

    /** linker64 模式下注入 PROROOT_* 路径（否则启动器会去 /proc/self/exe 旁边找）。 */
    public static void applyProrootEnv(ProcessBuilder pb, Context ctx) {
        if (!useLinker64 || ctx == null) return;
        File d = linkerLibDir(ctx);
        pb.environment().put("PROROOT_LIB_PATH", new File(d, "libproroot-runtime.so").getAbsolutePath());
        pb.environment().put("PROROOT_TRAMPOLINE_PATH", new File(d, "libproroot-bridge.so").getAbsolutePath());
        pb.environment().put("PROROOT_LINKER_PATH", new File(d, "libproroot-linker.so").getAbsolutePath());
        pb.environment().put("PROROOT_STUB_LOADER", new File(d, "libproroot-stub-loader.so").getAbsolutePath());
        pb.environment().put("PROROOT_GUEST_EXE", new File(d, PROROOT_LAUNCHER).getAbsolutePath());
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
        out.add("启动方式 = " + (useLinker64 ? "linker64 后备" : "直接 exec(nativeLibraryDir)"));
        out.add("linker64 = " + linkerPath() + "，存在 = " + new File(linkerPath()).exists());
        return out;
    }

    // ------------------------------------------------------------------ 默认工作区

    /**
     * 返回默认工作区在 guest 内的路径。
     *
     * <p>优先用手机共享存储（用户能在文件管理器里看到）：宿主侧 {@code /storage/emulated/0/APTUIDSH}
     * 与 guest 侧 {@code /sdcard/APTUIDSH} 是同一目录（通过 {@code -b} 绑定）。
     * 若手机存储不可写（未授予「所有文件访问」等），回退到 guest 内部的 {@code /root/workspace}。
     */
    public static String defaultWorkspaceGuestPath(Context ctx) {
        try {
            File shared = new File(HOST_SDCARD, "APTUIDSH");
            if (shared.isDirectory() || shared.mkdirs()) {
                return GUEST_WORKSPACE;
            }
        } catch (Throwable ignored) {
        }
        return GUEST_HOME + "/workspace";
    }

    // ------------------------------------------------------------------ 崩溃报告

    /** 崩溃报告文件（由 AptuidshApp 的全局未捕获异常处理器写入，用户可直接取）。 */
    public static File crashFile(Context ctx) {
        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        return new File(dir, "crash.txt");
    }

    /** 读取崩溃报告文本；不存在返回 null。 */
    public static String crashText(Context ctx) {
        File f = crashFile(ctx);
        if (!f.exists()) return null;
        try {
            byte[] all = new byte[(int) Math.min(f.length(), 256 * 1024)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, n;
                while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
                return new String(all, 0, off, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 诊断报告

    /**
     * 把环境事实与最近日志写成一份「用户可直接发送」的报告文件。
     *
     * <p>写到 {@code getExternalFilesDir()}：**不需要任何权限**，用系统文件管理器即可打开，
     * 路径形如 {@code Android/data/com.aptuidsh.kui/files/env-report.txt}。
     * 真机出问题时，这份文件就是唯一的客观证据（没有 logcat 权限时尤其关键）。
     */
    public static File writeEnvReport(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("APTUIDSH 环境诊断报告\n");
        sb.append("生成时间: ").append(new java.util.Date()).append('\n');
        sb.append("设备: ").append(android.os.Build.MANUFACTURER).append(' ')
          .append(android.os.Build.MODEL).append(" / Android ")
          .append(android.os.Build.VERSION.RELEASE)
          .append(" (API ").append(android.os.Build.VERSION.SDK_INT).append(")\n");
        sb.append("ABI: ").append(java.util.Arrays.toString(android.os.Build.SUPPORTED_ABIS)).append('\n');
        try {
            sb.append("版本: ").append(ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName).append('\n');
        } catch (Throwable ignored) {
        }
        sb.append('\n').append("---- 环境事实核查 ----\n");
        for (String line : diagnose(ctx)) {
            sb.append(line).append('\n');
        }
        sb.append('\n').append("---- 启动器自检 ----\n");
        Smoke launcher = smokeTestLauncher(ctx);
        sb.append("ok=").append(launcher.ok).append(' ').append(launcher.summary).append('\n');
        sb.append(launcher.output).append('\n');
        if (isInstalled(ctx)) {
            Smoke guest = smokeTestGuest(ctx);
            sb.append('\n').append("---- guest 自检 ----\n");
            sb.append("ok=").append(guest.ok).append(' ').append(guest.summary).append('\n');
            sb.append(guest.output).append('\n');
        }
        sb.append('\n').append("---- 全过程日志 ----\n");
        for (String line : EnvLog.lines()) {
            sb.append(line).append('\n');
        }

        File dir = ctx.getExternalFilesDir(null);
        if (dir == null) dir = ctx.getFilesDir();
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        File out = new File(dir, "env-report.txt");
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out, false)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            EnvLog.i("诊断报告已写入 " + out.getAbsolutePath());
            return out;
        } catch (IOException e) {
            EnvLog.e("写诊断报告失败", e);
            return null;
        }
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

        // 第一步：常规方式（nativeLibraryDir 直接 exec）
        if (launcher.canExecute()) {
            Smoke direct = runCaptured(new String[]{launcher.getAbsolutePath()}, null, 12_000L,
                    "启动器可执行", "启动器无法执行");
            if (direct.ok) {
                useLinker64 = false;
                EnvLog.i("启动方式 = 直接 exec（nativeLibraryDir）");
                return direct;
            }
            EnvLog.w("直接 exec 失败：" + direct.summary + "，改用 linker64 后备路径");
        } else {
            EnvLog.w("nativeLibraryDir 中的启动器没有可执行权限，改用 linker64 后备路径");
        }

        // 第二步：linker64 后备（不使用 execve 我们的文件）
        try {
            stageLinkerLibs(ctx);
        } catch (IOException e) {
            EnvLog.e("暂存 proroot 运行时库失败", e);
            return new Smoke(false, "无法准备 linker64 后备运行时库：" + e.getMessage(), "");
        }
        useLinker64 = true;
        Smoke viaLinker = runCaptured(launcherArgv(ctx), null, 15_000L,
                "启动器可执行（linker64 后备）", "启动器无法执行（linker64 后备也失败）");
        EnvLog.i("启动方式 = linker64 后备，ok=" + viaLinker.ok);
        if (!viaLinker.ok) {
            useLinker64 = false;
        }
        return viaLinker;
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
        java.util.List<String> argv = new java.util.ArrayList<>();
        for (String a : launcherArgv(ctx)) argv.add(a);
        argv.add("-r");
        argv.add(rootfsDir(ctx).getAbsolutePath());
        argv.add("-0");
        argv.add("--link2symlink");
        argv.add("-w");
        argv.add(GUEST_HOME);
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add("echo APTUIDSH-SMOKE-OK; uname -m; /usr/local/bin/node -v; /usr/local/bin/dsh --version");
        return runCaptured(argv.toArray(new String[0]), ctx, 30_000L,
                "guest 自检通过", "guest 自检失败");
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
            applyProrootEnv(pb, ctx);
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
