package com.aptuidsh.kui.env;
/** JVM 测试替身：真机实现依赖 SharedPreferences，这里只保留 Cookie 读取。 */
public final class DshAuth {
    private static volatile String cookie;
    private DshAuth() {}
    public static void setCookie(String c) { cookie = c; }
    public static String cookieHeader() { return cookie; }
}
