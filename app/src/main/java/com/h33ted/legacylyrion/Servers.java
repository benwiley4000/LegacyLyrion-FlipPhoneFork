package com.h33ted.legacylyrion;

import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Saved servers. Settings that belong to one server (its address and port, which player to control,
 * whether the phone is a player and how it connects, which music source) are kept per server, so
 * going back to a server brings all of them back; settings of the app itself (look, size) are not.
 *
 * The live settings stay where every screen already reads them: the usual keys in the app's
 * preferences (lms_host, lms_port, player_id, phone_player, player_link, source). The saved server
 * that is in use mirrors them: whenever one of those keys changes, from wherever, the saved
 * server is updated to match. Choosing a server writes its settings back into the live keys.
 * A login is kept by Auth under the server's address, so it follows the server without help.
 *
 * Works on a SharedPreferences, not a Context, so it can be tested without a phone.
 */
final class Servers {
    private static final String K_LIST = "servers", K_ACTIVE = "active_server";
    private static final String[] MIRRORED = {"lms_host", "lms_port", "player_id", "phone_player", "player_link", "source"};

    private Servers() {}

    static final class Server {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String name = "";
        String host = "", port = "9000";
        String player;               // control player; null = this phone
        boolean phone = true;        // use this phone as a player
        String link = "auto";        // auto | direct | bridge
        String source;               // music source; null = not chosen yet, so the first start asks the server

        /** What requests go to: the same string Prefs.hostPort() builds from the two address fields. */
        String address() {
            return host + ":" + port;
        }

        /** The same server however its address was written (host:port, or https://host plus a port). */
        String key() {
            return Auth.addressKey(address());
        }

        private JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id).put("name", name).put("host", host).put("port", port).put("phone", phone).put("link", link);
            if (player != null) o.put("player", player);
            if (source != null) o.put("source", source);
            return o;
        }

        private static Server fromJson(JSONObject o) {
            Server s = new Server();
            s.id = o.optString("id", s.id);
            s.name = o.optString("name", "");
            s.host = o.optString("host", "");
            s.port = o.optString("port", "9000");
            s.player = o.has("player") ? o.optString("player") : null;
            s.phone = o.optBoolean("phone", true);
            s.link = o.optString("link", "auto");
            s.source = o.has("source") ? o.optString("source") : null;
            return s;
        }

        Server copy() {
            Server c = new Server();
            c.id = id; c.name = name; c.host = host; c.port = port; c.player = player;
            c.phone = phone; c.link = link; c.source = source;
            return c;
        }
    }

    // ------------------------------------------------------------------ the list

    static synchronized List<Server> list(SharedPreferences sp) {
        List<Server> out = new ArrayList<Server>();
        String json = sp.getString(K_LIST, null);
        if (json == null) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Server.fromJson(o));
            }
        } catch (JSONException e) {
            // unreadable: start again rather than crash; the live settings are untouched
        }
        return out;
    }

    private static String toJson(List<Server> list) {
        JSONArray a = new JSONArray();
        try {
            for (Server s : list) a.put(s.toJson());
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
        return a.toString();
    }

    static synchronized String activeId(SharedPreferences sp) {
        return sp.getString(K_ACTIVE, null);
    }

    /** The saved server in use, or null (nothing saved yet, or it was forgotten). */
    static synchronized Server active(SharedPreferences sp) {
        String id = activeId(sp);
        if (id == null) return null;
        for (Server s : list(sp)) if (s.id.equals(id)) return s;
        return null;
    }

    /** The saved server with this address, however written, or null. */
    static Server find(List<Server> list, String host, String port) {
        String key = Auth.addressKey(host + ":" + port);
        for (Server s : list) if (s.key().equals(key)) return s;
        return null;
    }

    /** A new, unsaved server with the defaults every server starts with. */
    static Server create(String name, String host, String port) {
        Server s = new Server();
        s.host = host == null ? "" : host.trim();
        s.port = port == null || port.trim().length() == 0 ? "9000" : port.trim();
        s.name = name == null || name.trim().length() == 0 ? defaultName(s.host) : name.trim();
        return s;
    }

    /** "wileynet.online" for "https://wileynet.online"; an IP address stays as it is. */
    static String defaultName(String host) {
        String h = host == null ? "" : host.trim();
        int p = h.indexOf("://");
        if (p >= 0) h = h.substring(p + 3);
        int slash = h.indexOf('/');
        if (slash >= 0) h = h.substring(0, slash);
        return h.length() == 0 ? "Lyrion server" : h;
    }

    // ------------------------------------------------------------------ using one

    /**
     * Makes this server the one in use: its settings become the live ones, all in one commit (so a
     * change notification can never see half of them). Adds it to the saved servers if it is new.
     * Settings of the app itself are left alone.
     */
    static synchronized void activate(SharedPreferences sp, Server s) {
        mirror(sp);   // whatever changed on the server being left is kept before its settings are replaced
        List<Server> all = list(sp);
        boolean known = false;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(s.id)) {
                all.set(i, s.copy());
                known = true;
            }
        }
        if (!known) all.add(s.copy());
        SharedPreferences.Editor e = sp.edit();
        e.putString(K_LIST, toJson(all));
        e.putString(K_ACTIVE, s.id);
        e.putString("lms_host", s.host);
        e.putString("lms_port", s.port);
        if (s.player != null) e.putString("player_id", s.player); else e.remove("player_id");
        e.putBoolean("phone_player", s.phone);
        e.putString("player_link", s.link);
        if (s.source != null) {
            e.putString("source", s.source);
        } else {
            e.remove("source").remove("local_only");   // "not chosen yet": the first start asks this server
        }
        e.putBoolean("setup_done", true);
        e.commit();
    }

    /** Takes a saved server out of the list. The one in use can't be forgotten from here (its settings are live). */
    static synchronized Server forget(SharedPreferences sp, String id) {
        if (id.equals(activeId(sp))) return null;
        List<Server> all = list(sp);
        Server gone = null;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id.equals(id)) {
                gone = all.remove(i);
                break;
            }
        }
        if (gone != null) sp.edit().putString(K_LIST, toJson(all)).commit();
        return gone;
    }

    /**
     * Names the server in use. If none is (nothing saved yet, or it was forgotten) the live settings
     * become a saved server of this name.
     */
    static synchronized void nameActive(SharedPreferences sp, String name) {
        String n = name == null ? "" : name.trim();
        Server s = active(sp);
        if (s == null) {
            if (sp.getString("lms_host", "").length() == 0) return;
            s = fromLive(sp);
            if (n.length() > 0) s.name = n;
            List<Server> all = list(sp);
            all.add(s);
            sp.edit().putString(K_LIST, toJson(all)).putString(K_ACTIVE, s.id).commit();
            return;
        }
        if (n.length() == 0 || n.equals(s.name)) return;
        s.name = n;
        save(sp, s);
    }

    // ------------------------------------------------------------------ mirroring the live settings

    private static SharedPreferences.OnSharedPreferenceChangeListener listener;   // strong: Android only keeps it weakly
    private static SharedPreferences installedOn;

    /**
     * Once per process: saves the settings of an existing install as its first saved server, and starts
     * keeping the saved server in use up to date with the live settings.
     */
    static synchronized void install(SharedPreferences sp) {
        if (installedOn == sp) return;
        installedOn = sp;
        if (!sp.contains(K_LIST)) {
            List<Server> all = new ArrayList<Server>();
            SharedPreferences.Editor e = sp.edit();
            if (sp.getString("lms_host", "").length() > 0) {
                Server s = fromLive(sp);
                all.add(s);
                e.putString(K_ACTIVE, s.id);
            }
            e.putString(K_LIST, toJson(all)).commit();
        }
        listener = new SharedPreferences.OnSharedPreferenceChangeListener() {
            @Override
            public void onSharedPreferenceChanged(SharedPreferences p, String key) {
                if (key == null || isMirrored(key)) mirror(p);
            }
        };
        sp.registerOnSharedPreferenceChangeListener(listener);
    }

    /** For tests: forget which preferences are being watched. */
    static synchronized void reset() {
        installedOn = null;
        listener = null;
    }

    private static boolean isMirrored(String key) {
        for (String k : MIRRORED) if (k.equals(key)) return true;
        return key.equals("local_only");
    }

    private static Server fromLive(SharedPreferences sp) {
        Server s = new Server();
        readLive(sp, s);
        s.name = defaultName(s.host);
        return s;
    }

    private static void readLive(SharedPreferences sp, Server s) {
        s.host = sp.getString("lms_host", "");
        s.port = sp.getString("lms_port", "9000");
        s.player = sp.getString("player_id", null);
        s.phone = sp.getBoolean("phone_player", true);
        s.link = sp.getString("player_link", "auto");
        String src = sp.getString("source", null);
        s.source = src != null ? src : sp.getBoolean("local_only", false) ? Prefs.SRC_LOCAL : null;   // older versions had a checkbox
    }

    /** The live settings changed: the saved server in use follows. */
    private static synchronized void mirror(SharedPreferences sp) {
        Server s = active(sp);
        if (s == null) return;
        Server before = s.copy();
        readLive(sp, s);
        if (same(before, s)) return;
        save(sp, s);
    }

    private static boolean same(Server a, Server b) {
        return a.host.equals(b.host) && a.port.equals(b.port) && eq(a.player, b.player) && a.phone == b.phone
                && a.link.equals(b.link) && eq(a.source, b.source);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static void save(SharedPreferences sp, Server s) {
        List<Server> all = list(sp);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(s.id)) all.set(i, s.copy());
        sp.edit().putString(K_LIST, toJson(all)).apply();
    }

    // ------------------------------------------------------------------ describing one

    /** "http://192.168.1.20:9000" the way requests are addressed (what you see is what connects). */
    static String describe(Server s) {
        return s.address();
    }

    /** How the phone is a player on this server, in words. */
    static String linkText(Server s) {
        if (!s.phone) return "phone is not a player";
        boolean bridge = "bridge".equals(s.link) || ("auto".equals(s.link) && s.host.contains("://"));
        return bridge ? "LyrionJS bridge" : "SlimProto";
    }
}
