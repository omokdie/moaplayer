package com.moa.player;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads videos from MediaStore and groups them by folder. */
public final class MediaRepo {
    private static final String TAG = "MediaRepo";
    private static volatile List<VideoItem> cache;

    private MediaRepo() {}

    public static List<VideoItem> cached() {
        return cache;
    }

    public static List<VideoItem> load(Context ctx) {
        List<VideoItem> out = new ArrayList<>();
        ContentResolver cr = ctx.getContentResolver();
        Uri coll = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        List<String> proj = new ArrayList<>();
        proj.add(MediaStore.Video.Media._ID);
        proj.add(MediaStore.Video.Media.DISPLAY_NAME);
        proj.add(MediaStore.Video.Media.DATA);
        proj.add(MediaStore.Video.Media.SIZE);
        proj.add(MediaStore.Video.Media.DURATION);
        proj.add(MediaStore.Video.Media.DATE_ADDED);
        proj.add(MediaStore.Video.Media.DATE_MODIFIED);
        proj.add(MediaStore.Video.Media.WIDTH);
        proj.add(MediaStore.Video.Media.HEIGHT);
        proj.add(MediaStore.Video.Media.MIME_TYPE);
        proj.add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
        if (Build.VERSION.SDK_INT >= 29) proj.add(MediaStore.Video.Media.RELATIVE_PATH);
        Cursor c = null;
        try {
            c = cr.query(coll, proj.toArray(new String[0]), null, null, null);
            if (c != null) {
                int iId = c.getColumnIndex(MediaStore.Video.Media._ID);
                int iName = c.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                int iData = c.getColumnIndex(MediaStore.Video.Media.DATA);
                int iSize = c.getColumnIndex(MediaStore.Video.Media.SIZE);
                int iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                int iAdded = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED);
                int iMod = c.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED);
                int iW = c.getColumnIndex(MediaStore.Video.Media.WIDTH);
                int iH = c.getColumnIndex(MediaStore.Video.Media.HEIGHT);
                int iMime = c.getColumnIndex(MediaStore.Video.Media.MIME_TYPE);
                int iBucket = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                int iRel = Build.VERSION.SDK_INT >= 29 ? c.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) : -1;
                while (c.moveToNext()) {
                    VideoItem v = new VideoItem();
                    v.id = c.getLong(iId);
                    v.uri = ContentUris.withAppendedId(coll, v.id);
                    v.name = iName >= 0 ? c.getString(iName) : null;
                    v.path = iData >= 0 ? c.getString(iData) : null;
                    v.size = iSize >= 0 ? c.getLong(iSize) : 0;
                    v.duration = iDur >= 0 ? c.getLong(iDur) : 0;
                    v.dateAdded = iAdded >= 0 ? c.getLong(iAdded) : 0;
                    v.dateModified = iMod >= 0 ? c.getLong(iMod) : 0;
                    v.width = iW >= 0 ? c.getInt(iW) : 0;
                    v.height = iH >= 0 ? c.getInt(iH) : 0;
                    v.mime = iMime >= 0 ? c.getString(iMime) : null;
                    String bucket = iBucket >= 0 ? c.getString(iBucket) : null;
                    if (v.path != null && v.path.startsWith("/")) {
                        File f = new File(v.path);
                        if (v.name == null) v.name = f.getName();
                        v.folderPath = f.getParent();
                        File parent = f.getParentFile();
                        v.folderName = parent != null ? parent.getName() : bucket;
                    } else {
                        v.path = null;
                        String rel = iRel >= 0 ? c.getString(iRel) : null;
                        v.folderPath = rel != null ? rel : (bucket != null ? bucket : "?");
                        v.folderName = bucket != null ? bucket : v.folderPath;
                    }
                    if (v.name == null) v.name = "video_" + v.id;
                    if (v.folderName == null || v.folderName.isEmpty()) v.folderName = "/";
                    out.add(v);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "query failed", e);
        } finally {
            if (c != null) c.close();
        }
        cache = out;
        return out;
    }

    public static List<FolderItem> folders(List<VideoItem> all, Map<String, HistoryDb.Entry> history) {
        Map<String, FolderItem> map = new LinkedHashMap<>();
        for (VideoItem v : all) {
            String key = v.folderPath == null ? "?" : v.folderPath;
            FolderItem f = map.get(key);
            if (f == null) {
                f = new FolderItem();
                f.path = key;
                f.name = v.folderName;
                map.put(key, f);
            }
            f.videos.add(v);
            f.totalSize += v.size;
            f.lastModified = Math.max(f.lastModified, Math.max(v.dateModified, v.dateAdded));
            if (history != null && !history.containsKey(v.key())) f.newCount++;
        }
        return new ArrayList<>(map.values());
    }

    public static List<VideoItem> inFolder(List<VideoItem> all, String folderPath) {
        List<VideoItem> out = new ArrayList<>();
        if (all == null) return out;
        for (VideoItem v : all) {
            if (folderPath.equals(v.folderPath)) out.add(v);
        }
        return out;
    }

    public static List<VideoItem> search(List<VideoItem> all, String q) {
        List<VideoItem> out = new ArrayList<>();
        if (all == null) return out;
        String lq = q.toLowerCase();
        for (VideoItem v : all) {
            if (v.name != null && v.name.toLowerCase().contains(lq)) out.add(v);
        }
        return out;
    }

    public static void sortVideos(List<VideoItem> list, String by, final boolean desc) {
        Comparator<VideoItem> cmp;
        if ("date".equals(by)) {
            cmp = new Comparator<VideoItem>() {
                @Override public int compare(VideoItem a, VideoItem b) {
                    return Long.compare(Math.max(a.dateModified, a.dateAdded), Math.max(b.dateModified, b.dateAdded));
                }
            };
        } else if ("size".equals(by)) {
            cmp = new Comparator<VideoItem>() {
                @Override public int compare(VideoItem a, VideoItem b) { return Long.compare(a.size, b.size); }
            };
        } else if ("duration".equals(by)) {
            cmp = new Comparator<VideoItem>() {
                @Override public int compare(VideoItem a, VideoItem b) { return Long.compare(a.duration, b.duration); }
            };
        } else {
            cmp = new Comparator<VideoItem>() {
                @Override public int compare(VideoItem a, VideoItem b) { return Util.natural(a.name, b.name); }
            };
        }
        final Comparator<VideoItem> base = cmp;
        Collections.sort(list, new Comparator<VideoItem>() {
            @Override public int compare(VideoItem a, VideoItem b) {
                int r = base.compare(a, b);
                if (r == 0) r = Util.natural(a.name, b.name);
                return desc ? -r : r;
            }
        });
    }

    public static void sortFolders(List<FolderItem> list, String by, final boolean desc) {
        Comparator<FolderItem> cmp;
        if ("date".equals(by)) {
            cmp = new Comparator<FolderItem>() {
                @Override public int compare(FolderItem a, FolderItem b) { return Long.compare(a.lastModified, b.lastModified); }
            };
        } else if ("size".equals(by)) {
            cmp = new Comparator<FolderItem>() {
                @Override public int compare(FolderItem a, FolderItem b) { return Long.compare(a.totalSize, b.totalSize); }
            };
        } else if ("duration".equals(by)) {
            cmp = new Comparator<FolderItem>() {
                @Override public int compare(FolderItem a, FolderItem b) { return Integer.compare(a.videos.size(), b.videos.size()); }
            };
        } else {
            cmp = new Comparator<FolderItem>() {
                @Override public int compare(FolderItem a, FolderItem b) { return Util.natural(a.name, b.name); }
            };
        }
        final Comparator<FolderItem> base = cmp;
        Collections.sort(list, new Comparator<FolderItem>() {
            @Override public int compare(FolderItem a, FolderItem b) {
                int r = base.compare(a, b);
                if (r == 0) r = Util.natural(a.name, b.name);
                return desc ? -r : r;
            }
        });
    }

    /** Videos in the same folder as the given file path, sorted by the user's preference. */
    public static List<VideoItem> siblings(Context c, String path) {
        if (path == null) return new ArrayList<>();
        List<VideoItem> all = cache;
        if (all == null) all = load(c);
        String folder = new File(path).getParent();
        if (folder == null) return new ArrayList<>();
        List<VideoItem> list = inFolder(all, folder);
        sortVideos(list, Prefs.str(c, Prefs.SORT, "name"), Prefs.bool(c, Prefs.SORT_DESC, false));
        return list;
    }
}
