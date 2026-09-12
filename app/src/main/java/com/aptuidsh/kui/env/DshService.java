package com.aptuidsh.kui.env;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.aptuidsh.kui.MainActivity;
import com.aptuidsh.kui.R;

/**
 * 内置 dsh 后端的前台服务：让 proroot + dsh 进程在界面退到后台后继续存活。
 *
 * <p>Android 会在应用进入后台后回收普通进程；dsh 后端是 APP 进程的子进程，一旦 APP
 * 被回收，后端也会随之消失。用前台服务挂住进程是最省事且合规的做法。
 */
public class DshService extends Service {

    private static final String TAG = "AptuDshService";
    private static final String CHANNEL_ID = "aptuidsh_backend";
    private static final int NOTIFICATION_ID = 0x4453;

    public static final String ACTION_START = "com.aptuidsh.kui.action.BACKEND_START";
    public static final String ACTION_STOP = "com.aptuidsh.kui.action.BACKEND_STOP";
    public static final String ACTION_RESTART = "com.aptuidsh.kui.action.BACKEND_RESTART";

    private volatile boolean workerBusy;

    /** 便捷入口：请求启动后端。 */
    public static void requestStart(Context ctx) {
        Intent i = new Intent(ctx, DshService.class).setAction(ACTION_START);
        startCompat(ctx, i);
    }

    /** 便捷入口：请求停止后端并结束服务。 */
    public static void requestStop(Context ctx) {
        Intent i = new Intent(ctx, DshService.class).setAction(ACTION_STOP);
        startCompat(ctx, i);
    }

    /** 便捷入口：请求重启后端。 */
    public static void requestRestart(Context ctx) {
        Intent i = new Intent(ctx, DshService.class).setAction(ACTION_RESTART);
        startCompat(ctx, i);
    }

    private static void startCompat(Context ctx, Intent i) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i);
            } else {
                ctx.startService(i);
            }
        } catch (Throwable t) {
            Log.w(TAG, "start service failed: " + t);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForegroundCompat("内置 dsh 启动中…");
        DshAuth.restore(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            runAsync(() -> {
                DshBackend.get().stop(this);
                stopForegroundCompat();
                stopSelf();
            });
            return START_NOT_STICKY;
        }
        if (ACTION_RESTART.equals(action)) {
            runAsync(() -> {
                DshBackend.get().restart(this);
                updateNotification();
            });
            return START_STICKY;
        }
        runAsync(() -> {
            if (!ProrootEnv.isInstalled(this)) {
                // 环境未安装：交给界面去引导安装，服务不自动展开
                DshBackend.get().install(this);
            }
            DshBackend.get().start(this);
            updateNotification();
        });
        return START_STICKY;
    }

    private void runAsync(Runnable r) {
        if (workerBusy) {
            Log.i(TAG, "worker busy, ignoring duplicate request");
            return;
        }
        workerBusy = true;
        Thread t = new Thread(() -> {
            try {
                r.run();
            } catch (Throwable e) {
                Log.e(TAG, "service task failed", e);
            } finally {
                workerBusy = false;
            }
        }, "aptuidsh-backend-worker");
        t.setDaemon(true);
        t.start();
    }

    private void updateNotification() {
        DshBackend.Status s = DshBackend.get().status(this);
        String text;
        switch (s.phase) {
            case RUNNING:
                text = "运行中 · 127.0.0.1:" + ProrootEnv.DSH_PORT;
                break;
            case STARTING:
                text = "启动中…";
                break;
            case INSTALLING:
                text = "安装内置环境…";
                break;
            case ERROR:
                text = "异常：" + s.message;
                break;
            default:
                text = s.message.isEmpty() ? "已停止" : s.message;
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    private void startForegroundCompat(String text) {
        Notification n = buildNotification(text);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            //noinspection deprecation
            stopForeground(true);
        }
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        b.setContentTitle("APTUIDSH")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(open);

        PendingIntent stopPi = PendingIntent.getService(
                this, 1, new Intent(this, DshService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        b.addAction(new Notification.Action.Builder(null, "停止", stopPi).build());
        return b.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "内置 dsh 后端", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("保持内置 Linux 环境与 dsh 后端运行");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
