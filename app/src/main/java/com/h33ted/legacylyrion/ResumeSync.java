package com.h33ted.legacylyrion;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Picks the music up where it was when the phone has to move to another server.
 *
 * The same Lyrion server is often saved twice under different addresses (its LAN address, and the one
 * behind a reverse proxy). Moving from one to the other, the player this phone controlled and the player
 * it controls on the other address are not the same player to Lyrion: with the LyrionJS bridge the phone
 * even has another player ID (see {@link BridgeLink#mac}). The player we were using stays on the server,
 * playlist and all, but it is not connected any more.
 *
 * So after a server is chosen, if the player we were controlling is known to the new server too (it is
 * the same Lyrion behind both addresses), the player of the new server is synced to it, which makes the
 * old player's playlist the new player's. A player that is gone then leaves the group again, exactly as
 * Squeezer moves a playlist between players (sync, then unsync the old one): left in the group, the new
 * player would be a slave of a master that is offline, and Lyrion does not stream to such a slave. With the
 * new player on its own, and the old player unable to stream, the old player's current track is restarted
 * on it at the position it had when the connection was cut. A player that is still playing (a speaker we
 * were only controlling) is just joined, as any sync does, and stays in the group.
 *
 * Everything here goes through Lyrion's JSON-RPC, which is the same whichever way the phone connects as a
 * player (its own SlimProto connection or the LyrionJS bridge) and whichever way the old player did.
 *
 * The new player is likely to start playing whatever it had last time it was on that server, the moment
 * it connects. So from the moment a server is chosen until the decision is carried out (or turns out to be
 * "leave it alone"), the new player is <em>held</em>: the phone's output is muted, and status polls for that
 * player wait, so the wrong track is neither heard nor shown. The decision itself is taken at once, from
 * what the server says about the old player, without waiting for the new player to connect.
 */
final class ResumeSync {
    /** How long a position we saw stays usable: the last status before a loss, or a loss before a choice. */
    private static final long LIVE_MAX_MS = 5 * 60 * 1000;
    private static final long FROZEN_MAX_MS = 30 * 60 * 1000;

    private ResumeSync() {}

    interface Clock {
        long now();
    }

    /** Milliseconds that keep counting while the phone sleeps. Replaceable for tests only. */
    static Clock clock = new Clock() {
        @Override
        public long now() {
            return SystemClock.elapsedRealtime();
        }
    };

    static long now() {
        return clock.now();
    }

    // ------------------------------------------------------------------ what the player was doing

    /** What one player was doing on one server, as last seen. Immutable. */
    static final class Snapshot {
        final String key;            // the server, as Auth.addressKey writes it
        final String player;
        final boolean playing;
        final boolean frozen;        // true once the connection was lost: the position no longer advances
        final int index, tracks;     // current playlist index (-1 if none) and playlist length
        final double elapsed, duration;   // seconds; elapsed is what it was at 'at'
        final long at;

        Snapshot(String key, String player, boolean playing, boolean frozen, int index, int tracks,
                 double elapsed, double duration, long at) {
            this.key = key;
            this.player = player;
            this.playing = playing;
            this.frozen = frozen;
            this.index = index;
            this.tracks = tracks;
            this.elapsed = elapsed;
            this.duration = duration;
            this.at = at;
        }

        /** The same, stopped at the moment {@code when}: the position it had by then if it was playing. */
        Snapshot frozenAt(long when) {
            double e = elapsed;
            if (playing && when > at) e += (when - at) / 1000.0;
            if (duration > 0) e = Math.min(e, duration);
            return new Snapshot(key, player, playing, true, index, tracks, e, duration, when);
        }
    }

    private static final Map<String, Snapshot> SEEN = new HashMap<String, Snapshot>();

    private static String slot(String key, String player) {
        return key + "|" + player.toLowerCase(Locale.US);
    }

    /**
     * Called with every answer to a request: remembers what a status reply says. The controlled player is
     * polled all the time, so this is current to a few seconds, and is all that is left to go on once the
     * server stops answering.
     */
    static void observe(String address, String player, JSONArray cmd, JSONObject status) {
        if (status == null || player == null || player.length() == 0 || cmd == null) return;
        if (!"status".equals(cmd.optString(0)) || !status.has("mode")) return;
        String key = Auth.addressKey(address);
        Snapshot s = new Snapshot(key, player, "play".equals(status.optString("mode")), false,
                status.has("playlist_cur_index") ? status.optInt("playlist_cur_index", -1) : -1,
                status.optInt("playlist_tracks", 0),
                Math.max(0, status.optDouble("time", 0)), Math.max(0, status.optDouble("duration", 0)), now());
        synchronized (SEEN) {
            if (SEEN.size() > 16) SEEN.clear();
            SEEN.put(slot(key, player), s);
        }
    }

    /**
     * The connection to this server was lost at {@code when}: what its players were doing stops there, so
     * their positions don't keep advancing while the user picks another server.
     */
    static void freeze(String address, long when) {
        String key = Auth.addressKey(address);
        synchronized (SEEN) {
            for (Iterator<Map.Entry<String, Snapshot>> it = SEEN.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Snapshot> e = it.next();
                Snapshot s = e.getValue();
                if (!s.key.equals(key) || s.frozen) continue;
                if (when - s.at > LIVE_MAX_MS) it.remove();   // not seen for too long to say where it is
                else e.setValue(s.frozenAt(Math.max(when, s.at)));
            }
        }
    }

    /** What this player was doing on this server, or null if it was not seen lately. A live one is frozen now. */
    static Snapshot take(String address, String player) {
        String key = Auth.addressKey(address);
        synchronized (SEEN) {
            Snapshot s = SEEN.get(slot(key, player));
            if (s == null) return null;
            long t = now();
            if (s.frozen) return t - s.at > FROZEN_MAX_MS ? null : s;
            return t - s.at > LIVE_MAX_MS ? null : s.frozenAt(t);
        }
    }

    // ------------------------------------------------------------------ holding the new player

    /** Told when the phone's own output must be silent (true) or may be heard again. May be called from any thread. */
    interface MuteListener {
        void mute(boolean on);
    }

    /** However it ends, nobody is held longer than this: a stuck job must not leave the phone silent. */
    static final long HOLD_MAX_MS = 20000;

    private static final Object HOLD = new Object();
    private static String heldPlayer;        // null = nobody
    private static boolean heldPhone;        // the held player is this phone, so its output is what gets muted
    private static long heldUntil, holdSeq;
    private static MuteListener muteListener;

    /** Holds this player. Returns what to hand to {@link #release}. */
    static long hold(String player, boolean phone) {
        long id;
        MuteListener l;
        synchronized (HOLD) {
            heldPlayer = player;
            heldPhone = phone;
            heldUntil = now() + HOLD_MAX_MS;
            id = ++holdSeq;
            l = muteListener;
        }
        if (l != null && phone) l.mute(true);
        return id;
    }

    /** Ends that hold, unless a newer one has replaced it. */
    static void release(long id) {
        MuteListener l;
        boolean phone;
        synchronized (HOLD) {
            if (id != holdSeq || heldPlayer == null) return;
            phone = heldPhone;
            heldPlayer = null;
            heldPhone = false;
            l = muteListener;
            HOLD.notifyAll();
        }
        if (l != null && phone) l.mute(false);
    }

    private static boolean heldLocked(String player) {
        return heldPlayer != null && now() < heldUntil && heldPlayer.equalsIgnoreCase(player);
    }

    static boolean holds(String player) {
        synchronized (HOLD) {
            return heldLocked(player);
        }
    }

    /** Blocks (a worker thread, never the UI thread) until this player is not held, or the hold runs out. */
    static void awaitRelease(String player) {
        if (player == null) return;
        synchronized (HOLD) {
            while (heldLocked(player)) {
                try {
                    HOLD.wait(Math.max(1, heldUntil - now()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** The service that plays the phone's audio; it is told at once if a hold is already on. */
    static void setMuteListener(MuteListener l) {
        boolean on;
        synchronized (HOLD) {
            muteListener = l;
            on = heldPlayer != null && heldPhone && now() < heldUntil;
        }
        if (l != null) l.mute(on);
    }

    // ------------------------------------------------------------------ leaving one server for another

    /** What the phone leaves behind when it moves to another server. */
    static final class Handoff {
        final String key;         // the server left
        final String player;      // the player that was controlled there, as that server knows it
        final boolean phone;      // that player is this phone (through either connection)
        final Snapshot seen;      // what it was doing; null if unknown

        Handoff(String key, String player, boolean phone, Snapshot seen) {
            this.key = key;
            this.player = player;
            this.phone = phone;
            this.seen = seen;
        }
    }

    /**
     * Call before the new server's settings replace the live ones: afterwards the player ID of this phone
     * is the one for the new connection, and which player we controlled is no longer to be had.
     * Returns null when nothing was being played from here.
     */
    static Handoff leaving(Context c) {
        Context app = c.getApplicationContext();
        if (Prefs.host(app).length() == 0) return null;   // first run: nothing to carry over
        String address = Prefs.hostPort(app);
        String player = Prefs.player(app);
        boolean phone = SlimProtoService.isOwnMac(app, player);
        if (phone && !Prefs.phonePlayer(app)) return null;   // this phone was only a remote
        return new Handoff(Auth.addressKey(address), player, phone, take(address, player));
    }

    private static Job current;

    /**
     * Call after the new server's settings are live. Carries the music over if the player left behind is
     * known to the new server; in the background, as the phone's own player may still be connecting.
     */
    static void arrive(Context c, Handoff from) {
        if (from == null) return;
        final Context app = c.getApplicationContext();
        String address = Prefs.hostPort(app);
        String key = Auth.addressKey(address);
        String to = Prefs.player(app);
        boolean toPhone = SlimProtoService.isOwnMac(app, to);
        if (key.equals(from.key)) return;                  // the same server again
        if (to.equalsIgnoreCase(from.player)) return;      // the same player: Lyrion sees it reconnect, nothing to sync
        if (toPhone && !Prefs.phonePlayer(app)) return;    // no phone player over there
        // Before anything connects: from here the new player is held until the job has decided and acted
        Job job = new Job(new LiveLms(address), new LiveEnv(app, key), from, to, hold(to, toPhone));
        synchronized (ResumeSync.class) {
            if (current != null) current.cancel();
            current = job;
        }
        Thread t = new Thread(job, "ResumeSync");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ the job

    /** The new server, as the job talks to it. */
    interface Lms {
        /** One JSON-RPC request; null if the answer had no result. */
        JSONObject call(String player, JSONArray cmd) throws Exception;
    }

    /** The world around the job. */
    interface Env {
        long now();

        void sleep(long ms) throws InterruptedException;

        void log(String text);

        /** Tells the user (a toast). */
        void say(String text);

        /** The phone moved on to yet another server: stop. */
        boolean cancelled();
    }

    private static final class LiveLms implements Lms {
        private final String address;

        LiveLms(String address) {
            this.address = address;
        }

        @Override
        public JSONObject call(String player, JSONArray cmd) throws Exception {
            return LyrionRpcClient.sendCommandUnheld(address, player, cmd);
        }
    }

    private static final class LiveEnv implements Env {
        private final Context app;
        private final String key;
        private final Handler main = new Handler(Looper.getMainLooper());

        LiveEnv(Context app, String key) {
            this.app = app;
            this.key = key;
        }

        @Override
        public long now() {
            return ResumeSync.now();
        }

        @Override
        public void sleep(long ms) throws InterruptedException {
            Thread.sleep(ms);
        }

        @Override
        public void log(String text) {
            SlimProtoService.log("resume: " + text);
        }

        @Override
        public void say(final String text) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(app, text, Toast.LENGTH_LONG).show();
                }
            });
        }

        @Override
        public boolean cancelled() {
            return !Auth.addressKey(Prefs.hostPort(app)).equals(key);
        }
    }

    /** A player as the "players" list shows it. */
    static final class Entry {
        final String id, name;
        final boolean connected, power;

        Entry(String id, String name, boolean connected, boolean power) {
            this.id = id;
            this.name = name;
            this.connected = connected;
            this.power = power;
        }
    }

    static Entry find(JSONArray players, String id) {
        for (int i = 0; players != null && i < players.length(); i++) {
            JSONObject o = players.optJSONObject(i);
            if (o == null || !id.equalsIgnoreCase(o.optString("playerid"))) continue;
            return new Entry(o.optString("playerid"), o.optString("name", id), flag(o, "connected"), flag(o, "power"));
        }
        return null;
    }

    /** Lyrion writes yes/no as 1/0. */
    private static boolean flag(JSONObject o, String name) {
        Object v = o.opt(name);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return v != null && ("1".equals(v.toString()) || "true".equalsIgnoreCase(v.toString()));
    }

    /** What to do about the old player's session. */
    static final class Plan {
        String skip;           // why there is nothing to carry over; null when there is
        boolean play;          // it was playing: the new player must be on and get the music going
        boolean restart;       // nothing is streaming it any more: start the track again at the saved position
        boolean live;          // something still streams the old session, so the new player joins it and stays
        int index, seconds, tracks;
    }

    /**
     * Decides from what the server says about the old player (its status, and the players list) and from
     * what we saw while we were still connected. The server knows which track the old player was on and its
     * playlist; only we know whether it was playing and how far in, as the server stops the player the moment
     * it disconnects. A position of ours for another track than the server's is out of date: the track
     * changed after we last looked, so the server's own figure is the better one.
     */
    static Plan plan(Entry from, JSONObject status, JSONArray players, String toId, String fromId,
                     boolean fromIsPhone, Snapshot seen) {
        Plan p = new Plan();
        JSONObject st = status == null ? new JSONObject() : status;
        int tracks = st.has("playlist_tracks") ? st.optInt("playlist_tracks", 0) : seen != null ? seen.tracks : 0;
        int index = st.has("playlist_cur_index") ? st.optInt("playlist_cur_index", -1) : seen != null ? seen.index : -1;
        if (tracks <= 0 || index < 0 || index >= tracks) {
            p.skip = "the old player had nothing queued";
            return p;
        }
        boolean sameTrack = seen != null && seen.index == index;
        p.play = seen != null ? seen.playing : "play".equals(st.optString("mode"));
        double at = sameTrack ? seen.elapsed : Math.max(0, st.optDouble("time", 0));
        double length = sameTrack && seen.duration > 0 ? seen.duration : st.optDouble("duration", 0);
        if (length > 0 && at >= length) at = length - 1;
        p.index = index;
        p.tracks = tracks;
        p.seconds = (int) Math.max(0, at);
        // Something is still streaming the playlist if the old player is a live speaker we were only
        // controlling, or another player of its sync group is on: then syncing joins that stream.
        // The phone we just left is never "live", even if Lyrion has not noticed it going yet.
        p.live = (from.connected && !fromIsPhone) || othersActive(players, st, toId, fromId);
        p.restart = p.play && !p.live;
        return p;
    }

    private static boolean othersActive(JSONArray players, JSONObject st, String toId, String fromId) {
        String slaves = st.optString("sync_slaves", "");
        for (String id : slaves.split(",")) {
            id = id.trim();
            if (id.length() == 0 || id.equalsIgnoreCase(toId) || id.equalsIgnoreCase(fromId)) continue;
            Entry e = find(players, id);
            if (e != null && e.connected && e.power) return true;
        }
        return false;
    }

    /** "playlist index N 0 0 {timeOffset: S}": play track N from S seconds in (fade 0, not "noplay"). */
    static JSONArray restartCmd(int index, int seconds) throws JSONException {
        JSONArray c = Rpc.cmd("playlist", "index", String.valueOf(index), "0", "0");
        c.put(new JSONObject().put("timeOffset", seconds));   // the seekdata hash is a positional argument
        return c;
    }

    static final class Job implements Runnable {
        static final long TOTAL_MS = 60000;      // everything, including waiting for the phone's player to appear
        static final long POLL_MS = 400;
        static final long SETTLE_MS = 500;       // after the last command, so the end of the wrong stream is not heard
        static final long VERIFY_MS = 4000;

        private final Lms lms;
        private final Env env;
        private final Handoff from;
        private final String to;
        private final long holdId;
        private volatile boolean cancelled;

        Job(Lms lms, Env env, Handoff from, String to, long holdId) {
            this.lms = lms;
            this.env = env;
            this.from = from;
            this.to = to;
            this.holdId = holdId;
        }

        void cancel() {
            cancelled = true;
        }

        private boolean stop() {
            return cancelled || env.cancelled();
        }

        private void unhold() {
            release(holdId);
        }

        @Override
        public void run() {
            try {
                go();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                env.log("failed: " + e);
            } finally {
                unhold();   // whichever way it ended, the new player is let go
                synchronized (ResumeSync.class) {
                    if (current == this) current = null;
                }
            }
        }

        /** The players list; retried while the server (or the network) is still coming up. Null if it never answers. */
        private JSONArray players(long end) throws InterruptedException {
            for (;;) {
                if (stop()) return null;
                try {
                    JSONObject r = lms.call("", Rpc.cmd("players", "0", "99"));
                    if (r != null) {
                        JSONArray loop = r.optJSONArray("players_loop");
                        return loop != null ? loop : new JSONArray();   // no loop at all: no players
                    }
                } catch (Exception e) {
                    env.log("players: " + e);
                }
                if (env.now() + POLL_MS > end) return null;
                env.sleep(POLL_MS);
            }
        }

        private void go() throws Exception {
            final long begin = env.now();
            final long end = begin + TOTAL_MS;

            JSONArray loop = players(end);
            if (loop == null) {
                env.log("the new server did not answer");
                return;
            }
            Entry old = find(loop, from.player);
            if (old == null) {
                // not known there: another Lyrion server altogether, whose own session goes on
                env.log(from.player + " is not on the new server, nothing to carry over");
                return;
            }
            env.log("the same server: " + old.name + " (" + from.player + ") is known there");

            // Decided now, before the phone's own player has even connected: what the old player was doing is
            // there to read already, and nothing waits on the phone for it. Whatever the answer, the new player
            // is let go as soon as it has been dealt with (here at once if the answer is to leave it be).
            JSONObject st = lms.call(from.player, Rpc.cmd("status", "-", "1"));
            Plan p = plan(old, st, loop, to, from.player, from.phone, from.seen);
            if (p.skip != null) {
                env.log(p.skip);
                return;
            }
            env.log("old player at track " + (p.index + 1) + ", " + p.seconds + " s, "
                    + (p.play ? "was playing" : "was not playing") + (from.seen == null ? " (server's figures)" : "")
                    + (p.restart ? ": restarting it" : ": joining"));

            // Lyrion can only play to a player that is connected, and the phone's own is only just starting.
            // (The phone we left need not be waited for: it is ours to abandon, whatever Lyrion still thinks.)
            Entry target = find(loop, to);
            while (target == null || !target.connected) {
                if (env.now() >= end || env.now() - begin >= HOLD_MAX_MS) {
                    env.log("the new player " + to + " did not connect in time");
                    return;
                }
                env.sleep(POLL_MS);
                loop = players(end);
                if (loop == null) return;
                target = find(loop, to);
            }
            if (stop()) return;

            if (p.play && !target.power) lms.call(to, Rpc.cmd("power", "1"));   // a player that is off does not get the stream
            // the new player joins the old one's group, so the old player's playlist becomes its own
            lms.call(from.player, Rpc.cmd("sync", to));
            // Whoever the restart is sent to plays it: the new player once it is on its own, else the old
            // one as the group's master.
            String runner = from.player;
            if (!p.live) {
                // The old player is not coming back. If it stayed the master, the new player would be a slave
                // of nothing and Lyrion would not stream to it: so the old one leaves, and the new player
                // keeps the playlist.
                lms.call(from.player, Rpc.cmd("sync", "-"));
                JSONObject mine = lms.call(to, Rpc.cmd("status", "-", "1"));
                if (mine != null && mine.optInt("playlist_tracks", 0) == p.tracks) {
                    runner = to;
                } else {
                    env.log("the playlist did not come across; staying in the old player's group");
                    lms.call(from.player, Rpc.cmd("sync", to));
                }
            }
            if (p.restart) lms.call(runner, restartCmd(p.index, p.seconds));

            // Done: the new player is playing (or standing) where the old one was. Let it be heard and seen.
            env.sleep(SETTLE_MS);
            unhold();
            env.say(p.play ? "Carried on from " + old.name : "Synced to " + old.name);

            if (p.restart) {   // not part of the hold: only makes sure the restart took
                env.sleep(VERIFY_MS);
                if (stop()) return;
                JSONObject now = lms.call(to, Rpc.cmd("status", "-", "1"));
                if (now != null && !"play".equals(now.optString("mode"))) {
                    env.log("not playing yet, starting it once more");
                    lms.call(runner, restartCmd(p.index, p.seconds));
                }
            }
        }
    }
}
