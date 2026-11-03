package com.moa.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/** Typed access to the shared preferences written by the settings screen. */
public final class Prefs {
    private Prefs() {}

    public static final String DECODER = "decoder";
    public static final String RESUME = "resume";
    public static final String AUTOPLAY = "autoplay_next";
    public static final String ORIENTATION = "orientation";
    public static final String BG_PLAY = "background_play";
    public static final String AUTO_PIP = "auto_pip";
    public static final String G_BRIGHT = "gesture_brightness";
    public static final String G_VOLUME = "gesture_volume";
    public static final String G_SEEK = "gesture_seek";
    public static final String G_ZOOM = "gesture_zoom";
    public static final String DOUBLE_TAP = "double_tap";
    public static final String SEEK_SEC = "seek_seconds";
    public static final String LONG_SPEED = "long_press_speed";
    public static final String VOL_BOOST = "volume_boost";
    public static final String REMEMBER_BRIGHT = "remember_brightness";
    public static final String BRIGHT_LEVEL = "brightness_level_v1";
    public static final String SUB_SIZE = "sub_size";
    public static final String SUB_COLOR = "sub_color";
    public static final String SUB_OUTLINE = "sub_outline";
    public static final String SUB_BG = "sub_bg";
    public static final String SUB_BOTTOM = "sub_bottom";
    public static final String SUB_ENC = "sub_encoding";
    public static final String SORT = "sort_by";
    public static final String SORT_DESC = "sort_desc";
    public static final String VIEW = "view_mode";
    public static final String THUMBS = "show_thumbs";
    public static final String ASKED_ALL_FILES = "asked_all_files";
    public static final String AUTO_UPDATE = "auto_update";
    public static final String UPDATE_LAST_CHECK = "update_last_check";
    public static final String UPDATE_SKIPPED = "update_skipped";

    public static SharedPreferences sp(Context c) {
        return PreferenceManager.getDefaultSharedPreferences(c.getApplicationContext());
    }

    public static String str(Context c, String key, String def) {
        try {
            String v = sp(c).getString(key, def);
            return v == null ? def : v;
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static boolean bool(Context c, String key, boolean def) {
        try {
            return sp(c).getBoolean(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static int intOf(Context c, String key, int def) {
        try {
            return Integer.parseInt(str(c, key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public static int rawInt(Context c, String key, int def) {
        try {
            return sp(c).getInt(key, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static void put(Context c, String key, String v) {
        sp(c).edit().putString(key, v).apply();
    }

    public static void put(Context c, String key, boolean v) {
        sp(c).edit().putBoolean(key, v).apply();
    }

    public static void putInt(Context c, String key, int v) {
        sp(c).edit().putInt(key, v).apply();
    }
}
