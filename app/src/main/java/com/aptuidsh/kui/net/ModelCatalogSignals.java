package com.aptuidsh.kui.net;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程级「模型目录已失效」信号（对齐官方 ui-model-selection 的刷新触发点）。
 *
 * <p><b>背景（官方实现，packages/client/ui-model-selection）</b>：
 * 官方 {@code ModelDirectoryResolver} 在客户端根上下文里同时订阅两条 Host 转发事件，
 * 任一到达即对该会话的目录控制器调用 {@code load()}（重新 RPC {@code session.models}），
 * 并在 {@code connection/reset}（Host 重启/换连接代）时先清空再重拉：
 *
 * <pre>
 *   ctx.remote.$on('llm/adapters-updated', refresh);
 *   ctx.remote.$on('settings/document-updated', refresh);
 *   ctx.on('connection/reset', () =&gt; { for (d of live) d.resetConnected(); });
 * </pre>
 *
 * <p>这两条事件由 Host 经 {@code events.host} 通道以
 * {@code {type:"host/remote-event", event:"llm/adapters-updated"|"settings/document-updated", args:[...]}}
 * 信封转发（见 dsh-host-apiproxy 的 API_REMOTE_FORWARDED_EVENTS 转发循环）。
 * 触发场景：新增/移除 LLM 适配器、模型目录 refresh、settings 文档提交（含模型相关命名空间）。
 *
 * <p><b>本类职责</b>：EventStream 线程收到上述 host 帧后只做一件事——把本代信号版本号 +1；
 * UI（ChatActivity）在主线程读取版本号并与自己已消费的版本比较，不同则重拉目录。
 * 这样事件线程永不触碰 Compose 状态，也不依赖 Activity 是否在前台存活
 * （Activity 重建后仍能发现"离线期间发生过变更"）。
 *
 * <p>线程模型：volatile 版本号 + 写时复制监听器表；任意线程可读，任意线程可触发。
 */
public final class ModelCatalogSignals {

    /** host 通道上代表「模型目录可能已变」的 remote-event 名集合（官方同名）。 */
    public static final String EV_ADAPTERS_UPDATED = "llm/adapters-updated";
    public static final String EV_SETTINGS_DOCUMENT_UPDATED = "settings/document-updated";

    /** host 帧类型：Host 转发事件信封。 */
    public static final String FRAME_REMOTE_EVENT = "host/remote-event";

    /** 目录失效信号监听器（在 EventStream 通道线程上回调，实现方须自行切主线程）。 */
    public interface Listener {
        /**
         * 模型目录可能已变化（适配器拓扑变更 / 设置文档提交）。
         *
         * @param eventName 触发本信号的 host remote-event 名（见常量）。
         */
        void onModelCatalogInvalidated(String eventName);
    }

    /** 单调递增的失效版本号；UI 用它做「我是否已消费过本次变更」的判断。 */
    private static final AtomicLong revision = new AtomicLong(0L);

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private ModelCatalogSignals() {
    }

    /** 当前失效版本号（任意线程可读）。 */
    public static long revision() {
        return revision.get();
    }

    /** 订阅信号；返回取消订阅句柄（幂等）。 */
    public static Runnable subscribe(Listener l) {
        if (l == null) return () -> { };
        listeners.addIfAbsent(l);
        return () -> listeners.remove(l);
    }

    /**
     * 处理一帧 host 通道信封：若是「模型目录已失效」类 remote-event，则推进版本号并通知监听器。
     *
     * @param envelope host 通道完整信封（{@code {"type":"server-request","payload":{...}}}）。
     * @return 本帧是否被识别为目录失效信号。
     */
    public static boolean acceptHostEnvelope(org.json.JSONObject envelope) {
        if (envelope == null) return false;
        org.json.JSONObject payload = envelope.optJSONObject("payload");
        if (payload == null) return false;
        if (!FRAME_REMOTE_EVENT.equals(payload.optString("type", ""))) return false;
        String event = payload.optString("event", "");
        if (!EV_ADAPTERS_UPDATED.equals(event) && !EV_SETTINGS_DOCUMENT_UPDATED.equals(event)) {
            return false;
        }
        invalidate(event);
        return true;
    }

    /**
     * 主动标记目录失效（连接代重置 / 手动校准等场景）。
     *
     * @param reason 触发原因（仅用于日志，可为空串）。
     */
    public static void invalidate(String reason) {
        revision.incrementAndGet();
        for (Listener l : listeners) {
            try {
                l.onModelCatalogInvalidated(reason == null ? "" : reason);
            } catch (RuntimeException ignored) {
                // 单个监听器异常不得影响其余监听器与事件通道线程
            }
        }
    }
}
