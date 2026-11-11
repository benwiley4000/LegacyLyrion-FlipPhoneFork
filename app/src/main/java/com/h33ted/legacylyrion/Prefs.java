package com.h33ted.legacylyrion;

import android.content.Context;
import android.content.SharedPreferences;

/** One shared settings file for every screen (server, player, music source). */
final class Prefs {
    static final String FILE = "lyrion";
    static final String DEFAULT_HOST = ""; // no default: first-run setup finds or asks for the server

    private Prefs() {}

    static SharedPreferences get(Context c) {
        Auth.init(c);
        SharedPreferences p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!p.contains("lms_host")) {
            // migrate the old per-activity prefs written by MainActivity.getPreferences()
            SharedPreferences old = c.getSharedPreferences("MainActivity", Context.MODE_PRIVATE);
            if (old.contains("lms_host")) {
                p.edit().putString("lms_host", old.getString("lms_host", DEFAULT_HOST))
                        .putString("lms_port", old.getString("lms_port", "9000"))
                        .commit();
            }
        }
        Servers.install(p);
        return p;
    }

    static String host(Context c) { return get(c).getString("lms_host", DEFAULT_HOST); }

    static String port(Context c) { return get(c).getString("lms_port", "9000"); }

    static String hostPort(Context c) { return host(c) + ":" + port(c); }

    static String player(Context c) {
        String id = get(c).getString("player_id", null);
        if (id == null || SlimProtoService.LEGACY_SHARED_MAC.equalsIgnoreCase(id) || SlimProtoService.isOwnMac(c, id)) {
            return SlimProtoService.localMac(c);   // this phone, whichever way it connects
        }
        return id;
    }

    /** Whether this phone also registers itself as a Lyrion player (SlimProto). */
    static boolean phonePlayer(Context c) { return get(c).getBoolean("phone_player", true); }

    /**
     * How the phone connects as a player: "direct" (its own SlimProto client, server port 3483), "bridge"
     * (the LyrionJS plugin's WebSocket on the web port, so one http/https port is enough) or "auto":
     * direct for a plain address, bridge for one typed with a protocol (https://...), which means the
     * server is behind a web proxy and port 3483 is not reachable.
     */
    static String playerLink(Context c) { return get(c).getString("player_link", "auto"); }

    static boolean useBridge(Context c) {
        String mode = playerLink(c);
        if (mode.equals("bridge")) return true;
        if (mode.equals("direct")) return false;
        return isUrl(host(c));
    }

    /** Changes whenever the way the phone player connects does, so the service knows to reconnect. */
    static String linkKey(Context c) {
        return useBridge(c) ? "bridge " + withProtocol(hostPort(c)) : "direct";
    }

    static boolean isUrl(String host) { return host != null && host.contains("://"); }

    /** "http://" is assumed when the address has no protocol; a trailing slash is dropped. */
    static String withProtocol(String address) {
        String a = address == null ? "" : address.trim();
        while (a.endsWith("/")) a = a.substring(0, a.length() - 1);
        return a.contains("://") ? a : "http://" + a;
    }

    /** The host name alone: no protocol, login, port or path. For raw TCP connections. */
    static String bareHost(String host) {
        String h = host == null ? "" : host.trim();
        int proto = h.indexOf("://");
        if (proto >= 0) h = h.substring(proto + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        h = h.substring(h.lastIndexOf('@') + 1);
        if (h.startsWith("[")) {   // IPv6 literal: [::1] or [::1]:9100
            int end = h.indexOf(']');
            return end > 0 ? h.substring(1, end) : h;
        }
        int colon = h.indexOf(':');
        return colon >= 0 && colon == h.lastIndexOf(':') ? h.substring(0, colon) : h;   // host:port -> host
    }

    static boolean setupDone(Context c) { return get(c).getBoolean("setup_done", false) || get(c).contains("lms_host"); }

    /** Where the home screen gets its music: Spotify (Spotty), YouTube Music, or the server's own library. */
    static final String SRC_SPOTIFY = "spotify", SRC_YOUTUBE = "youtube", SRC_LOCAL = "local";

    static String source(Context c) {
        SharedPreferences p = get(c);
        String s = p.getString("source", null);
        if (s != null) return s;
        return p.getBoolean("local_only", false) ? SRC_LOCAL : SRC_SPOTIFY;   // older versions had a checkbox
    }

    static void setSource(Context c, String s) { get(c).edit().putString("source", s).commit(); }

    /** True until a music source has been chosen, by the first-start check or by hand. */
    static boolean sourceUnset(Context c) {
        SharedPreferences p = get(c);
        return !p.contains("source") && !p.contains("local_only");
    }

    static boolean localOnly(Context c) { return SRC_LOCAL.equals(source(c)); }

    static boolean notifCompatMode(Context c) { return get(c).getBoolean("notif_compat_mode", false); }
}
