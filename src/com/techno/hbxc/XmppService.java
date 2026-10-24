package com.techno.hbxc;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Keeps the process (and the XMPP connection owned by App) alive in the background. */
public class XmppService extends Service {
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int flags, int id) { return START_STICKY; }
}
