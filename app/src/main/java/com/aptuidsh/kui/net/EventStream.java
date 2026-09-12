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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DSH 后端双事件通道客户端（devcrew 契约 2）。
 *
 * <p><b>重要（依据 docs/protocol-notes.md §3 实测）</b>：{@code /api/events.host} 与
 * {@code /api/events.mux} <b>不是 SSE</b>——普通 HTTP GET 返回 426 upgrade required，
 * 两通道均为 <b>WebSocket downlink</b>（只下发、不接收客户端数据帧）。因此本类实现
 * 极简 WebSocket 客户端：HTTP Upgrade 握手（Sec-WebSocket-Key/Accept 校验）+ RFC 6455
 * 帧解析（文本帧、分片、ping/pong、close），零第三方依赖（仅 JDK + android.jar org.json）。
 *
 * <p>两条通道各由一个独立守护线程驱动，永不阻塞主线程；断线后指数退避自动重连
 * （3s 起、每次翻倍、上限 30s，握手成功即重置）。
 *
 * <p>帧信封：{@code {"type":"server-request","rpcId":"<uuid>","method":"<payload.type>","payload":{...}}}，
 * 事件类型 = {@code payload.type}。mux 通道的 {@code session/event} 帧内
 * {@code assistant/chunk}（{@code chunk.type="text-delta"}）为流式正文增量 → 触发
 * {@link DshEventListener#onTextDelta}；{@code assistant/message}（最终消息）与
 * {@code turn/end}（reason=aborted/error 且未发消息时）→ 触发
 * {@link DshEventListener#onStreamEnd}。全部帧同时按通道原样分发到
 * {@code onMuxEvent}/{@code onHostEvent}，供 UI 订阅 projection/jobs/approval 等原始帧。
 *
 * <p><b>host 通道的目录失效信号</b>：Host 会经 events.host 转发
 * {@code {type:"host/remote-event", event:"llm/adapters-updated"|"settings/document-updated", args:[...]}}
 * 帧（官方 client 端 {@code ctx.remote.$on} 的同一批事件，见 dsh-host-apiproxy 的
 * API_REMOTE_FORWARDED_EVENTS 转发循环）。这两条帧在分发前统一交给
 * {@link ModelCatalogSignals} 记录版本号，与是否有 UI 监听器无关。
 *
 * <p>线程模型：所有回调在通道工作线程（daemon）上执行，调用方自行决定是否切回 UI 线程；
 * 本类绝不触碰主线程。
 */
public class EventStream {

    /** 事件监听器（契约 2）：帧分发 + 流式回调。 */
    public interface DshEventListener {
        /** events.host 通道的一帧（envelope 原样），按 payload.type 分发（host/session-status 等）。 */
        void onHostEvent(JSONObject envelope);

        /** events.mux 通道的一帧（envelope 原样），按 payload.type 分发（session/event、projection、jobs 等）。 */
        void onMuxEvent(JSONObject envelope);

        /** 流式正文增量：session/event 内 assistant/chunk 且 chunk.type=text-delta。meta 含 seq/turn/step/index。 */
        void onTextDelta(String sessionId, String delta, JSONObject meta);

        /** 流结束：assistant/message（正常/中断含内容）或 turn/end（aborted/error 且无消息）。 */
        void onStreamEnd(String sessionId);
    }

    /** 可选连接状态监听（M1 连接状态页 / M2 重连提示用；非契约 2 强制）。 */
    public interface ConnectionListener {
        /** 通道建立成功（握手 101 完成）。channel 为 {@link #CH_HOST} 或 {@link #CH_MUX}。 */
        void onConnected(String channel);

        /** 通道断开（异常或服务端关闭）。reason 为人类可读原因。 */
        void onDisconnected(String channel, String reason);
    }

    /** events.host 通道名。 */
    public static final String CH_HOST = "host";
    /** events.mux 通道名。 */
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

    private final AtomicBoolean stopped = new AtomicBoolean(true);
    private final Object lock = new Object();
    private final Set<String> streamingSessions = new HashSet<>();

    private volatile String baseUrl;
    private volatile DshEventListener listener;
    private volatile ConnectionListener connectionListener;
    private volatile Socket hostSocket;
    private volatile Socket muxSocket;
    private volatile boolean hostConnected;
    private volatile boolean muxConnected;
    private Thread hostThread;
    private Thread muxThread;

    // ---- 进程级"待处理交互"视图(修复:关掉 APP/重进会话后提问审批卡片无法唤醒) ----
    // 官方 dsh 每次 /api/events.mux 订阅(含 APP 重启后的新连接)都会重放仍待处理的
    // approval/requested、question/requested 帧(rpcId 不变、应答仍有效)。本 APP 事件通道
    // 在 MainActivity 即启动、监听器要进入会话页才挂载;且用户按返回键切走会话页时
    // 进程仍存活(不会再重放)。因此这里持续维护"当前 pending 视图":requested → 记录、
    // resolved → 删除;ChatActivity 进入时主动 snapshotPendingInteractions() 拉取该视图
    // 恢复卡片。mux 断开时清空(重连后的重放帧会重建它,避免陈旧残留)。
    private final Object frameLock = new Object();
    private final Map<String, JSONObject> pendingInteractionFrames = new LinkedHashMap<>();
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
        return CH_MUX.equals(channel) ? muxConnected : hostConnected;
    }

    // ==================== 生命周期 ====================

    /** 启动两条事件通道（幂等）。 */
    public void start() {
        synchronized (lock) {
            if (!stopped.get()) return;
            stopped.set(false);
            streamingSessions.clear();
            hostThread = new Thread(new Worker(CH_HOST, "/api/events.host"), "dsh-ev-host");
            muxThread = new Thread(new Worker(CH_MUX, "/api/events.mux"), "dsh-ev-mux");
            hostThread.setDaemon(true);
            muxThread.setDaemon(true);
            hostThread.start();
            muxThread.start();
        }
    }

    /** 停止两条事件通道并释放连接（幂等；线程 join 至多 2s）。 */
    public void stop() {
        synchronized (lock) {
            if (stopped.get()) return;
            stopped.set(true);
            closeSocket(hostSocket);
            closeSocket(muxSocket);
        }
        joinQuietly(hostThread);
        joinQuietly(muxThread);
        hostThread = null;
        muxThread = null;
        synchronized (this) {
            hostConnected = false;
            muxConnected = false;
        }
        streamingSessions.clear();
    }

    // ==================== 通道工作线程 ====================

    /** 一条事件通道的驱动线程：连接 → 泵帧 → 断线退避重连。 */
    private final class Worker implements Runnable {
        final String channel;
        final String path;

        Worker(String channel, String path) {
            this.channel = channel;
            this.path = path;
        }

        @Override
        public void run() {
            long delay = INITIAL_BACKOFF_MS;
            while (!stopped.get()) {
                Conn conn = null;
                try {

                    conn = openSocket(channel, path);
                    setChannelSocket(channel, conn.socket);
                    if (stopped.get()) break;
                    delay = INITIAL_BACKOFF_MS; // 握手成功 → 退避重置
                    setChannelConnected(channel, true);
                    fireConnected(channel);

                    pump(channel, conn);
                    // 正常/异常结束（服务端关闭、EOF、IO 异常）
                    if (stopped.get()) break;
                    fireDisconnected(channel, "stream closed");

                } catch (Exception e) {
                    if (stopped.get()) break;
                    setChannelConnected(channel, false);
                    fireDisconnected(channel, describe(e));

                } finally {
                    closeSocket(conn != null ? conn.socket : null);
                    setChannelConnected(channel, false);
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
     * 仅支持 http（本地回环明文）；https/wss 明确报错。
     * <p>注意：握手响应与紧随的首批帧可能同段到达，BufferedInputStream 会一并缓冲——
     * 因此返回的 Conn 携带该流，pump() 必须继续使用它，不能重新 getInputStream()。
     */
    private Conn openSocket(String channel, String path) throws IOException {
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

            StringBuilder req = new StringBuilder(256);
            req.append("GET ").append(path).append(" HTTP/1.1\r\n");
            req.append("Host: ").append(host).append(":").append(port).append("\r\n");
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

    // ==================== 帧泵 ====================

    /** 循环读帧并分发，直到连接关闭/异常/stop()。 */
    private void pump(String channel, Conn conn) throws IOException {
        InputStream in = conn.in;
        OutputStream out = conn.socket.getOutputStream();
        boolean fragmenting = false;
        ByteArrayOutputStream fragment = new ByteArrayOutputStream(1024);
        while (!stopped.get()) {
            Frame f = readFrame(in);
            if (f == null) {
                return; // EOF / close
            }

            switch (f.opcode) {
                case 0x1: // 文本帧
                    if (f.fin) {
                        handleText(channel, f.payload);
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
                            handleText(channel, all);
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
                default: // 二进制/未知：忽略（downlink 只用文本帧）
                    break;
            }
        }
    }

    private void handleText(String channel, byte[] payload) {
        String s = new String(payload, StandardCharsets.UTF_8);
        if (s.length() == 0) return; // 空帧过滤
        JSONObject envelope;
        try {
            envelope = new JSONObject(s);
        } catch (JSONException e) {
            return; // 非 JSON（心跳等）过滤
        }
        dispatchEnvelope(channel, envelope);
    }

    // ==================== 分发 ====================

    private void dispatchEnvelope(String channel, JSONObject envelope) {
        JSONObject payload = envelope.optJSONObject("payload");
        if (payload == null) {
            return;
        }
        String type = payload.optString("type", "");
        String sid = payload.optString("sessionId", "");

        // 审批/提问交互帧:无论有无监听器都先更新"当前 pending 视图",
        // 有监听器时再照常实时投递(实时弹窗路径与原版一致)。
        if (CH_MUX.equals(channel) && isPendingInteractionType(type)) {
            synchronized (frameLock) {
                updatePendingInteractionView(envelope, payload, type);
                if (listener == null) return;
            }
        }

        DshEventListener l = listener;

        if (CH_HOST.equals(channel)) {
            // 模型目录实时性(对齐官方 ui-model-selection 的 ModelDirectoryResolver):
            // Host 经 events.host 转发 llm/adapters-updated 与 settings/document-updated,
            // 任一到达即视为「目录已失效」。此处独立于监听器处理——事件通道在 MainActivity
            // 启动、监听器要进会话页才挂载,而目录失效必须在任意时刻都能被记录,
            // 否则用户在设置页改完模型后回到会话页仍看到旧列表。
            ModelCatalogSignals.acceptHostEnvelope(envelope);
            if (l != null) l.onHostEvent(envelope);
            return;
        }
        if (l != null) l.onMuxEvent(envelope);
        if ("session/event".equals(type)) {
            JSONObject event = payload.optJSONObject("event");
            if (event != null) handleSessionEvent(payload.optString("sessionId", ""), event);
        } else if ("session/subscribed".equals(type)) {
            // 新连接代：清掉上一代的流式跟踪，避免误触发 onStreamEnd
            String sids = payload.optString("sessionId", "");
            if (sids.length() > 0) streamingSessions.remove(sids);
        }
    }

    /** 该 mux 帧类型是否属于"审批/提问请求与解决"(需维护视图的交互帧)。 */
    private static boolean isPendingInteractionType(String type) {
        return "approval/requested".equals(type) || "approval/resolved".equals(type)
            || "question/requested".equals(type) || "question/resolved".equals(type);
    }

    /** 更新进程级 pending 视图(调用方须持有 frameLock):requested 记录、resolved 删除。 */
    private void updatePendingInteractionView(JSONObject envelope, JSONObject payload, String type) {
        String sid = payload.optString("sessionId", "");
        boolean approval = type.startsWith("approval/");
        boolean requested = type.endsWith("/requested");
        String id = approval
            ? payload.optString("approvalId", "")
            : (requested
                ? envelope.optString("rpcId", "")
                : payload.optString("questionRpcId", ""));
        if (sid.isEmpty() || id.isEmpty()) return; // 缺会话/缺 id:不可寻址,忽略
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

            if (sessionId.length() > 0) streamingSessions.add(sessionId);
            return;
        }
        if ("assistant/chunk".equals(et)) {
            JSONObject data = event.optJSONObject("data");
            if (data == null) return;
            JSONObject chunk = data.optJSONObject("chunk");
            if (chunk == null) return;
            String ctype = chunk.optString("type", "");
            if (!"text-delta".equals(ctype)) {
                return; // reasoning/tool-call 增量走原始帧
            }
            String text = chunk.optString("text", "");
            if (text.length() == 0) return; // 空 delta 过滤

            JSONObject meta = new JSONObject();
            try {
                meta.put("seq", event.optLong("seq", 0L));
                meta.put("turn", data.optInt("turn", 0));
                meta.put("step", data.optInt("step", 0));
                meta.put("index", chunk.optInt("index", 0));
            } catch (JSONException ignored) {
                // meta 组装失败仍可回调
            }
            DshEventListener l = listener;
            if (l != null) l.onTextDelta(sessionId, text, meta);
            return;
        }
        if ("assistant/message".equals(et)) {
            // 最终消息（含中断/错误时带部分内容）→ 流结束

            streamingSessions.remove(sessionId);
            DshEventListener l = listener;
            if (l != null) l.onStreamEnd(sessionId);
            return;
        }
        if ("turn/end".equals(et)) {
            // 轮次结束：若此前没有 assistant/message（如 abort 于首 token 前/error），补发流结束

            if (streamingSessions.remove(sessionId)) {
                DshEventListener l = listener;
                if (l != null) l.onStreamEnd(sessionId);
            }
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
        if (b0 < 0) return null; // EOF
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

    /** 发送一帧（客户端帧须掩码）。用于回 pong。 */
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

    private void setChannelSocket(String channel, Socket socket) {
        if (CH_MUX.equals(channel)) muxSocket = socket;
        else hostSocket = socket;
    }

    private void setChannelConnected(String channel, boolean connected) {
        if (CH_MUX.equals(channel)) {
            muxConnected = connected;
            if (!connected) {
                // 连接代结束:清空 pending 视图(重连后 events.mux 会重放仍待处理的帧重建它,
                // 避免断线期间被解决/取消的请求残留成陈旧卡片)。
                synchronized (frameLock) {
                    pendingInteractionFrames.clear();
                }
            }
        } else {
            hostConnected = connected;
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
