package com.techno.hbxc;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.util.Linkify;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends Activity implements App.UiListener {
    static final int PICK = 77;
    String peer; List<Db.Msg> msgs = new ArrayList<Db.Msg>();
    BaseAdapter ad; ListView lv; EditText input; ImageButton send;
    int screenW; boolean room, first = true, stickNext;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        peer = getIntent().getStringExtra("peer");
        if (peer == null || App.I.client == null) { finish(); return; }
        room = App.I.isRoom(peer);
        setTitle(App.I.nameOf(peer));
        getActionBar().setSubtitle(room ? "Group chat · " + peer : peer);
        getActionBar().setLogo(Ui.d(this, "ic_logo"));
        getActionBar().setDisplayUseLogoEnabled(true);
        getActionBar().setDisplayShowHomeEnabled(true);
        getActionBar().setDisplayHomeAsUpEnabled(true);
        screenW = getResources().getDisplayMetrics().widthPixels;

        lv = new ListView(this);
        lv.setDivider(null); lv.setBackgroundColor(0xffe1e1e1);
        lv.setTranscriptMode(ListView.TRANSCRIPT_MODE_DISABLED);
        lv.setStackFromBottom(true);
        lv.setSelector(new android.graphics.drawable.ColorDrawable(0));
        lv.setCacheColorHint(0);
        lv.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 4)); lv.setClipToPadding(false);
        ad = new BaseAdapter() {
            public int getCount() { return msgs.size(); }
            public Object getItem(int i) { return msgs.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup g) { return makeRow(msgs.get(i)); }
        };
        lv.setAdapter(ad);

        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(0xffffffff); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(Ui.dp(this, 56));
        input = new EditText(this); input.setHint("Send a message"); input.setHintTextColor(0xff9e9e9e);
        input.setTextSize(18); input.setTextColor(0xff333333); input.setMaxLines(4);
        input.setBackgroundDrawable(null); input.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        bar.addView(input, new LinearLayout.LayoutParams(0, -2, 1));
        send = new ImageButton(this);
        send.setBackgroundDrawable(null);
        send.setImageDrawable(Ui.d(this, "ic_send_off"));
        send.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { doSend(); } });
        bar.addView(send, new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 52)));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable e) {
                send.setImageDrawable(Ui.d(ChatActivity.this, e.toString().trim().length() > 0 ? "ic_send_on" : "ic_send_off"));
            }
        });
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.addView(lv, new LinearLayout.LayoutParams(-1, 0, 1)); root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
    }

    View makeRow(final Db.Msg m) {
        Context c = this;
        int d8 = Ui.dp(c, 8), avatar = Ui.dp(c, 48);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(d8, Ui.dp(c, 4), d8, Ui.dp(c, 4));
        row.setGravity(m.out ? Gravity.RIGHT : Gravity.LEFT);

        int cardMax = screenW - 2 * d8 - avatar;
        int innerMax = cardMax - Ui.dp(c, 24);

        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(0xffffffff);
        card.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 6));

        final String body = m.body.trim();
        boolean image = ImgLoader.isImageUrl(body) && !ImgLoader.hasFailed(body);
        if (image) {
            Bitmap bmp = ImgLoader.get(body, 1000, new Runnable() { public void run() { if (ad != null && !isFinishing()) relayout(false, false); } });
            ImageView iv = new ImageView(c);
            iv.setAdjustViewBounds(true); iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            if (bmp != null) {
                iv.setImageBitmap(bmp);
                card.addView(iv, new LinearLayout.LayoutParams(innerMax, LinearLayout.LayoutParams.WRAP_CONTENT));
            } else {
                iv.setBackgroundColor(0xffeeeeee);
                card.addView(iv, new LinearLayout.LayoutParams(innerMax, Ui.dp(c, 150)));
            }
            iv.setOnClickListener(new View.OnClickListener() { public void onClick(View v) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(body))); } catch (Exception e) { }
            } });
        } else {
            TextView t = new TextView(c);
            t.setText(m.body); t.setTextSize(18); t.setTextColor(0xff333333); t.setMaxWidth(innerMax);
            Linkify.addLinks(t, Linkify.WEB_URLS);
            card.addView(t, new LinearLayout.LayoutParams(-2, -2));
        }
        TextView meta = new TextView(c);
        meta.setTextSize(12); meta.setTextColor(0xff9b9b9b); meta.setSingleLine();
        meta.setText(m.out ? Ui.relShort(m.ts) : (room ? (m.nick == null ? "" : m.nick) : App.I.client.nameOf(peer)) + " • " + Ui.relShort(m.ts));
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.topMargin = Ui.dp(c, 2);
        card.addView(meta, ml);

        ImageView av = new ImageView(c);
        av.setScaleType(ImageView.ScaleType.CENTER_CROP);
        av.setImageDrawable(Ui.d(c, "default_avatar"));
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(avatar, avatar);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        if (m.out) { row.addView(card, cl); row.addView(av, al); }
        else { row.addView(av, al); row.addView(card, cl); }
        return row;
    }

    void doSend() {
        String s = input.getText().toString().trim();
        if (TextUtils.isEmpty(s)) return;
        input.setText("");
        stickNext = true;
        if (room) App.I.sendGroup(peer, s); else App.I.send(peer, s);
    }

    static String keyOf(Db.Msg m) { return m.ts + "|" + m.out + "|" + m.body; }

    boolean atBottom() {
        int n = msgs.size();
        if (n == 0 || lv.getChildCount() == 0) return true;
        if (lv.getLastVisiblePosition() < n - 1) return false;
        View last = lv.getChildAt(lv.getChildCount() - 1);
        return last.getBottom() <= lv.getHeight() - lv.getPaddingBottom() + Ui.dp(this, 12);
    }

    /** Re-renders the list without losing the reader's place. */
    void relayout(boolean reload, boolean forceBottom) {
        boolean bottom = forceBottom || atBottom();
        int pos = lv.getFirstVisiblePosition();
        View c0 = lv.getChildAt(0);
        int top = c0 != null ? c0.getTop() : 0;
        String key = (pos >= 0 && pos < msgs.size()) ? keyOf(msgs.get(pos)) : null;
        if (reload) { msgs.clear(); msgs.addAll(App.I.db.thread(peer)); }
        ad.notifyDataSetChanged();
        if (msgs.isEmpty()) return;
        if (bottom) {
            final int last = msgs.size() - 1;
            lv.setSelection(last);
            lv.post(new Runnable() { public void run() { lv.setSelection(last); } });
        } else {
            int np = Math.min(pos, msgs.size() - 1);
            if (key != null) for (int i = 0; i < msgs.size(); i++) if (keyOf(msgs.get(i)).equals(key)) { np = i; break; }
            lv.setSelectionFromTop(np, top);
        }
    }

    void refresh() { relayout(true, false); }

    @Override protected void onResume() {
        super.onResume();
        if (ad == null) return;
        App.openPeer = peer; App.I.clearNotification(peer); App.I.markRead(peer); App.I.add(this);
        relayout(true, first); first = false;
    }
    @Override protected void onPause() { super.onPause(); if (peer != null) App.I.markRead(peer); App.openPeer = null; App.I.remove(this); }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        MenuItem plus = m.add(0, 1, 0, "Attach");
        plus.setIcon(Ui.d(this, "ic_menu_new"));
        plus.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, 2, 1, room ? "Leave group chat" : "Delete conversation");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        switch (i.getItemId()) {
            case android.R.id.home: finish(); return true;
            case 1: {
                Intent it = new Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                startActivityForResult(Intent.createChooser(it, "Attach"), PICK);
                return true;
            }
            case 2:
                new AlertDialog.Builder(this).setTitle(room ? "Leave group chat?" : "Delete conversation?").setMessage(room ? "You will stop receiving messages from this room and its history is removed from this device." : "Messages are removed from this device only.")
                    .setPositiveButton(room ? "Leave" : "Delete", new DialogInterface.OnClickListener() { public void onClick(DialogInterface d, int w) {
                        if (room) App.I.leaveRoom(peer); else App.I.db.deletePeer(peer);
                        finish();
                    } }).setNegativeButton(android.R.string.cancel, null).show();
                return true;
        }
        return false;
    }

    @Override protected void onActivityResult(int req, int res, Intent data) {
        if (req == PICK && res == RESULT_OK && data != null && data.getData() != null) App.I.sendAttachment(peer, data.getData());
    }

    public void onChanged() { App.I.markRead(peer); boolean s = stickNext; stickNext = false; relayout(true, s); }
    public void onState(String s) { }
}
