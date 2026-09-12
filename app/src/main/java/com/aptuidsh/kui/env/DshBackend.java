package com.aptuidsh.kui.env;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置 dsh 后端的生命周期管理：在 proroot 里拉起 {@code dsh web --port 3081} 并守护它。
 *
 * <h3>启动链</h3>
 * <pre>
 * APP 进程
 *   └─ nativeLibraryDir/libproroot.so   （Android 10+ 唯一允许 exec 的位置）
 *        └─ /bin/sh -c '…'
 *             └─ exec /usr/local/bin/dsh web --host 127.0.0.1 --port 3081 --no-open
 * </pre>
 *
 * <h3>两个必须遵守的实测约束</h3>
 * <ol>
 *   <li><b>环境必须干净</b>：Android 宿主进程的环境变量（尤其各类 PREFIX/HOME）一旦透传进
 *       guest，会让 npm 与工具链把全局前缀解析到宿主路径上。这里用
 *       {@code ProcessBuilder.environment().clear()} 后只注入 Ubuntu 需要的最小集合。</li>
 *   <li><b>工作目录与 DSH_HOME 必须在 guest 内</b>，且工作区优先落在绑定的手机存储上。</li>
 * </ol>
 */
public final class DshBackend {

    private static final String TAG = "AptuDshBackend";

    /** 后端阶段。 */
    public enum Phase {
        /** rootfs 未安装。 */
        NOT_INSTALLED,
        /** 正在安装 rootfs。 */
        INSTALLING,
        /** 已安装但后端未运行。 */
        STOPPED,
        /** 正在启动。 */
        STARTING,
        /** 正在运行且 API 可用。 */
        RUNNING,
        /** 正在停止。 */
        STOPPING,
        /** 出错（message 含原因）。 */
        ERROR,
    }

    /** 对外状态快照（不可变）。 */
    public static final class Status {
        public final Phase phase;
        public final String message;
        public final boolean installed;
        public final boolean portAlive;
        public final long pid;
        public final int progressPercent;
        public final String imageInfo;

        Status(Phase phase, String message, boolean installed, boolean portAlive,
               long pid, int progressPercent, String imageInfo) {
            this.phase = phase;
            this.message = message;
            this.installed = installed;
            this.portAlive = portAlive;
            this.pid = pid;
            this.progressPercent = progressPercent;
            this.imageInfo = imageInfo;
        }

        public boolean running() {
            return phase == Phase.RUNNING;
        }
    }

    /** 状态变化监听。 */
    public interface Listener {
        void onStatus(Status status);

        /** 后端新增一行输出（可能非常频繁，UI 需自行节流）。 */
        void onLogLine(String line);
    }

    private static final DshBackend INSTANCE = new DshBackend();
    private static final int LOG_RING_LINES = 400;
    private static final long LOG_FILE_MAX_BYTES = 512 * 1024L;

    private static final Pattern TOKEN_PATTERN = Pattern.compile("token=([A-Za-z0-9_-]{16,})");

    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final ArrayDeque<String> logRing = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile Process process;
    private volatile Thread pumpThread;
    private volatile Phase phase = Phase.STOPPED;
    private volatile String message = "";
    private volatile long pid = -1L;
    private volatile boolean portAlive;
    private volatile int progressPercent = -1;
    private volatile boolean stopRequested;
    /** guest 自检是否已通过（每个进程只需验一次）。 */
    private volatile boolean guestVerified;

    private DshBackend() {
    }

    public static DshBackend get() {
        return INSTANCE;
    }

    // ------------------------------------------------------------------ 状态

    public Status status(Context ctx) {
        return new Status(phase, message, ProrootEnv.isInstalled(ctx), portAlive, pid,
                progressPercent, ProrootEnv.imageInfo(ctx));
    }

    public void addListener(Listener l) {
        if (l != null && !listeners.contains(l)) {
            listeners.add(l);
        }
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void publish(Context ctx) {
        Status s = status(ctx);
        main.post(() -> {
            for (Listener l : listeners) {
                try {
                    l.onStatus(s);
                } catch (Throwable t) {
                    Log.w(TAG, "listener failed: " + t);
                }
            }
        });
    }

    private void setPhase(Context ctx, Phase p, String msg) {
        phase = p;
        message = msg == null ? "" : msg;
        publish(ctx);
    }

    /** 由服务层在后台任务失败时调用，把错误暴露到界面上（此时不在主线程，不能直接 publish）。 */
    public void reportError(String msg) {
        message = msg == null ? "未知错误" : msg;
        phase = Phase.ERROR;
        EnvLog.e(msg, null);
    }

    /** 最近若干行后端输出（用于日志面板）。 */
    public List<String> recentLog() {
        synchronized (logRing) {
            return new ArrayList<>(logRing);
        }
    }

    // ------------------------------------------------------------------ 安装

    /** 安装内置 rootfs（阻塞，后台线程调用）。 */
    public boolean install(Context ctx) {
        try {
            return installInner(ctx);
        } catch (Throwable t) {
            EnvLog.e("环境安装出现未预期异常", t);
            setPhase(ctx, Phase.ERROR, "环境安装异常：" + t);
            return false;
        }
    }

    private boolean installInner(Context ctx) {
        EnvLog.i("== install() 进入 ==");
        if (ProrootEnv.isInstalled(ctx)) {
            setPhase(ctx, Phase.STOPPED, "环境已就绪");
            return true;
        }
        setPhase(ctx, Phase.INSTALLING, "正在安装内置 Linux 环境…");
        // 解压 585MB 之前先确认启动器能跑：Android 10+ 只允许 exec nativeLibraryDir 里的文件，
        // 一旦这条不成立，装完也是白装。
        ProrootEnv.Smoke launcherSmoke = ProrootEnv.smokeTestLauncher(ctx);
        EnvLog.i("启动器自检: ok=" + launcherSmoke.ok + " " + launcherSmoke.summary);
        if (!launcherSmoke.ok) {
            setPhase(ctx, Phase.ERROR, "无法执行内置 proroot 启动器：" + launcherSmoke.summary
                    + (launcherSmoke.output.isEmpty() ? "" : "\n" + launcherSmoke.output));
            return false;
        }
        try {
            RootfsInstaller.install(ctx, (stage, percent) -> {
                progressPercent = percent;
                message = stage;
                main.post(() -> {
                    Status s = status(ctx);
                    for (Listener l : listeners) {
                        try {
                            l.onStatus(s);
                        } catch (Throwable ignored) {
                        }
                    }
                });
            });
            progressPercent = -1;
            EnvLog.i("== install() 成功返回 ==");
            setPhase(ctx, Phase.STOPPED, "环境安装完成");
            return true;
        } catch (OutOfMemoryError oom) {
            // OOM 不是 Exception：首版只 catch IOException，导致它直接击穿协程打死进程
            progressPercent = -1;
            EnvLog.e("安装过程内存不足", oom);
            setPhase(ctx, Phase.ERROR, "内存不足，安装失败。请关闭其他应用后重试");
            return false;
        } catch (Throwable e) {
            progressPercent = -1;
            EnvLog.e("环境安装失败", e);
            setPhase(ctx, Phase.ERROR, "环境安装失败：" + e);
            return false;
        }
    }

    // ------------------------------------------------------------------ 启动

    /**
     * 启动后端：必要时先安装环境，然后拉起 proroot + dsh，等待端口就绪并完成 Cookie 交换。
     * 阻塞，请在后台线程调用。
     */
    public boolean start(Context ctx) {
        try {
            return startInner(ctx);
        } catch (Throwable t) {
            EnvLog.e("启动后端出现未预期异常", t);
            setPhase(ctx, Phase.ERROR, "启动异常：" + t);
            return false;
        }
    }

    private boolean startInner(Context ctx) {
        EnvLog.i("== start() 进入 ==");
        synchronized (lock) {
            if (!ProrootEnv.isInstalled(ctx)) {
                EnvLog.w("start() 中止：尚未安装内置环境");
                setPhase(ctx, Phase.NOT_INSTALLED, "尚未安装内置环境");
                return false;
            }
            if (phase == Phase.RUNNING && portAlive) {
                return true;
            }
            stopRequested = false;
            setPhase(ctx, Phase.STARTING, "正在启动内置 dsh…");

            // 一次性 guest 自检：在拉起常驻进程之前先确认「proroot + rootfs」这条链是通的，
            // 否则用户只会看到一个语焉不详的启动超时。
            if (!guestVerified) {
                ProrootEnv.Smoke smoke = ProrootEnv.smokeTestGuest(ctx);
                EnvLog.i("guest 自检: ok=" + smoke.ok + " " + smoke.summary);
                if (!smoke.ok) {
                    setPhase(ctx, Phase.ERROR, "自检未通过：" + smoke.summary);
                    return false;
                }
                guestVerified = true;
                Log.i(TAG, "guest smoke test passed");
            }

            // 已有 dsh 实例在监听（例如上次进程未被回收）：直接接管。
            // 用 HTTP 身份探测而不是裸 TCP，避免把恰好占用 3081 的其它服务当成自己人。
            if (probeDsh(ctx)) {
                portAlive = true;
                DshAuth.restore(ctx);
                DshAuth.exchange(ctx);
                if (DshAuth.cookieHeader() == null && !DshAuth.reauth()) {
                    setPhase(ctx, Phase.ERROR,
                            "3081 端口已被另一个 dsh 实例占用，且无法取得其鉴权凭据。"
                                    + "请先停掉那个实例，或在环境控制台点「重启」");
                    return false;
                }
                setPhase(ctx, Phase.RUNNING, "已接管运行中的 dsh 后端");
                return true;
            }

            ProrootEnv.syncResolvConf(ctx);
            try {
                launchProcess(ctx);
            } catch (Throwable e) {
                EnvLog.e("launchProcess 失败", e);
                setPhase(ctx, Phase.ERROR, "启动失败：" + e.getMessage());
                return false;
            }

            // 等端口就绪（最多 90 秒：首次启动 dsh 要装载 profile 与插件树）
            long deadline = System.currentTimeMillis() + 90_000L;
            boolean up = false;
            while (System.currentTimeMillis() < deadline) {
                if (stopRequested) {
                    setPhase(ctx, Phase.STOPPED, "已取消启动");
                    return false;
                }
                Process p = process;
                if (p != null && !p.isAlive() && !probePort()) {
                    setPhase(ctx, Phase.ERROR, "dsh 进程已退出，请查看运行日志");
                    return false;
                }
                if (probePort()) {
                    up = true;
                    break;
                }
                sleep(600);
            }
            portAlive = up;
            if (!up) {
                EnvLog.e("启动超时：90 秒内 3081 未就绪", null);
                setPhase(ctx, Phase.ERROR, "启动超时（90 秒内端口未就绪），请查看运行日志");
                return false;
            }
            EnvLog.i("端口 3081 已就绪");

            // 端口通了之后再等 launchToken（stdout 一般先于端口就绪打印，稳妥起见重试）
            boolean authed = false;
            long authDeadline = System.currentTimeMillis() + 15_000L;
            while (System.currentTimeMillis() < authDeadline) {
                DshAuth.restore(ctx);
                if (DshAuth.exchange(ctx)) {
                    authed = true;
                    break;
                }
                sleep(500);
            }
            EnvLog.i("鉴权交换: ok=" + authed);
            setPhase(ctx, authed ? Phase.RUNNING : Phase.RUNNING,
                    authed ? "内置 dsh 已就绪 · 端口 " + ProrootEnv.DSH_PORT
                            : "dsh 已启动，但鉴权交换未完成（部分功能可能不可用）");
            return true;
        }
    }

    private void launchProcess(Context ctx) throws IOException {
        File libDir = new File(ctx.getApplicationInfo().nativeLibraryDir);
        for (String lib : ProrootEnv.PROROOT_LIBS) {
            File f = new File(libDir, lib);
            if (!f.exists()) {
                throw new IOException("缺少 proroot 组件 " + lib + "（预期位于 " + libDir + "）");
            }
        }

        File rootfs = ProrootEnv.rootfsDir(ctx);
        String script = buildGuestScript();

        java.util.List<String> argv = new java.util.ArrayList<>();
        for (String a : ProrootEnv.launcherArgv(ctx)) argv.add(a);
        argv.add("-r");
        argv.add(rootfs.getAbsolutePath());
        argv.add("-b");
        argv.add(ProrootEnv.HOST_SDCARD + ":" + ProrootEnv.GUEST_SDCARD);
        argv.add("-0");
        argv.add("--link2symlink");
        argv.add("-w");
        argv.add(ProrootEnv.GUEST_HOME);
        argv.add("/bin/sh");
        argv.add("-c");
        argv.add(script);

        ProcessBuilder pb = new ProcessBuilder(argv);

        // 干净环境：只注入 Ubuntu 需要的最小集合。
        // 必须清空——宿主环境里的 PREFIX 等变量会透传进 guest，把 npm 的全局前缀
        // 解析到宿主路径（实测踩过：包会被装进宿主的 node_modules）。
        pb.environment().clear();
        pb.environment().put("HOME", ProrootEnv.GUEST_HOME);
        pb.environment().put("PATH", ProrootEnv.GUEST_PATH);
        pb.environment().put("TERM", "xterm");
        pb.environment().put("LANG", "C.UTF-8");
        pb.environment().put("TMPDIR", "/tmp");
        pb.environment().put("PROROOT_TMP_DIR", ProrootEnv.tmpDir(ctx).getAbsolutePath());
        // linker64 后备模式需要显式给出 proroot 运行时库路径
        ProrootEnv.applyProrootEnv(pb, ctx);
        // Android 平台变量：bionic 与部分系统调用路径会读它们，补上以免出现平台相关怪象
        pb.environment().put("ANDROID_ROOT", "/system");
        pb.environment().put("ANDROID_DATA", "/data");
        pb.redirectErrorStream(true);
        pb.directory(rootfs);

        clearLogFile(ctx);
        String full = String.join(" ", argv);
        EnvLog.i("exec(" + (ProrootEnv.isLinkerMode() ? "linker64" : "direct") + "): "
                + full.substring(0, Math.min(300, full.length())) + " …");
        Process p;
        try {
            p = pb.start();
        } catch (Throwable t) {
            EnvLog.e("proroot 启动器 exec 失败（EACCES 通常意味着 Android 沙箱禁止执行该路径）", t);
            throw t;
        }
        EnvLog.i("proroot 已启动");
        process = p;
        pid = -1L;

        Thread t = new Thread(() -> pumpOutput(ctx, p), "dsh-backend-log");
        t.setDaemon(true);
        pumpThread = t;
        t.start();

        // 退出监视：非预期退出时给出确切的退出码，而不是让 UI 一直停在「启动中」
        Thread watcher = new Thread(() -> {
            int code;
            try {
                code = p.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            EnvLog.w("dsh 后端进程退出，exit=" + code + "（stopRequested=" + stopRequested + "）");
            if (stopRequested) return;
            portAlive = false;
            if (phase == Phase.STARTING || phase == Phase.RUNNING) {
                setPhase(ctx, Phase.ERROR,
                        "dsh 后端进程已退出（exit=" + code + "），详见运行日志");
            }
        }, "dsh-backend-watch");
        watcher.setDaemon(true);
        watcher.start();

        // 等 PID 文件（由 guest 脚本写入）
        for (int i = 0; i < 40 && pid < 0; i++) {
            pid = readGuestPid(ctx);
            if (pid >= 0) break;
            sleep(150);
        }
        EnvLog.i("guest pid=" + pid + (pid < 0 ? "（未能读取 PID 文件，停止时可能较慢）" : ""));
    }

    /** 组装 guest 内启动脚本。 */
    private String buildGuestScript() {
        // 工作区优先落在绑定的手机存储上；不可用时退回 guest 内部目录
        return "export HOME=" + ProrootEnv.GUEST_HOME + "\n"
                + "export PATH=" + ProrootEnv.GUEST_PATH + "\n"
                + "export DSH_HOME=" + ProrootEnv.GUEST_DSH_HOME + "\n"
                + "export DSH_PERMISSION_MODE=danger-full-access\n"
                + "export LANG=C.UTF-8\n"
                + "mkdir -p " + ProrootEnv.GUEST_DSH_HOME + " /root/.aptuidsh /root/workspace 2>/dev/null\n"
                + "mkdir -p " + ProrootEnv.GUEST_WORKSPACE + " 2>/dev/null\n"
                + "WORK=" + ProrootEnv.GUEST_WORKSPACE + "\n"
                + "[ -d \"$WORK\" ] || WORK=/root/workspace\n"
                + "echo $$ > " + ProrootEnv.GUEST_PID_FILE + "\n"
                + "cd \"$WORK\" || cd /root\n"
                + "echo \"[aptuidsh] workdir=$WORK\"\n"
                + "exec /usr/local/bin/dsh web --host " + ProrootEnv.DSH_HOST
                + " --port " + ProrootEnv.DSH_PORT + " --no-open\n";
    }

    // ------------------------------------------------------------------ 停止

    /** 停止后端。阻塞，后台线程调用。 */
    public void stop(Context ctx) {
        try {
            stopInner(ctx);
        } catch (Throwable t) {
            EnvLog.e("停止后端出现未预期异常", t);
            setPhase(ctx, Phase.STOPPED, "停止异常：" + t);
        }
    }

    private void stopInner(Context ctx) {
        EnvLog.i("== stop() 进入 ==");
        synchronized (lock) {
            stopRequested = true;
            setPhase(ctx, Phase.STOPPING, "正在停止内置 dsh…");

            long guestPid = readGuestPid(ctx);
            if (guestPid > 0) {
                signalKill(guestPid, "TERM");
                for (int i = 0; i < 20; i++) {
                    if (!probePort()) break;
                    sleep(250);
                }
                if (probePort()) {
                    signalKill(guestPid, "KILL");
                    sleep(500);
                }
            }

            Process p = process;
            if (p != null) {
                p.destroy();
                sleep(300);
                if (p.isAlive()) p.destroyForcibly();
            }
            process = null;
            pid = -1L;
            portAlive = false;
            //noinspection ResultOfMethodCallIgnored
            new File(ProrootEnv.rootfsDir(ctx), "root/.aptuidsh/dsh.pid").delete();
            DshAuth.invalidate(ctx);
            setPhase(ctx, Phase.STOPPED, "内置 dsh 已停止");
        }
    }

    /** 重启。 */
    public boolean restart(Context ctx) {
        stop(ctx);
        sleep(700);
        return start(ctx);
    }

    /** 探活：端口是否可连（不依赖 Cookie）。 */
    public boolean probePort() {
        Socket s = null;
        try {
            s = new Socket();
            s.connect(new InetSocketAddress(ProrootEnv.DSH_HOST, ProrootEnv.DSH_PORT), 700);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (s != null) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * 身份探测：3081 上跑的是不是 dsh 后端。
     *
     * <p>只做裸 TCP 探测会把「恰好占用 3081 的其它服务」误判成自己的后端并直接接管，
     * 之后所有 API 调用都会莫名失败。dsh 的根路径有稳定特征：
     * <ul>
     *   <li>未鉴权时 401，响应体是 {@code dsh web authentication required; …}；</li>
     *   <li>带有效 Cookie 时 200/303。</li>
     * </ul>
     * 因此以「401 且响应体含 dsh」或「200/303」作为判定依据。
     */
    public boolean probeDsh(Context ctx) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ProrootEnv.BASE_URL + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Connection", "close");
            String ck = DshAuth.cookieHeader();
            if (ck != null && !ck.isEmpty()) {
                conn.setRequestProperty("Cookie", ck);
            }
            int code = conn.getResponseCode();
            if (code == 200 || code == 303) {
                drainQuietly(conn);
                return true;
            }
            if (code == 401) {
                String body = readQuietly(conn);
                return body != null && body.contains("dsh");
            }
            drainQuietly(conn);
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String readQuietly(HttpURLConnection conn) {
        try {
            InputStream is = conn.getErrorStream();
            if (is == null) is = conn.getInputStream();
            if (is == null) return null;
            byte[] buf = new byte[512];
            int n = is.read(buf);
            is.close();
            return n > 0 ? new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static void drainQuietly(HttpURLConnection conn) {
        readQuietly(conn);
    }

    /** 刷新 portAlive 并广播（供 UI 定时刷新用）。 */
    public void refresh(Context ctx) {
        boolean alive = probePort();
        if (alive != portAlive) {
            portAlive = alive;
            if (alive && (phase == Phase.STOPPED || phase == Phase.ERROR)) {
                phase = Phase.RUNNING;
                message = "已接管运行中的 dsh 后端";
            } else if (!alive && phase == Phase.RUNNING && !stopRequested) {
                phase = Phase.STOPPED;
                message = "后端已退出";
            }
            publish(ctx);
        } else if (phase == Phase.RUNNING) {
            publish(ctx);
        }
    }

    // ------------------------------------------------------------------ 内部

    private void pumpOutput(Context ctx, Process p) {
        File logFile = ProrootEnv.backendLogFile(ctx);
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
             FileOutputStream out = new FileOutputStream(logFile, true)) {
            String line;
            while ((line = r.readLine()) != null) {
                appendLog(out, line);
                synchronized (logRing) {
                    if (logRing.size() >= LOG_RING_LINES) {
                        logRing.pollFirst();
                    }
                    logRing.addLast(line);
                }
                Matcher m = TOKEN_PATTERN.matcher(line);
                if (m.find()) {
                    DshAuth.setLaunchToken(ctx, m.group(1));
                }
                EnvLog.i("[dsh] " + line);
                for (Listener l : listeners) {
                    try {
                        l.onLogLine(line);
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "log pump ended: " + e.getMessage());
        }
    }

    private void appendLog(FileOutputStream out, String line) {
        try {
            if (out.getChannel().size() > LOG_FILE_MAX_BYTES) {
                return; // 日志文件封顶，避免无界增长
            }
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private void clearLogFile(Context ctx) {
        File f = ProrootEnv.backendLogFile(ctx);
        try (FileOutputStream fos = new FileOutputStream(f, false)) {
            fos.write(("=== APTUIDSH backend start " + new java.util.Date() + " ===\n")
                    .getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }

    private long readGuestPid(Context ctx) {
        File f = new File(ProrootEnv.rootfsDir(ctx), "root/.aptuidsh/dsh.pid");
        if (!f.exists()) return -1L;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
            String s = r.readLine();
            if (s == null) return -1L;
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return -1L;
        }
    }

    /** 用 /system/bin/kill 给 guest 进程发信号（guest 与宿主 PID 空间一致）。 */
    private void signalKill(long guestPid, String signal) {
        try {
            Process p = new ProcessBuilder("/system/bin/kill", "-" + signal, String.valueOf(guestPid))
                    .redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception e) {
            Log.w(TAG, "kill -" + signal + " " + guestPid + " failed: " + e.getMessage());
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 读取后端根路径的 HTTP 状态码（401/303/200 都算活着）。 */
    public int httpStatus(Context ctx) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ProrootEnv.BASE_URL + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Connection", "close");
            String ck = DshAuth.cookieHeader();
            if (ck != null) conn.setRequestProperty("Cookie", ck);
            int code = conn.getResponseCode();
            InputStream is = conn.getErrorStream();
            if (is == null) is = conn.getInputStream();
            if (is != null) {
                byte[] b = new byte[128];
                //noinspection StatementWithEmptyBody
                while (is.read(b) > 0) {
                }
                is.close();
            }
            return code;
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
