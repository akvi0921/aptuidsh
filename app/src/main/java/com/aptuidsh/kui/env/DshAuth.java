package com.aptuidsh.kui.env;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * dsh ≥ 0.1.5 的 web 后端浏览器鉴权桥。
 *
 * <h3>为什么需要它</h3>
 * 0.1.1 及更早版本的 {@code /api/*} 对本机回环请求不做鉴权，因此原生客户端可直接调用。
 * 从 0.1.5 起 {@code dsh web} 引入「进程启动令牌 + 签名 Cookie」两道关：
 * <ul>
 *   <li>启动时把 {@code http://127.0.0.1:3081/?token=<launchToken>} 打印到 stdout；</li>
 *   <li>对该 URL 发一次 GET，服务端 303 并下发
 *       {@code Set-Cookie: dsh-auth-<sha256(authority)>=v1.<payload>.<sig>}；</li>
 *   <li>此后所有 {@code /api/*} 请求必须携带该 Cookie（Host 必须是回环，否则 403）。</li>
 * </ul>
 * 本类负责：捕获 stdout 里的 launchToken → 交换 Cookie → 持久化 → 供 HTTP 层读取。
 * Cookie 的签名密钥由 dsh 自己持久化在 {@code .credentials.yaml}，因此 Cookie 在
 * dsh 重启后依然有效；launchToken 则每次进程启动都会变，必须用最新的。
 */
public final class DshAuth {

    private static final String TAG = "AptuDshAuth";
    private static final String PREFS = "aptuidsh_auth";
    private static final String KEY_COOKIE = "cookie";
    private static final String KEY_TOKEN = "token";

    /** 完整 Cookie 头值（形如 {@code dsh-auth-xxx=v1.yyy.zzz}）。 */
    private static volatile String cookie;
    /** 最近一次从 dsh stdout 捕获的进程启动令牌。 */
    private static volatile String launchToken;
    /** 应用上下文（供 HTTP 层在收到 401 时静默重取 Cookie）。 */
    private static volatile Context appContext;

    private DshAuth() {
    }

    /** 应用启动时调用一次：绑定上下文并恢复持久化的令牌/Cookie。 */
    public static void init(Context ctx) {
        appContext = ctx.getApplicationContext();
        restore(appContext);
    }

    /** 收到 401 时调用：清掉旧 Cookie 并立即重新交换。 */
    public static boolean reauth() {
        Context ctx = appContext;
        if (ctx == null) return false;
        invalidate(ctx);
        return exchange(ctx);
    }

    /** 当前 Cookie 头值；无则返回 null。 */
    public static String cookieHeader() {
        return cookie;
    }

    /** 当前 launchToken；无则返回 null。 */
    public static String launchToken() {
        return launchToken;
    }

    /** 记录 dsh 启动时打印的令牌（由后端日志线程调用）。 */
    public static void setLaunchToken(Context ctx, String token) {
        if (token == null || token.isEmpty()) return;
        if (token.equals(launchToken)) return;
        launchToken = token;
        prefs(ctx).edit().putString(KEY_TOKEN, token).apply();
        // 令牌变了说明 dsh 重启过，旧 Cookie 的 authority 虽相同但仍需重新交换以刷新
        Log.i(TAG, "captured launch token len=" + token.length());
    }

    /** 读取持久化的令牌/Cookie（进程冷启时恢复）。 */
    public static void restore(Context ctx) {
        SharedPreferences p = prefs(ctx);
        if (cookie == null) cookie = p.getString(KEY_COOKIE, null);
        if (launchToken == null) launchToken = p.getString(KEY_TOKEN, null);
    }

    /** 用已知令牌交换 Cookie；成功返回 true。 */
    public static synchronized boolean exchange(Context ctx) {
        String token = launchToken;
        if (token == null) {
            token = prefs(ctx).getString(KEY_TOKEN, null);
            launchToken = token;
        }
        if (token == null || token.isEmpty()) {
            Log.w(TAG, "no launch token yet; cannot exchange cookie");
            return false;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ProrootEnv.BASE_URL + "/?token=" + token);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("Connection", "close");
            int code = conn.getResponseCode();
            // 303 See Other（首次交换）或 200（Cookie 仍有效时直接放行）
            String setCookie = conn.getHeaderField("Set-Cookie");
            if (setCookie != null) {
                int semi = setCookie.indexOf(';');
                String pair = (semi >= 0 ? setCookie.substring(0, semi) : setCookie).trim();
                if (!pair.isEmpty()) {
                    storeCookie(ctx, pair);
                    Log.i(TAG, "cookie exchanged (http " + code + ")");
                    return true;
                }
            }
            if (code == 200 && cookie != null) {
                return true;
            }
            Log.w(TAG, "cookie exchange returned http " + code + " without Set-Cookie");
            return false;
        } catch (IOException e) {
            Log.w(TAG, "cookie exchange failed: " + e.getMessage());
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** 丢弃当前 Cookie（收到 401 时调用，随后应重新交换）。 */
    public static void invalidate(Context ctx) {
        cookie = null;
        prefs(ctx).edit().remove(KEY_COOKIE).apply();
    }

    /** 是否已有可用 Cookie。 */
    public static boolean hasCookie() {
        return cookie != null && !cookie.isEmpty();
    }

    private static void storeCookie(Context ctx, String pair) {
        cookie = pair;
        prefs(ctx).edit().putString(KEY_COOKIE, pair).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 主动探测一次：返回后端 HTTP 状态码（-1 表示连不上）。 */
    public static int probe(Context ctx) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(ProrootEnv.BASE_URL + "/");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(2500);
            conn.setReadTimeout(2500);
            conn.setRequestProperty("Connection", "close");
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            int code = conn.getResponseCode();
            drain(conn);
            return code;
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static void drain(HttpURLConnection conn) {
        try {
            InputStream is = conn.getErrorStream();
            if (is == null) is = conn.getInputStream();
            if (is != null) {
                byte[] buf = new byte[256];
                //noinspection StatementWithEmptyBody
                while (is.read(buf) > 0) {
                    // 丢弃
                }
                is.close();
            }
        } catch (IOException ignored) {
            // 探测用途，忽略
        }
    }
}
