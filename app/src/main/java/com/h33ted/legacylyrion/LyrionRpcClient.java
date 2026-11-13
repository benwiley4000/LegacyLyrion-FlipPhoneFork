package com.h33ted.legacylyrion;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class LyrionRpcClient {

    /**
     * Every JSON-RPC request of the app goes through here, so this is where the rest of the app learns
     * whether the server is there ({@link ServerWatch}) and what the controlled player is playing
     * ({@link ResumeSync}, which needs it to carry the music over when the server has to change).
     */
    public static JSONObject sendCommand(String serverHostPort, String playerId, JSONArray commandArray) throws Exception {
        // While the music of the old server is being carried over, what this player reports is about to change:
        // the screens' polls wait for that rather than show the track the player had before
        if ("status".equals(commandArray.optString(0))) ResumeSync.awaitRelease(playerId);
        return sendCommandUnheld(serverHostPort, playerId, commandArray);
    }

    /** As {@link #sendCommand}, but never waits for a hold: for the code that is doing the carrying over. */
    static JSONObject sendCommandUnheld(String serverHostPort, String playerId, JSONArray commandArray) throws Exception {
        JSONObject result;
        try {
            result = sendCommandRaw(serverHostPort, playerId, commandArray, 4500, 6000);
        } catch (Exception e) {
            ServerWatch.failed(serverHostPort, e);
            throw e;
        }
        ServerWatch.ok(serverHostPort);
        ResumeSync.observe(serverHostPort, playerId, commandArray, result);
        return result;
    }

    /** The request itself, reporting to no one: for asking whether a server is there at all. */
    static JSONObject sendCommandRaw(String serverHostPort, String playerId, JSONArray commandArray,
                                     int connectTimeoutMs, int readTimeoutMs) throws Exception {
        URL url = new URL((serverHostPort.contains("://") ? "" : "http://") + serverHostPort + "/jsonrpc.js");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        Auth.apply(conn);

        JSONObject payload = new JSONObject();
        payload.put("id", 1);
        payload.put("method", "slim.request");

        JSONArray params = new JSONArray();
        params.put(playerId == null ? "" : playerId);
        params.put(commandArray);
        payload.put("params", params);

        byte[] body = payload.toString().getBytes("UTF-8");
        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.flush();
        os.close();
        Auth.check(conn);   // 401 with a Basic challenge -> Auth.Required

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        conn.disconnect();

        return new JSONObject(sb.toString()).optJSONObject("result");
    }

    public static Bitmap fetchCoverArt(String serverHostPort, String trackId, String artworkUrl) {
        try {
            String fullUrl;
            if (artworkUrl != null && artworkUrl.startsWith("http")) {
                fullUrl = artworkUrl;
            } else if (trackId != null && trackId.length() > 0 && !"0".equals(trackId)) {
                fullUrl = (serverHostPort.contains("://") ? "" : "http://") + serverHostPort + "/music/" + trackId + "/cover_300x300.jpg";
            } else {
                return null;
            }
            URL url = new URL(fullUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            Auth.apply(conn);
            conn.setConnectTimeout(3500);
            conn.setReadTimeout(3500);
            InputStream is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            is.close();
            conn.disconnect();
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }
}
