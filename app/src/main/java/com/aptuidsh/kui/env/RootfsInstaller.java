package com.aptuidsh.kui.env;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * rootfs 镜像安装器：把 {@code assets/rootfs.tar.xz} 展开为可运行的 guest 根文件系统。
 *
 * <h3>为什么用 /system/bin/tar</h3>
 * 镜像里含 3 万+ 文件、大量长路径与符号链接（Ubuntu 的 {@code /bin -> usr/bin} 等），
 * 且不能有硬链接。Android 自带 toybox 的 {@code tar -xJf} 已实测可正确处理 xz 压缩、
 * GNU 长名与符号链接，10 秒左右完成解压，比在 Java 里手写 tar 解析器可靠得多。
 *
 * <h3>安装流程</h3>
 * <ol>
 *   <li>assets → {@code filesDir/rootfs.tar.xz}（按字节上报进度）</li>
 *   <li>清空上次残留的 rootfs（无标记视为脏）</li>
 *   <li>{@code tar -xJf} 解压到 {@code filesDir/rootfs}</li>
 *   <li>校验关键文件 + 补建目录 + 写安装标记</li>
 * </ol>
 */
public final class RootfsInstaller {

    private static final String TAG = "AptuDshInstall";

    private RootfsInstaller() {
    }

    /** 安装进度回调（在调用线程触发，UI 自行切主线程）。 */
    public interface Progress {
        /**
         * @param stage   当前阶段的可读描述
         * @param percent 0..100；负值表示"不确定进度"
         */
        void onStage(String stage, int percent);
    }

    /** 安装期间的取消标记。 */
    private static volatile boolean cancelled;

    /** 请求取消当前安装。 */
    public static void cancel() {
        cancelled = true;
    }

    /** 安装（阻塞；请在后台线程调用）。已安装则直接返回。 */
    public static void install(Context ctx, Progress progress) throws IOException {
        cancelled = false;
        File root = ProrootEnv.rootfsDir(ctx);
        EnvLog.i("安装开始：target=" + root.getAbsolutePath()
                + "，可用空间=" + (root.getParentFile() == null ? "?" :
                (root.getParentFile().getUsableSpace() >> 20) + "MB"));
        File marker = ProrootEnv.installMarker(ctx);

        if (ProrootEnv.isInstalled(ctx)) {
            EnvLog.i("环境已安装，跳过");
            report(progress, "环境已就绪", 100);
            return;
        }

        // 脏残留清理：标记缺失说明上次安装未完成
        if (root.exists()) {
            report(progress, "清理上次未完成的安装…", 2);
            deleteRecursively(root);
        }
        //noinspection ResultOfMethodCallIgnored
        marker.delete();

        // 归档放 filesDir 而不是 cacheDir：cache 在存储紧张时会被系统回收，
        // 若在解压途中被清掉会导致 rootfs 半成品（filesDir 不可被系统回收）。
        File archive = new File(ctx.getFilesDir(), ProrootEnv.ROOTFS_ASSET);
        long total = assetSize(ctx);
        EnvLog.i("读取内置镜像 assets/" + ProrootEnv.ROOTFS_ASSET
                + "，报告大小=" + (total >> 20) + "MB -> " + archive.getAbsolutePath());
        if (total <= 0) {
            EnvLog.w("assetSize 返回 " + total + "，进度条将退化为不确定态（不影响安装）");
        }
        long copied = 0;
        report(progress, "释放内置运行环境…", 3);
        try (InputStream in = ctx.getAssets().open(ProrootEnv.ROOTFS_ASSET, AssetManager.ACCESS_STREAMING);
             FileOutputStream out = new FileOutputStream(archive, false)) {
            byte[] buf = new byte[1 << 16];
            int n;
            long lastPct = -1;
            while ((n = in.read(buf)) != -1) {
                checkCancelled();
                out.write(buf, 0, n);
                copied += n;
                if (total > 0) {
                    int pct = 3 + (int) (copied * 32 / total); // 3% → 35%
                    if (pct != lastPct) {
                        lastPct = pct;
                        report(progress, "释放内置运行环境 " + (copied >> 20) + "/" + (total >> 20) + " MB", pct);
                    }
                } else {
                    // 尺寸未知时也要有反馈，否则界面看起来像卡死
                    int mb = (int) (copied >> 20);
                    if (mb != lastPct) {
                        lastPct = mb;
                        report(progress, "释放内置运行环境 " + mb + " MB…", -1);
                    }
                }
            }
            out.flush();
        }

        checkCancelled();
        EnvLog.i("镜像释放完成：" + (archive.length() >> 20) + "MB 已落盘");
        report(progress, "正在展开 Linux 根文件系统（约 3 万个文件）…", 38);
        //noinspection ResultOfMethodCallIgnored
        root.mkdirs();
        runTarExtract(archive, root, progress);
        checkCancelled();

        report(progress, "校验运行环境…", 94);
        verifyLayout(root);

        // 补建 guest 运行目录
        //noinspection ResultOfMethodCallIgnored
        new File(root, "root/.dsh").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(root, "root/workspace").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(root, "root/.aptuidsh").mkdirs();
        //noinspection ResultOfMethodCallIgnored
        new File(root, "tmp").mkdirs();

        ProrootEnv.syncResolvConf(ctx);

        // 安装标记里必须写进【本份镜像的指纹】：
        // 覆盖安装 APK 时 Android 不会碰 filesDir 里已解压的 rootfs，
        // 只有靠指纹比对（见 ProrootEnv.isInstalled）才知道设备上这份是不是当前 APK 内置的那份。
        // 注意原先的写法是「createNewFile() 成功就不写内容」→ 标记是个空文件、没有指纹，
        // 导致升级内置 dsh 后环境永远不更新。这里统一改成总是覆盖写入。
        try (FileOutputStream fos = new FileOutputStream(marker, false)) {
            String fp = ProrootEnv.bundledImageFingerprint(ctx);
            StringBuilder sb = new StringBuilder("aptuidsh\n");
            if (fp != null) {
                sb.append(ProrootEnv.FINGERPRINT_PREFIX).append(fp).append('\n');
            } else {
                EnvLog.w("算不出内置镜像指纹，安装标记将不含 image= 行（下次启动会再重装一次）");
            }
            sb.append("installedAt=").append(System.currentTimeMillis()).append('\n');
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IOException("无法写入安装标记: " + e.getMessage(), e);
        }

        //noinspection ResultOfMethodCallIgnored
        archive.delete();
        EnvLog.i("安装完成：rootfs=" + root.getAbsolutePath());
        report(progress, "环境就绪", 100);
    }

    /** 删除已安装的 rootfs（释放空间）。 */
    public static void uninstall(Context ctx) {
        deleteRecursively(ProrootEnv.rootfsDir(ctx));
        //noinspection ResultOfMethodCallIgnored
        ProrootEnv.installMarker(ctx).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getFilesDir(), ProrootEnv.ROOTFS_ASSET).delete();
    }

    /** 已安装环境的磁盘占用（字节）。 */
    public static long installedBytes(Context ctx) {
        return dirSize(ProrootEnv.rootfsDir(ctx));
    }

    // ------------------------------------------------------------------ 内部

    private static void runTarExtract(File archive, File root, Progress progress) throws IOException {
        // 与 Termux 端验证过的调用方式完全一致：工作目录设为 rootfs，解压归档到当前目录
        // 镜像内容是 gzip 压缩的 tar，但文件名刻意用中性的 .img：
        // AGP 对以 .gz 结尾的 asset 会在构建期**自动解压**（实测 APK 里变成了 553MB 的
        // rootfs.tar，APK 从 137MB 涨到 166MB），换后缀即可绕开该行为。
        //
        // 必须用 gzip(-z) 而不是 xz(-J)：Android 的 toybox tar 对 xz 是靠 exec 外部 xz 实现的，
        // 而系统里没有 xz —— 会报 "tar: exec xz: No such file or directory"，
        // 而且 toybox 在这种情况下**仍然返回退出码 0**。gzip 是 toybox 内置实现，零外部依赖。
        EnvLog.i("执行解压: /system/bin/tar -xzf " + archive.getAbsolutePath()
                + " (cwd=" + root.getAbsolutePath() + ")");
        ProcessBuilder pb = new ProcessBuilder(
                "/system/bin/tar", "-xzf", archive.getAbsolutePath());
        pb.directory(root);
        pb.redirectErrorStream(true);
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            EnvLog.e("无法启动 /system/bin/tar（Android 缺少 toybox tar？）", e);
            throw e;
        }

        Thread poller = new Thread(() -> {
            int pct = 40;
            try {
                while (p.isAlive() && pct < 92) {
                    Thread.sleep(1200);
                    pct += 4;
                    report(progress, "正在展开 Linux 根文件系统…", pct);
                }
            } catch (InterruptedException ignored) {
            }
        }, "rootfs-extract-progress");
        poller.setDaemon(true);
        poller.start();

        StringBuilder tail = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            int lines = 0;
            while ((line = r.readLine()) != null) {
                if (lines++ < 40) {
                    tail.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // 读流失败不影响退出码判定
        }

        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroy();
            throw new IOException("解压被中断");
        }
        String tarOut = tail.toString();
        EnvLog.i("tar 退出码=" + code + (tarOut.isEmpty() ? "" : "，输出：" + tarOut.trim()));
        // toybox 在"解压器缺失"这类失败上会返回 0，因此不能只看退出码，必须看输出
        boolean tarReportedError = tarOut.contains("tar: ");
        if (tarReportedError) {
            EnvLog.e("tar 报告了错误（退出码却是 " + code + "）：" + tarOut.trim(), null);
        }
        if (code != 0 || tarReportedError) {
            throw new IOException("解压 rootfs 失败 (tar exit=" + code + ")"
                    + (tarOut.isEmpty() ? "" : "\n" + tarOut));
        }
    }

    private static void verifyLayout(File root) throws IOException {
        String[] required = {
                "bin/sh",
                "usr/local/bin/node",
                "usr/local/bin/dsh",
                "usr/local/lib/node_modules/@deepseek-ai/dsh/lib/bin.js",
                "etc/os-release",
        };
        for (String rel : required) {
            if (!new File(root, rel).exists()) {
                EnvLog.e("rootfs 校验失败：缺少 " + rel, null);
                throw new IOException("rootfs 不完整，缺少 " + rel);
            }
        }
    }

    /**
     * 读取内置镜像的未压缩尺寸，用于进度条。
     *
     * <p><b>为什么直接从 APK 的 zip 条目读</b>：首版写成
     * {@code ctx.getAssets().open(name, ACCESS_UNKNOWN).available()}。
     * 对未压缩资产，{@code ACCESS_UNKNOWN} 可能让 AssetManager 把整个 77MB
     * 资产映射甚至读入内存；而 {@code OutOfMemoryError} <b>不是 IOException</b>，
     * 会直接击穿上层 catch 把进程打死——现象就是「点了安装没反应、随后 APP 消失」。
     * 读 zip 条目零内存且精确。
     */
    private static long assetSize(Context ctx) {
        try (java.util.zip.ZipFile zip =
                     new java.util.zip.ZipFile(ctx.getApplicationInfo().sourceDir)) {
            java.util.zip.ZipEntry e = zip.getEntry("assets/" + ProrootEnv.ROOTFS_ASSET);
            if (e != null) {
                return e.getSize();
            }
            EnvLog.w("APK 内找不到 assets/" + ProrootEnv.ROOTFS_ASSET);
        } catch (IOException ex) {
            EnvLog.e("读取 APK 内 asset 尺寸失败", ex);
        }
        return -1L;
    }

    private static void checkCancelled() throws IOException {
        if (cancelled) {
            throw new IOException("安装已取消");
        }
    }

    private static void report(Progress p, String stage, int pct) {
        if (p != null) {
            p.onStage(stage, pct);
        }
    }

    private static void deleteRecursively(File f) {
        if (f == null || !f.exists()) return;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursively(k);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static long dirSize(File f) {
        if (f == null || !f.exists()) return 0L;
        if (f.isFile()) return f.length();
        long sum = 0;
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) {
                sum += dirSize(k);
            }
        }
        return sum;
    }
}
