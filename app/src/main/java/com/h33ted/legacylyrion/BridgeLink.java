package com.h33ted.legacylyrion;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The player's SlimProto connection through the LyrionJS plugin's WebSocket, which pipes it to LMS's
 * own SlimProto port. Only the web port (http or https) has to be reachable.
 *
 * It looks like a socket to the protocol code: {@link #in()} yields the bytes LMS sends and
 * {@link #out()} takes the frames the player sends (each flush is one frame). Underneath it follows the
 * plugin's rules: a "hello" message opens a session, and a socket that drops within the plugin's short
 * hold is resumed, with what LMS sent meanwhile replayed and what the player sent into the dead socket
 * sent again, so a phone changing networks does not look like a player leaving.
 */
final class BridgeLink implements PlayerLink {
    interface Listener {
        /** The same session carries on after a dropout (LMS never saw us leave). */
        void resumed();
    }

    /**
     * The LyrionJS plugin only lets players with its MAC prefix (02:4c:4a) through its WebSocket, so
     * through the bridge the phone is the same device under that prefix: its own last three bytes.
     */
    static String mac(String direct) {
        return "02:4c:4a" + direct.substring(8);
    }

    /** Close codes after which the session is over and resuming is pointless (the plugin's, see its docs). */
    private static final int[] NO_RESUME = {1000, 1001, 1002, 1008, 1009, 1011, 1013, 4000, 4403, 4404};
    private static final int[] BACKOFF = {250, 600, 1200, 2500};
    static final String PATH = "/plugins/LyrionJS/ws";
    private static final int CONNECT_MS = 6000;
    /** LMS talks to every player at least every 5 s and drops one silent for 15: two missed polls mean dead. */
    private static final int DEAD_MS = 11000;
    private static final int RECENT_MAX = 200;

    /** WebSocket close codes the player cares about. */
    static final int REPLACED = 4000, DISABLED = 4403;

    private static final class Frame {
        final long end;
        final byte[] bytes;

        Frame(long end, byte[] bytes) {
            this.end = end;
            this.bytes = bytes;
        }
    }

    private final String address;
    private final String wsUrl;
    private final Listener listener;
    private final Object lock = new Object();

    private WsClient ws;                       // changed only under lock, and only by the reading thread
    private boolean attached;
    private volatile boolean closed;
    private String session, secret;
    private int hold = 10;
    private long received, sent;               // session bytes from LMS / sent to LMS
    private final List<Frame> recent = new ArrayList<Frame>();   // frames sent (not heartbeats), to send again if lost
    private List<byte[]> outbox = new ArrayList<byte[]>();       // frames wanted while away

    /** @param address the server with its protocol, e.g. "https://music.example.com:9100" or "http://192.168.1.5:9000" */
    BridgeLink(String address, Listener listener) {
        String a = address.trim();
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        if (!a.contains("://")) a = "http://" + a;
        this.address = a;
        // https://host -> wss://host, http://host -> ws://host, with the plugin's path appended
        this.wsUrl = (a.regionMatches(true, 0, "https", 0, 5) ? "wss" : "ws") + a.substring(a.indexOf("://")) + PATH;
        this.listener = listener;
    }

    String url() {
        return wsUrl;
    }

    /** Connects and starts a new session; returns (the stream LMS's frames arrive on) when LMS's side is ready for the player's HELO. */
    @Override
    public InputStream open() throws IOException {
        WsClient w = WsClient.connect(wsUrl, Auth.header(address), CONNECT_MS);
        try {
            w.sendText("{\"hello\":1}");
            JSONObject g = greeting(w);
            synchronized (lock) {
                session = g.optString("session");
                secret = g.optString("secret");
                hold = g.optInt("hold", 10);
                received = 0;
                sent = 0;
                recent.clear();
                outbox = new ArrayList<byte[]>();
                ws = w;
                attached = true;
            }
            w.setReadTimeout(DEAD_MS);
        } catch (IOException e) {
            w.abort();
            throw e;
        }
        return in();
    }

    /** Audio comes from the same address as everything else: LMS streams to a LyrionJS player itself, radio included. */
    @Override
    public String streamBase() {
        return address;
    }

    @Override
    public Failure failure(Exception e) {
        int code = e instanceof WsClient.Closed ? ((WsClient.Closed) e).code : 0;
        if (e instanceof Auth.Required) return new Failure("THE SERVER NEEDS A LOGIN: SET IT UNDER SERVER LOGIN", 15000);
        if (code == REPLACED) return new Failure("ANOTHER SESSION TOOK THIS PLAYER", 30000);
        if (code == DISABLED) return new Failure("BROWSER PLAYERS ARE SWITCHED OFF IN THE LYRIONJS SETTINGS", 30000);
        if (String.valueOf(e.getMessage()).startsWith("HTTP 404")) return new Failure("THE LYRIONJS PLUGIN WAS NOT FOUND ON THE SERVER", 15000);
        return new Failure("BRIDGE RECONNECTING...", 3500);
    }

    /** What LMS sends, as a byte stream. A dropout within the hold is resumed inside read(). */
    InputStream in() {
        return new InputStream() {
            private byte[] cur = new byte[0];
            private int pos;

            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                while (pos >= cur.length) {
                    cur = nextChunk();
                    pos = 0;
                }
                int n = Math.min(len, cur.length - pos);
                System.arraycopy(cur, pos, b, off, n);
                pos += n;
                return n;
            }
        };
    }

    /** The frames the player sends: bytes are gathered until flush(), which sends them as one frame. */
    @Override
    public OutputStream out() {
        return new OutputStream() {
            private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) {
                buf.write(b);
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) {
                buf.write(b, off, len);
            }

            @Override
            public synchronized void flush() throws IOException {
                if (buf.size() == 0) return;
                byte[] frame = buf.toByteArray();
                buf.reset();
                sendFrame(frame);
            }
        };
    }

    /** Says goodbye (so LMS drops the player now rather than after the hold) and closes. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        WsClient w;
        synchronized (lock) {
            w = ws;
            if (w != null && attached) {
                try {
                    w.sendBinary(new byte[]{'B', 'Y', 'E', '!', 0, 0, 0, 1, 0});
                } catch (IOException ignored) {}
            }
            attached = false;
        }
        if (w != null) w.close(1000);
    }

    static boolean isNoResume(int code) {
        for (int c : NO_RESUME) if (c == code) return true;
        return false;
    }

    // ------------------------------------------------------------------ sending

    /** STAT with event STMt: LMS asks for one every second or so and re-bases its clock on each, so a stale one is worse than none. */
    static boolean isHeartbeat(byte[] f) {
        return f.length >= 12 && f[0] == 'S' && f[1] == 'T' && f[2] == 'A' && f[3] == 'T'
                && f[8] == 'S' && f[9] == 'T' && f[10] == 'M' && f[11] == 't';
    }

    private void sendFrame(byte[] f) throws IOException {
        synchronized (lock) {
            if (attached && ws != null) {
                try {
                    put(f);
                    return;
                } catch (IOException e) {
                    attached = false;   // the reading thread will see the dead socket and resume
                    ws.abort();
                }
            }
            if (closed) throw new WsClient.Closed(1000, "closed");
            if (!isHeartbeat(f)) {   // keep what LMS must hear (track started, decoder done); never heartbeats
                outbox.add(f);
                if (outbox.size() > 500) outbox.remove(0);
            }
        }
    }

    private void put(byte[] f) throws IOException {
        ws.sendBinary(f);
        sent += f.length;
        if (!isHeartbeat(f)) {
            recent.add(new Frame(sent, f));
            if (recent.size() > RECENT_MAX) recent.remove(0);
        }
    }

    // ------------------------------------------------------------------ receiving and resuming

    private byte[] nextChunk() throws IOException {
        for (;;) {
            WsClient w;
            synchronized (lock) {
                w = ws;
            }
            try {
                WsClient.Message m = w.read();
                if (m.text) continue;   // nothing is defined after the greeting
                if (m.data.length == 0) continue;
                received += m.data.length;
                return m.data;
            } catch (WsClient.Closed c) {
                if (closed) throw c;
                if (isNoResume(c.code)) {
                    closed = true;
                    throw c;
                }
            } catch (IOException e) {
                if (closed) throw e;
            }
            resume();
        }
    }

    private void resume() throws IOException {
        synchronized (lock) {
            attached = false;
            if (ws != null) ws.abort();
        }
        long gapStart = System.currentTimeMillis();
        int tries = 0;
        for (;;) {
            if (closed) throw new WsClient.Closed(1000, "closed");
            if (System.currentTimeMillis() - gapStart >= (hold - 1.5) * 1000) {
                closed = true;
                throw new WsClient.Closed(1006, "the connection was lost for too long to resume");
            }
            try {
                Thread.sleep(BACKOFF[Math.min(tries++, BACKOFF.length - 1)]);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WsClient.Closed(1000, "interrupted");
            }
            WsClient w = null;
            try {
                w = WsClient.connect(wsUrl, Auth.header(address), CONNECT_MS);
                JSONObject r = new JSONObject();
                JSONObject inner = new JSONObject();
                inner.put("session", session);
                inner.put("secret", secret);
                inner.put("received", received);
                r.put("resume", inner);
                w.sendText(r.toString());
                JSONObject g = greeting(w);
                if (!session.equals(g.optString("session")) || !truthy(g.opt("resumed"))) {
                    throw new WsClient.Closed(4404, "cannot resume");
                }
                long got = g.has("got") ? g.optLong("got") : -1;
                synchronized (lock) {
                    hold = g.optInt("hold", hold);
                    ws = w;
                    List<byte[]> again = new ArrayList<byte[]>();
                    if (got >= 0 && got <= sent) {   // what the dead socket never received goes again, in order
                        for (Frame f : recent) if (f.end > got) again.add(f.bytes);
                        sent = got;
                    }
                    recent.clear();
                    List<byte[]> held = outbox;
                    outbox = new ArrayList<byte[]>();
                    attached = true;
                    for (byte[] f : again) put(f);
                    for (byte[] f : held) put(f);
                }
                w.setReadTimeout(DEAD_MS);
                if (listener != null) listener.resumed();
                return;
            } catch (WsClient.Closed c) {
                if (w != null) w.abort();
                if (isNoResume(c.code)) {
                    closed = true;
                    throw c;
                }
            } catch (Auth.Required r) {
                closed = true;
                throw r;
            } catch (IOException e) {
                if (w != null) w.abort();
            } catch (org.json.JSONException e) {
                if (w != null) w.abort();
            }
        }
    }

    /** The plugin writes true/false as 1/0; other implementations of the protocol as JSON booleans. Both mean yes/no. */
    static boolean truthy(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return v != null && "true".equalsIgnoreCase(String.valueOf(v));
    }

    /** The plugin's first message: {"session":..., "secret":..., "resumed":0|1, "hold":10, ...}. */
    private static JSONObject greeting(WsClient w) throws IOException {
        WsClient.Message m = w.read();
        if (!m.text) throw new IOException("the bridge sent data before its greeting");
        try {
            JSONObject g = new JSONObject(m.string());
            if (g.optString("session").length() == 0) throw new IOException("the bridge's greeting has no session");
            return g;
        } catch (org.json.JSONException e) {
            throw new IOException("the bridge's greeting is not JSON");
        }
    }
}
