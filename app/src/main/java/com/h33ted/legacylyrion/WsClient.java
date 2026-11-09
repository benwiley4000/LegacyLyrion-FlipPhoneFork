package com.h33ted.legacylyrion;

import android.os.Build;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal WebSocket client (RFC 6455) over a plain or TLS socket: Android has none built in, and
 * this app has no dependencies. It does just what the LyrionJS bridge needs: one connection, text and
 * binary messages, ping/pong and close. A login can be sent with the upgrade request, the Origin *is*
 * sent (to simulate the browser page), and for wss the server's name is
 * checked against its certificate.
 */
final class WsClient {
    /** The connection is over: a close frame with its code, or 1006 when it just dropped or went silent. */
    static final class Closed extends IOException {
        final int code;

        Closed(int code, String reason) {
            super("closed (" + code + ")" + (reason == null || reason.length() == 0 ? "" : ": " + reason));
            this.code = code;
        }
    }

    static final class Message {
        final boolean text;
        final byte[] data;

        Message(boolean text, byte[] data) {
            this.text = text;
            this.data = data;
        }

        String string() {
            try {
                return new String(data, "UTF-8");
            } catch (Exception e) {
                return "";
            }
        }
    }

    /** Checks a wss server's certificate against its name. Replaceable for tests only. */
    static HostnameVerifier verifier = null;

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 4 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean closed;

    private WsClient(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    /**
     * Opens the connection and completes the upgrade. {@code url} is ws:// or wss://.
     * {@code authorization} (a ready "Basic ..." value, or null) is sent with the upgrade request: a
     * password-protected LMS checks it there. A 401 becomes {@link Auth.Required}.
     */
    static WsClient connect(String url, String authorization, int timeoutMs) throws IOException {
        URL u = new URL(url.replaceFirst("^(?i)ws", "http"));   // ws -> http, wss -> https: only to parse it
        boolean tls = u.getProtocol().equalsIgnoreCase("https");
        String host = u.getHost();
        int port = u.getPort() == -1 ? u.getDefaultPort() : u.getPort();
        String bare = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        String path = u.getFile().length() == 0 ? "/" : u.getFile();

        Socket plain = new Socket();
        Socket sock = plain;
        try {
            plain.connect(new InetSocketAddress(bare, port), timeoutMs);
            plain.setTcpNoDelay(true);
            plain.setKeepAlive(true);
            plain.setSoTimeout(timeoutMs);
            if (tls) {
                SSLSocketFactory f = Build.VERSION.SDK_INT >= 16 && Build.VERSION.SDK_INT < 20
                        ? tls12() : (SSLSocketFactory) SSLSocketFactory.getDefault();   // 4.1-4.4 leave TLS 1.2 off
                SSLSocket ss = (SSLSocket) f.createSocket(plain, bare, port, true);
                sock = ss;
                ss.startHandshake();
                HostnameVerifier hv = verifier != null ? verifier : HttpsURLConnection.getDefaultHostnameVerifier();
                if (!hv.verify(bare, ss.getSession())) {
                    throw new SSLPeerUnverifiedException("certificate is not for " + bare);
                }
            }

            byte[] keyBytes = new byte[16];
            RANDOM.nextBytes(keyBytes);
            String key = base64(keyBytes);
            boolean defaultPort = port == (tls ? 443 : 80);

            String originProto = tls ? "https" : "http";
            String origin = originProto + "://" + host + (defaultPort ? "" : ":" + port);

            StringBuilder req = new StringBuilder();
            req.append("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append(defaultPort ? "" : ":" + port).append("\r\n")
                    .append("Origin: ").append(origin).append("\r\n")
                    .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n");
            if (authorization != null) req.append("Authorization: ").append(authorization).append("\r\n");
            req.append("\r\n");
            OutputStream o = sock.getOutputStream();
            o.write(req.toString().getBytes("US-ASCII"));
            o.flush();

            InputStream i = new BufferedInputStream(sock.getInputStream(), 8192);
            String head = readHead(i);
            String[] lines = head.split("\r\n");
            String[] status = lines[0].split(" ", 3);
            int code = status.length > 1 ? parseInt(status[1]) : 0;
            Map<String, String> h = new HashMap<String, String>();
            for (int n = 1; n < lines.length; n++) {
                int c = lines[n].indexOf(':');
                if (c > 0) h.put(lines[n].substring(0, c).trim().toLowerCase(Locale.US), lines[n].substring(c + 1).trim());
            }
            if (code == 401) {
                String ch = h.get("www-authenticate");
                if (ch == null || ch.regionMatches(true, 0, "Basic", 0, 5)) {
                    throw new Auth.Required(host + ":" + port, realm(ch), authorization != null);
                }
            }
            if (code != 101) {
                throw new IOException("HTTP " + code + (status.length > 2 ? " " + status[2] : ""));
            }
            String upgrade = h.get("upgrade");
            if (upgrade == null || !upgrade.equalsIgnoreCase("websocket")) throw new IOException("not a WebSocket server");
            String want = base64(MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes("US-ASCII")));
            if (!want.equals(h.get("sec-websocket-accept"))) throw new IOException("bad WebSocket handshake");
            return new WsClient(sock, i, o);
        } catch (IOException e) {
            closeQuietly(sock);
            throw e;
        } catch (Exception e) {
            closeQuietly(sock);
            throw new IOException(e.toString());
        }
    }

    private static SSLSocketFactory tls12() throws IOException {
        try {
            return Updater.Tls12.factory();
        } catch (Exception e) {
            throw new IOException("TLS 1.2 is not available: " + e);
        }
    }

    // ------------------------------------------------------------------ messages

    /** Blocks for the next text or binary message. Answers pings; throws {@link Closed} when the connection ends. */
    Message read() throws IOException {
        ByteArrayOutputStream frag = null;
        boolean fragText = false;
        for (;;) {
            int b1 = in.read();
            int b2 = in.read();
            if (b1 < 0 || b2 < 0) throw drop("connection closed");
            boolean fin = (b1 & 0x80) != 0;
            int op = b1 & 0x0f;
            boolean masked = (b2 & 0x80) != 0;
            long len = b2 & 0x7f;
            if (len == 126) {
                len = (readByte() << 8) | readByte();
            } else if (len == 127) {
                len = 0;
                for (int n = 0; n < 8; n++) len = (len << 8) | readByte();
            }
            if (len < 0 || len > MAX_MESSAGE) throw fail(1009, "message too big");
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] data = new byte[(int) len];
            readFully(data);
            if (mask != null) for (int n = 0; n < data.length; n++) data[n] ^= mask[n & 3];

            switch (op) {
                case 0x8: {   // close
                    int code = data.length >= 2 ? ((data[0] & 0xff) << 8) | (data[1] & 0xff) : 1005;
                    String why = data.length > 2 ? new String(data, 2, data.length - 2, "UTF-8") : "";
                    if (!closed) {
                        try {
                            send(0x8, data.length >= 2 ? new byte[]{data[0], data[1]} : new byte[0]);
                        } catch (IOException ignored) {}
                    }
                    shutdown();
                    throw new Closed(code, why);
                }
                case 0x9:   // ping
                    send(0xA, data);
                    break;
                case 0xA:   // pong
                    break;
                case 0x0:   // continuation
                    if (frag == null) throw fail(1002, "unexpected continuation");
                    if (frag.size() + data.length > MAX_MESSAGE) throw fail(1009, "message too big");
                    frag.write(data, 0, data.length);
                    if (fin) return new Message(fragText, frag.toByteArray());
                    break;
                case 0x1:
                case 0x2:
                    if (frag != null) throw fail(1002, "new message inside a fragmented one");
                    if (fin) return new Message(op == 0x1, data);
                    frag = new ByteArrayOutputStream();
                    frag.write(data, 0, data.length);
                    fragText = op == 0x1;
                    break;
                default:
                    throw fail(1002, "unknown opcode");
            }
        }
    }

    void sendText(String s) throws IOException {
        send(0x1, s.getBytes("UTF-8"));
    }

    void sendBinary(byte[] b) throws IOException {
        send(0x2, b);
    }

    /** One masked frame, as clients must send. */
    synchronized void send(int op, byte[] data) throws IOException {
        if (closed) throw new Closed(1006, "already closed");
        int n = data.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(n + 14);
        f.write(0x80 | op);
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n < 65536) {
            f.write(0x80 | 126);
            f.write(n >> 8);
            f.write(n);
        } else {
            f.write(0x80 | 127);
            for (int s = 56; s >= 0; s -= 8) f.write((int) (((long) n >> s) & 0xff));
        }
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        f.write(mask, 0, 4);
        byte[] masked = new byte[n];
        for (int i = 0; i < n; i++) masked[i] = (byte) (data[i] ^ mask[i & 3]);
        f.write(masked, 0, n);
        try {
            out.write(f.toByteArray());
            out.flush();
        } catch (IOException e) {
            shutdown();
            throw e;
        }
    }

    void setReadTimeout(int ms) {
        try {
            socket.setSoTimeout(ms);
        } catch (Exception ignored) {}
    }

    /** Says goodbye with a close code, then drops the connection. */
    void close(int code) {
        if (closed) return;
        try {
            send(0x8, new byte[]{(byte) (code >> 8), (byte) code});
        } catch (IOException ignored) {}
        shutdown();
    }

    /** Drops the connection without a word (it is already dead, or about to be replaced). */
    void abort() {
        shutdown();
    }

    // ------------------------------------------------------------------ internals

    private void shutdown() {
        closed = true;
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {}
    }

    private Closed drop(String why) {
        shutdown();
        return new Closed(1006, why);
    }

    private Closed fail(int code, String why) {
        try {
            send(0x8, new byte[]{(byte) (code >> 8), (byte) code});
        } catch (IOException ignored) {}
        shutdown();
        return new Closed(code, why);
    }

    private int readByte() throws IOException {
        int b;
        try {
            b = in.read();
        } catch (SocketTimeoutException e) {
            throw drop("silent");
        } catch (IOException e) {
            throw drop(e.toString());
        }
        if (b < 0) throw drop("connection closed");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n;
            try {
                n = in.read(buf, off, buf.length - off);
            } catch (SocketTimeoutException e) {
                throw drop("silent");
            } catch (IOException e) {
                throw drop(e.toString());
            }
            if (n < 0) throw drop("connection closed");
            off += n;
        }
    }

    private static String readHead(InputStream i) throws IOException {
        StringBuilder sb = new StringBuilder();
        int state = 0;   // how much of \r\n\r\n has been seen
        while (state < 4) {
            int b = i.read();
            if (b < 0) throw new IOException("connection closed during the handshake");
            sb.append((char) b);
            if (sb.length() > 16 * 1024) throw new IOException("handshake too long");
            state = (b == '\r' && (state == 0 || state == 2)) ? state + 1 : (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
        }
        return sb.substring(0, sb.length() - 4);
    }

    private static String realm(String challenge) {
        if (challenge == null) return "";
        int i = challenge.toLowerCase(Locale.US).indexOf("realm=");
        if (i < 0) return "";
        String r = challenge.substring(i + 6).trim();
        if (r.startsWith("\"")) {
            int e = r.indexOf('"', 1);
            return e > 0 ? r.substring(1, e) : r.substring(1);
        }
        int e = r.indexOf(',');
        return e > 0 ? r.substring(0, e) : r;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /** Standard base64 with padding (android.util.Base64 would tie this to a device). */
    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder((d.length + 2) / 3 * 4);
        for (int i = 0; i < d.length; i += 3) {
            int n = (d[i] & 0xff) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xff) << 8 : 0) | (i + 2 < d.length ? d[i + 2] & 0xff : 0);
            sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(n >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[n & 63] : '=');
        }
        return sb.toString();
    }
}
