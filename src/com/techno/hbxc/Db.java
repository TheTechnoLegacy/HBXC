package com.techno.hbxc;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.util.ArrayList;
import java.util.List;

public class Db extends SQLiteOpenHelper {
    public static class Msg { public String peer, body, nick; public boolean out; public long ts; }

    public Db(Context c) { super(c, "chat.db", null, 3); }

    @Override public void onCreate(SQLiteDatabase d) {
        d.execSQL("CREATE TABLE msgs (_id INTEGER PRIMARY KEY AUTOINCREMENT, peer TEXT, body TEXT, out INTEGER, ts INTEGER, sid TEXT, nick TEXT)");
        d.execSQL("CREATE INDEX msgs_peer ON msgs(peer, ts)");
    }
    @Override public void onUpgrade(SQLiteDatabase d, int a, int b) {
        if (a < 2) d.execSQL("ALTER TABLE msgs ADD COLUMN sid TEXT");
        if (a < 3) d.execSQL("ALTER TABLE msgs ADD COLUMN nick TEXT");
    }

    public void add(String peer, String body, boolean out, long ts) {
        ContentValues v = new ContentValues();
        v.put("peer", peer); v.put("body", body); v.put("out", out ? 1 : 0); v.put("ts", ts);
        getWritableDatabase().insert("msgs", null, v);
    }

    /** Inserts an archived/forwarded message unless it is already stored. Returns true if added. */
    public synchronized boolean addIfNew(String peer, String body, boolean out, long ts, String sid) {
        SQLiteDatabase db = getWritableDatabase();
        if (sid != null) {
            Cursor c = db.rawQuery("SELECT 1 FROM msgs WHERE sid=?", new String[]{sid});
            boolean have = c.moveToFirst(); c.close();
            if (have) return false;
        }
        Cursor c = db.rawQuery("SELECT 1 FROM msgs WHERE peer=? AND body=? AND out=? AND ts BETWEEN ? AND ?",
            new String[]{peer, body, out ? "1" : "0", String.valueOf(ts - 120000), String.valueOf(ts + 120000)});
        boolean dup = c.moveToFirst(); c.close();
        if (dup) return false;
        ContentValues v = new ContentValues();
        v.put("peer", peer); v.put("body", body); v.put("out", out ? 1 : 0); v.put("ts", ts); v.put("sid", sid);
        db.insert("msgs", null, v);
        return true;
    }

    public synchronized boolean addGroup(String room, String nick, String body, boolean out, long ts, String sid) {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c;
        if (sid != null) c = db.rawQuery("SELECT 1 FROM msgs WHERE sid=?", new String[]{sid});
        else c = db.rawQuery("SELECT 1 FROM msgs WHERE peer=? AND nick=? AND body=? AND ts=?", new String[]{room, nick, body, String.valueOf(ts)});
        boolean have = c.moveToFirst(); c.close();
        if (have) return false;
        ContentValues v = new ContentValues();
        v.put("peer", room); v.put("nick", nick); v.put("body", body); v.put("out", out ? 1 : 0); v.put("ts", ts); v.put("sid", sid);
        db.insert("msgs", null, v);
        return true;
    }

    public List<Msg> thread(String peer) {
        List<Msg> l = new ArrayList<Msg>();
        Cursor c = getReadableDatabase().rawQuery("SELECT peer, body, out, ts, nick FROM msgs WHERE peer=? ORDER BY ts, _id", new String[]{peer});
        while (c.moveToNext()) { Msg m = new Msg(); m.peer = c.getString(0); m.body = c.getString(1); m.out = c.getInt(2) == 1; m.ts = c.getLong(3); m.nick = c.getString(4); l.add(m); }
        c.close();
        return l;
    }

    public List<Msg> conversations() {
        List<Msg> l = new ArrayList<Msg>();
        Cursor c = getReadableDatabase().rawQuery("SELECT peer, body, out, MAX(ts), nick FROM msgs GROUP BY peer ORDER BY MAX(ts) DESC", null);
        while (c.moveToNext()) { Msg m = new Msg(); m.peer = c.getString(0); m.body = c.getString(1); m.out = c.getInt(2) == 1; m.ts = c.getLong(3); m.nick = c.getString(4); l.add(m); }
        c.close();
        return l;
    }

    public void deletePeer(String peer) { getWritableDatabase().delete("msgs", "peer=?", new String[]{peer}); }

    public void clear() { getWritableDatabase().delete("msgs", null, null); }
}
