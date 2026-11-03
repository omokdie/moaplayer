package com.moa.player;

import android.content.Context;
import android.util.TypedValue;
import android.widget.Toast;

import java.util.Locale;

public final class Util {
    private Util() {}

    private static Toast lastToast;

    public static int dp(Context c, float dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                c.getResources().getDisplayMetrics()));
    }

    public static void toast(Context c, String msg) {
        if (lastToast != null) lastToast.cancel();
        lastToast = Toast.makeText(c.getApplicationContext(), msg, Toast.LENGTH_SHORT);
        lastToast.show();
    }

    public static String time(long ms) {
        if (ms < 0) ms = 0;
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, sec = s % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec);
        return String.format(Locale.US, "%02d:%02d", m, sec);
    }

    public static String signedTime(long ms) {
        return (ms < 0 ? "-" : "+") + time(Math.abs(ms));
    }

    public static String size(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + "B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format(Locale.US, "%.0fKB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb);
        return String.format(Locale.US, "%.2fGB", mb / 1024.0);
    }

    public static String stripExt(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i > 0 ? name.substring(0, i) : name;
    }

    public static String ext(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i >= 0 ? name.substring(i + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static long clamp(long v, long lo, long hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public static String speed(float s) {
        String t = String.format(Locale.US, "%.2f", s);
        if (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        return t + "x";
    }

    /** Human readable language name in Korean for ISO codes such as "ko", "eng". */
    public static String langName(String code) {
        if (code == null || code.isEmpty() || "und".equals(code) || "unk".equals(code)) return null;
        try {
            String n = new Locale(code).getDisplayLanguage(Locale.KOREAN);
            if (n != null && !n.isEmpty() && !n.equalsIgnoreCase(code)) return n;
        } catch (Exception ignored) {
        }
        return code;
    }

    /** Natural order comparison so that "2화" sorts before "10화". */
    public static int natural(String a, String b) {
        if (a == null) a = "";
        if (b == null) b = "";
        int i = 0, j = 0, na = a.length(), nb = b.length();
        while (i < na && j < nb) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < na && Character.isDigit(a.charAt(i))) i++;
                while (j < nb && Character.isDigit(b.charAt(j))) j++;
                String da = trimZeros(a.substring(si, i)), db = trimZeros(b.substring(sj, j));
                if (da.length() != db.length()) return da.length() - db.length();
                int c = da.compareTo(db);
                if (c != 0) return c;
            } else {
                int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                if (c != 0) return c;
                i++;
                j++;
            }
        }
        return (na - i) - (nb - j);
    }

    private static String trimZeros(String s) {
        int k = 0;
        while (k < s.length() - 1 && s.charAt(k) == '0') k++;
        return s.substring(k);
    }
}
