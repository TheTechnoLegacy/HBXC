package com.techno.hbxc;

import android.util.Base64;
import android.util.Xml;
import java.io.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.*;
import org.xmlpull.v1.XmlPullParser;

public class XmppClient {
    public interface Listener {
        void onState(String state);
        void onMessage(String peer, String body, boolean outgoing, long ts);
        void onRoster();
        void onLog();
        void onGroupMessage(String room, String nick, String body, boolean out, long ts, String sid);
    }

    public static final StringBuilder LOG = new StringBuilder();
    public static String logText() { synchronized (LOG) { return LOG.toString(); } }
    private void log(String m) {
        synchronized (LOG) { LOG.append(m).append('\n'); if (LOG.length() > 4000) LOG.delete(0, LOG.length() - 3000); }
        if (listener != null) listener.onLog();
    }

    static class Node {
        String name; Map<String, String> a = new HashMap<String, String>();
        StringBuilder text = new StringBuilder(); List<Node> kids = new ArrayList<Node>();
        String attr(String k) { return a.get(k); }
        Node child(String n) { for (Node k : kids) if (k.name.equals(n)) return k; return null; }
    }

    public static class Contact {
        public String jid, name, sub; public boolean online;
        public String display() { return (name != null && name.length() > 0) ? name : jid.split("@")[0]; }
    }

    static class AuthException extends Exception { AuthException(String m) { super(m); } }
    static class RegException extends AuthException { RegException(String m) { super("Registration: " + m); } }
    public boolean registerMode;

    private final String jid, pass, domain, user;
    private Listener listener;
    private volatile boolean wanted;
    private volatile boolean online;
    private Socket sock; private OutputStream out; private XmlPullParser p;
    private Thread thread;
    public String boundJid;
    private final Map<String, Contact> roster = new LinkedHashMap<String, Contact>();

    public XmppClient(String jid, String pass, Listener l) {
        this.jid = jid; this.pass = pass; this.listener = l;
        this.user = jid.substring(0, jid.indexOf('@'));
        this.domain = jid.substring(jid.indexOf('@') + 1);
    }

    // ---- Carbons / MAM sync ----
    // ---- Group chats (XEP-0045) ----
    public final Map<String, String> joined = Collections.synchronizedMap(new HashMap<String, String>());
    public volatile String mucJid;
    public interface RoomsCb { void onRooms(List<String[]> rooms); void onError(String why); }
    private final Map<String, RoomsCb> roomCbs = new HashMap<String, RoomsCb>();

    private void sendJoin(String room, String nick) throws IOException {
        send("<presence to='" + esc(room + "/" + nick) + "'><x xmlns='http://jabber.org/protocol/muc'><history maxstanzas='60'/></x></presence>");
    }

    private void joinAll() {
        List<String[]> l = new ArrayList<String[]>();
        synchronized (joined) { for (Map.Entry<String, String> e : joined.entrySet()) l.add(new String[]{e.getKey(), e.getValue()}); }
        for (String[] r : l) { try { log("Joining " + r[0] + " as " + r[1]); sendJoin(r[0], r[1]); } catch (IOException e) { } }
    }

    public void joinRoom(final String room, final String nick) {
        joined.put(room, nick);
        if (!online) return;
        new Thread(new Runnable() { public void run() { try { sendJoin(room, nick); } catch (IOException e) { } } }).start();
    }

    public void leaveRoom(final String room) {
        final String nick = joined.remove(room);
        if (nick == null || !online) return;
        new Thread(new Runnable() { public void run() {
            try { send("<presence to='" + esc(room + "/" + nick) + "' type='unavailable'/>"); } catch (IOException e) { }
        } }).start();
    }

    public boolean sendGroup(String room, String body) {
        if (!online || !joined.containsKey(room)) return false;
        try {
            send("<message to='" + esc(room) + "' type='groupchat' id='g" + System.nanoTime() + "'><body>" + esc(body) + "</body></message>");
            return true;
        } catch (IOException e) { return false; }
    }

    public boolean sendGroupOob(String room, String url) {
        if (!online || !joined.containsKey(room)) return false;
        try {
            send("<message to='" + esc(room) + "' type='groupchat' id='g" + System.nanoTime() + "'><body>" + esc(url) + "</body><x xmlns='jabber:x:oob'><url>" + esc(url) + "</url></x></message>");
            return true;
        } catch (IOException e) { return false; }
    }

    public void browseRooms(RoomsCb cb) {
        if (!online || mucJid == null) { cb.onError(mucJid == null ? "No group chat service found on this server" : "Not connected"); return; }
        String id = "rooms|" + System.nanoTime();
        synchronized (roomCbs) { roomCbs.put(id, cb); }
        try { send("<iq type='get' id='" + id + "' to='" + esc(mucJid) + "'><query xmlns='http://jabber.org/protocol/disco#items'/></iq>"); }
        catch (IOException e) { synchronized (roomCbs) { roomCbs.remove(id); } cb.onError("Not connected"); }
    }

    private void handleRooms(Node n, String id) {
        RoomsCb cb; synchronized (roomCbs) { cb = roomCbs.remove(id); }
        if (cb == null) return;
        if (!"result".equals(n.attr("type"))) { cb.onError("Couldn't list rooms"); return; }
        List<String[]> l = new ArrayList<String[]>();
        Node q = n.child("query");
        if (q != null) for (Node k : q.kids) if ("item".equals(k.name) && k.attr("jid") != null)
            l.add(new String[]{k.attr("jid"), k.attr("name") != null ? k.attr("name") : k.attr("jid").split("@")[0]});
        cb.onRooms(l);
    }

    private void handleMucPresence(Node n, String room) throws IOException {
        String type = n.attr("type");
        if ("error".equals(type)) {
            Node e = n.child("error"); String cond = "error";
            if (e != null) for (Node k : e.kids) if (!"text".equals(k.name)) { cond = k.name; break; }
            String nick = joined.get(room);
            if ("conflict".equals(cond) && nick != null && nick.length() < 40) {
                String nn = nick + "_"; joined.put(room, nn); App.I.setRoomNick(room, nn);
                log("Nickname taken in " + room + ", trying " + nn); sendJoin(room, nn);
            } else {
                joined.remove(room);
                App.I.roomFailed(room, cond.replace('-', ' '));
            }
            return;
        }
        Node x = n.child("x");
        if (x != null) for (Node k : x.kids) if ("status".equals(k.name) && "110".equals(k.attr("code")) && type == null) log("Joined " + room);
    }

    private void handleGroup(Node n) {
        String from = n.attr("from"); if (from == null) return;
        String room = bare(from);
        if (!joined.containsKey(room)) return;
        int sl = from.indexOf('/');
        String nick = sl >= 0 ? from.substring(sl + 1) : "";
        if (nick.length() == 0) return;
        Node body = n.child("body");
        if (body == null || body.text.length() == 0) return;
        long ts = System.currentTimeMillis();
        Node delay = n.child("delay");
        if (delay != null && delay.attr("stamp") != null) ts = parseStamp(delay.attr("stamp"));
        String sid = null;
        Node sn = n.child("stanza-id");
        if (sn != null && sn.attr("id") != null) sid = "g:" + room + ":" + sn.attr("id");
        else if (n.attr("id") != null) sid = "g:" + room + ":" + nick + ":" + n.attr("id");
        boolean out = nick.equals(joined.get(room));
        if (listener != null) listener.onGroupMessage(room, nick, body.text.toString(), out, ts, sid);
    }

    public interface SlotCb { void onSlot(String putUrl, Map<String, String> headers, String getUrl); void onError(String why); }
    public volatile String uploadJid;
    private final Map<String, SlotCb> slots = new HashMap<String, SlotCb>();

    private void checkUpload(Node n, String from) {
        if (!"result".equals(n.attr("type"))) return;
        Node q = n.child("query"); if (q == null) return;
        for (Node k : q.kids) {
            if ("identity".equals(k.name) && "conference".equals(k.attr("category")) && mucJid == null) { mucJid = from; log("Group chats: available (" + from + ")"); }
            if ("feature".equals(k.name)) {
                String var = k.attr("var");
                if ("http://jabber.org/protocol/muc".equals(var) && mucJid == null && !from.equals(domain)) { mucJid = from; log("Group chats: available (" + from + ")"); }
                if ("urn:xmpp:http:upload:0".equals(var) && uploadJid == null) { uploadJid = from; log("File sharing: available (" + from + ")"); }
            }
        }
    }

    public void requestSlot(String filename, long size, String mime, SlotCb cb) {
        if (!online || uploadJid == null) { cb.onError(uploadJid == null ? "This server doesn't offer file sharing" : "Not connected"); return; }
        String id = "slot" + System.nanoTime();
        synchronized (slots) { slots.put(id, cb); }
        try {
            send("<iq type='get' to='" + esc(uploadJid) + "' id='" + id + "'><request xmlns='urn:xmpp:http:upload:0' filename='" + esc(filename) + "' size='" + size + "' content-type='" + esc(mime) + "'/></iq>");
        } catch (IOException e) { synchronized (slots) { slots.remove(id); } cb.onError("Not connected"); }
    }

    private void handleSlot(Node n, String id) {
        SlotCb cb; synchronized (slots) { cb = slots.remove(id); }
        if (cb == null) return;
        if (!"result".equals(n.attr("type"))) {
            String why = "The server refused the upload";
            Node e = n.child("error"); Node t = e != null ? e.child("text") : null;
            if (t != null && t.text.length() > 0) why = t.text.toString().trim();
            cb.onError(why); return;
        }
        Node slot = n.child("slot");
        Node put = slot != null ? slot.child("put") : null, get = slot != null ? slot.child("get") : null;
        if (put == null || get == null || put.attr("url") == null || get.attr("url") == null) { cb.onError("Bad upload reply from server"); return; }
        Map<String, String> h = new HashMap<String, String>();
        for (Node k : put.kids) if ("header".equals(k.name) && k.attr("name") != null) h.put(k.attr("name"), k.text.toString().trim());
        cb.onSlot(put.attr("url"), h, get.attr("url"));
    }

    public boolean sendOob(String to, String url) {
        if (!online) return false;
        try {
            send("<message to='" + esc(to) + "' type='chat' id='m" + System.nanoTime() + "'><body>" + esc(url) + "</body><x xmlns='jabber:x:oob'><url>" + esc(url) + "</url></x></message>");
            return true;
        } catch (IOException e) { return false; }
    }

    private String mamNs; private String mamIq, mamQueryId; private boolean mamBackward; private int mamPages; private int mamAdded;
    private int mamCounter;

    private android.content.SharedPreferences mamPrefs() { return App.I.getSharedPreferences("mam", 0); }
    private String myBare() { return bare(boundJid != null ? boundJid : jid); }

    private static long parseStamp(String st) {
        try {
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            f.setTimeZone(TimeZone.getTimeZone("UTC"));
            return f.parse(st.substring(0, 19)).getTime();
        } catch (Exception e) { return System.currentTimeMillis(); }
    }

    private void startSync() throws IOException {
        send("<iq type='set' id='carb1'><enable xmlns='urn:xmpp:carbons:2'/></iq>");
        send("<iq type='get' id='disco_acc' to='" + esc(myBare()) + "'><query xmlns='http://jabber.org/protocol/disco#info'/></iq>");
        send("<iq type='get' id='disco_srv' to='" + esc(domain) + "'><query xmlns='http://jabber.org/protocol/disco#info'/></iq>");
        send("<iq type='get' id='ditems' to='" + esc(domain) + "'><query xmlns='http://jabber.org/protocol/disco#items'/></iq>");
    }

    private void handleDisco(Node n) throws IOException {
        Node q = n.child("query");
        List<String> feats = new ArrayList<String>();
        if (q != null) for (Node k : q.kids) if ("feature".equals(k.name) && k.attr("var") != null) feats.add(k.attr("var"));
        if (!"result".equals(n.attr("type"))) { log("Archive check failed (server error)"); return; }
        if (feats.contains("urn:xmpp:mam:2")) mamNs = "urn:xmpp:mam:2";
        else if (feats.contains("urn:xmpp:mam:1")) mamNs = "urn:xmpp:mam:1";
        else { mamNs = null; log("Server archive (MAM): not supported – no history to fetch"); return; }
        log("Server archive (MAM): supported (" + mamNs + ")");
        String last = mamPrefs().getString("last_" + myBare(), null);
        mamPages = 0; mamAdded = 0; mamBackward = (last == null);
        sendMam(last, null);
    }

    private void sendMam(String after, String before) throws IOException {
        mamIq = "mam" + (++mamCounter); mamQueryId = "q" + mamCounter;
        StringBuilder sb = new StringBuilder();
        sb.append("<iq type='set' id='").append(mamIq).append("'><query xmlns='").append(mamNs).append("' queryid='").append(mamQueryId).append("'>");
        sb.append("<x xmlns='jabber:x:data' type='submit'><field var='FORM_TYPE' type='hidden'><value>").append(mamNs).append("</value></field></x>");
        sb.append("<set xmlns='http://jabber.org/protocol/rsm'><max>100</max>");
        if (mamBackward) sb.append(before == null ? "<before/>" : "<before>" + esc(before) + "</before>");
        else if (after != null) sb.append("<after>").append(esc(after)).append("</after>");
        sb.append("</set></query></iq>");
        send(sb.toString());
    }

    private void handleMamFin(Node n) throws IOException {
        if (!"result".equals(n.attr("type"))) { log("History fetch failed"); mamIq = null; return; }
        Node fin = n.child("fin"); Node set = fin != null ? fin.child("set") : null;
        String first = null, last = null;
        if (set != null) {
            Node f = set.child("first"), l = set.child("last");
            if (f != null) first = f.text.toString().trim();
            if (l != null) last = l.text.toString().trim();
        }
        boolean complete = fin != null && "true".equals(fin.attr("complete"));
        mamPages++;
        if (mamBackward) {
            if (mamPages == 1 && last != null) mamPrefs().edit().putString("last_" + myBare(), last).commit();
            if (!complete && first != null && mamPages < 5) { sendMam(null, first); return; }
        } else {
            if (last != null) mamPrefs().edit().putString("last_" + myBare(), last).commit();
            if (!complete && last != null && mamPages < 30) { sendMam(last, null); return; }
        }
        mamIq = null;
        log("History sync done: " + mamAdded + " new message(s)");
        if (listener != null) listener.onRoster();
    }

    private void handleForwarded(Node fwd, boolean forceOut, boolean forceIn, String sid, boolean live) {
        if (fwd == null) return;
        Node inner = fwd.child("message");
        if (inner == null) return;
        Node body = inner.child("body");
        if (body == null || body.text.length() == 0) return;
        if ("error".equals(inner.attr("type")) || "groupchat".equals(inner.attr("type"))) return;
        String from = bare(inner.attr("from")), to = bare(inner.attr("to"));
        boolean out;
        if (forceOut) out = true; else if (forceIn) out = false; else out = from.equalsIgnoreCase(myBare());
        String peer = out ? to : from;
        if (peer.length() == 0 || peer.equalsIgnoreCase(myBare())) return;
        long ts = System.currentTimeMillis();
        Node delay = fwd.child("delay");
        if (delay != null && delay.attr("stamp") != null) ts = parseStamp(delay.attr("stamp"));
        if (live) {
            if (listener != null) listener.onMessage(peer, body.text.toString(), out, ts);
        } else if (App.I.db.addIfNew(peer, body.text.toString(), out, ts, sid)) mamAdded++;
    }

    public boolean isOnline() { return online; }
    public String getJid() { return jid; }

    public synchronized List<Contact> contacts() { return new ArrayList<Contact>(roster.values()); }
    public synchronized String nameOf(String bare) {
        Contact c = roster.get(bare); return c != null ? c.display() : bare.split("@")[0];
    }

    public void start() {
        if (wanted) return;
        wanted = true;
        thread = new Thread(new Runnable() { public void run() { loop(); } }, "xmpp");
        thread.start();
    }

    public void stop() {
        wanted = false; online = false;
        try { if (sock != null) sock.close(); } catch (Exception e) { }
    }

    private void loop() {
        int delay = 3000;
        while (wanted) {
            try {
                state("Connecting…");
                session();
            } catch (AuthException e) {
                wanted = false; state("Error: " + e.getMessage()); return;
            } catch (Exception e) {
                if (!wanted) break;
                state("Error: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            online = false;
            try { if (sock != null) sock.close(); } catch (Exception e) { }
            if (!wanted) break;
            state("Offline");
            try { Thread.sleep(delay); } catch (InterruptedException e) { return; }
            delay = Math.min(delay * 2, 60000);
        }
        state("Offline");
    }

    private void state(String s) { if (listener != null) listener.onState(s); }

    private synchronized void send(String s) throws IOException {
        if (!online) trace(">> ", (s.startsWith("<auth") || s.startsWith("<response")) ? s.substring(0, s.indexOf('>') + 1) + "(hidden)" : s);
        out.write(s.getBytes("UTF-8")); out.flush();
    }

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    private class TraceIn extends FilterInputStream {
        TraceIn(InputStream i) { super(i); }
        @Override public int read() throws IOException { int b = super.read(); if (b >= 0 && !online) trace("<< ", new String(new byte[]{(byte) b}, "UTF-8")); return b; }
        @Override public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0 && !online) trace("<< ", new String(b, off, Math.min(n, 400), "UTF-8"));
            return n;
        }
    }

    private void trace(String dir, String txt) {
        txt = txt.replace("\r", " ").replace("\n", " ");
        log("  " + dir + (txt.length() > 400 ? txt.substring(0, 400) + "…" : txt));
    }

    private void openStream(InputStream in) throws Exception {
        // Send our stream header BEFORE attaching the parser: Android's KXmlParser reads from
        // the input as soon as setInput() is called, which would deadlock against the server.
        log("  > opening stream");
        send("<?xml version='1.0'?><stream:stream to='" + esc(domain) + "' xmlns='jabber:client' xmlns:stream='http://etherx.jabber.org/streams' version='1.0'>");
        p = Xml.newPullParser();
        p.setInput(in, "UTF-8");
        int ev = p.getEventType();
        while (ev != XmlPullParser.START_TAG) ev = p.next();
        log("  < stream header (" + p.getName() + ")");
    }

    private Node readNode() throws Exception {
        Node n = new Node(); n.name = p.getName();
        for (int i = 0; i < p.getAttributeCount(); i++) n.a.put(p.getAttributeName(i), p.getAttributeValue(i));
        int ev;
        while ((ev = p.next()) != XmlPullParser.END_TAG) {
            if (ev == XmlPullParser.START_TAG) n.kids.add(readNode());
            else if (ev == XmlPullParser.TEXT) n.text.append(p.getText());
            else if (ev == XmlPullParser.END_DOCUMENT) throw new EOFException("connection closed");
        }
        return n;
    }

    private Node nextStanza() throws Exception {
        while (true) {
            int ev = p.next();
            if (ev == XmlPullParser.START_TAG) { Node n = readNode(); if (!online) log("  < " + n.name); return n; }
            if (ev == XmlPullParser.END_TAG || ev == XmlPullParser.END_DOCUMENT) throw new EOFException("stream closed");
        }
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacSHA1"); m.init(new SecretKeySpec(key, "HmacSHA1")); return m.doFinal(data);
    }

    private void scram(Node mech) throws Exception {
        byte[] cn = new byte[18]; new SecureRandom().nextBytes(cn);
        String cnonce = Base64.encodeToString(cn, Base64.NO_WRAP);
        String bare = "n=" + user.replace("=", "=3D").replace(",", "=2C") + ",r=" + cnonce;
        send("<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='SCRAM-SHA-1'>" + Base64.encodeToString(("n,," + bare).getBytes("UTF-8"), Base64.NO_WRAP) + "</auth>");
        Node r = nextStanza();
        if ("failure".equals(r.name)) throw new AuthException("Authentication failed");
        if (!"challenge".equals(r.name)) throw new IOException("Unexpected SASL reply: " + r.name);
        String sf = new String(Base64.decode(r.text.toString().trim(), Base64.DEFAULT), "UTF-8");
        String nonce = null, salt = null; int iter = 0;
        for (String part : sf.split(",")) {
            if (part.startsWith("r=")) nonce = part.substring(2);
            else if (part.startsWith("s=")) salt = part.substring(2);
            else if (part.startsWith("i=")) iter = Integer.parseInt(part.substring(2));
        }
        if (nonce == null || salt == null || iter < 1 || !nonce.startsWith(cnonce)) throw new IOException("Bad SCRAM challenge");
        byte[] pw = pass.getBytes("UTF-8"), sb = Base64.decode(salt, Base64.DEFAULT);
        byte[] first = new byte[sb.length + 4]; System.arraycopy(sb, 0, first, 0, sb.length); first[first.length - 1] = 1;
        byte[] u = hmac(pw, first), salted = u.clone();
        for (int i = 1; i < iter; i++) { u = hmac(pw, u); for (int k = 0; k < salted.length; k++) salted[k] ^= u[k]; }
        byte[] ck = hmac(salted, "Client Key".getBytes("UTF-8"));
        byte[] stored = MessageDigest.getInstance("SHA-1").digest(ck);
        String noProof = "c=biws,r=" + nonce;
        byte[] sig = hmac(stored, (bare + "," + sf + "," + noProof).getBytes("UTF-8"));
        byte[] proof = new byte[ck.length]; for (int i = 0; i < ck.length; i++) proof[i] = (byte) (ck[i] ^ sig[i]);
        String fin = noProof + ",p=" + Base64.encodeToString(proof, Base64.NO_WRAP);
        send("<response xmlns='urn:ietf:params:xml:ns:xmpp-sasl'>" + Base64.encodeToString(fin.getBytes("UTF-8"), Base64.NO_WRAP) + "</response>");
        r = nextStanza();
        if ("challenge".equals(r.name)) { send("<response xmlns='urn:ietf:params:xml:ns:xmpp-sasl'/>"); r = nextStanza(); }
        if (!"success".equals(r.name)) throw new AuthException("Authentication failed");
    }

    /** Minimal DNS SRV lookup over UDP. Returns {host, port} pairs in priority order, or empty. */
    private List<String[]> srv(String name) {
        List<String[]> res = new ArrayList<String[]>();
        String[] servers = {"8.8.8.8", "1.1.1.1"};
        for (String ns : servers) {
            DatagramSocket ds = null;
            try {
                ByteArrayOutputStream q = new ByteArrayOutputStream();
                q.write(0x12); q.write(0x34); q.write(1); q.write(0); q.write(0); q.write(1); q.write(0); q.write(0); q.write(0); q.write(0); q.write(0); q.write(0);
                for (String lab : name.split("\\.")) { byte[] b = lab.getBytes("UTF-8"); q.write(b.length); q.write(b); }
                q.write(0); q.write(0); q.write(33); q.write(0); q.write(1);
                byte[] qb = q.toByteArray();
                ds = new DatagramSocket(); ds.setSoTimeout(3000);
                ds.send(new DatagramPacket(qb, qb.length, InetAddress.getByName(ns), 53));
                byte[] buf = new byte[1500]; DatagramPacket rp = new DatagramPacket(buf, buf.length);
                ds.receive(rp);
                int an = ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);
                int pos = 12;
                while (buf[pos] != 0) { if ((buf[pos] & 0xc0) == 0xc0) { pos++; break; } pos += (buf[pos] & 0xff) + 1; }
                pos += 1 + 4;
                final List<int[]> order = new ArrayList<int[]>(); final List<String[]> tmp = new ArrayList<String[]>();
                for (int i = 0; i < an; i++) {
                    pos = skipName(buf, pos);
                    int type = ((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff);
                    int rdlen = ((buf[pos + 8] & 0xff) << 8) | (buf[pos + 9] & 0xff);
                    int rd = pos + 10;
                    if (type == 33) {
                        int prio = ((buf[rd] & 0xff) << 8) | (buf[rd + 1] & 0xff);
                        int port = ((buf[rd + 4] & 0xff) << 8) | (buf[rd + 5] & 0xff);
                        String host = readName(buf, rd + 6);
                        if (host.length() > 0 && port > 0) { order.add(new int[]{prio, tmp.size()}); tmp.add(new String[]{host, String.valueOf(port)}); }
                    }
                    pos = rd + rdlen;
                }
                Collections.sort(order, new Comparator<int[]>() { public int compare(int[] a, int[] b) { return a[0] - b[0]; } });
                for (int[] o : order) res.add(tmp.get(o[1]));
                if (!res.isEmpty() || an == 0) return res;
            } catch (Exception e) { log("DNS " + ns + ": " + e.getMessage()); }
            finally { if (ds != null) ds.close(); }
        }
        return res;
    }

    private static int skipName(byte[] b, int pos) {
        while (true) {
            int l = b[pos] & 0xff;
            if (l == 0) return pos + 1;
            if ((l & 0xc0) == 0xc0) return pos + 2;
            pos += l + 1;
        }
    }

    private static String readName(byte[] b, int pos) {
        StringBuilder sb = new StringBuilder(); int guard = 0;
        while (guard++ < 50) {
            int l = b[pos] & 0xff;
            if (l == 0) break;
            if ((l & 0xc0) == 0xc0) { pos = ((l & 0x3f) << 8) | (b[pos + 1] & 0xff); continue; }
            if (sb.length() > 0) sb.append('.');
            sb.append(new String(b, pos + 1, l)); pos += l + 1;
        }
        return sb.toString();
    }

    private void session() throws Exception {
        List<String[]> cands = new ArrayList<String[]>();
        if (domain.equals("yax.im")) cands.add(new String[]{"xmpp.yaxim.org", "5222", "0"});
        log("Looking up " + domain + " …");
        for (String[] h : srv("_xmpp-client._tcp." + domain)) { cands.add(new String[]{h[0], h[1], "0"}); }
        for (String[] h : srv("_xmpps-client._tcp." + domain)) { cands.add(new String[]{h[0], h[1], "1"}); }
        cands.add(new String[]{domain, "5222", "0"});
        if (!domain.equals("yax.im")) cands.add(new String[]{domain, "5223", "1"});
        Exception last = null;
        for (String[] c : cands) {
            online = false;
            try {
                log("--- Trying " + c[0] + ":" + c[1] + ("1".equals(c[2]) ? " (direct TLS)" : " (STARTTLS)"));
                connectAndRun(c[0], Integer.parseInt(c[1]), "1".equals(c[2]));
            } catch (AuthException e) { throw e; }
            catch (Exception e) {
                if (!wanted) throw e;
                if (boundJidReady) throw e;
                last = e; log("  failed: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                try { if (sock != null) sock.close(); } catch (Exception x) { }
            }
        }
        throw last != null ? last : new IOException("No server reachable");
    }


    private InetAddress[] resolve(String host) throws Exception {
        try {
            return InetAddress.getAllByName(host);
        } catch (Exception e) {
            log("  system DNS failed: " + e.getMessage());
        }
        List<InetAddress> l = new ArrayList<InetAddress>();
        for (String ns : new String[]{"8.8.8.8", "1.1.1.1"}) {
            for (int type : new int[]{1, 28}) {
                try { l.addAll(udpAddrs(ns, host, type)); } catch (Exception e) { log("  DNS " + ns + " type " + type + ": " + e.getMessage()); }
            }
            if (!l.isEmpty()) { log("  resolved via " + ns); break; }
        }
        if (l.isEmpty()) {
            for (int type : new int[]{1, 28}) {
                try { l.addAll(dohAddrs(host, type)); } catch (Exception e) { log("  DoH type " + type + ": " + e.getMessage()); }
            }
            if (!l.isEmpty()) log("  resolved via DNS-over-HTTPS");
        }
        if (l.isEmpty()) throw new java.net.UnknownHostException("Unable to resolve " + host + " (system DNS, UDP DNS and DoH all failed)");
        return l.toArray(new InetAddress[l.size()]);
    }

    private List<InetAddress> udpAddrs(String ns, String name, int type) throws Exception {
        List<InetAddress> res = new ArrayList<InetAddress>();
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        q.write(0x43); q.write(0x21); q.write(1); q.write(0); q.write(0); q.write(1); q.write(0); q.write(0); q.write(0); q.write(0); q.write(0); q.write(0);
        for (String lab : name.split("\\.")) { byte[] b = lab.getBytes("UTF-8"); q.write(b.length); q.write(b); }
        q.write(0); q.write(0); q.write(type); q.write(0); q.write(1);
        byte[] qb = q.toByteArray();
        DatagramSocket ds = new DatagramSocket();
        try {
            ds.setSoTimeout(3000);
            ds.send(new DatagramPacket(qb, qb.length, InetAddress.getByName(ns), 53));
            byte[] buf = new byte[1500]; DatagramPacket rp = new DatagramPacket(buf, buf.length);
            ds.receive(rp);
            int an = ((buf[6] & 0xff) << 8) | (buf[7] & 0xff);
            int pos = skipName(buf, 12) + 4;
            for (int i = 0; i < an; i++) {
                pos = skipName(buf, pos);
                int t = ((buf[pos] & 0xff) << 8) | (buf[pos + 1] & 0xff);
                int rdlen = ((buf[pos + 8] & 0xff) << 8) | (buf[pos + 9] & 0xff);
                int rd = pos + 10;
                if ((t == 1 && rdlen == 4) || (t == 28 && rdlen == 16)) {
                    byte[] a = new byte[rdlen]; System.arraycopy(buf, rd, a, 0, rdlen);
                    res.add(InetAddress.getByAddress(a));
                }
                pos = rd + rdlen;
            }
        } finally { ds.close(); }
        return res;
    }

    private List<InetAddress> dohAddrs(String name, int type) throws Exception {
        List<InetAddress> res = new ArrayList<InetAddress>();
        java.net.URL u = new java.net.URL("https://1.1.1.1/dns-query?name=" + name + "&type=" + type);
        HttpsURLConnection c = (HttpsURLConnection) u.openConnection();
        c.setConnectTimeout(6000); c.setReadTimeout(6000);
        c.setRequestProperty("Accept", "application/dns-json");
        try {
            BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(); String ln;
            while ((ln = br.readLine()) != null) sb.append(ln);
            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            org.json.JSONArray arr = o.optJSONArray("Answer");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject a = arr.getJSONObject(i);
                if (a.getInt("type") == type) res.add(InetAddress.getByName(a.getString("data")));
            }
        } finally { c.disconnect(); }
        return res;
    }

    private volatile boolean boundJidReady;

    private static String errCond(Node n) {
        Node e = n.child("error");
        if (e != null) for (Node k : e.kids) if (!"text".equals(k.name)) return k.name;
        return "error";
    }
    private static String errText(Node n) {
        Node e = n.child("error"); Node t = e != null ? e.child("text") : null;
        return t != null && t.text.length() > 0 ? t.text.toString().trim() : null;
    }

    /** In-band account registration (XEP-0077), done on the encrypted stream before login. */
    private void register() throws Exception {
        send("<iq type='get' id='reg1'><query xmlns='jabber:iq:register'/></iq>");
        Node r;
        do { r = nextStanza(); } while (!"iq".equals(r.name) || !"reg1".equals(r.attr("id")));
        if (!"result".equals(r.attr("type"))) throw new RegException(errText(r) != null ? errText(r) : "this server doesn't allow creating accounts here");
        Node q = r.child("query");
        if (q == null) throw new RegException("unexpected reply from server");
        if (q.child("registered") != null) throw new RegException("that username is already registered");
        String extra = "";
        boolean legacy = q.child("username") != null && q.child("password") != null;
        if (legacy) {
            for (Node k : q.kids) {
                String nm = k.name;
                if (nm.equals("username") || nm.equals("password") || nm.equals("instructions")) continue;
                if (nm.equals("email")) { extra += "<email/>"; continue; }
                throw new RegException("the server asks for extra verification (" + nm + "). Create the account in another app first");
            }
        } else {
            Node x = q.child("x");
            if (x == null) throw new RegException("the server's sign-up form isn't supported");
            for (Node k : x.kids) {
                String v = k.attr("var");
                if (!"field".equals(k.name) || v == null) continue;
                if (v.equals("FORM_TYPE") || v.equals("username") || v.equals("password")) continue;
                throw new RegException("the server asks for extra verification (" + v + "). Create the account in another app first");
            }
        }
        if (legacy) {
            send("<iq type='set' id='reg2'><query xmlns='jabber:iq:register'><username>" + esc(user) + "</username><password>" + esc(pass) + "</password>" + extra + "</query></iq>");
        } else {
            send("<iq type='set' id='reg2'><query xmlns='jabber:iq:register'><x xmlns='jabber:x:data' type='submit'><field var='FORM_TYPE'><value>jabber:iq:register</value></field><field var='username'><value>" + esc(user) + "</value></field><field var='password'><value>" + esc(pass) + "</value></field></x></query></iq>");
        }
        do { r = nextStanza(); } while (!"iq".equals(r.name) || !"reg2".equals(r.attr("id")));
        if ("result".equals(r.attr("type"))) return;
        String c = errCond(r), t = errText(r);
        if (c.equals("conflict")) throw new RegException("that username is already taken");
        if (c.equals("not-acceptable") || c.equals("bad-request") || c.equals("not-allowed") && t == null)
            throw new RegException(t != null ? t : "that username or password isn't allowed (usernames need at least 2 letters; some names are reserved)");
        if (c.equals("resource-constraint") || c.equals("policy-violation") || c.equals("forbidden") || c.equals("not-allowed"))
            throw new RegException(t != null ? t : "too many sign-ups from your network – try again later");
        throw new RegException(t != null ? t : c.replace('-', ' '));
    }

    private void connectAndRun(String host, int port, boolean directTls) throws Exception {
        boundJidReady = false;
        InetAddress[] addrs = resolve(host);
        List<InetAddress> ord = new ArrayList<InetAddress>();
        for (InetAddress ia : addrs) if (ia instanceof java.net.Inet4Address) ord.add(ia);
        for (InetAddress ia : addrs) if (!(ia instanceof java.net.Inet4Address)) ord.add(ia);
        sock = null; Exception ce = null;
        for (InetAddress ia : ord) {
            Socket t = new Socket();
            try {
                t.connect(new InetSocketAddress(ia, port), 8000);
                sock = t; break;
            } catch (Exception e) {
                ce = e; log("  " + ia.getHostAddress() + ": " + e.getMessage());
                try { t.close(); } catch (Exception x) { }
            }
        }
        if (sock == null) throw ce != null ? ce : new IOException("Cannot resolve " + host);
        sock.setKeepAlive(true);
        sock.setSoTimeout(20000);
        log("  connected to " + sock.getInetAddress().getHostAddress());
        Node f, r;
        SSLSocket ssl;
        if (directTls) {
            ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(sock, domain, port, true);
            ssl.startHandshake();
            log("  TLS ok");
        } else {
            out = sock.getOutputStream();
            openStream(sock.getInputStream());
            f = nextStanza();
            if (f.child("starttls") == null) throw new IOException("Server does not offer STARTTLS");
            send("<starttls xmlns='urn:ietf:params:xml:ns:xmpp-tls'/>");
            r = nextStanza();
            if (!"proceed".equals(r.name)) throw new IOException("STARTTLS refused");
            ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(sock, domain, port, true);
            ssl.startHandshake();
            log("  TLS ok");
        }
        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(domain, ssl.getSession()))
            throw new IOException("Certificate does not match " + domain);
        sock = ssl; out = ssl.getOutputStream();
        openStream(ssl.getInputStream());
        f = nextStanza();
        if (registerMode) {
            if (f.child("register") == null) throw new RegException("this server doesn't allow creating accounts from an app");
            log("  registering account");
            register();
            registerMode = false;
            log("  account created");
        }
        Node mech = f.child("mechanisms");
        if (mech == null) throw new IOException("No SASL mechanisms offered");
        List<String> ms = new ArrayList<String>();
        for (Node m : mech.kids) ms.add(m.text.toString().trim());
        log("  SASL: " + ms);
        if (ms.contains("SCRAM-SHA-1")) scram(mech);
        else if (ms.contains("PLAIN")) {
            String plain = "\0" + user + "\0" + pass;
            send("<auth xmlns='urn:ietf:params:xml:ns:xmpp-sasl' mechanism='PLAIN'>" + Base64.encodeToString(plain.getBytes("UTF-8"), Base64.NO_WRAP) + "</auth>");
            r = nextStanza();
            if (!"success".equals(r.name)) throw new AuthException("Authentication failed");
        } else throw new IOException("No supported login method: " + ms);
        log("  logged in");
        sock.setSoTimeout(0);
        openStream(ssl.getInputStream());
        f = nextStanza();
        send("<iq type='set' id='bind1'><bind xmlns='urn:ietf:params:xml:ns:xmpp-bind'><resource>HBXC</resource></bind></iq>");
        while (true) {
            r = nextStanza();
            if ("iq".equals(r.name) && "bind1".equals(r.attr("id"))) {
                if (!"result".equals(r.attr("type"))) throw new IOException("Resource bind failed");
                Node b = r.child("bind"); Node j = b != null ? b.child("jid") : null;
                boundJid = j != null ? j.text.toString().trim() : jid;
                break;
            }
        }
        boundJidReady = true;
        if (f.child("session") != null) send("<iq type='set' id='sess1'><session xmlns='urn:ietf:params:xml:ns:xmpp-session'/></iq>");
        send("<iq type='get' id='roster1'><query xmlns='jabber:iq:roster'/></iq>");
        send("<presence/>");
        startSync();
        joinAll();
        online = true;
        state("Online");
        final Thread ka = new Thread(new Runnable() {
            public void run() {
                try { while (online) { Thread.sleep(60000); if (online) send(" "); } } catch (Exception e) { }
            }
        });
        ka.setDaemon(true); ka.start();
        while (true) handle(nextStanza());
    }

    private static String bare(String j) { if (j == null) return ""; int i = j.indexOf('/'); return i < 0 ? j : j.substring(0, i); }

    private void handle(Node n) throws Exception {
        if ("message".equals(n.name)) {
            if ("groupchat".equals(n.attr("type"))) { handleGroup(n); return; }
            Node sent = n.child("sent"), rec = n.child("received");
            if (sent != null || rec != null) {
                // Message Carbons: only trust copies that come from our own account
                if (bare(n.attr("from")).equalsIgnoreCase(myBare()))
                    handleForwarded((sent != null ? sent : rec).child("forwarded"), sent != null, rec != null, null, true);
                return;
            }
            Node res = n.child("result");
            if (res != null && res.attr("queryid") != null && (res.attr("xmlns") == null || res.attr("xmlns").startsWith("urn:xmpp:mam"))) {
                if (mamQueryId != null && mamQueryId.equals(res.attr("queryid")))
                    handleForwarded(res.child("forwarded"), false, false, res.attr("id") == null ? null : "mam:" + res.attr("id"), false);
                return;
            }
            if (joined.containsKey(bare(n.attr("from")))) return;
            Node body = n.child("body");
            String type = n.attr("type");
            if (body != null && body.text.length() > 0 && !"error".equals(type) && !"groupchat".equals(type)) {
                long ts = System.currentTimeMillis();
                if (listener != null) listener.onMessage(bare(n.attr("from")), body.text.toString(), false, ts);
            }
        } else if ("presence".equals(n.name)) {
            String type = n.attr("type"), from = bare(n.attr("from"));
            if (joined.containsKey(from)) { handleMucPresence(n, from); return; }
            if ("subscribe".equals(type)) {
                send("<presence to='" + esc(from) + "' type='subscribed'/>");
                boolean known; synchronized (this) { known = roster.containsKey(from); }
                if (!known) send("<presence to='" + esc(from) + "' type='subscribe'/>");
            } else if (type == null || "unavailable".equals(type)) {
                synchronized (this) {
                    Contact c = roster.get(from);
                    if (c != null) c.online = (type == null);
                }
                if (listener != null) listener.onRoster();
            }
        } else if ("iq".equals(n.name)) {
            String type = n.attr("type"), id = n.attr("id");
            if ("disco_acc".equals(id)) { handleDisco(n); return; }
            if ("disco_srv".equals(id)) { checkUpload(n, domain); return; }
            if ("ditems".equals(id)) {
                Node dq = n.child("query");
                if (dq != null && "result".equals(type)) for (Node k : dq.kids)
                    if ("item".equals(k.name) && k.attr("jid") != null) send("<iq type='get' id='dinfo|" + esc(k.attr("jid")) + "' to='" + esc(k.attr("jid")) + "'><query xmlns='http://jabber.org/protocol/disco#info'/></iq>");
                return;
            }
            if (id != null && id.startsWith("dinfo|")) { checkUpload(n, id.substring(6)); return; }
            if (id != null && id.startsWith("slot")) { handleSlot(n, id); return; }
            if (id != null && id.startsWith("rooms|")) { handleRooms(n, id); return; }
            if (id != null && id.equals(mamIq)) { handleMamFin(n); return; }
            if ("carb1".equals(id)) { log("Message Carbons: " + ("result".equals(type) ? "enabled" : "not available")); return; }
            Node q = n.child("query");
            if (q != null && (q.attr("xmlns") == null || "jabber:iq:roster".equals(q.attr("xmlns")))) {
                synchronized (this) {
                    for (Node it : q.kids) {
                        if (!"item".equals(it.name)) continue;
                        String j = it.attr("jid");
                        if ("remove".equals(it.attr("subscription"))) { roster.remove(j); continue; }
                        Contact c = roster.get(j);
                        if (c == null) { c = new Contact(); c.jid = j; roster.put(j, c); }
                        c.name = it.attr("name"); c.sub = it.attr("subscription");
                    }
                }
                if ("set".equals(type)) send("<iq type='result' id='" + esc(id) + "'/>");
                if (listener != null) listener.onRoster();
            } else if ("get".equals(type) || "set".equals(type)) {
                if (n.child("ping") != null) send("<iq type='result' id='" + esc(id) + "' to='" + esc(n.attr("from") == null ? domain : n.attr("from")) + "'/>");
                else send("<iq type='error' id='" + esc(id) + "'><error type='cancel'><service-unavailable xmlns='urn:ietf:params:xml:ns:xmpp-stanzas'/></error></iq>");
            }
        }
    }

    /** Returns false if not connected. */
    public boolean sendMessage(String to, String body) {
        if (!online) return false;
        try {
            send("<message to='" + esc(to) + "' type='chat' id='m" + System.nanoTime() + "'><body>" + esc(body) + "</body></message>");
            return true;
        } catch (IOException e) { return false; }
    }

    public void addContact(final String j) {
        new Thread(new Runnable() { public void run() {
            try {
                send("<iq type='set' id='add" + System.nanoTime() + "'><query xmlns='jabber:iq:roster'><item jid='" + esc(j) + "'/></query></iq>");
                send("<presence to='" + esc(j) + "' type='subscribe'/>");
            } catch (IOException e) { }
        } }).start();
    }
}
