package com.aptuidsh.kui.net;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DSH 0.1.5 事件流传输层：单一 WebSocket 多路复用。
 *
 * <p><b>协议背景</b>：
 * 0.1.1 的双通道 {@code /api/events.host} + {@code /api/events.mux} 在 0.1.5 已返回 404，
 * 统一替换为 {@code ws://127.0.0.1:3081/api/remote.mux}。所有下行情报（全局事件、会话跟随、
 * 控制帧）均经该单一连接以 {@code streamId} 多路复用。
 *
 * <p>客户端 → 服务端（文本帧）：
 * <ul>
 *   <li>{@code {"type":"open","streamId":"<id>","endpoint":"<name>","payload":{"args":{…}}}}</li>
 *   <li>{@code {"type":"cancel","streamId":"<id>"}}</li>
 * </ul>
 *
 * <p>服务端 → 客户端（文本帧）：
 * <ul>
 *   <li>{@code {"type":"item","streamId":"<id>","value":…}}</li>
 *   <li>{@code {"type":"end","streamId":"<id>"}}</li>
 *   <li>{@code {"type":"error","streamId":"<id>","error":{…}}}</li>
 * </ul>
 *
 * <p><b>本类职责</b>：在 {@link DshEventListener} 公开 API 不变的前提下，把新协议 item 翻译回
 * UI 已有的旧信封格式（{@code server-request}），使 {@code ChatActivity / HomeViewModel} 等
 * 消费方无需修改。
 *
 * <p>通道映射（旧 → 新）：
 * <ul>
 *   <li>{@link #CH_HOST} → {@code $events} 流：全局 Cordis 事件（目录失效信号等）。</li>
 *   <li>{@link #CH_MUX} → 实际承载所有下行情报的 WebSocket 连接本身。</li>
 *   <li>按需 {@code session/follow}（会话实时流）与 {@code session/control}（队列/任务/投影）。</li>
 * </ul>
 *
 * <p>线程模型：所有回调在通道工作线程（daemon）上执行，调用方自行决定是否切回 UI 线程；
 * 本类绝不触碰主线程。
 */
public class EventStream {

    /** 事件监听器（契约 2）：帧分发 + 流式回调。 */
    public interface DshEventListener {
        /** host 通道等价帧（envelope 原样），按 payload.type 分发（host/session-status 等）。 */
        void onHostEvent(JSONObject envelope);

        /** mux 通道等价帧（envelope 原样），按 payload.type 分发（session/event、projection、jobs 等）。 */
        void onMuxEvent(JSONObject envelope);

        /** 流式正文增量：assistant-stream chunk.type=text-delta。meta 含 seq/turn/step/index。 */
        void onTextDelta(String sessionId, String delta, JSONObject meta);

        /** 流结束：assistant/message、turn/end、或 assistant-stream end 帧触发。 */
        void onStreamEnd(String sessionId);
    }

    /** 可选连接状态监听（M1 连接状态页 / M2 重连提示用；非契约 2 强制）。 */
    public interface ConnectionListener {
        /** 通道建立成功（握手 101 完成）。channel 为 {@link #CH_HOST} 或 {@link #CH_MUX}。 */
        void onConnected(String channel);

        /** 通道断开（异常或服务端关闭）。reason 为人类可读原因。 */
        void onDisconnected(String channel, String reason);
    }

    /** host 等价通道名（语义对应旧 events.host，实际走 $events 逻辑流）。 */
    public static final String CH_HOST = "host";
    /** mux 等价通道名（语义对应旧 events.mux，实际走 WebSocket 连接本身）。 */
    public static final String CH_MUX = "mux";

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final String DEFAULT_BASE_URL = "http://127.0.0.1:3081";
    /** 初始重连退避 3s。 */
    private static final long INITIAL_BACKOFF_MS = 3_000L;
    /** 重连退避上限 30s。 */
    private static final long MAX_BACKOFF_MS = 30_000L;
    /** 握手/连接超时。 */
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    /** 单帧 payload 上限（防御性，防 OOM）。 */
    private static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    /** 握手响应头行长度上限。 */
    private static final int MAX_HEADER_LINE = 8 * 1024;

    private static final String STREAM_ID_EVENTS = "dsh-ev-host";
    private static final String PREFIX_CTRL = "dsh-ctrl:";
    private static final String PREFIX_FOLLOW = "dsh-follow:";

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final Object lock = new Object();
    private final Set<String> followSessions = new HashSet<>();

    private volatile String baseUrl;
    private volatile DshEventListener listener;
    private volatile ConnectionListener connectionListener;
    private volatile Socket muxSocket;
    private volatile boolean muxConnected;
    private Thread muxThread;

    // ---- 进程级"待处理交互"视图(修复:关掉 APP/重进会话后提问审批卡片无法唤醒) ----
    // 旧 events.mux 在每次连接建立后会重放仍待处理的 approval/requested、question/requested。
    // 新协议经 session/follow 实时下推；连接代结束后清空、重连后的 item 会重建该视图。
    private final Object frameLock = new Object();
    private final LinkedHashMap<String, JSONObject> pendingInteractionFrames = new LinkedHashMap<>();
    private static final int MAX_PENDING_INTERACTION_FRAMES = 64;

    public EventStream() {
        this(DEFAULT_BASE_URL);
    }

    public EventStream(String baseUrl) {
        this.baseUrl = normalizeBase(baseUrl);
    }

    // ==================== 配置 ====================

    public String getBaseUrl() {
        return baseUrl;
    }

    /** 修改基址：下一次重连/启动生效（SettingsVm 修改基址后配合 stop()+start() 立即重建）。 */
    public void setBaseUrl(String baseUrl) {
        String b = normalizeBase(baseUrl);
        if (b != null) this.baseUrl = b;
    }

    public void setListener(DshEventListener listener) {
        this.listener = listener;
    }

    public void setConnectionListener(ConnectionListener connectionListener) {
        this.connectionListener = connectionListener;
    }

    public boolean isRunning() {
        return !stopped.get();
    }

    /** 通道当前是否已连接（握手完成且未断开）。 */
    public boolean isChannelConnected(String channel) {
        return CH_MUX.equals(channel) ? muxConnected : false;
    }

    // ==================== 生命周期 ====================

    /** 启动事件通道（幂等）。 */
    public void start() {
        synchronized (lock) {
            if (!stopped.get()) return;
            stopped.set(false);
            followSessions.clear();
            muxThread = new Thread(new Worker(), "dsh-ev-mux");
            muxThread.setDaemon(true);
            muxThread.start();
        }
    }

    /** 停止事件通道并释放连接（幂等；线程 join 至多 2s）。 */
    public void stop() {
        synchronized (lock) {
            if (stopped.get()) return;
            stopped.set(true);
            closeSocket(muxSocket);
        }
        joinQuietly(muxThread);
        muxThread = null;
        synchronized (this) {
            muxConnected = false;
        }
        followSessions.clear();
    }

    /** 按需开启会话跟随流（UI 进入会话时调用）。 */
    public void followSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        boolean changed;
        synchronized (lock) {
            changed = followSessions.add(sessionId);
        }
        if (changed && !stopped.get() && muxConnected) {
            sendMuxOpenFollow(sessionId);
        }
    }

    /** 按需关闭会话跟随流（UI 离开会话时调用；兼容旧 API）。 */
    public void unfollowSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return;
        String streamId;
        synchronized (lock) {
            if (!followSessions.remove(sessionId)) return;
            streamId = PREFIX_FOLLOW + sessionId;
        }
        if (!stopped.get() && muxConnected) {
            sendMuxCancel(streamId);
        }
    }

    /**
     * 旧兼容入口：设置"流式会话"（旧协议每个 session/event 帧携带 sessionId）。
     * <p>新协议必须显式 open session/follow 才能收到该会话的实时事件，因此本方法
     * 等价于 {@link #followSession}，供旧调用方继续使用。
     */
    public void setStreamingSession(String sessionId) {
        followSession(sessionId);
    }

    /**
     * 旧兼容入口：清除"流式会话"。
     */
    public void clearStreamingSession(String sessionId) {
        unfollowSession(sessionId);
    }

    // ==================== 通道工作线程 ====================

    /** 事件通道路线驱动线程：连接 → 泵帧 → 断线退避重连。 */
    private final class Worker implements Runnable {
        @Override
        public void run() {
            long delay = INITIAL_BACKOFF_MS;
            while (!stopped.get()) {
                Conn conn = null;
                try {
                    conn = openSocket("/api/remote.mux");
                    setMuxSocket(conn.socket);
                    if (stopped.get()) break;
                    delay = INITIAL_BACKOFF_MS;
                    setMuxConnected(true);
                    fireConnected(CH_MUX);
                    pumpMux(conn);
                    if (stopped.get()) break;
                    fireDisconnected(CH_MUX, "stream closed");
                } catch (Exception e) {
                    if (stopped.get()) break;
                    setMuxConnected(false);
                    fireDisconnected(CH_MUX, describe(e));
                } finally {
                    closeSocket(conn != null ? conn.socket : null);
                    setMuxConnected(false);
                }
                if (stopped.get()) break;
                long wait = delay;
                delay = Math.min(delay * 2, MAX_BACKOFF_MS);
                try {
                    Thread.sleep(wait);
                } catch (InterruptedException ie) {
                    break;
                }
            }
        }
    }

    // ==================== WebSocket 连接与握手 ====================

    /** 已连接通道：socket + 握手后继续读取所用的缓冲输入流（避免握手缓冲的帧字节丢失）。 */
    private static final class Conn {
        final Socket socket;
        final BufferedInputStream in;

        Conn(Socket socket, BufferedInputStream in) {
            this.socket = socket;
            this.in = in;
        }
    }

    /**
     * 建立 WS 连接：HTTP Upgrade 握手（校验 101 与 Sec-WebSocket-Accept）。
     * <p>新版握手必须携带 {@code Cookie: <DshAuth.cookieHeader()>} 与 {@code Host: 127.0.0.1:3081}。
     */
    private Conn openSocket(String path) throws IOException {
        URL u;
        try {
            u = new URL(baseUrl);
        } catch (Exception e) {
            throw new IOException("invalid base url: " + baseUrl);
        }
        String scheme = u.getProtocol();
        if (!"http".equalsIgnoreCase(scheme)) {
            throw new IOException("EventStream only supports http base url (got " + scheme + "), use http://127.0.0.1:3081");
        }
        String host = u.getHost();
        int port = u.getPort();
        if (port < 0) port = 80;

        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(0);
            socket.setTcpNoDelay(true);
            OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 2048);

            byte[] nonce = new byte[16];
            new SecureRandom().nextBytes(nonce);
            String key = base64Encode(nonce);

            String cookieHeader = com.aptuidsh.kui.env.DshAuth.cookieHeader();
            StringBuilder req = new StringBuilder(256);
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(":").append(port).append("\r\n");
            if (cookieHeader != null && !cookieHeader.isEmpty()) {
                req.append("Cookie: ").append(cookieHeader).append("\r\n");
            }
            req.append("Upgrade: websocket\r\n");
            req.append("Connection: Upgrade\r\n");
            req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
            req.append("Sec-WebSocket-Version: 13\r\n");
            req.append("\r\n");
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();

            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 16384);
            String statusLine = readHeaderLine(in);
            if (statusLine == null) throw new IOException("websocket handshake: no response");
            if (!statusLine.contains(" 101 ")) {
                throw new IOException("websocket handshake failed: " + statusLine);
            }
            String accept = null;
            String line;
            while ((line = readHeaderLine(in)) != null && line.length() > 0) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String name = line.substring(0, idx).trim().toLowerCase();
                    String value = line.substring(idx + 1).trim();
                    if ("sec-websocket-accept".equals(name)) accept = value;
                }
            }
            if (accept != null) {
                String expect = wsAccept(key);
                if (!expect.equals(accept)) {
                    throw new IOException("websocket handshake: bad Sec-WebSocket-Accept");
                }
            }
            return new Conn(socket, in);
        } catch (IOException e) {
            closeSocket(socket);
            throw e;
        } catch (RuntimeException e) {
            closeSocket(socket);
            throw new IOException("websocket handshake error: " + e.getMessage(), e);
        }
    }

    /** 按行读取握手响应头（兼容 \r\n 与 \n）。 */
    private static String readHeaderLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        while (true) {
            int b = in.read();
            if (b < 0) {
                return sb.length() > 0 ? sb.toString() : null;
            }
            if (b == '\n') {
                String s = sb.toString();
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            sb.append((char) b);
            if (sb.length() > MAX_HEADER_LINE) throw new IOException("handshake header line too long");
        }
    }

    // ==================== 帧泵（mux 连接） ====================

    /** 循环读帧并分发，直到连接关闭/异常/stop()。 */
    private void pumpMux(Conn conn) throws IOException {
        InputStream in = conn.in;
        OutputStream out = conn.socket.getOutputStream();
        boolean fragmenting = false;
        ByteArrayOutputStream fragment = new ByteArrayOutputStream(1024);

        // 连接建立后立即 open 固定流：$events（全局事件）+ session/control（控制帧）+ 当前 follow 会话
        sendMuxOpenEvents();
        sendMuxOpenControl();
        synchronized (lock) {
            for (String sid : followSessions) {
                sendMuxOpenFollow(sid);
            }
        }

        while (!stopped.get()) {
            Frame f = readFrame(in);
            if (f == null) {
                return; // EOF / close
            }
            switch (f.opcode) {
                case 0x1: // 文本帧
                    if (f.fin) {
                        handleText(f.payload);
                    } else {
                        fragment.reset();
                        fragment.write(f.payload);
                        fragmenting = true;
                    }
                    break;
                case 0x0: // 续帧
                    if (fragmenting) {
                        fragment.write(f.payload);
                        if (f.fin) {
                            byte[] all = fragment.toByteArray();
                            fragment.reset();
                            fragmenting = false;
                            handleText(all);
                        }
                    }
                    break;
                case 0x9: // ping → 回 pong（客户端帧须掩码）
                    sendFrame(out, 0xA, f.payload);
                    break;
                case 0xA: // pong：忽略
                    break;
                case 0x8: // close：服务端主动关闭
                    return;
                default: // 二进制/未知：忽略
                    break;
            }
        }
    }

    private void handleText(byte[] payload) {
        String s = new String(payload, StandardCharsets.UTF_8);
        if (s.length() == 0) return;
        JSONObject msg;
        try {
            msg = new JSONObject(s);
        } catch (JSONException e) {
            return; // 非 JSON（心跳等）过滤
        }
        String type = msg.optString("type", "");
        if ("item".equals(type)) {
            handleMuxItem(msg);
        } else if ("error".equals(type)) {
            // 服务端对 open/cancel 的应答错误；按需可升级为连接状态诊断，目前静默
        }
        // end/close 等帧暂不做特别处理
    }

    private void handleMuxItem(JSONObject msg) {
        String streamId = msg.optString("streamId", "");
        Object value = msg.opt("value");
        if (value == null || !(value instanceof JSONObject)) return;
        JSONObject item = (JSONObject) value;
        String itemType = item.optString("type", "");

        if (STREAM_ID_EVENTS.equals(streamId)) {
            dispatchHostItem(item, itemType);
            return;
        }

        if (streamId.startsWith(PREFIX_FOLLOW)) {
            String sessionId = streamId.substring(PREFIX_FOLLOW.length());
            dispatchFollowItem(sessionId, item, itemType);
            return;
        }

        if (streamId.startsWith(PREFIX_CTRL)) {
            dispatchControlItem(item, itemType);
            return;
        }
    }

    // ==================== 旧信封合成 ====================

    /** 合成旧 server-request 信封（mux 通道）。 */
    private static JSONObject muxEnvelope(String method, JSONObject payload) {
        JSONObject envelope = new JSONObject();
        try {
            envelope.put("type", "server-request");
            envelope.put("rpcId", UUID.randomUUID().toString());
            envelope.put("method", method);
            envelope.put("payload", payload == null ? new JSONObject() : payload);
        } catch (JSONException ignored) {
        }
        return envelope;
    }

    /** 合成旧 server-request 信封（host 通道）。 */
    private static JSONObject hostEnvelope(String method, JSONObject payload) {
        return muxEnvelope(method, payload);
    }

    // ==================== $events（全局事件 → host 等价帧） ====================

    private void dispatchHostItem(JSONObject item, String itemType) {
        // ready 帧携带本连接代的 clientId —— 回执（$events/result）必须带它，先登记
        if ("ready".equals(itemType)) {
            WaterfallRegistry.setClientId(item.optString("clientId", ""));
            return;
        }
        // waterfall 帧 = 需要客户端给出 outcome 的请求（审批 / 提问）。
        // 实测外壳：
        //   {"type":"waterfall","event":"user-questions/request",
        //    "eventId":"31a228f3-…","agentId":"session-…",
        //    "request":{"questions":[{"id":"color",…,"options":[…]}]}}
        if ("waterfall".equals(itemType)) {
            dispatchWaterfall(item);
            return;
        }
        if ("emit".equals(itemType)) {
            // 官方 Cordis emit 事件经 $events 下发，旧 host 通道转发的是 host/remote-event 信封
            String event = item.optString("event", "");
            Object argsObj = item.opt("args");
            JSONObject payload = new JSONObject();
            try {
                payload.put("type", "host/remote-event");
                payload.put("event", event);
                if (argsObj != null) {
                    payload.put("args", argsObj);
                }
            } catch (JSONException ignored) {
            }
            JSONObject envelope = hostEnvelope("host/remote-event", payload);
            ModelCatalogSignals.acceptHostEnvelope(envelope);
            DshEventListener l = listener;
            if (l != null) l.onHostEvent(envelope);
        }
    }

    /**
     * waterfall 帧 → 旧版待应答帧。
     *
     * <p>新协议把审批/提问做成 {@code approval/request}、{@code user-questions/request}
     * 两个 waterfall 事件，统一带 {@code eventId}；旧 UI 认的是
     * {@code approval/requested} 与 {@code question/requested} 两个 mux 帧，
     * 且把 rpcId 当作应答凭据。这里把 {@code eventId} 同时写进信封 rpcId，
     * 使 {@code DshClient.respond} 能原样把它映射回 {@code $events/result}。
     */
    private void dispatchWaterfall(JSONObject item) {
        String event = item.optString("event", "");
        String eventId = item.optString("eventId", "");
        String agentId = item.optString("agentId", "");
        JSONObject request = item.optJSONObject("request");
        if (eventId.isEmpty()) return;

        WaterfallRegistry.record(eventId, event, agentId, request == null ? new JSONObject() : request);

        String legacyType;
        if ("user-questions/request".equals(event)) {
            legacyType = "question/requested";
        } else if ("approval/request".equals(event)) {
            legacyType = "approval/requested";
        } else {
            return; // 其它 waterfall 事件暂不映射
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("type", legacyType);
            payload.put("sessionId", agentId);
            payload.put("rpcId", eventId);
            if ("question/requested".equals(legacyType)) {
                payload.put("questions", request == null
                        ? new org.json.JSONArray() : request.optJSONArray("questions"));
                payload.put("questionId", eventId);
            } else {
                payload.put("approvalId", eventId);
                if (request != null) {
                    if (request.has("toolName")) payload.put("toolName", request.optString("toolName", ""));
                    if (request.has("callId")) payload.put("callId", request.optString("callId", ""));
                    if (request.has("reason")) payload.put("reason", request.optString("reason", ""));
                }
            }
        } catch (JSONException ignored) {
            return;
        }

        JSONObject envelope = muxEnvelope(legacyType, payload);
        try {
            envelope.put("rpcId", eventId);
        } catch (JSONException ignored) {
        }
        updatePendingInteractionView(envelope, payload, legacyType);
        DshEventListener l = listener;
        if (l != null) l.onMuxEvent(envelope);
    }

    // ==================== session/follow（会话实时流 → mux 等价帧） ====================

    /**
     * 把新 follow item 翻译回旧 mux 信封，驱动 UI 现有的 {@code onMuxEvent / onTextDelta / onStreamEnd}。
     *
     * <p><b>实测到的 $events 与 session/follow item 真实外壳</b>（0.1.5-rc.1）：
     * <pre>
     * // 全局事件流 ready
     * {"type":"item","streamId":"dsh-ev-host","value":{"type":"ready","clientId":"...","host":{"home":"/root"}}}
     *
     * // 全局 Cordis emit（目录失效信号等）
     * {"type":"item","streamId":"dsh-ev-host","value":{"type":"emit","event":"api-session/status","args":[...]}}
     *
     * // 会话 follow 快照
     * {"type":"item","streamId":"dsh-follow:<sid>","value":{"type":"snapshot","header":{...},"cursor":2,"records":[...],"hasMore":false,"projections":{...},"assistantStream":{"revision":0}}}
     *
     * // 会话 follow 事件
     * {"type":"item","streamId":"dsh-follow:<sid>","value":{"type":"event","event":{"type":"turn/start","seq":4,"time":...,"data":{...}}}}
     *
     * // 会话 follow assistant 流
     * {"type":"item","streamId":"dsh-follow:<sid>","value":{"type":"assistant-stream","frame":{"type":"start|chunk|end",...}}}
     * </pre>
     */
    private void dispatchFollowItem(String sessionId, JSONObject item, String itemType) {
        if ("snapshot".equals(itemType)) {
            // 首帧快照：翻译为旧 session/event + session/subscribed
            dispatchSnapshotAsSessionEvent(sessionId, item);
            return;
        }

        if ("event".equals(itemType)) {
            JSONObject event = item.optJSONObject("event");
            if (event == null) return;

            // 审批/提问交互帧：无论有无监听器都先维护 pending 视图
            String eventTypeName = event.optString("type", "");
            if (isPendingInteractionEventType(eventTypeName)) {
                JSONObject envelope = buildSessionEventEnvelope(sessionId, event);
                synchronized (frameLock) {
                    updatePendingInteractionView(envelope, event, eventTypeName);
                    if (listener == null) return;
                }
            }

            // 正常走旧 session/event 信封路径
            JSONObject envelope = buildSessionEventEnvelope(sessionId, event);
            DshEventListener l = listener;
            if (l != null) l.onMuxEvent(envelope);

            handleSessionEvent(sessionId, event);
            return;
        }

        if ("assistant-stream".equals(itemType)) {
            JSONObject frame = item.optJSONObject("frame");
            if (frame == null) return;
            String frameType = frame.optString("type", "");
            if ("chunk".equals(frameType)) {
                handleAssistantChunk(sessionId, frame);
            } else if ("end".equals(frameType)) {
                handleAssistantEnd(sessionId, frame);
            }
            // start 帧：暂不做特别处理，由 event 侧的 turn/start 覆盖
            return;
        }
    }

    private void dispatchSnapshotAsSessionEvent(String sessionId, JSONObject snapshot) {
        // 按 records 顺序投递每条事件帧，模拟旧 events.mux 重放
        DshEventListener l = listener;
        if (l == null) return;

        // 优先按 records 数组投递（官方 follow 里 records 为事件数组）
        // records 每条形如 {"type":"event","event":{...}}
        // 由于旧 UI 主要靠 event.type 判断，这里逐条投递
        org.json.JSONArray recordsArr = snapshot.optJSONArray("records");
        if (recordsArr != null) {
            for (int i = 0; i < recordsArr.length(); i++) {
                JSONObject rec = recordsArr.optJSONObject(i);
                if (rec == null) continue;
                JSONObject ev = rec.optJSONObject("event");
                if (ev == null) continue;
                JSONObject envelope = buildSessionEventEnvelope(sessionId, ev);
                l.onMuxEvent(envelope);
                handleSessionEvent(sessionId, ev);
            }
        }

        // 快照里的投影帧也要投递一次，否则 UI 不会刷新 permissions/modelSelection 等
        JSONObject projections = snapshot.optJSONObject("projections");
        if (projections != null) {
            JSONObject values = projections.optJSONObject("values");
            if (values != null) {
                // 逐 key 投递 projection
                for (java.util.Iterator<String> it = values.keys(); it.hasNext(); ) {
                    String key = it.next();
                    Object value = values.opt(key);
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("type", "session/projection");
                        payload.put("sessionId", sessionId);
                        payload.put("key", key);
                        payload.put("value", value);
                    } catch (JSONException ignored) {
                    }
                    l.onMuxEvent(muxEnvelope("session/projection", payload));
                }
            }
        }
    }

    private JSONObject buildSessionEventEnvelope(String sessionId, JSONObject event) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "session/event");
            payload.put("sessionId", sessionId);
            payload.put("event", event);
        } catch (JSONException ignored) {
        }
        return muxEnvelope("session/event", payload);
    }

    /** 该 follow event.type 是否属于"审批/提问请求与解决"(需维护视图的交互帧)。 */
    private static boolean isPendingInteractionEventType(String type) {
        return "approval/requested".equals(type) || "approval/resolved".equals(type)
            || "question/requested".equals(type) || "question/resolved".equals(type);
    }

    /** 更新进程级 pending 视图(调用方须持有 frameLock):requested 记录、resolved 删除。 */
    private void updatePendingInteractionView(JSONObject envelope, JSONObject event, String type) {
        JSONObject data = event.optJSONObject("data");
        String sid = event.optString("sessionId", "");
        if (sid.isEmpty() && data != null) sid = data.optString("sessionId", "");
        boolean approval = type.startsWith("approval/");
        boolean requested = type.endsWith("/requested");
        String id = approval
            ? event.optString("approvalId", event.optString("id", ""))
            : (requested
                ? envelope.optString("rpcId", "")
                : event.optString("questionRpcId", event.optString("rpcId", "")));
        if (sid.isEmpty() || id.isEmpty()) return;
        String key = sid + "|" + (approval ? "a" : "q") + "|" + id;
        if (requested) {
            pendingInteractionFrames.remove(key);
            pendingInteractionFrames.put(key, envelope);
            if (pendingInteractionFrames.size() > MAX_PENDING_INTERACTION_FRAMES) {
                Iterator<String> it = pendingInteractionFrames.keySet().iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        } else {
            pendingInteractionFrames.remove(key);
        }
    }

    /** 当前仍待处理的审批/提问请求帧快照(供 UI 进入会话时恢复卡片)。 */
    public List<JSONObject> snapshotPendingInteractions() {
        synchronized (frameLock) {
            return new ArrayList<>(pendingInteractionFrames.values());
        }
    }

    /** 从 session/event 帧派生流式回调。 */
    private void handleSessionEvent(String sessionId, JSONObject event) {
        String et = event.optString("type", "");
        if ("turn/start".equals(et)) {
            return;
        }
        if ("assistant/message".equals(et)) {
            DshEventListener l = listener;
            if (l != null) l.onStreamEnd(sessionId);
            return;
        }
        if ("turn/end".equals(et)) {
            DshEventListener l = listener;
            if (l != null) l.onStreamEnd(sessionId);
        }
    }

    /** assistant-stream chunk 处理：text-delta → onTextDelta；其它 chunk 经 onMuxEvent 原样投递。 */
    private void handleAssistantChunk(String sessionId, JSONObject frame) {
        JSONObject chunk = frame.optJSONObject("chunk");
        if (chunk == null) return;
        String chunkType = chunk.optString("type", "");
        if ("text-delta".equals(chunkType)) {
            String text = chunk.optString("text", "");
            if (text.isEmpty()) return;
            JSONObject meta = new JSONObject();
            try {
                meta.put("attemptId", frame.optString("attemptId", ""));
                meta.put("revision", frame.optInt("revision", 0));
                meta.put("index", frame.optInt("index", 0));
            } catch (JSONException ignored) {
            }
            DshEventListener l = listener;
            if (l != null) l.onTextDelta(sessionId, text, meta);
            return;
        }

        // 非 text-delta chunk（block-start/block-end/usage/finish 等）→ 翻译为旧 assistant/chunk 信封
        DshEventListener l = listener;
        if (l == null) return;
        JSONObject event = new JSONObject();
        try {
            event.put("type", "assistant/chunk");
            JSONObject data = new JSONObject();
            data.put("chunk", chunk);
            event.put("data", data);
        } catch (JSONException ignored) {
        }
        l.onMuxEvent(buildSessionEventEnvelope(sessionId, event));
    }

    /** assistant-stream end 处理：最终成功/放弃 → onStreamEnd。 */
    private void handleAssistantEnd(String sessionId, JSONObject frame) {
        DshEventListener l = listener;
        if (l != null) l.onStreamEnd(sessionId);
    }

    // ==================== session/control（控制帧 → mux projection/queue 信封） ====================

    private void dispatchControlItem(JSONObject item, String itemType) {
        if ("baseline".equals(itemType) || "update".equals(itemType)) {
            dispatchControlProjections(item);
            dispatchControlQueues(item);
        }
    }

    private void dispatchControlProjections(JSONObject item) {
        DshEventListener l = listener;
        if (l == null) return;
        JSONObject value = item.optJSONObject("value");
        if (value == null) return;
        JSONObject projections = value.optJSONObject("projections");
        if (projections == null) return;
        for (java.util.Iterator<String> it = projections.keys(); it.hasNext(); ) {
            String sessionId = it.next();
            JSONObject projValues = projections.optJSONObject(sessionId);
            if (projValues == null) continue;
            JSONObject values = projValues.optJSONObject("values");
            if (values == null) continue;
            for (java.util.Iterator<String> k = values.keys(); k.hasNext(); ) {
                String key = k.next();
                Object val = values.opt(key);
                JSONObject payload = new JSONObject();
                try {
                    payload.put("type", "session/projection");
                    payload.put("sessionId", sessionId);
                    payload.put("key", key);
                    payload.put("value", val);
                } catch (JSONException ignored) {
                }
                l.onMuxEvent(muxEnvelope("session/projection", payload));
            }
        }
    }

    private void dispatchControlQueues(JSONObject item) {
        DshEventListener l = listener;
        if (l == null) return;
        JSONObject value = item.optJSONObject("value");
        if (value == null) return;
        JSONObject queues = value.optJSONObject("queues");
        if (queues == null) return;
        for (java.util.Iterator<String> it = queues.keys(); it.hasNext(); ) {
            String sessionId = it.next();
            org.json.JSONArray arr = queues.optJSONArray(sessionId);
            if (arr == null) continue;
            JSONObject payload = new JSONObject();
            try {
                payload.put("type", "session/queue");
                payload.put("sessionId", sessionId);
                payload.put("items", arr);
            } catch (JSONException ignored) {
            }
            l.onMuxEvent(muxEnvelope("session/queue", payload));
        }
    }

    // ==================== 发送 mux open/cancel ====================

    private void sendMuxOpenEvents() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("args", new JSONObject());
        } catch (JSONException ignored) {
        }
        sendMuxOpen(STREAM_ID_EVENTS, "$events", payload);
    }

    private void sendMuxOpenControl() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("args", new JSONObject());
        } catch (JSONException ignored) {
        }
        sendMuxOpen(PREFIX_CTRL + "global", "session/control", payload);
    }

    private void sendMuxOpenFollow(String sessionId) {
        JSONObject payload = new JSONObject();
        try {
            JSONObject request = new JSONObject();
            JSONObject address = new JSONObject();
            address.put("kind", "session");
            address.put("sessionId", sessionId);
            request.put("address", address);
            request.put("assistantStream", true);
            payload.put("args", new JSONObject().put("request", request));
        } catch (JSONException ignored) {
        }
        sendMuxOpen(PREFIX_FOLLOW + sessionId, "session/follow", payload);
    }

    private void sendMuxCancel(String streamId) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "cancel");
            msg.put("streamId", streamId);
        } catch (JSONException ignored) {
        }
        sendTextFrame(msg.toString());
    }

    private void sendMuxOpen(String streamId, String endpoint, JSONObject payload) {
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "open");
            msg.put("streamId", streamId);
            msg.put("endpoint", endpoint);
            msg.put("payload", payload == null ? new JSONObject() : payload);
        } catch (JSONException ignored) {
        }
        sendTextFrame(msg.toString());
    }

    private void sendTextFrame(String text) {
        Socket s = muxSocket;
        if (s == null || s.isClosed()) return;
        try {
            OutputStream out = s.getOutputStream();
            synchronized (out) {
                sendFrame(out, 0x1, text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
            // 发送失败时由读帧侧检测断连并触发重连
        }
    }

    // ==================== 连接状态回调 ====================

    private void fireConnected(String channel) {
        ConnectionListener cl = connectionListener;
        if (cl != null) {
            try {
                cl.onConnected(channel);
            } catch (Exception ignored) {
            }
        }
    }

    private void fireDisconnected(String channel, String reason) {
        ConnectionListener cl = connectionListener;
        if (cl != null) {
            try {
                cl.onDisconnected(channel, reason);
            } catch (Exception ignored) {
            }
        }
    }

    // ==================== RFC 6455 帧 ====================

    private static final class Frame {
        final boolean fin;
        final int opcode;
        final byte[] payload;

        Frame(boolean fin, int opcode, byte[] payload) {
            this.fin = fin;
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    /** 读一帧；EOF/close 返回 null。 */
    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) throw new EOFException("websocket: truncated frame header");
        boolean fin = (b0 & 0x80) != 0;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            int hi = in.read();
            int lo = in.read();
            if (hi < 0 || lo < 0) throw new EOFException("websocket: truncated 16-bit length");
            len = ((long) hi << 8) | lo;
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                int v = in.read();
                if (v < 0) throw new EOFException("websocket: truncated 64-bit length");
                len = (len << 8) | v;
            }
        }
        if (len < 0 || len > MAX_FRAME_BYTES) throw new IOException("websocket: frame too large (" + len + ")");
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask, 0, 4);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload, 0, payload.length);
        if (masked) {
            for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
        }
        return new Frame(fin, b0 & 0x0F, payload);
    }

    /** 发送一帧（客户端帧须掩码）。用于回 pong 与发送文本帧。 */
    private static void sendFrame(OutputStream out, int opcode, byte[] payload) throws IOException {
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        int len = payload != null ? payload.length : 0;
        out.write(0x80 | (opcode & 0x0F)); // FIN + opcode
        if (len < 126) {
            out.write(0x80 | len); // MASK + len7
        } else if (len < 65536) {
            out.write(0x80 | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) out.write((int) ((len >> (8 * i)) & 0xFF));
        }
        out.write(mask);
        if (payload != null) {
            for (int i = 0; i < payload.length; i++) out.write(payload[i] ^ mask[i & 3]);
        }
        out.flush();
    }

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int read = 0;
        while (read < len) {
            int n = in.read(buf, off + read, len - read);
            if (n < 0) throw new EOFException("websocket: unexpected EOF");
            read += n;
        }
    }

    // ==================== 工具 ====================

    private static String wsAccept(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(key.getBytes(StandardCharsets.US_ASCII));
            md.update(WS_GUID.getBytes(StandardCharsets.US_ASCII));
            return base64Encode(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    /** 极简 Base64 编码（避免 android.util.Base64 的 API 26 要求与桌面 JVM 依赖）。 */
    private static String base64Encode(byte[] data) {
        final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();
        StringBuilder sb = new StringBuilder(((data.length + 2) / 3) * 4);
        int i = 0;
        while (i + 2 < data.length) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8) | (data[i + 2] & 0xFF);
            sb.append(ALPHABET[(v >> 18) & 0x3F]).append(ALPHABET[(v >> 12) & 0x3F])
                    .append(ALPHABET[(v >> 6) & 0x3F]).append(ALPHABET[v & 0x3F]);
            i += 3;
        }
        int rem = data.length - i;
        if (rem == 1) {
            int v = (data[i] & 0xFF) << 16;
            sb.append(ALPHABET[(v >> 18) & 0x3F]).append(ALPHABET[(v >> 12) & 0x3F]).append("==");
        } else if (rem == 2) {
            int v = ((data[i] & 0xFF) << 16) | ((data[i + 1] & 0xFF) << 8);
            sb.append(ALPHABET[(v >> 18) & 0x3F]).append(ALPHABET[(v >> 12) & 0x3F])
                    .append(ALPHABET[(v >> 6) & 0x3F]).append('=');
        }
        return sb.toString();
    }

    private static String describe(Exception e) {
        String m = e.getMessage();
        return m != null && m.length() > 0 ? m : e.getClass().getSimpleName();
    }

    private static String normalizeBase(String url) {
        if (url == null) return DEFAULT_BASE_URL;
        String b = url.trim();
        if (b.length() == 0) return DEFAULT_BASE_URL;
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private void setMuxSocket(Socket socket) {
        muxSocket = socket;
    }

    private void setMuxConnected(boolean connected) {
        muxConnected = connected;
        if (!connected) {
            // 连接代结束:清空 pending 视图(重连后的 follow item 会重建它,避免陈旧残留)。
            synchronized (frameLock) {
                pendingInteractionFrames.clear();
            }
            // 旧连接代的 eventId/clientId 在新连接里已失效，一并作废（重连会重放仍待处理的帧）
            WaterfallRegistry.clear();
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void joinQuietly(Thread t) {
        if (t == null) return;
        try {
            t.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
