package com.moa.player;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.HashMap;
import java.util.Map;

/** Stores resume positions, last played time and per-file subtitle delay. */
public final class HistoryDb extends SQLiteOpenHelper {
    private static HistoryDb inst;

    public static synchronized HistoryDb get(Context c) {
        if (inst == null) inst = new HistoryDb(c.getApplicationContext());
        return inst;
    }

    public static final class Entry {
        public String key, uri, title, folder;
        public long position, duration, lastPlayed, subDelay;

        public boolean finished() {
            return duration > 0 && position >= duration - Math.max(3000, duration / 50);
        }

        public float progress() {
            if (duration <= 0) return 0f;
            return Math.min(1f, position / (float) duration);
        }
    }

    private HistoryDb(Context c) {
        super(c, "history.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE history (key TEXT PRIMARY KEY, uri TEXT, title TEXT, folder TEXT, "
                + "position INTEGER DEFAULT 0, duration INTEGER DEFAULT 0, last_played INTEGER DEFAULT 0, "
                + "sub_delay INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_last ON history(last_played)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS history");
        onCreate(db);
    }

    private static Entry read(Cursor c) {
        Entry e = new Entry();
        e.key = c.getString(0);
        e.uri = c.getString(1);
        e.title = c.getString(2);
        e.folder = c.getString(3);
        e.position = c.getLong(4);
        e.duration = c.getLong(5);
        e.lastPlayed = c.getLong(6);
        e.subDelay = c.getLong(7);
        return e;
    }

    private static final String COLS = "key, uri, title, folder, position, duration, last_played, sub_delay";

    public synchronized Entry get(String key) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM history WHERE key=?", new String[]{key});
            return c.moveToFirst() ? read(c) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    public synchronized Map<String, Entry> all() {
        Map<String, Entry> m = new HashMap<>();
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM history", null);
            while (c.moveToNext()) {
                Entry e = read(c);
                m.put(e.key, e);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return m;
    }

    public synchronized Entry lastPlayed() {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT " + COLS + " FROM history ORDER BY last_played DESC LIMIT 1", null);
            return c.moveToFirst() ? read(c) : null;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    public synchronized void save(MediaRef r, long position, long duration) {
        if (r == null || r.stream) return;
        try {
            SQLiteDatabase db = getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put("uri", r.uri.toString());
            v.put("title", r.title);
            v.put("folder", r.folder);
            v.put("position", Math.max(0, position));
            if (duration > 0) v.put("duration", duration);
            v.put("last_played", System.currentTimeMillis());
            int n = db.update("history", v, "key=?", new String[]{r.key()});
            if (n == 0) {
                v.put("key", r.key());
                db.insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void saveSubDelay(MediaRef r, long delay) {
        if (r == null || r.stream) return;
        try {
            ContentValues v = new ContentValues();
            v.put("sub_delay", delay);
            int n = getWritableDatabase().update("history", v, "key=?", new String[]{r.key()});
            if (n == 0) {
                v.put("key", r.key());
                v.put("uri", r.uri.toString());
                v.put("title", r.title);
                v.put("folder", r.folder);
                getWritableDatabase().insertWithOnConflict("history", null, v, SQLiteDatabase.CONFLICT_IGNORE);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void clear() {
        try {
            getWritableDatabase().delete("history", null, null);
        } catch (Exception ignored) {
        }
    }

    public synchronized void remove(String key) {
        try {
            getWritableDatabase().delete("history", "key=?", new String[]{key});
        } catch (Exception ignored) {
        }
    }
}
