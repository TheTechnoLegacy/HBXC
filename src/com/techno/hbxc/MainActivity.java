package com.techno.hbxc;

import android.app.Activity;
import android.content.Context;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

public class MainActivity extends Activity implements App.UiListener {
    static class Row { String peer, name, last; long ts; boolean online, unread; }
    List<Row> rows = new ArrayList<Row>();
    BaseAdapter ad; TextView empty;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!App.I.hasAccount()) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        getActionBar().setLogo(Ui.d(this, "ic_logo")); getActionBar().setDisplayUseLogoEnabled(true);
        ListView lv = new ListView(this);
        lv.setBackgroundColor(0xffffffff);
        lv.setDivider(new android.graphics.drawable.ColorDrawable(0xffdddddd)); lv.setDividerHeight(1);
        ad = new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int i) { return rows.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup g) {
                Context c = MainActivity.this;
                LinearLayout row; View[] h;
                if (v == null) {
                    int p = Ui.dp(c, 8);
                    row = new LinearLayout(c); row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setPadding(p, p, p + Ui.dp(c, 2), p); row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setBackgroundColor(0xffffffff);
                    FrameLayout av = new FrameLayout(c);
                    ImageView img = new ImageView(c); img.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    img.setImageDrawable(Ui.d(c, "default_avatar"));
                    av.addView(img, new FrameLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
                    View dot = new View(c);
                    android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
                    gd.setShape(android.graphics.drawable.GradientDrawable.OVAL); gd.setColor(Ui.GREEN); gd.setStroke(Ui.dp(c, 2), 0xffffffff);
                    dot.setBackgroundDrawable(gd);
                    FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14), Gravity.BOTTOM | Gravity.RIGHT);
                    av.addView(dot, dl);
                    row.addView(av, new LinearLayout.LayoutParams(Ui.dp(c, 56), Ui.dp(c, 56)));
                    LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout top = new LinearLayout(c); top.setGravity(Gravity.CENTER_VERTICAL);
                    TextView n = new TextView(c); n.setTextSize(18); n.setSingleLine(); n.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    TextView t = new TextView(c); t.setTextSize(12); t.setTextColor(0xff8d8d8d); t.setSingleLine();
                    t.setPadding(Ui.dp(c, 8), 0, 0, 0);
                    top.addView(n, new LinearLayout.LayoutParams(0, -2, 1)); top.addView(t);
                    TextView pv = new TextView(c); pv.setTextSize(15); pv.setSingleLine(); pv.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    col.addView(top, new LinearLayout.LayoutParams(-1, -2)); col.addView(pv, new LinearLayout.LayoutParams(-1, -2));
                    LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, -2, 1); cl.leftMargin = Ui.dp(c, 12);
                    row.addView(col, cl);
                    h = new View[]{n, t, pv, dot};
                    row.setTag(h);
                } else { row = (LinearLayout) v; h = (View[]) row.getTag(); }
                Row r = rows.get(i);
                TextView n = (TextView) h[0], t = (TextView) h[1], pv = (TextView) h[2];
                n.setText(r.name); t.setText(r.ts > 0 ? Ui.rel(r.ts) : "");
                pv.setText(r.last == null ? "" : r.last);
                n.setTypeface(null, r.unread ? Typeface.BOLD : Typeface.NORMAL);
                pv.setTypeface(null, r.unread ? Typeface.BOLD : Typeface.NORMAL);
                n.setTextColor(r.unread ? 0xff000000 : 0xff8d8d8d);
                pv.setTextColor(r.unread ? 0xff333333 : 0xff9e9e9e);
                t.setTypeface(null, r.unread ? Typeface.BOLD : Typeface.NORMAL);
                t.setTextColor(r.unread ? 0xff000000 : 0xff8d8d8d);
                h[3].setVisibility(r.online ? View.VISIBLE : View.GONE);
                return row;
            }
        };
        lv.setAdapter(ad);
        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int i, long id) { open(rows.get(i).peer); }
        });
        FrameLayout f = new FrameLayout(this);
        f.addView(lv);
        empty = new TextView(this); empty.setText("No conversations yet.\nUse the menu to start one."); empty.setGravity(Gravity.CENTER);
        empty.setTextColor(0xff999999);
        f.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        setContentView(f);
    }

    void open(String peer) { startActivity(new Intent(this, ChatActivity.class).putExtra("peer", peer)); }

    void refresh() {
        if (App.I.client == null) return;
        Map<String, Row> m = new LinkedHashMap<String, Row>();
        for (Db.Msg x : App.I.db.conversations()) {
            boolean room = App.I.isRoom(x.peer);
            Row r = new Row(); r.peer = x.peer; r.name = App.I.nameOf(x.peer); r.last = (x.out ? "You: " : (room && x.nick != null ? x.nick + ": " : "")) + (ImgLoader.isImageUrl(x.body) ? "Photo" : x.body); r.ts = x.ts; r.unread = !x.out && x.ts > App.I.lastRead(x.peer);
            m.put(x.peer, r);
        }
        for (String room : App.I.rooms().keySet()) if (!m.containsKey(room)) {
            Row r = new Row(); r.peer = room; r.name = Ui.local(room); r.last = "Group chat"; r.ts = 0; m.put(room, r);
        }
        List<Row> others = new ArrayList<Row>();
        for (XmppClient.Contact c : App.I.client.contacts()) {
            Row r = m.get(c.jid);
            if (r == null) { r = new Row(); r.peer = c.jid; r.name = c.display(); others.add(r); }
            r.online = c.online;
        }
        Collections.sort(others, new Comparator<Row>() { public int compare(Row a, Row b) { return a.name.compareToIgnoreCase(b.name); } });
        rows.clear(); rows.addAll(m.values()); rows.addAll(others);
        ad.notifyDataSetChanged();
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
    }

    @Override protected void onResume() {
        super.onResume();
        if (ad == null) return;
        App.I.add(this); onState(App.I.state); refresh();
    }
    @Override protected void onPause() { super.onPause(); App.I.remove(this); }
    public void onChanged() { refresh(); }
    public void onState(String s) { getActionBar().setSubtitle("Online".equals(s) ? null : s); }

    void prompt(String title, String hint, final java.util.concurrent.Callable<Void> unused, final boolean add) {
        final EditText e = new EditText(this); e.setHint(hint); e.setSingleLine();
        new AlertDialog.Builder(this).setTitle(title).setView(e)
            .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    String j = e.getText().toString().trim().toLowerCase();
                    if (j.indexOf('@') < 1) { Toast.makeText(MainActivity.this, "Enter a full address like user@yax.im", Toast.LENGTH_SHORT).show(); return; }
                    if (add && App.I.client != null) App.I.client.addContact(j);
                    open(j);
                }
            }).setNegativeButton(android.R.string.cancel, null).show();
    }


    void joinDialog(String preset) {
        final String defNick = Ui.local(App.I.myJid());
        LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16); l.setPadding(p, p / 2, p, 0);
        final EditText room = new EditText(this); room.setHint("room@conference.example.com"); room.setSingleLine();
        room.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (preset != null) room.setText(preset);
        final EditText nick = new EditText(this); nick.setHint("Nickname"); nick.setText(defNick); nick.setSingleLine();
        l.addView(room); l.addView(nick);
        new AlertDialog.Builder(this).setTitle("Join group chat").setView(l)
            .setPositiveButton("Join", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                String r = room.getText().toString().trim().toLowerCase(), n = nick.getText().toString().trim();
                if (r.indexOf('@') < 0 && App.I.client != null && App.I.client.mucJid != null) r = r + "@" + App.I.client.mucJid;
                if (r.indexOf('@') < 1) { Toast.makeText(MainActivity.this, "Enter the full room address, like room@conference.yax.im", Toast.LENGTH_LONG).show(); return; }
                if (n.length() == 0) n = defNick;
                App.I.joinRoom(r, n);
                open(r);
            } }).setNegativeButton(android.R.string.cancel, null).show();
    }

    void browseRooms() {
        if (App.I.client == null) return;
        Toast.makeText(this, "Loading group chats…", Toast.LENGTH_SHORT).show();
        App.I.client.browseRooms(new XmppClient.RoomsCb() {
            public void onError(final String why) { runOnUiThread(new Runnable() { public void run() { Toast.makeText(MainActivity.this, why, Toast.LENGTH_LONG).show(); } }); }
            public void onRooms(final List<String[]> rooms) {
                runOnUiThread(new Runnable() { public void run() {
                    if (rooms.isEmpty()) { Toast.makeText(MainActivity.this, "No public group chats found", Toast.LENGTH_LONG).show(); return; }
                    Collections.sort(rooms, new Comparator<String[]>() { public int compare(String[] a, String[] b) { return a[1].compareToIgnoreCase(b[1]); } });
                    final List<String[]> shown = rooms.size() > 300 ? rooms.subList(0, 300) : rooms;
                    String[] names = new String[shown.size()];
                    for (int i = 0; i < names.length; i++) names[i] = shown.get(i)[1];
                    new AlertDialog.Builder(MainActivity.this).setTitle("Group chats").setItems(names, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { joinDialog(shown.get(w)[0]); }
                    }).show();
                } });
            }
        });
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        MenuItem plus = m.add(0, 1, 0, "New conversation"); plus.setIcon(Ui.d(this, "ic_menu_new")); plus.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, 2, 1, "Add contact");
        m.add(0, 5, 1, "Join group chat");
        m.add(0, 6, 1, "Browse group chats");
        m.add(0, 4, 2, "Connection log");
        m.add(0, 3, 3, "Sign out");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        switch (i.getItemId()) {
            case 1: prompt("New conversation", "user@yax.im", null, false); return true;
            case 2: prompt("Add contact", "user@yax.im", null, true); return true;
            case 5: joinDialog(null); return true;
            case 6: browseRooms(); return true;
            case 4: {
                TextView tv = new TextView(this); tv.setTextIsSelectable(true); tv.setTextSize(12);
                tv.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
                tv.setText(XmppClient.logText());
                ScrollView sv = new ScrollView(this); sv.addView(tv);
                new AlertDialog.Builder(this).setTitle("Connection log").setView(sv).setPositiveButton(android.R.string.ok, null).show();
                return true;
            }
            case 3:
                App.I.logout(); startActivity(new Intent(this, LoginActivity.class)); finish(); return true;
        }
        return false;
    }
}
