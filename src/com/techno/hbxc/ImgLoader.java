package com.techno.hbxc;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Tiny in-memory image downloader for inline attachment previews. */
class ImgLoader {
    private static final Pattern IMG = Pattern.compile("(?i)^https?://\\S+\\.(jpe?g|png|gif|webp|bmp)(\\?\\S*)?$");
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>((int) (Runtime.getRuntime().maxMemory() / 1024 / 8)) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount() / 1024; }
    };
    private static final Set<String> pending = Collections.synchronizedSet(new HashSet<String>());
    private static final Set<String> failed = Collections.synchronizedSet(new HashSet<String>());
    private static final Handler ui = new Handler(Looper.getMainLooper());

    static boolean isImageUrl(String s) { return s != null && IMG.matcher(s.trim()).matches(); }
    static boolean hasFailed(String url) { return failed.contains(url); }

    /** Returns the bitmap if cached; otherwise starts a download and runs done on the UI thread when finished. */
    static Bitmap get(final String url, final int maxPx, final Runnable done) {
        Bitmap b = cache.get(url);
        if (b != null) return b;
        if (failed.contains(url) || !pending.add(url)) return null;
        new Thread(new Runnable() { public void run() {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000); c.setReadTimeout(15000);
                InputStream in = c.getInputStream();
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192]; int n; long total = 0;
                while ((n = in.read(buf)) > 0) { total += n; if (total > 12L * 1024 * 1024) throw new Exception("too big"); bo.write(buf, 0, n); }
                in.close();
                byte[] data = bo.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int s = 1; while (o.outWidth / (s * 2) >= maxPx) s *= 2;
                o = new BitmapFactory.Options(); o.inSampleSize = s;
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                if (bmp == null) throw new Exception("not an image");
                cache.put(url, bmp);
            } catch (Exception e) { failed.add(url); }
            finally { pending.remove(url); ui.post(done); }
        } }).start();
        return null;
    }
}
