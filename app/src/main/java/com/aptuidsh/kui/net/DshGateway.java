package com.aptuidsh.kui.net;

import org.json.JSONObject;

/**
 * 应用级协议网关：{@link DshClient}（HTTP JSON-RPC）与 {@link EventStream}（host/mux
 * 双 WebSocket 事件通道）的生命周期接线（devcrew 步骤 3 缺口 1）。
 *
 * <p>组合契约 1 与契约 2，统一对外生命周期：
 * <ul>
 *   <li>{@link #start()}：启动双事件通道（幂等）。RPC 走独立 HTTP POST，不依赖事件通道——
 *       事件通道断线/退避重连期间 {@link #rpc(String, JSONObject, DshClient.DshCallback)}
 *       照常可用。</li>
 *   <li>{@link #shutdown()}：联动停止双事件通道并释放 RPC 线程池（幂等）。</li>
 *   <li>{@link #setBaseUrl(String)}：同步修改 RPC 基址与事件通道基址（SettingsVm 改基址后
 *       配合 {@link #restartEvents()} 立即重建通道）。</li>
 * </ul>
 *
 * <p>UI 层用法：应用启动时创建网关 → {@code gateway.start()}；监听
 * {@link EventStream.ConnectionListener} 渲染连接状态；{@link #setListener} 订阅事件帧；
 * RPC 直接 {@link #client()} 或 {@link #rpc}。退出时 {@code gateway.shutdown()}。
 *
 * <p>零第三方依赖（仅 JDK + android.jar org.json），不触碰主线程（底层线程均 daemon）。
 */
public class DshGateway {

    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:3081";

    private final DshClient client;
    private final EventStream events;
    private volatile String baseUrl;

    public DshGateway() {
        this(DEFAULT_BASE_URL);
    }

    public DshGateway(String baseUrl) {
        this.baseUrl = normalizeBase(baseUrl);
        this.client = new DshClient(this.baseUrl, 10_000);
        this.events = new EventStream(this.baseUrl);
    }

    // ==================== 生命周期接线 ====================

    /**
     * 启动双事件通道（幂等）。RPC 无需先调用本方法——{@link DshClient} 独立可用；
     * 事件通道未连接/退避重连期间 RPC 照常工作。
     */
    public void start() {

        events.start();
    }

    /**
     * 联动停机（幂等）：停止 host/mux 双事件通道并释放 RPC 线程池。
     * 应用退出 / 整体重建网关时调用。
     */
    public void shutdown() {

        events.stop();
        client.shutdown();
    }

    /** 重启事件通道（改基址后调用；RPC 不受影响）。 */
    public void restartEvents() {

        events.stop();
        events.start();
    }

    /**
     * 修改基址（RPC 立即生效；事件通道下次重连生效——配合 {@link #restartEvents()} 立即重建）。
     */
    public void setBaseUrl(String baseUrl) {
        String b = normalizeBase(baseUrl);
        if (b == null) return;

        this.baseUrl = b;
        client.setBaseUrl(b);
        events.setBaseUrl(b);
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    // ==================== 访问器 / 便捷透传 ====================

    /** 底层 RPC 客户端（契约 1，53 方法面直接可用）。 */
    public DshClient client() {
        return client;
    }

    /** 底层事件通道（契约 2）。 */
    public EventStream events() {
        return events;
    }

    /** 事件通道整体是否在运行（含退避重连期间）。 */
    public boolean isRunning() {
        return events.isRunning();
    }

    /** 指定通道当前是否已连接（{@link EventStream#CH_HOST} / {@link EventStream#CH_MUX}）。 */
    public boolean isChannelConnected(String channel) {
        return events.isChannelConnected(channel);
    }

    /** 订阅事件帧（透传 {@link EventStream#setListener}）。 */
    public void setListener(EventStream.DshEventListener listener) {
        events.setListener(listener);
    }

    /** 订阅连接状态（透传 {@link EventStream#setConnectionListener}）。 */
    public void setConnectionListener(EventStream.ConnectionListener listener) {
        events.setConnectionListener(listener);
    }

    /** 便捷 RPC（透传 {@link DshClient#rpc}）。 */
    public void rpc(String method, JSONObject payload, DshClient.DshCallback cb) {
        client.rpc(method, payload, cb);
    }

    // ==================== 工具 ====================

    private static String normalizeBase(String url) {
        if (url == null) return DEFAULT_BASE_URL;
        String b = url.trim();
        if (b.length() == 0) return DEFAULT_BASE_URL;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }
}
