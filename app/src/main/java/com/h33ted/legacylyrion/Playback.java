package com.h33ted.legacylyrion;

/**
 * Track changes the way LMS expects a player to do them, so one track runs into the next without a gap.
 *
 * LMS only sends the next track early if the player says it has finished decoding the current one (STMd)
 * well before it has finished playing it. A stream that then arrives is the next track, not a replacement:
 * it is fetched and prepared while the current one plays out, and takes over the moment that ends. A stream
 * that arrives before STMd is a skip and replaces the current one, as does one after a flush. This is the same
 * choreography as squeezelite and the LyrionJS web player.
 *
 * Platform-free on purpose: the Android specifics (MediaPlayer, handlers, the audio effect session) are
 * behind {@link Stream} and {@link Host}. Every method is called on the main thread, except elapsedMs().
 */
final class Playback {
    /** How long before a track's end LMS is told the decoder is done: time to fetch the next one. LyrionJS uses 10 s too. */
    static final int LEAD_MS = 10000;
    private static final long TICK_MS = 500;
    private static final long PREPARE_WATCH_MS = 12000;

    /** One stream, being prepared or playing (a MediaPlayer in the app). Its methods must not throw. */
    interface Stream {
        void start();

        void pause();

        boolean isPlaying();

        void setVolume(float v);

        /** Milliseconds into the track, or -1 when not known. */
        int position();

        /** Length of the track in milliseconds, or <= 0 when not known (a stream of unknown length). */
        int duration();

        /** Stops and frees it. */
        void release();
    }

    interface Host {
        /**
         * Begins opening a stream; the host calls prepared()/completed()/failed() for it later. {@code next}
         * means it is the track after the one playing. Throws if the stream cannot even be set up.
         */
        Stream open(String url, String login, boolean next, float gain) throws Exception;

        /** Lets the platform start {@code next} itself the instant {@code current} ends, where it can (Android 4.1+). */
        void chain(Stream current, Stream next);

        /** Every stream is gone: free what they share (the audio effect session). */
        void idle();

        /** A STAT event for LMS ("STMs", "STMd", ...). */
        void stat(String code);

        void status(String text);

        void log(String text);

        long now();

        void later(Runnable r, long delayMs);

        void cancelLater(Runnable r);
    }

    private final Host host;
    private volatile Stream cur;       // the track that is playing (or loading); read by elapsedMs() from another thread
    private Stream next;               // the track after it, being fetched or ready
    private boolean curPrepared, curAuto, curStarted, curDone, curEnded, nextReady;
    private long endedAt;
    private float gain = 1.0f;
    private final Runnable tickTask = new Runnable() {
        @Override
        public void run() {
            tick();
        }
    };

    Playback(Host host) {
        this.host = host;
    }

    /** strm 's': a stream to play. */
    void start(String url, String login, boolean autoStart) {
        if (cur != null && curStarted && curDone && !curEnded && autoStart) {
            prefetch(url, login);
            return;
        }
        releaseAll();
        curAuto = autoStart;
        endedAt = 0;
        host.log("stream URL " + url);
        host.stat("STMc");
        host.status("SLIMPROTO BUFFERING STREAM...");
        try {
            final Stream s = host.open(url, login, false, gain);
            cur = s;
            host.later(new Runnable() {
                @Override
                public void run() {
                    if (cur == s && !curPrepared) host.log("still not prepared after 12 s");
                }
            }, PREPARE_WATCH_MS);
            scheduleTick();
        } catch (Exception e) {
            host.log("stream setup failed: " + e);
            host.stat("STMn");
            host.status("SLIMPROTO STREAM ERROR");
        }
    }

    /** The next track, while the current one is still playing out. */
    private void prefetch(String url, String login) {
        dropNext();
        host.log("gapless: fetching the next track while this one plays out: " + url);
        host.stat("STMc");
        try {
            next = host.open(url, login, true, gain);
        } catch (Exception e) {
            host.log("next stream setup failed: " + e);
            host.stat("STMn");
        }
    }

    void prepared(Stream s) {
        if (s == cur) {
            curPrepared = true;
            host.log("prepared, autostart=" + curAuto);
            int len = s.duration();   // gapless needs to know where the end is
            host.log("track length " + (len > 0 ? len + " ms" : "unknown: the next track will not be joined early"));
            host.stat("STMh");
            host.stat("STMl");
            if (curAuto) {
                s.start();
                curStarted = true;
                host.stat("STMs");
                host.status("SLIMPROTO STREAMING AUDIO");
            } else {
                host.status("SLIMPROTO BUFFERED, WAITING");
            }
        } else if (s == next) {
            nextReady = true;
            host.log("gapless: the next track is ready");
            if (cur == null || curEnded) {
                promote();   // the current one ended while this one was still loading
            } else {
                host.chain(cur, next);
            }
        }
    }

    void completed(Stream s) {
        host.log("playback completed");
        if (s != cur) return;
        curEnded = true;
        endedAt = host.now();
        if (next != null) {
            if (nextReady) {
                promote();
            } else {
                host.log("gapless: the next track is not ready yet");   // prepared() carries on from here
            }
            return;
        }
        if (!curDone) {
            curDone = true;
            host.stat("STMd");
        }
        host.stat("STMu");
    }

    void failed(Stream s, int what, int extra) {
        host.log("MediaPlayer error what=" + what + " extra=" + extra);
        if (s == next) {   // the next track can't be played: tell LMS, keep the current one going
            dropNext();
            host.stat("STMn");
            return;
        }
        host.stat("STMn");
        host.status("SLIMPROTO DECODER ERROR (" + what + ")");
    }

    /** strm 'p'. */
    void pause() {
        Stream c = cur;
        if (c != null) {
            if (c.isPlaying()) c.pause();
            host.stat("STMp");
            host.status("SLIMPROTO PAUSED");
        }
    }

    /** strm 'u'. */
    void unpause() {
        Stream c = cur;
        if (c != null) {
            c.start();
            host.stat("STMr");
            if (!curStarted) {
                curStarted = true;
                host.stat("STMs");
            }
            host.status("SLIMPROTO STREAMING AUDIO");
        }
    }

    /** strm 'q' / 'f'. */
    void flush() {
        releaseAll();
        host.stat("STMf");
    }

    void volume(float g) {
        gain = g;
        Stream c = cur, n = next;
        if (c != null) c.setVolume(g);
        if (n != null) n.setVolume(g);
    }

    /** For STAT, from any thread: how far into the current track, 0 when nothing is playing. */
    int elapsedMs() {
        Stream c = cur;
        if (c == null) return 0;
        return c.isPlaying() ? Math.max(0, c.position()) : 0;
    }

    /** Stops everything (a flush, a skip, the service going away). */
    void releaseAll() {
        host.cancelLater(tickTask);
        Stream a = cur, b = next;
        cur = null;
        next = null;
        curPrepared = curStarted = curDone = curEnded = nextReady = false;
        if (a != null || b != null) host.idle();
        if (a != null) a.release();
        if (b != null) b.release();
    }

    // ---------------------------------------------------------------------------------------------

    /** The next track takes over: it is already playing if the platform chained it, and is started otherwise. */
    private void promote() {
        Stream old = cur, n = next;
        next = null;
        nextReady = false;
        cur = n;
        curPrepared = curAuto = curStarted = true;
        curDone = curEnded = false;
        n.start();
        long gap = endedAt > 0 ? host.now() - endedAt : -1;
        host.log("gapless: carried on into the next track" + (gap >= 0 ? " (" + gap + " ms after the last one ended)" : ""));
        endedAt = 0;
        host.stat("STMs");
        host.status("SLIMPROTO STREAMING AUDIO");
        if (old != null) old.release();   // not releaseAll(): the effect session carries on
        scheduleTick();
    }

    private void dropNext() {
        Stream n = next;
        next = null;
        nextReady = false;
        if (n != null) n.release();
    }

    private void scheduleTick() {
        host.cancelLater(tickTask);
        host.later(tickTask, TICK_MS);
    }

    /** Watches the playing track and tells LMS the decoder is done once the end is near. */
    private void tick() {
        Stream c = cur;
        if (c == null) return;
        if (curStarted && !curDone && !curEnded) {
            int dur = c.duration();
            int pos = c.position();
            if (dur > 0 && pos >= 0 && dur - pos <= LEAD_MS) {
                curDone = true;
                host.log("gapless: " + (dur - pos) + " ms left, telling LMS the decoder is done");
                host.stat("STMd");
            }
        }
        if (!curEnded || next != null) host.later(tickTask, TICK_MS);
    }
}
