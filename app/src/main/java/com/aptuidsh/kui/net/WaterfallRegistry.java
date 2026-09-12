package com.aptuidsh.kui.net;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 待应答 waterfall 事件登记表（跨线程）。
 *
 * <h3>为什么需要它</h3>
 * dsh ≤ 0.1.1 用「HTTP POST /api/respond + {@code client-response} 信封」回执审批与提问。
 * dsh ≥ 0.1.5 把回执挪进了网关的事件通道，改由
 * {@code POST /api/$events/result} 携带 {@code {clientId, eventId, outcome}} 完成：
 *
 * <pre>
 * // 服务端下发（实测，waterfall 帧，需要客户端给出 outcome）
 * {"type":"item","streamId":"dsh-ev-host","value":{
 *    "type":"waterfall",
 *    "event":"user-questions/request",
 *    "eventId":"31a228f3-4982-4899-bc4b-9d7871b150d1",
 *    "agentId":"session-4b67fafb-…",
 *    "request":{"questions":[{"id":"color","question":"你喜欢什么颜色？",
 *                             "options":[{"label":"红色"},{"label":"蓝色"}]}]}}}
 *
 * // 客户端回执
 * POST /api/$events/result
 * {"args":{"clientId":"&lt;ready 帧里的 clientId&gt;",
 *          "eventId":"31a228f3-…",
 *          "outcome":{"kind":"result","value":{"answers":[{"id":"color","selected":["蓝色"]}]}}}}
 * </pre>
 *
 * <p>{@code clientId} 由 {@code $events} 流的 ready 帧下发，重连会换新的；
 * {@code eventId} 就是待应答事件的唯一标识——本表按它索引，供
 * {@link DshClient#respond} 把 UI 的旧式应答映射到新端点。
 */
public final class WaterfallRegistry {

    /** 登记表容量上限（防止长期不清理导致无界增长）。 */
    private static final int MAX_ENTRIES = 64;

    /** 最近一次 {@code $events} ready 帧下发的 clientId。 */
    private static volatile String clientId;

    /** eventId → 待应答事件（保持插入顺序，便于淘汰最旧的）。 */
    private static final Map<String, Entry> PENDING =
            new LinkedHashMap<String, Entry>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    private WaterfallRegistry() {
    }

    /** 一条待应答的 waterfall 事件。 */
    public static final class Entry {
        /** 事件名，如 {@code user-questions/request}、{@code approval/request}。 */
        public final String event;
        /** 归属会话（服务端字段名 agentId）。 */
        public final String agentId;
        /** 原始 request 载荷。 */
        public final JSONObject request;

        Entry(String event, String agentId, JSONObject request) {
            this.event = event;
            this.agentId = agentId;
            this.request = request;
        }
    }

    /** 记录 ready 帧的 clientId（每次重连都会刷新）。 */
    public static void setClientId(String id) {
        if (id != null && !id.isEmpty()) {
            clientId = id;
        }
    }

    public static String clientId() {
        return clientId;
    }

    /** 登记一条待应答事件。 */
    public static void record(String eventId, String event, String agentId, JSONObject request) {
        if (eventId == null || eventId.isEmpty()) return;
        synchronized (PENDING) {
            PENDING.put(eventId, new Entry(event, agentId, request));
        }
    }

    /** 取一条待应答事件（不删除——事件可能被重放）。 */
    public static Entry get(String eventId) {
        if (eventId == null) return null;
        synchronized (PENDING) {
            return PENDING.get(eventId);
        }
    }

    /** 应答完成后清除。 */
    public static void resolve(String eventId) {
        if (eventId == null) return;
        synchronized (PENDING) {
            PENDING.remove(eventId);
        }
    }

    /** 找该会话下最近一条指定事件（重放帧里 eventId 稳定，按会话兜底匹配）。 */
    public static String findEventId(String event, String agentId) {
        synchronized (PENDING) {
            String found = null;
            for (Map.Entry<String, Entry> e : PENDING.entrySet()) {
                Entry v = e.getValue();
                if (event.equals(v.event) && agentId != null && agentId.equals(v.agentId)) {
                    found = e.getKey();
                }
            }
            return found;
        }
    }

    /** 连接代切换时清空（旧 eventId 在新连接里已失效）。 */
    public static void clear() {
        synchronized (PENDING) {
            PENDING.clear();
        }
    }
}
