package com.watchtogether.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import okhttp3.*;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SyncService extends Service {
    public static final String ACTION_CONNECT = "com.watchtogether.CONNECT";
    public static final String ACTION_CHAT = "com.watchtogether.CHAT";
    public static final String ACTION_SYNC = "com.watchtogether.SYNC";
    public static final String ACTION_STATE_REQUEST = "com.watchtogether.STATE_REQUEST";
    public static final String ACTION_DISCONNECT = "com.watchtogether.DISCONNECT";
    public static final String ACTION_PRESENCE_ACK = "com.watchtogether.PRESENCE_ACK";
    public static final String ACTION_PRESENCE_UPDATE = "com.watchtogether.PRESENCE_UPDATE";
    public static final String ACTION_EVENT = "com.watchtogether.EVENT";
    public static final String EXTRA_EVENT = "event";
    public static final String EXTRA_DATA = "data";

    private static final String CHANNEL = "watchtogether_sync";
    private final OkHttpClient client = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private WebSocket socket;
    private String serverUrl = "", anonKey = "", username = "", roomName = "", roomPassword = "", topic = "", profilePicture = "";
    private long ref = 1;
    private long reconnectDelay = 1000;
    private long syncSequence = 0;
    private boolean intentionalStop = false;
    private boolean joined = false;
    private String joinRef = null;
    private String presenceKey = "";
    private Handler handler;
    private final Runnable heartbeatRunnable = this::heartbeat;
    private Runnable reconnectRunnable;
    private Runnable presenceAnnounceRunnable;

    @Override public void onCreate() {
        super.onCreate();
        handler = new Handler(getMainLooper());
        createChannel();
        startForeground(12, notification("Connected room service"));
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "CoView", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Keeps room synchronization connected while CoView is in the background.");
            ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private Notification notification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("CoView")
                .setContentText(text)
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_CONNECT.equals(action)) {
                intentionalStop = false;
                serverUrl = intent.getStringExtra("server");
                anonKey = intent.getStringExtra("key");
                username = intent.getStringExtra("user");
                roomName = intent.getStringExtra("room");
                presenceKey = username + "-" + UUID.randomUUID().toString().substring(0, 8);
                roomPassword = intent.getStringExtra("password");
                profilePicture = intent.getStringExtra("pfp");
                if (profilePicture == null) profilePicture = "";
                connect();
            } else if (ACTION_CHAT.equals(action)) {
                try { broadcast("chat", new JSONObject().put("user", username).put("text", intent.getStringExtra("text")).put("pfp", intent.getStringExtra("pfp") == null ? profilePicture : intent.getStringExtra("pfp"))); } catch (Exception ignored) {}
            } else if (ACTION_SYNC.equals(action)) {
                try {
                    JSONObject d = new JSONObject();
                    d.put("user", username);
                    d.put("senderId", presenceKey);
                    d.put("position", intent.getLongExtra("position", 0));
                    d.put("playing", intent.getBooleanExtra("playing", false));
                    d.put("sentAt", System.currentTimeMillis());
                    d.put("seq", ++syncSequence);
                    d.put("eventId", UUID.randomUUID().toString());
                    String requestId = intent.getStringExtra("requestId");
                    if (requestId != null && !requestId.isEmpty()) d.put("requestId", requestId);
                    broadcast("sync", d);
                } catch (Exception ignored) {}
            } else if (ACTION_STATE_REQUEST.equals(action)) {
                try {
                    String requestId = intent.getStringExtra("requestId");
                    if (requestId == null || requestId.isEmpty()) requestId = UUID.randomUUID().toString();
                    broadcast("state_request", new JSONObject().put("user", presenceKey).put("requestId", requestId));
                } catch (Exception ignored) {}
            } else if (ACTION_PRESENCE_ACK.equals(action)) {
                try {
                    String forKey=intent.getStringExtra("forKey");
                    if(forKey!=null&&!forKey.isEmpty()) broadcast("room_presence_ack",new JSONObject().put("forKey",forKey).put("key",presenceKey).put("username",username).put("pfp",profilePicture));
                } catch(Exception ignored){}
            } else if (ACTION_PRESENCE_UPDATE.equals(action)) {
                String pfp=intent.getStringExtra("pfp");if(pfp!=null)profilePicture=pfp;
                try {
                    JSONObject presence = new JSONObject();
                    presence.put("key", presenceKey);
                    presence.put("username", username);
                    presence.put("pfp", profilePicture);
                    broadcast("room_presence_update", presence);
                } catch (Exception ignored) {}
            } else if (ACTION_DISCONNECT.equals(action)) {
                intentionalStop = true;
                if(joined){
                    try{broadcast("room_presence_leave",new JSONObject().put("key",presenceKey).put("username",username));}catch(Exception ignored){}
                    if(handler!=null) handler.postDelayed(()->{closeSocket();stopForeground(true);stopSelf();},350);
                } else {
                    closeSocket();
                    stopForeground(true);
                    stopSelf();
                }
            }
        }
        return START_STICKY;
    }

    private void connect() {
        closeSocket();
        joined = false;
        joinRef = null;
        if (serverUrl == null || serverUrl.isEmpty() || anonKey == null || anonKey.isEmpty()) return;
        String base = serverUrl.replaceFirst("^https?://", "").replaceAll("/+$", "");
        String scheme = serverUrl.startsWith("http://") ? "ws://" : "wss://";
        String ws = scheme + base + "/realtime/v1/websocket?apikey=" + urlEncode(anonKey) + "&vsn=1.0.0";
        Request req = new Request.Builder().url(ws).build();
        socket = client.newWebSocket(req, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                socket = webSocket; reconnectDelay = 1000; joinRoom();
            }
            @Override public void onMessage(WebSocket webSocket, String text) { handle(text); }
            @Override public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (socket != webSocket) return;
                emit("error", new JSONObjectSafe().put("message", t.getMessage() == null ? "Connection failed" : t.getMessage()).obj);
                joined = false;
                joinRef = null;
                scheduleReconnect();
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (socket != webSocket) return;
                socket = null;
                joined = false;
                joinRef = null;
                emit("disconnected", new JSONObjectSafe().put("reason", reason == null ? "" : reason).obj);
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (intentionalStop) return;
        long delay = reconnectDelay;
        reconnectDelay = Math.min(reconnectDelay * 2, 30000);
        if (reconnectRunnable != null) handler.removeCallbacks(reconnectRunnable);
        reconnectRunnable = () -> { if (!intentionalStop) connect(); };
        handler.postDelayed(reconnectRunnable, delay);
    }

    private void joinRoom() {
        try {
            topic = "realtime:room:" + sha256(roomName.trim().toLowerCase(Locale.ROOT) + "\n" + roomPassword);
            JSONObject config = new JSONObject();
            config.put("broadcast", new JSONObject().put("ack", true).put("self", false));
            config.put("presence", new JSONObject().put("key", presenceKey));
            JSONObject payload = new JSONObject().put("config", config);
            joinRef = String.valueOf(ref++);
            send(topic, "phx_join", payload, joinRef);
        } catch (Exception ignored) {}
    }

    private void track() {
        if (joined) return;
        joined = true;
        try {
            JSONObject track = new JSONObject()
                    .put("user_id", presenceKey)
                    .put("username", username)
                    .put("pfp", profilePicture);
            JSONObject payload = new JSONObject()
                    .put("type", "presence")
                    .put("event", "track")
                    .put("payload", track);
            send(topic, "presence", payload, String.valueOf(ref++));
            // Tell the activity its local presence identity BEFORE announcing the user.
            // This prevents the first roster acknowledgement from arriving before the
            // activity knows which presence key belongs to this device.
            emit("connected", new JSONObjectSafe().put("room", roomName).put("presenceKey", presenceKey).obj);
            broadcast("room_presence_join", new JSONObject().put("key",presenceKey).put("username",username).put("pfp",profilePicture));
            if(handler!=null){
                if(presenceAnnounceRunnable!=null) handler.removeCallbacks(presenceAnnounceRunnable);
                final int[] remaining={3};
                presenceAnnounceRunnable=()->{
                    if(!joined||socket==null)return;
                    try{broadcast("room_presence_join",new JSONObject().put("key",presenceKey).put("username",username).put("pfp",profilePicture));}catch(Exception ignored){}
                    if(--remaining[0]>0) handler.postDelayed(presenceAnnounceRunnable,900);
                };
                handler.postDelayed(presenceAnnounceRunnable,450);
            }

            // The activity sends a request after receiving the connected event.
            // This avoids creating an untracked catch-up request inside the service.
            heartbeat();
        } catch (Exception e) {
            emit("error", new JSONObjectSafe()
                    .put("message", e.getMessage() == null ? "Could not join room" : e.getMessage()).obj);
        }
    }

    private void heartbeat() {
        if (socket == null) return;
        try { send("phoenix", "heartbeat", new JSONObject(), String.valueOf(ref++)); } catch (Exception ignored) {}
        handler.removeCallbacks(heartbeatRunnable);
        handler.postDelayed(heartbeatRunnable, 18000);
    }

    private void send(String t, String e, JSONObject p, String r) {
        if (socket == null) return;
        try { socket.send(new JSONObject().put("topic", t).put("event", e).put("payload", p).put("ref", r).toString()); } catch (Exception ignored) {}
    }

    private void broadcast(String event, JSONObject data) {
        if (socket == null || topic.isEmpty() || !joined) return;
        try {
            JSONObject payload = new JSONObject().put("type", "broadcast").put("event", event).put("payload", data);
            send(topic, "broadcast", payload, "b" + ref++);
        } catch (Exception ignored) {}
    }

    private void handle(String raw) {
        try {
            JSONObject o = new JSONObject(raw);
            String event = o.optString("event");
            if ("phx_reply".equals(event)) {
                JSONObject p = o.optJSONObject("payload");
                if (topic.equals(o.optString("topic")) && p != null) {
                    if ("ok".equals(p.optString("status"))) {
                        // Only the acknowledgement for our exact phx_join may establish the room.
                        String replyRef = o.optString("ref", "");
                        if (!joined && joinRef != null && joinRef.equals(replyRef)) track();
                    } else {
                        JSONObject err = p.optJSONObject("response");
                        String reason = err == null ? p.optString("status", "Realtime join failed") : err.toString();
                        emit("error", new JSONObjectSafe().put("message", reason).obj);
                    }
                }
            } else if ("broadcast".equals(event)) {
                JSONObject p = o.optJSONObject("payload");
                if (p == null) return;
                JSONObject data = p.optJSONObject("payload");
                String ev = p.optString("event");
                if (data == null) return;
                if ("sync".equals(ev)) emit("sync", data);
                else if ("chat".equals(ev)) emit("chat", data);
                else if ("state_request".equals(ev) && !presenceKey.equals(data.optString("user"))) emit("state_request", data);
                else if ("room_presence_join".equals(ev) && !presenceKey.equals(data.optString("key"))) { emit("room_presence_join", data); try{broadcast("room_presence_ack",new JSONObject().put("forKey",data.optString("key","")).put("key",presenceKey).put("username",username).put("pfp",profilePicture));}catch(Exception ignored){} }
                else if ("room_presence_update".equals(ev) && !presenceKey.equals(data.optString("key"))) emit("room_presence_update", data);
                else if ("room_presence_ack".equals(ev)) emit("room_presence_ack", data);
                else if ("room_presence_leave".equals(ev) && !presenceKey.equals(data.optString("key"))) emit("room_presence_leave", data);
            } else if ("presence_state".equals(event)) {
                JSONObject state = o.optJSONObject("payload");
                if (state != null) emit("presence_state", state);
            } else if ("presence_diff".equals(event)) {
                JSONObject diff = o.optJSONObject("payload");
                if (diff != null) emit("presence_diff", diff);
            }
        } catch (Exception ignored) {}
    }

    private void emit(String event, JSONObject data) {
        Intent i = new Intent(ACTION_EVENT);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_EVENT, event);
        i.putExtra(EXTRA_DATA, data == null ? "{}" : data.toString());
        sendBroadcast(i);
    }

    private String urlEncode(String s) { try { return URLEncoder.encode(s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; } }
    private String sha256(String value) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte x : b) out.append(String.format(Locale.US, "%02x", x)); return out.toString();
        } catch (Exception e) { return Integer.toHexString(value.hashCode()); }
    }
    private void closeSocket() {
        if (handler != null) {
            handler.removeCallbacks(heartbeatRunnable);
            if (reconnectRunnable != null) handler.removeCallbacks(reconnectRunnable);
            if (presenceAnnounceRunnable != null) handler.removeCallbacks(presenceAnnounceRunnable);
        }
        joined = false;
        joinRef = null;
        if (socket != null) {
            try { socket.close(1000, "leave"); } catch (Exception ignored) {}
            socket = null;
        }
    }
    @Override public void onDestroy() { intentionalStop = true; closeSocket(); super.onDestroy(); }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }

    private static class JSONObjectSafe {
        final JSONObject obj = new JSONObject();
        JSONObjectSafe put(String k, String v) { try { obj.put(k, v); } catch (Exception ignored) {} return this; }
    }
}
