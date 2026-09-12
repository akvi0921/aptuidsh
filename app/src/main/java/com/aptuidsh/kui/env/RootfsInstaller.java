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
        File marker = ProrootEnv.installMarker(ctx);

        if (ProrootEnv.isInstalled(ctx)) {
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

        File archive = new File(ctx.getCacheDir(), ProrootEnv.ROOTFS_ASSET);
        long total = assetSize(ctx);
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
                }
            }
            out.flush();
        }

        checkCancelled();
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

        try {
            boolean ok = marker.createNewFile();
            if (!ok) {
                try (FileOutputStream fos = new FileOutputStream(marker, false)) {
                    fos.write("aptuidsh\n".getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException e) {
            throw new IOException("无法写入安装标记: " + e.getMessage(), e);
        }

        //noinspection ResultOfMethodCallIgnored
        archive.delete();
        report(progress, "环境就绪", 100);
        Log.i(TAG, "rootfs installed at " + root.getAbsolutePath());
    }

    /** 删除已安装的 rootfs（释放空间）。 */
    public static void uninstall(Context ctx) {
        deleteRecursively(ProrootEnv.rootfsDir(ctx));
        //noinspection ResultOfMethodCallIgnored
        ProrootEnv.installMarker(ctx).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(ctx.getCacheDir(), ProrootEnv.ROOTFS_ASSET).delete();
    }

    /** 已安装环境的磁盘占用（字节）。 */
    public static long installedBytes(Context ctx) {
        return dirSize(ProrootEnv.rootfsDir(ctx));
    }

    // ------------------------------------------------------------------ 内部

    private static void runTarExtract(File archive, File root, Progress progress) throws IOException {
        // 与 Termux 端验证过的调用方式完全一致：工作目录设为 rootfs，解压归档到当前目录
        ProcessBuilder pb = new ProcessBuilder(
                "/system/bin/tar", "-xJf", archive.getAbsolutePath());
        pb.directory(root);
        pb.redirectErrorStream(true);
        Process p = pb.start();

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
        if (code != 0) {
            throw new IOException("解压 rootfs 失败 (tar exit=" + code + ")"
                    + (tail.length() > 0 ? "\n" + tail : ""));
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
                throw new IOException("rootfs 不完整，缺少 " + rel);
            }
        }
    }

    private static long assetSize(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ProrootEnv.ROOTFS_ASSET,
                AssetManager.ACCESS_UNKNOWN)) {
            return in.available();
        } catch (IOException e) {
            return -1;
        }
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
