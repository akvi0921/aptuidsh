package com.aptuidsh.kui.env;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 环境引导全过程日志。
 *
 * <h3>为什么必须有它</h3>
 * 首版把「安装并启动」交给 Compose 的 {@code rememberCoroutineScope()} 执行，
 * 一旦该作用域被取消、或工作抛异常，界面上表现为<b>完全静默</b>——按钮点了没反应、
 * 控制台也没有任何输出，用户和开发者都无从下手。同类问题在真机上无法复现调试，
 * 因此这里把每一步都落到<b>内存 + 文件</b>：
 *
 * <ul>
 *   <li>内存环形缓冲（最近 500 行）供控制台实时展示；</li>
 *   <li>追加写入 {@code filesDir/logs/bootstrap.log}，进程重启后仍可查；</li>
 *   <li>异常一律连栈一起记录，绝不吞掉。</li>
 * </ul>
 */
public final class EnvLog {

    private static final String TAG = "AptuDshEnv";
    private static final String FILE_NAME = "bootstrap.log";
    private static final int MAX_LINES = 500;
    private static final long MAX_FILE_BYTES = 512 * 1024L;

    /** 日志监听（控制台用）。 */
    public interface Sink {
        void onLine(String line);
    }

    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final CopyOnWriteArrayList<Sink> SINKS = new CopyOnWriteArrayList<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private EnvLog() {
    }

    public static void addSink(Sink s) {
        if (s != null && !SINKS.contains(s)) SINKS.add(s);
    }

    public static void removeSink(Sink s) {
        SINKS.remove(s);
    }

    /** 当前全部日志行。 */
    public static List<String> lines() {
        synchronized (LINES) {
            return new ArrayList<>(LINES);
        }
    }

    public static void clear() {
        synchronized (LINES) {
            LINES.clear();
        }
    }

    public static void i(String msg) {
        write("INFO ", msg);
    }

    public static void w(String msg) {
        write("WARN ", msg);
    }

    /** 记录异常，连同完整栈。 */
    public static void e(String msg, Throwable t) {
        StringBuilder sb = new StringBuilder(msg == null ? "异常" : msg);
        if (t != null) {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            sb.append('\n').append(sw);
        }
        write("ERROR", sb.toString());
    }

    private static void write(String level, String msg) {
        String stamp = TS.format(new Date());
        // 多行消息逐行加前缀，便于阅读
        StringBuilder out = new StringBuilder();
        for (String line : msg.split("\n", -1)) {
            out.append(stamp).append(' ').append(level).append(' ').append(line).append('\n');
        }
        String block = out.toString();
        Log.println("ERROR".equals(level) ? Log.ERROR : Log.INFO, TAG, msg);

        synchronized (LINES) {
            for (String line : block.split("\n")) {
                if (line.isEmpty()) continue;
                LINES.addLast(line);
                while (LINES.size() > MAX_LINES) LINES.pollFirst();
            }
        }
        for (Sink s : SINKS) {
            try {
                s.onLine(block);
            } catch (Throwable ignored) {
            }
        }
        appendFile(block);
    }

    /** 日志文件（放在 filesDir，用户也可通过环境控制台一键导出）。 */
    public static File file(Context ctx) {
        File dir = ProrootEnv.logsDir(ctx);
        return new File(dir, FILE_NAME);
    }

    private static volatile Context appCtx;

    /** 由 Application 调用一次，之后 write() 才能落盘。 */
    public static void attach(Context ctx) {
        appCtx = ctx.getApplicationContext();
    }

    private static void appendFile(String block) {
        Context ctx = appCtx;
        if (ctx == null) return;
        try {
            File f = file(ctx);
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                // 超限则半量截断，保留最近内容
                byte[] all = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0, n;
                    while (off < all.length && (n = in.read(all, off, all.length - off)) > 0) off += n;
                }
                try (FileOutputStream fos = new FileOutputStream(f, false)) {
                    fos.write(all, all.length / 2, all.length / 2);
                }
            }
            try (FileOutputStream fos = new FileOutputStream(f, true)) {
                fos.write(block.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 日志落盘失败不能影响主流程
        }
    }
}
