package com.h33ted.legacylyrion;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.EOFException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Notices that the server in use has gone away (the phone left the Wi-Fi, the server's address stopped
 * working) and, if one of the other saved servers can be reached from where the phone is now, offers to
 * go there: a dialog if the app is in front, a notification if it is not, both leading to the find server
 * screen. Choosing a server there carries the music over ({@link ResumeSync}).
 *
 * Nothing here depends on how the phone connects as a player. There are two kinds of hints that something
 * is wrong, and both are reported by the code that sees them: a JSON-RPC request that could not connect
 * ({@link LyrionRpcClient}, which every screen and the notification poll through) and the phone player's
 * own connection failing, whether it is a SlimProto socket or the LyrionJS WebSocket
 * ({@link SlimProtoService}). A hint is only a reason to look: the server counts as gone when it fails
 * to answer a few direct requests in a row, which is also what makes a link problem with a working web
 * port (port 3483 blocked, say) not look like a lost server.
 */
final class ServerWatch {
    private static final int HEALTHY = 0, CHECKING = 1, LOST = 2;
    private static final int NOTIF_ID = 3484;
    private static final int CHECKS = 3;                   // this many requests, ...
    private static final long CHECK_GAP_MS = 2000;         // ... this far apart, must fail before the server counts as gone
    private static final long QUIET_RPC_MS = 5000;         // after a check found the server fine, request failures wait this long
    private static final long QUIET_LINK_MS = 30000;       // ... and link failures this long: a blocked player port fails forever
    private static final long RESCAN_MS = 8000;            // while gone: how often another server is looked for
    private static final long GIVE_UP_MS = 5 * 60 * 1000;  // ... and for how long

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private static Context app;
    private static volatile int state = HEALTHY;
    private static volatile long firstTrouble;     // when the current run of failures began (ResumeSync clock); 0 = none
    private static long lastFine;
    private static long episode;                   // changes whenever a look at the server starts or ends; older looks stop
    private static String watchKey = "", lostName = "";
    private static boolean pending;                // an offer has not been answered yet
    private static List<String> offered = new ArrayList<String>();
    private static AlertDialog dialog;             // UI thread only
    private static Activity dialogHost;            // the screen it was shown on

    private ServerWatch() {}

    /** Once per process, from Prefs. */
    static void init(Context c) {
        if (app == null) app = c.getApplicationContext();
    }

    // ------------------------------------------------------------------ what the rest of the app reports

    /** A request to this server worked (or the server answered that it wants a login). */
    static void ok(String address) {
        if (app == null) return;
        if (state == HEALTHY && firstTrouble == 0) return;   // nothing to undo: the usual case, kept cheap
        synchronized (LOCK) {
            if (state != HEALTHY && !activeKey().equals(watchKey)) {   // the phone moved to another server meanwhile
                endLocked(false);
                return;
            }
            if (!isActive(address)) return;
            endLocked(true);
        }
    }

    /** A request to this server failed. */
    static void failed(String address, Exception e) {
        if (app == null) return;
        if (e instanceof Auth.Required) {   // it answered: it wants a login
            ok(address);
            return;
        }
        if (isConnectionFailure(e)) trouble(address, QUIET_RPC_MS);
    }

    /** The phone player's own connection to this server failed (SlimProto socket or LyrionJS bridge alike). */
    static void linkFailed(String address) {
        if (app != null) trouble(address, QUIET_LINK_MS);
    }

    /** The phone moved to another server on purpose. */
    static void reset() {
        synchronized (LOCK) {
            endLocked(false);
            lastFine = 0;
        }
    }

    /** The screen in front changed: an offer made while the app was in the background is shown now. */
    static void onResumed() {
        boolean wanted;
        synchronized (LOCK) {
            wanted = pending;
        }
        if (wanted) show();
    }

    static boolean isConnectionFailure(Exception e) {
        return e instanceof SocketException || e instanceof SocketTimeoutException
                || e instanceof UnknownHostException || e instanceof EOFException;
    }

    // ------------------------------------------------------------------ looking at the server

    private static String activeKey() {
        return Auth.addressKey(Prefs.hostPort(app));
    }

    private static boolean isActive(String address) {
        return Auth.addressKey(address).equals(activeKey());
    }

    /** Back to normal. Call with LOCK held. */
    private static void endLocked(boolean fine) {
        boolean offer = pending;
        state = HEALTHY;
        firstTrouble = 0;
        episode++;
        watchKey = "";
        if (fine) lastFine = ResumeSync.now();
        if (offer) {
            pending = false;
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    withdraw();
                }
            });
        }
    }

    private static void trouble(final String address, long quietMs) {
        if (!isActive(address)) return;
        final long gen;
        synchronized (LOCK) {
            if (state != HEALTHY) return;   // already looking
            long now = ResumeSync.now();
            if (firstTrouble == 0) firstTrouble = now;
            if (lastFine != 0 && now - lastFine < quietMs) return;
            state = CHECKING;
            watchKey = activeKey();
            gen = ++episode;
        }
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    confirm(gen, address);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "ServerWatch");
        t.setDaemon(true);
        t.start();
    }

    /** True while this look is still the current one and the phone is still on the same server. */
    private static boolean watching(long gen) {
        synchronized (LOCK) {
            return episode == gen && activeKey().equals(watchKey);
        }
    }

    private static void confirm(long gen, String address) throws InterruptedException {
        for (int i = 0; i < CHECKS; i++) {
            if (i > 0) Thread.sleep(CHECK_GAP_MS);
            if (!watching(gen)) return;
            if (Reach.check(address) != Reach.DOWN) {
                synchronized (LOCK) {
                    if (episode == gen) endLocked(true);
                }
                return;
            }
        }
        lost(gen, address);
    }

    private static void lost(long gen, String address) throws InterruptedException {
        long since;
        String lostKey;
        synchronized (LOCK) {
            if (episode != gen) return;
            state = LOST;
            since = firstTrouble;
            lostKey = watchKey;
            lostName = nameOf(address);
        }
        SlimProtoService.log("server lost: " + address);
        // the music stops where the connection did, whatever the polls say about it from here on
        ResumeSync.freeze(address, since);

        long until = ResumeSync.now() + GIVE_UP_MS;
        while (watching(gen) && ResumeSync.now() < until) {
            if (Reach.check(address) != Reach.DOWN) {   // it came back by itself
                synchronized (LOCK) {
                    if (episode == gen) endLocked(true);
                }
                return;
            }
            List<Servers.Server> others = reachableOthers(lostKey);
            if (!others.isEmpty()) {
                offer(gen, others);
                return;
            }
            // a phone that just left the Wi-Fi may need a few seconds for mobile data: look again
            Thread.sleep(RESCAN_MS);
        }
    }

    /** The saved servers other than the lost one that answer from here. */
    private static List<Servers.Server> reachableOthers(String lostKey) throws InterruptedException {
        List<Servers.Server> candidates = new ArrayList<Servers.Server>();
        for (Servers.Server s : Servers.list(Prefs.get(app))) {
            if (!s.key().equals(lostKey)) candidates.add(s);
        }
        return Reach.reachable(candidates);
    }

    private static String nameOf(String address) {
        String key = Auth.addressKey(address);
        for (Servers.Server s : Servers.list(Prefs.get(app))) {
            if (s.key().equals(key)) return s.name;
        }
        return Servers.defaultName(address);
    }

    // ------------------------------------------------------------------ the offer

    private static void offer(long gen, List<Servers.Server> others) {
        synchronized (LOCK) {
            if (episode != gen) return;
            offered = new ArrayList<String>();
            for (Servers.Server s : others) offered.add(s.name);
            pending = true;
        }
        SlimProtoService.log("server lost, others reachable: " + offered);
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                show();
            }
        });
    }

    private static String namesText(List<String> names) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(i == names.size() - 1 ? " and " : ", ");
            sb.append(names.get(i));
        }
        return sb.toString();
    }

    private static Intent chooser(Context c, String lost) {
        return new Intent(c, SetupActivity.class).putExtra(SetupActivity.EXTRA_LOST, lost);
    }

    private static void answered() {
        synchronized (LOCK) {
            pending = false;
        }
    }

    /** UI thread. Asks on the screen in front, or leaves a notification if there is none. */
    private static void show() {
        final List<String> names;
        final String lost;
        synchronized (LOCK) {
            if (!pending) return;
            names = new ArrayList<String>(offered);
            lost = lostName;
        }
        final Activity a = Auth.foreground();
        if (a == null) {
            notifyAway(lost, names);
            return;
        }
        cancelNotification();
        if (a instanceof SetupActivity) {   // already where a server is chosen; make sure it shows the one that is back
            answered();
            ((SetupActivity) a).rescanIfIdle();
            return;
        }
        if (dialog != null) {
            if (dialogHost == a && dialog.isShowing()) return;
            try {
                dialog.dismiss();   // one that belongs to a screen that is gone
            } catch (RuntimeException ignored) {}
            dialog = null;
        }
        dialogHost = a;
        dialog = new AlertDialog.Builder(a)
                .setTitle("Lost connection to " + lost)
                .setMessage(namesText(names) + (names.size() == 1 ? " is" : " are") + " reachable from here. "
                        + "Choose a server to carry on; the music picks up where it left off.")
                .setPositiveButton("Choose server", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        answered();
                        a.startActivity(chooser(a, lost));
                    }
                })
                .setNegativeButton("Not now", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        answered();
                    }
                })
                .create();
        dialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface d) {
                answered();
            }
        });
        dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface d) {
                dialog = null;
                dialogHost = null;
            }
        });
        dialog.show();
    }

    /** UI thread. The server is back, or the phone moved on: the offer no longer stands. */
    private static void withdraw() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
        dialogHost = null;
        cancelNotification();
    }

    @SuppressWarnings("deprecation")
    private static void notifyAway(String lost, List<String> names) {
        Notification.Builder b = new Notification.Builder(app)
                .setSmallIcon(R.drawable.ic_stat_legacylyrion)
                .setContentTitle("Lost connection to " + lost)
                .setContentText(namesText(names) + " can be reached. Tap to choose a server.")
                .setAutoCancel(true)
                .setContentIntent(PendingIntent.getActivity(app, 1,
                        chooser(app, lost).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT));
        if (Build.VERSION.SDK_INT >= 16) b.setPriority(Notification.PRIORITY_HIGH);
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, b.getNotification());
    }

    private static void cancelNotification() {
        NotificationManager nm = (NotificationManager) app.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ID);
    }
}
