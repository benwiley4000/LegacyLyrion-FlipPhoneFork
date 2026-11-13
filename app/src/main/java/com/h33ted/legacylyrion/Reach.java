package com.h33ted.legacylyrion;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * Is a Lyrion server answering from here? One small JSON-RPC request ("serverstatus"), which works the
 * same for a LAN address, a hostname, a port forward and an https address behind a reverse proxy, and
 * says nothing about how the phone would connect as a player (SlimProto or the LyrionJS bridge):
 * that is decided per saved server, after it is chosen.
 *
 * A server that answers 401 is reachable, it just wants a login.
 */
final class Reach {
    static final int UP = 0, LOGIN = 1, DOWN = 2;

    private static final int CONNECT_MS = 3000, READ_MS = 3500;

    /** Several servers are asked at once, so a few dead ones don't make the live ones wait. */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(6, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "Reach");
            t.setDaemon(true);
            return t;
        }
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Reach() {}

    interface Listener {
        /** Runs on the UI thread. */
        void done(Servers.Server server, int result);
    }

    /** UP, LOGIN or DOWN for this address ("host:port", or "https://host" plus a port). Blocks; never throws. */
    static int check(String address) {
        try {
            JSONObject r = LyrionRpcClient.sendCommandRaw(address, "", Rpc.cmd("serverstatus", "0", "0"), CONNECT_MS, READ_MS);
            return r != null ? UP : DOWN;
        } catch (Auth.Required e) {
            return LOGIN;
        } catch (Exception e) {
            return DOWN;
        }
    }

    /** Asks every server at the same time; each answer reaches the listener, on the UI thread, as it arrives. */
    static void checkAll(List<Servers.Server> servers, final Listener listener) {
        for (final Servers.Server s : servers) {
            final String address = s.address();
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    final int result = check(address);
                    MAIN.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.done(s, result);
                        }
                    });
                }
            });
        }
    }

    /** The servers of this list that answer (or want a login), after asking them all at once. Blocks. */
    static List<Servers.Server> reachable(final List<Servers.Server> servers) throws InterruptedException {
        List<Callable<Integer>> jobs = new ArrayList<Callable<Integer>>();
        for (final Servers.Server s : servers) {
            jobs.add(new Callable<Integer>() {
                @Override
                public Integer call() {
                    return check(s.address());
                }
            });
        }
        List<Servers.Server> up = new ArrayList<Servers.Server>();
        List<Future<Integer>> results = POOL.invokeAll(jobs);
        for (int i = 0; i < results.size(); i++) {
            try {
                if (results.get(i).get() != DOWN) up.add(servers.get(i));
            } catch (Exception ignored) {
                // that one failed to be asked: it is not reachable
            }
        }
        return up;
    }
}
