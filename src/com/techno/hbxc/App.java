package com.techno.hbxc;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CopyOnWriteArrayList;

public class App extends Application implements XmppClient.Listener {
    public interface UiListener { void onChanged(); void onState(String s); }

    public static App I;
    public Db db;
    public XmppClient client;
    public String state = "Offline";
    public static String openPeer;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<UiListener> ls = new CopyOnWriteArrayList<UiListener>();
    private SharedPreferences prefs;

    @Override public void onCreate() {
        super.onCreate();
        I = this;
        db = new Db(this);
        prefs = getSharedPreferences("acct", MODE_PRIVATE);
        if (hasAccount()) connect(prefs.getString("jid", ""), prefs.getString("pass", ""), false);
    }

    private SharedPreferences readPrefs() { return getSharedPreferences("read", MODE_PRIVATE); }
    public long lastRead(String peer) {
        SharedPreferences p = readPrefs();
        if (!p.contains("_base")) p.edit().putLong("_base", System.currentTimeMillis()).commit();
        return p.getLong(peer, p.getLong("_base", 0));
    }
    public void markRead(String peer) { readPrefs().edit().putLong(peer, System.currentTimeMillis()).commit(); }

    // ---- group chats ----
    private SharedPreferences roomPrefs() { return getSharedPreferences("rooms_" + myJid().toLowerCase(), MODE_PRIVATE); }
    public java.util.Map<String, String> rooms() {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<String, String>();
        for (java.util.Map.Entry<String, ?> e : roomPrefs().getAll().entrySet()) m.put(e.getKey(), String.valueOf(e.getValue()));
        return m;
    }
    public boolean isRoom(String jid) { return jid != null && hasAccount() && roomPrefs().contains(jid); }
    public String nameOf(String peer) { return isRoom(peer) ? Ui.local(peer) : (client != null ? client.nameOf(peer) : peer); }
    public void joinRoom(String room, String nick) {
        room = room.toLowerCase();
        roomPrefs().edit().putString(room, nick).commit();
        if (client != null) client.joinRoom(room, nick);
        fire();
    }
    public void setRoomNick(String room, String nick) { roomPrefs().edit().putString(room, nick).commit(); }
    public void leaveRoom(String room) {
        roomPrefs().edit().remove(room).commit();
        if (client != null) client.leaveRoom(room);
        db.deletePeer(room);
    }
    public void roomFailed(final String room, final String why) {
        roomPrefs().edit().remove(room).commit();
        toast("Couldn't join " + room + ": " + why);
        ui.post(new Runnable() { public void run() { fire(); } });
    }
    public void sendGroup(final String room, final String body) {
        new Thread(new Runnable() { public void run() {
            XmppClient c = client;
            if (c == null || !c.sendGroup(room, body)) toast("Not connected – message not sent");
        } }).start();
    }
    public void onGroupMessage(final String room, final String nick, final String body, final boolean out, final long ts, String sid) {
        if (!isRoom(room)) return;
        boolean added = db.addGroup(room, nick, body, out, ts, sid);
        if (!added) return;
        final boolean live = Math.abs(System.currentTimeMillis() - ts) < 120000;
        ui.post(new Runnable() { public void run() {
            fire();
            if (live && !out && !room.equals(openPeer)) notifyGroup(room, nick, body);
        } });
    }
    @SuppressWarnings("deprecation")
    private void notifyGroup(String room, String nick, String body) {
        Intent i = new Intent(this, ChatActivity.class).putExtra("peer", room).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, room.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(Ui.local(room)).setContentText(nick + ": " + body).setContentIntent(pi).setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_SOUND).getNotification();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(room.hashCode(), n);
    }

    public boolean hasAccount() { return prefs.getString("jid", null) != null; }
    public String myJid() { return prefs.getString("jid", ""); }

    public void login(String jid, String pass) {
        prefs.edit().putString("jid", jid).putString("pass", pass).commit();
        connect(jid, pass, false);
    }

    public void register(String jid, String pass) {
        prefs.edit().putString("jid", jid).putString("pass", pass).commit();
        connect(jid, pass, true);
    }

    private void connect(String jid, String pass, boolean reg) {
        if (client != null) client.stop();
        client = new XmppClient(jid, pass, this);
        client.registerMode = reg;
        for (java.util.Map.Entry<String, String> e : rooms().entrySet()) client.joined.put(e.getKey(), e.getValue());
        client.start();
        startService(new Intent(this, XmppService.class));
    }

    public void logout() {
        if (client != null) client.stop();
        client = null;
        prefs.edit().clear().commit();
        stopService(new Intent(this, XmppService.class));
        state = "Offline";
    }

    private void toast(final String s) {
        ui.post(new Runnable() { public void run() { android.widget.Toast.makeText(App.this, s, android.widget.Toast.LENGTH_LONG).show(); } });
    }

    public void sendAttachment(final String peer, final android.net.Uri uri) {
        final XmppClient cl = client;
        if (cl == null || !cl.isOnline()) { toast("Not connected"); return; }
        if (cl.uploadJid == null) { toast("This server doesn't offer file sharing"); return; }
        toast("Uploading…");
        new Thread(new Runnable() { public void run() {
            try {
                android.content.ContentResolver cr = getContentResolver();
                String name = "file";
                android.database.Cursor c = cr.query(uri, null, null, null, null);
                if (c != null) { try { int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (c.moveToFirst() && i >= 0) name = c.getString(i); } finally { c.close(); } }
                String mime = cr.getType(uri); if (mime == null) mime = "application/octet-stream";
                java.io.InputStream in = cr.openInputStream(uri);
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[16384]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { total += n; if (total > 30L * 1024 * 1024) { in.close(); toast("File is too large (max 30 MB)"); return; } bo.write(buf, 0, n); }
                in.close();
                final byte[] data = bo.toByteArray(); final String fmime = mime;
                cl.requestSlot(name, data.length, mime, new XmppClient.SlotCb() {
                    public void onError(String why) { toast(why); }
                    public void onSlot(final String put, final java.util.Map<String, String> headers, final String get) {
                        new Thread(new Runnable() { public void run() {
                            try {
                                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(put).openConnection();
                                h.setConnectTimeout(15000); h.setReadTimeout(60000);
                                h.setRequestMethod("PUT"); h.setDoOutput(true);
                                h.setFixedLengthStreamingMode(data.length);
                                h.setRequestProperty("Content-Type", fmime);
                                for (java.util.Map.Entry<String, String> e : headers.entrySet()) h.setRequestProperty(e.getKey(), e.getValue());
                                java.io.OutputStream o = h.getOutputStream(); o.write(data); o.close();
                                int code = h.getResponseCode();
                                if (code < 200 || code >= 300) { toast("Upload failed (HTTP " + code + ")"); return; }
                                final long ts = System.currentTimeMillis();
                                if (isRoom(peer)) {
                                    if (!cl.sendGroupOob(peer, get)) toast("Not connected – attachment not sent");
                                } else if (cl.sendOob(peer, get)) {
                                    db.add(peer, get, true, ts);
                                    ui.post(new Runnable() { public void run() { fire(); } });
                                } else toast("Not connected – attachment not sent");
                            } catch (Exception e) { toast("Upload failed: " + e.getMessage()); }
                        } }).start();
                    }
                });
            } catch (Exception e) { toast("Could not read the file"); }
        } }).start();
    }

    public void add(UiListener l) { ls.add(l); }
    public void remove(UiListener l) { ls.remove(l); }

    public void send(final String peer, final String body) {
        final long ts = System.currentTimeMillis();
        new Thread(new Runnable() { public void run() {
            XmppClient c = client;
            if (c != null && c.sendMessage(peer, body)) {
                db.add(peer, body, true, ts);
                ui.post(new Runnable() { public void run() { fire(); } });
            } else {
                ui.post(new Runnable() { public void run() {
                    android.widget.Toast.makeText(App.this, "Not connected – message not sent", android.widget.Toast.LENGTH_SHORT).show();
                } });
            }
        } }).start();
    }

    private void fire() { for (UiListener l : ls) l.onChanged(); }

    // XmppClient.Listener (called from the xmpp thread)
    public void onState(final String s) {
        state = s;
        if (s.equals("Online")) {
            // Keep history across sign-outs; wipe only when a different account signs in.
            SharedPreferences ow = getSharedPreferences("owner", MODE_PRIVATE);
            String owner = ow.getString("jid", null), me = myJid();
            if (owner != null && !owner.equalsIgnoreCase(me)) {
                db.clear();
                getSharedPreferences("mam", MODE_PRIVATE).edit().clear().commit();
                getSharedPreferences("read", MODE_PRIVATE).edit().clear().commit();
            }
            if (owner == null || !owner.equalsIgnoreCase(me)) ow.edit().putString("jid", me).commit();
        }
        ui.post(new Runnable() { public void run() { for (UiListener l : ls) l.onState(s); } });
    }

    public void onLog() { ui.post(new Runnable() { public void run() { for (UiListener l : ls) l.onState(state); } }); }

    public void onRoster() { ui.post(new Runnable() { public void run() { fire(); } }); }

    public void onMessage(final String peer, final String body, boolean outgoing, long ts) {
        db.add(peer, body, outgoing, ts);
        ui.post(new Runnable() { public void run() {
            fire();
            if (!outgoing && !peer.equals(openPeer)) notifyMsg(peer, body);
        } });
    }

    @SuppressWarnings("deprecation")
    private void notifyMsg(String peer, String body) {
        String name = client != null ? client.nameOf(peer) : peer;
        Intent i = new Intent(this, ChatActivity.class).putExtra("peer", peer).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(this, peer.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(name).setContentText(body).setContentIntent(pi).setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE).getNotification();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(peer.hashCode(), n);
    }

    public void clearNotification(String peer) {
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).cancel(peer.hashCode());
    }
}
