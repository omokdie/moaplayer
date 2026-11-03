package com.moa.player;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Asynchronous video thumbnail loading with memory and disk caches. */
public final class ThumbLoader {
    private static ThumbLoader inst;

    public static synchronized ThumbLoader get(Context c) {
        if (inst == null) inst = new ThumbLoader(c.getApplicationContext());
        return inst;
    }

    private final Context ctx;
    private final LruCache<String, Bitmap> mem;
    private final ExecutorService exec = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final File dir;

    private ThumbLoader(Context c) {
        ctx = c;
        int max = (int) Math.min(Runtime.getRuntime().maxMemory() / 8, 48L * 1024 * 1024);
        mem = new LruCache<String, Bitmap>(max) {
            @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
        };
        dir = new File(c.getCacheDir(), "thumbs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    public void load(final VideoItem v, final ImageView iv) {
        final String key = v.key() + "|" + v.dateModified + "|" + v.size;
        iv.setTag(R.id.tag_thumb, key);
        Bitmap b = mem.get(key);
        if (b != null) {
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageAlpha(255);
            iv.setImageBitmap(b);
            return;
        }
        iv.setScaleType(ImageView.ScaleType.CENTER);
        iv.setImageResource(R.drawable.ic_movie);
        iv.setImageAlpha(90);
        exec.execute(new Runnable() {
            @Override public void run() {
                if (!key.equals(iv.getTag(R.id.tag_thumb))) return;
                File f = new File(dir, md5(key) + ".jpg");
                Bitmap bm = null;
                if (f.exists()) bm = BitmapFactory.decodeFile(f.getAbsolutePath());
                if (bm == null) {
                    bm = create(v);
                    if (bm != null) save(bm, f);
                }
                if (bm == null) return;
                mem.put(key, bm);
                final Bitmap fb = bm;
                main.post(new Runnable() {
                    @Override public void run() {
                        if (key.equals(iv.getTag(R.id.tag_thumb))) {
                            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            iv.setImageAlpha(255);
                            iv.setImageBitmap(fb);
                        }
                    }
                });
            }
        });
    }

    private Bitmap create(VideoItem v) {
        try {
            if (Build.VERSION.SDK_INT >= 29 && v.id >= 0) {
                return ctx.getContentResolver().loadThumbnail(v.uri, new Size(384, 216), null);
            }
            if (v.id >= 0) {
                @SuppressWarnings("deprecation")
                Bitmap b = MediaStore.Video.Thumbnails.getThumbnail(ctx.getContentResolver(), v.id,
                        MediaStore.Video.Thumbnails.MINI_KIND, null);
                if (b != null) return b;
            }
        } catch (Throwable ignored) {
        }
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(ctx, v.uri);
            long t = v.duration > 0 ? Math.min(v.duration / 10, 30000) * 1000 : 1000000;
            Bitmap b = r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return null;
            int w = 384, h = Math.max(1, b.getHeight() * 384 / Math.max(1, b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            if (s != b) b.recycle();
            return s;
        } catch (Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch (Throwable ignored) { }
        }
    }

    private static void save(Bitmap b, File f) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            b.compress(Bitmap.CompressFormat.JPEG, 82, out);
        } catch (Exception ignored) {
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) { }
        }
    }

    public void clearMemory() {
        mem.evictAll();
    }

    static String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }
}
