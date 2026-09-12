package com.aptuidsh.kui.net;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

import com.aptuidsh.kui.env.DshAuth;

/**
 * 一次性网关流客户端：开一条逻辑流、读回一个 item、立即关闭。
 *
 * <h3>用途</h3>
 * 主要服务于「取会话历史」。dsh ≥ 0.1.5 的 {@code session/page} 要求
 * {@code throughSeq} <b>不能超过会话当前游标</b>，实测超了会直接报错：
 * <pre>
 * {"ok":false,"error":{"code":"gateway/bad-request",
 *   "message":"session page through seq 2147483647 is past cursor 2"}}
 * </pre>
 * 而游标只在 {@code session/follow} 的 opening snapshot 里才有。官方客户端正是
 * 「先 follow 拿快照，再按需 page 往前翻」。本类把前半步封装成一次同步调用，
 * 让上层可以像调普通 RPC 一样拿到历史记录。
 *
 * <p>实现是极简 RFC6455 客户端（握手 + 文本帧），零第三方依赖，与
 * {@link EventStream} 中的实现同源但更精简——只处理「一个流、一个 item」。
 */
public final class MuxClient {

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_FRAME_BYTES = 32 * 1024 * 1024;
    private static final int MAX_HEADER_LINE = 8 * 1024;

    private MuxClient() {
    }

    /**
     * 打开一条逻辑流并返回第一个 {@code item} 的 {@code value}。
     *
     * @param baseUrl  HTTP 基址（如 http://127.0.0.1:3081）
     * @param endpoint 逻辑流端点（如 {@code session/follow}）
     * @param args     该端点的 {@code args} 对象
     * @param timeoutMs 整体超时
     * @return 第一个 item 的 value；超时或出错返回 null
     */
    public static JSONObject openAndFirstItem(String baseUrl, String endpoint,
                                              JSONObject args, int timeoutMs) throws IOException {
        // 注意：不能用 new URL("ws://…") —— java.net.URL 没有 ws 协议处理器，
        // 会抛 MalformedURLException: unknown protocol: ws。
        // 这里保持 http 形式解析出 host/port，WebSocket 握手只用到这两个值 + 路径。
        URL url = new URL(baseUrl);
        String host = url.getHost();
        int port = url.getPort() < 0 ? 80 : url.getPort();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), Math.min(timeoutMs, 8000));
            socket.setSoTimeout(timeoutMs);

            handshake(socket, "/api/remote.mux", host + ":" + port);

            JSONObject open = new JSONObject();
            try {
                open.put("type", "open");
                open.put("streamId", "oneshot");
                open.put("endpoint", endpoint);
                JSONObject payload = new JSONObject();
                payload.put("args", args == null ? new JSONObject() : args);
                open.put("payload", payload);
            } catch (org.json.JSONException e) {
                throw new IOException("build open message failed: " + e.getMessage(), e);
            }
            sendText(socket.getOutputStream(), open.toString());

            long deadline = System.currentTimeMillis() + timeoutMs;
            InputStream in = new BufferedInputStream(socket.getInputStream());
            while (System.currentTimeMillis() < deadline) {
                Frame f = readFrame(in);
                if (f == null) return null;
                if (f.opcode == 0x8) return null;              // close
                if (f.opcode == 0x9) {                          // ping → pong
                    sendControl(socket.getOutputStream(), 0xA, f.payload);
                    continue;
                }
                if (f.opcode != 0x1) continue;                  // 只关心文本帧
                JSONObject msg;
                try {
                    msg = new JSONObject(new String(f.payload, StandardCharsets.UTF_8));
                } catch (org.json.JSONException e) {
                    continue;
                }
                String type = msg.optString("type", "");
                if ("item".equals(type) && "oneshot".equals(msg.optString("streamId", ""))) {
                    return msg.optJSONObject("value");
                }
                if ("error".equals(type)) {
                    throw new IOException("mux stream error: " + msg.optJSONObject("error"));
                }
                if ("end".equals(type)) return null;
            }
            return null;
        } finally {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ 协议细节

    private static void handshake(Socket socket, String path, String host) throws IOException {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String key = base64(nonce);
        String cookie = DshAuth.cookieHeader();
        StringBuilder sb = new StringBuilder();
        sb.append("GET ").append(path).append(" HTTP/1.1\r\n");
        sb.append("Host: ").append(host).append("\r\n");
        sb.append("Upgrade: websocket\r\n");
        sb.append("Connection: Upgrade\r\n");
        sb.append("Sec-WebSocket-Version: 13\r\n");
        sb.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        if (cookie != null && !cookie.isEmpty()) {
            sb.append("Cookie: ").append(cookie).append("\r\n");
        }
        sb.append("\r\n");
        OutputStream out = socket.getOutputStream();
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = socket.getInputStream();
        String status = readHeaderLine(in);
        if (status == null || !status.contains("101")) {
            throw new IOException("WebSocket upgrade failed: " + status);
        }
        while (true) {
            String line = readHeaderLine(in);
            if (line == null || line.isEmpty()) break;
        }
    }

    private static String readHeaderLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
            if (buf.size() > MAX_HEADER_LINE) throw new IOException("header line too long");
        }
        if (c == -1 && buf.size() == 0) return null;
        return new String(buf.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static void sendText(OutputStream out, String text) throws IOException {
        sendControl(out, 0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private static synchronized void sendControl(OutputStream out, int opcode, byte[] payload)
            throws IOException {
        byte[] data = payload == null ? new byte[0] : payload;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(data.length + 14);
        frame.write(0x80 | opcode);
        if (data.length < 126) {
            frame.write(0x80 | data.length);          // 掩码位必须置位（客户端→服务端）
        } else if (data.length < 65536) {
            frame.write(0x80 | 126);
            frame.write((data.length >> 8) & 0xFF);
            frame.write(data.length & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) {
                frame.write((int) (((long) data.length >> (8 * i)) & 0xFF));
            }
        }
        byte[] mask = new byte[4];
        new SecureRandom().nextBytes(mask);
        frame.write(mask, 0, 4);
        for (int i = 0; i < data.length; i++) {
            frame.write(data[i] ^ mask[i & 3]);
        }
        out.write(frame.toByteArray());
        out.flush();
    }

    private static final class Frame {
        int opcode;
        byte[] payload;
    }

    private static Frame readFrame(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 == -1) return null;
        int b1 = in.read();
        if (b1 == -1) return null;
        Frame f = new Frame();
        f.opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte(in);
            }
        }
        if (len < 0 || len > MAX_FRAME_BYTES) {
            throw new IOException("frame too large: " + len);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] data = new byte[(int) len];
        readFully(in, data);
        if (masked) {
            for (int i = 0; i < data.length; i++) {
                data[i] ^= mask[i & 3];
            }
        }
        f.payload = data;
        return f;
    }

    private static int readByte(InputStream in) throws IOException {
        int v = in.read();
        if (v == -1) throw new IOException("unexpected EOF");
        return v;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("unexpected EOF");
            off += n;
        }
    }

    private static String base64(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
    }

    /** 供外部拼 WebSocket 接受键校验（当前未用，保留以便排查）。 */
    static String acceptKey(String key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        return base64(md.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII)));
    }
}
