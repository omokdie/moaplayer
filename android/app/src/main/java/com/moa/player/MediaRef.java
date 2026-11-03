package com.moa.player;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** A playable item in the player playlist. */
public final class MediaRef {
    public Uri uri;
    public String path;      // absolute file path when known
    public String title;
    public String folder;    // parent folder path when known
    public int widthHint, heightHint;
    public boolean stream;
    public String mime;      // MIME type given by another app, if any

    public String key() {
        return path != null ? path : uri.toString();
    }

    public static MediaRef of(VideoItem v) {
        MediaRef r = new MediaRef();
        r.uri = v.uri;
        r.path = v.path;
        r.title = v.title();
        r.folder = v.folderPath;
        r.widthHint = v.width;
        r.heightHint = v.height;
        return r;
    }

    public static List<MediaRef> list(List<VideoItem> items) {
        List<MediaRef> out = new ArrayList<>();
        for (VideoItem v : items) out.add(of(v));
        return out;
    }

    /** Builds a reference for an arbitrary uri coming from another app or a typed URL. */
    public static MediaRef fromUri(Context c, Uri uri) {
        return fromUri(c, uri, null);
    }

    public static MediaRef fromUri(Context c, Uri uri, String mime) {
        MediaRef r = new MediaRef();
        r.uri = uri;
        r.mime = mime;
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("file".equals(scheme)) {
            r.path = uri.getPath();
        } else if ("content".equals(scheme)) {
            ContentResolver cr = c.getContentResolver();
            String name = null, data = null;
            Cursor cur = null;
            try {
                cur = cr.query(uri, null, null, null, null);
                if (cur != null && cur.moveToFirst()) {
                    int iName = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    int iData = cur.getColumnIndex("_data");
                    if (iName >= 0) name = cur.getString(iName);
                    if (iData >= 0) data = cur.getString(iData);
                }
            } catch (Exception ignored) {
            } finally {
                if (cur != null) cur.close();
            }
            if (data != null && data.startsWith("/")) r.path = data;
            if (name != null) r.title = Util.stripExt(name);
        } else {
            r.stream = true;
        }
        if (r.path != null) {
            File f = new File(r.path);
            r.folder = f.getParent();
            if (r.title == null) r.title = Util.stripExt(f.getName());
        }
        if (r.title == null) {
            String seg = uri.getLastPathSegment();
            r.title = seg != null ? Util.stripExt(seg) : uri.toString();
        }
        return r;
    }
}
