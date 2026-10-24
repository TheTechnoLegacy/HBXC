package com.techno.hbxc;

import android.content.Context;
import android.graphics.drawable.Drawable;
import java.text.DateFormat;
import java.util.Date;

class Ui {
    static final int GREEN = 0xff53a93f;
    static Drawable d(Context c, String n) {
        int id = c.getResources().getIdentifier(n, "drawable", c.getPackageName());
        return c.getResources().getDrawable(id);
    }
    static String local(String jid) { int i = jid.indexOf('@'); return i > 0 ? jid.substring(0, i) : jid; }
    static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }
    static String relShort(long ts) {
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60000) return "Now";
        long min = diff / 60000;
        if (min < 60) return min + " min";
        long hrs = min / 60;
        if (hrs < 24) return hrs + " hr";
        if (hrs < 24 * 6) return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(new Date(ts));
        return DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(ts));
    }
    static String rel(long ts) {
        long diff = System.currentTimeMillis() - ts;
        long min = diff / 60000;
        if (diff < 60000) return "Now";
        if (min < 60) return min + (min == 1 ? " min" : " mins");
        long hrs = min / 60;
        if (hrs < 24) return hrs + (hrs == 1 ? " hr" : " hrs");
        if (hrs < 24 * 6) return new java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(new Date(ts));
        return DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(ts));
    }
    static String time(long ts) {
        Date d = new Date(ts);
        long day = 24L * 3600 * 1000;
        if (System.currentTimeMillis() - ts < day) return DateFormat.getTimeInstance(DateFormat.SHORT).format(d);
        return DateFormat.getDateInstance(DateFormat.SHORT).format(d);
    }
}
