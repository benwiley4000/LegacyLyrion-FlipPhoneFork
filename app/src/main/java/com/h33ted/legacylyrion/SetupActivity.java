package com.h33ted.legacylyrion;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The find server screen (also the first-run setup). Two lists, so a server that can be used right now is
 * easy to pick:
 * <ul>
 * <li><b>Available servers</b>: the ones found on the Wi-Fi with the same UDP discovery Squeezebox hardware
 * uses (port 3483, 'e' request with IPAD/NAME/JSON tags), and every saved server that answers when asked
 * directly, which is how a saved server at another address (the one behind a reverse proxy, say) shows up
 * while the phone is away from that Wi-Fi.</li>
 * <li><b>Saved servers not reachable</b>: the rest of the saved servers, still there to tap and try.</li>
 * </ul>
 * An address can also be typed by hand. Opened because the server in use went away (see
 * {@link ServerWatch}), it says so, and whichever server is chosen takes the music over ({@link ResumeSync}).
 */
public class SetupActivity extends Activity {
    /** Intent extra: the name of the server that was lost; present when this screen was opened because of that. */
    static final String EXTRA_LOST = "lost_server";

    private LinearLayout found;
    private TextView foundHead;
    private ProgressBar spinner;
    private TextView status;
    private EditText host, port;
    private int scanGen = 0;
    private long scanStartedAt;
    private LinearLayout savedBox;
    private TextView savedHead;
    private String lostName;
    private boolean broadcastDone;
    private int probesLeft;
    private final List<Server> udp = new ArrayList<Server>();                // found by the broadcast, in the order they answered
    private final Map<String, Integer> reach = new HashMap<String, Integer>();   // saved servers (by key) that were asked directly: Reach.UP/LOGIN/DOWN

    static final class Server {
        String name, ip, port, version;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Skin.apply(this);
        super.onCreate(savedInstanceState);

        int p = Ui.dp(this, 20);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView head = Ui.text(this, 24, true, true);
        head.setSingleLine(false);
        head.setText("Find your Lyrion server");
        root.addView(head);
        TextView expl = Ui.text(this, 14, false, false);
        expl.setSingleLine(false);
        expl.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        expl.setText("Looking on this Wi-Fi network and trying the servers you saved. Tap one that is available, or type an address below.");
        root.addView(expl);
        lostName = getIntent().getStringExtra(EXTRA_LOST);
        if (lostName != null) {
            TextView lost = Ui.text(this, 15, true, true);
            lost.setSingleLine(false);
            lost.setTextColor(Ui.accent(this));
            lost.setPadding(0, 0, 0, Ui.dp(this, 12));
            lost.setText("Lost connection to " + lostName + ". Choose a server to carry on: if it is the same Lyrion server "
                    + "under another address, the music picks up where it left off.");
            root.addView(lost);
        }

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        spinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        row.addView(spinner);
        status = Ui.text(this, 14, false, false);
        status.setPadding(Ui.dp(this, 10), 0, 0, 0);
        row.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button again = new Button(this);
        again.setText("Search again");
        again.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                scan();
            }
        });
        row.addView(again);
        root.addView(row);

        foundHead = Ui.text(this, 17, true, true);
        foundHead.setText("Available servers");
        foundHead.setPadding(0, Ui.dp(this, 8), 0, 0);
        foundHead.setVisibility(View.GONE);
        root.addView(foundHead);
        found = new LinearLayout(this);
        found.setOrientation(LinearLayout.VERTICAL);
        found.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(found);

        savedHead = Ui.text(this, 17, true, true);
        savedHead.setText("Saved servers not reachable now");
        savedHead.setPadding(0, Ui.dp(this, 8), 0, 0);
        root.addView(savedHead);
        savedBox = new LinearLayout(this);
        savedBox.setOrientation(LinearLayout.VERTICAL);
        savedBox.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        root.addView(savedBox);

        TextView manual = Ui.text(this, 17, true, true);
        manual.setText("Enter it yourself");
        manual.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(manual);
        LinearLayout mrow = new LinearLayout(this);
        host = Skin.editText(this);
        host.setHint("IP address, e.g. 192.168.1.20");
        host.setSingleLine(true);
        host.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        if (Prefs.get(this).contains("lms_host")) host.setText(Prefs.host(this));
        mrow.addView(host, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        port = Skin.editText(this);
        port.setHint("9000");
        port.setSingleLine(true);
        port.setInputType(InputType.TYPE_CLASS_NUMBER);
        port.setText(Prefs.port(this));
        mrow.addView(port, new LinearLayout.LayoutParams(Ui.dp(this, 84), ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(mrow);
        Button connect = new Button(this);
        connect.setText("Connect");
        connect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String hst = host.getText().toString().trim();
                String prt = port.getText().toString().trim();
                if (hst.length() == 0) {
                    host.setError("Enter the server's address");
                    return;
                }
                test(null, hst, prt.length() == 0 ? "9000" : prt);
            }
        });
        root.addView(connect);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
        Skin.decorate(this, "Set up LegacyLyrion", null, false);
        scan();
    }

    // ------------------------------------------------------------------ discovery

    /** For {@link ServerWatch}: a server may have just become reachable; look again unless this screen only just looked. */
    void rescanIfIdle() {
        if (System.currentTimeMillis() - scanStartedAt > 3000) scan();
    }

    private void scan() {
        final int gen = ++scanGen;
        scanStartedAt = System.currentTimeMillis();
        udp.clear();
        reach.clear();
        broadcastDone = false;
        List<Servers.Server> saved = Servers.list(Prefs.get(this));
        probesLeft = saved.size();
        render();
        spinner.setVisibility(View.VISIBLE);
        status.setText("Searching…");
        // the saved servers are asked directly, all at once, alongside the broadcast: a server that is not
        // on this Wi-Fi never answers the broadcast, but can still be right there at its other address
        Reach.checkAll(saved, new Reach.Listener() {
            @Override
            public void done(Servers.Server server, int result) {
                if (gen != scanGen) return;
                reach.put(server.key(), result);
                probesLeft--;
                render();
                finishIfDone();
            }
        });
        final InetAddress bcast = broadcastAddress();
        new AsyncTask<Void, Server, Integer>() {
            @Override
            protected Integer doInBackground(Void... v) {
                List<String> seen = new ArrayList<String>();
                DatagramSocket s = null;
                try {
                    s = new DatagramSocket();
                    s.setBroadcast(true);
                    s.setSoTimeout(700);
                    byte[] req = request();
                    long end = System.currentTimeMillis() + 4000;
                    int sends = 0;
                    while (System.currentTimeMillis() < end && gen == scanGen) {
                        if (sends < 3) {
                            s.send(new DatagramPacket(req, req.length, InetAddress.getByName("255.255.255.255"), 3483));
                            if (bcast != null) s.send(new DatagramPacket(req, req.length, bcast, 3483));
                            sends++;
                        }
                        byte[] buf = new byte[1024];
                        DatagramPacket pk = new DatagramPacket(buf, buf.length);
                        try {
                            s.receive(pk);
                        } catch (SocketTimeoutException te) {
                            continue;
                        }
                        Server sv = parse(pk);
                        if (sv != null && !seen.contains(sv.ip + ":" + sv.port)) {
                            seen.add(sv.ip + ":" + sv.port);
                            publishProgress(sv);
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    if (s != null) s.close();
                }
                return seen.size();
            }

            @Override
            protected void onProgressUpdate(Server... list) {
                if (gen != scanGen) return;
                udp.add(list[0]);
                render();
            }

            @Override
            protected void onPostExecute(Integer n) {
                if (gen != scanGen) return;
                broadcastDone = true;
                finishIfDone();
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    /** Once the broadcast and every saved server have answered (or not): says what was found. */
    private void finishIfDone() {
        if (!broadcastDone || probesLeft > 0) return;
        spinner.setVisibility(View.GONE);
        int n = found.getChildCount();
        status.setText(n == 0 ? "No server answered. Check your connection, or type the address."
                : n == 1 ? "Found 1 server" : "Found " + n + " servers");
    }

    private static byte[] request() {
        String[] tags = {"IPAD", "NAME", "JSON", "VERS"};
        byte[] b = new byte[1 + tags.length * 5];
        b[0] = 'e';
        int o = 1;
        for (String t : tags) {
            for (int i = 0; i < 4; i++) b[o++] = (byte) t.charAt(i);
            b[o++] = 0; // empty value = "please tell me"
        }
        return b;
    }

    private static Server parse(DatagramPacket pk) {
        byte[] d = pk.getData();
        int n = pk.getLength();
        if (n < 1 || d[0] != 'E') return null;
        Server sv = new Server();
        sv.ip = pk.getAddress().getHostAddress();
        sv.port = "9000";
        int o = 1;
        while (o + 5 <= n) {
            String tag = new String(d, o, 4);
            int len = d[o + 4] & 0xFF;
            o += 5;
            if (o + len > n) break;
            String val = new String(d, o, len);
            o += len;
            if ("NAME".equals(tag)) sv.name = val;
            else if ("JSON".equals(tag)) sv.port = val;
            else if ("IPAD".equals(tag) && val.length() > 0) sv.ip = val;
            else if ("VERS".equals(tag)) sv.version = val;
        }
        if (sv.name == null) sv.name = "Lyrion Music Server";
        return sv;
    }

    private InetAddress broadcastAddress() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            DhcpInfo di = wm == null ? null : wm.getDhcpInfo();
            if (di == null || di.ipAddress == 0) return null;
            int b = (di.ipAddress & di.netmask) | ~di.netmask;
            byte[] q = new byte[4];
            for (int k = 0; k < 4; k++) q[k] = (byte) ((b >> (k * 8)) & 0xFF);
            return InetAddress.getByAddress(q);
        } catch (Exception e) {
            return null;
        }
    }

    /** Draws both lists from what has been found so far. */
    private void render() {
        SharedPreferences sp = Prefs.get(this);
        String active = Servers.activeId(sp);
        List<Servers.Server> saved = Servers.list(sp);
        found.removeAllViews();
        savedBox.removeAllViews();
        List<String> listed = new ArrayList<String>();   // keys already on screen: a server is listed once
        for (Server sv : udp) {
            listed.add(Auth.addressKey(sv.ip + ":" + sv.port));
            found.addView(foundRow(sv, Servers.find(saved, sv.ip, sv.port), active));
        }
        for (Servers.Server s : saved) {   // a saved server that answers belongs with the found ones, default or not
            Integer r = reach.get(s.key());
            if (listed.contains(s.key()) || r == null || r == Reach.DOWN) continue;
            listed.add(s.key());
            found.addView(savedRow(s, active, r));
        }
        foundHead.setVisibility(found.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        for (Servers.Server s : saved) {
            if (listed.contains(s.key())) continue;
            savedBox.addView(savedRow(s, active, reach.get(s.key())));
        }
        savedHead.setText(probesLeft > 0 ? "Saved servers" : "Saved servers not reachable now");   // until they have all been asked
        savedHead.setVisibility(savedBox.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    private LinearLayout rowBox() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        Ui.selectable(row);
        return row;
    }

    /** Marks the server in use; "connection lost" if that is why this screen is open and it still does not answer. */
    private String inUse(boolean answering) {
        return lostName != null && !answering ? "  •  connection lost" : "  •  in use";
    }

    /** A server the broadcast found. If it is a saved one too, choosing it brings its settings back. */
    private View foundRow(final Server sv, Servers.Server saved, String active) {
        LinearLayout row = rowBox();
        TextView name = Ui.text(this, 18, true, true);
        name.setText(saved != null ? saved.name : sv.name);
        TextView addr = Ui.text(this, 14, false, false);
        addr.setText(sv.ip + ":" + sv.port + (sv.version != null ? "  •  version " + sv.version : ""));
        row.addView(name);
        row.addView(addr);
        if (saved != null) {   // a server we know: choosing it brings its settings back
            TextView known = Ui.text(this, 14, false, false);
            known.setText("Saved  •  " + Servers.linkText(saved) + (saved.id.equals(active) ? inUse(true) : ""));
            row.addView(known);
        }
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                test(sv.name, sv.ip, sv.port);
            }
        });
        return row;
    }

    /**
     * A saved server. {@code result} is what asking it directly found: Reach.UP or LOGIN (it answers, so it is
     * listed among the available servers), DOWN (it does not), or null (the question is still out).
     */
    private View savedRow(final Servers.Server s, String active, Integer result) {
        final boolean current = s.id.equals(active);
        LinearLayout row = rowBox();
        TextView name = Ui.text(this, 18, true, true);
        name.setText(s.name);
        TextView addr = Ui.text(this, 14, false, false);
        addr.setText(Servers.describe(s));
        StringBuilder how = new StringBuilder(Servers.linkText(s));
        if (result != null && result == Reach.LOGIN && !Auth.has(s.address())) how.append("  •  needs a login");
        else if (Auth.has(s.address())) how.append("  •  login saved");
        if (result == null) how.append("  •  checking…");
        else if (result == Reach.DOWN) how.append("  •  not reachable");
        if (current) how.append(inUse(result != null && result != Reach.DOWN));
        TextView extra = Ui.text(this, 14, false, false);
        extra.setText(how.toString());
        row.addView(name);
        row.addView(addr);
        row.addView(extra);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                test(s.name, s.host, s.port);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                confirmForget(s, current);
                return true;
            }
        });
        return row;
    }

    private void confirmForget(final Servers.Server s, boolean current) {
        if (current) {
            Toast.makeText(this, "This is the server in use. Choose another one first.", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Forget " + s.name + "?")
                .setMessage("Its saved settings and login are removed. You can add it again any time.")
                .setPositiveButton("Forget", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        Servers.Server gone = Servers.forget(Prefs.get(SetupActivity.this), s.id);
                        if (gone != null) Auth.save(SetupActivity.this, gone.address(), "", "");
                        render();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------------ connect

    private void test(final String name, final String hst, final String prt) {
        // what the phone is controlling now, and how far along: once the new server's settings are live it is too late to ask
        final ResumeSync.Handoff leaving = ResumeSync.leaving(this);
        spinner.setVisibility(View.VISIBLE);
        status.setText("Connecting to " + hst + ":" + prt + "…");
        Rpc.call(hst + ":" + prt, "", Rpc.cmd("serverstatus", "0", "0"), new Rpc.Callback() {
            @Override
            public void done(JSONObject r, Exception e) {
                spinner.setVisibility(View.GONE);
                if (e instanceof Auth.Required) {
                    status.setText(hst + ":" + prt + " is password protected");
                    Auth.prompt(SetupActivity.this, hst + ":" + prt, (Auth.Required) e, new Runnable() {
                        @Override
                        public void run() {
                            test(name, hst, prt);
                        }
                    });
                    return;
                }
                if (r == null) {
                    status.setText("No Lyrion server at " + hst + ":" + prt
                            + (e != null && e.getMessage() != null ? " (" + e.getMessage() + ")" : ""));
                    return;
                }
                // a saved server comes back with its own settings; a new one starts from the defaults
                SharedPreferences sp = Prefs.get(SetupActivity.this);
                Servers.Server known = Servers.find(Servers.list(sp), hst, prt);
                Servers.activate(sp, known != null ? known : Servers.create(name, hst, prt));
                ServerWatch.reset();   // a lost server is no longer the one in use
                // if the player we were using is on this server too (the same Lyrion under another address),
                // the player here is synced to it, so the music carries on from where it was
                ResumeSync.arrive(SetupActivity.this, leaving);
                startActivity(new Intent(SetupActivity.this, HomeActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
                finish();
            }
        });
    }
}
