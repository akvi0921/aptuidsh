package android.util;
/** JVM 测试替身：仅为让 MuxClient 的 Base64 调用在桌面 JVM 上可运行。 */
public final class Base64 {
    public static final int NO_WRAP = 2;
    private Base64() {}
    public static String encodeToString(byte[] data, int flags) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }
}
