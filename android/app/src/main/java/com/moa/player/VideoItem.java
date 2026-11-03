package com.moa.player;

import android.net.Uri;

/** One video entry from MediaStore. */
public final class VideoItem {
    public long id = -1;
    public Uri uri;
    public String path;
    public String name;
    public String folderPath;
    public String folderName;
    public long size;
    public long duration;
    public long dateAdded;      // seconds
    public long dateModified;   // seconds
    public int width, height;
    public String mime;

    public String key() {
        return path != null ? path : (uri != null ? uri.toString() : name);
    }

    public String title() {
        return Util.stripExt(name);
    }
}
