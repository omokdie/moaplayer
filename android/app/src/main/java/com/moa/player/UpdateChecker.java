package com.moa.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Looks at the newest GitHub Release (the same place the Windows version updates from) and,
 * when it carries a newer APK, offers to download it.  Automatic checks run at most every
 * 12 hours and stay silent when offline; "지금 업데이트 확인" in the settings always reports back.
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    private static final long INTERVAL_MS = 12L * 60 * 60 * 1000;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean running;

    /** Result of one check. */
    static final class Release {
        String version;   // "1.2.0"
        String apkUrl;    // direct download of MoaPlayer-x.y.z.apk
        String pageUrl;   // release page on GitHub
        String notes;     // plain text, may be empty
        long apkSize;
    }

    /** Called from the folder screen; does nothing when switched off or checked recently. */
    public static void maybeCheck(Activity a) {
        if (!Prefs.bool(a, Prefs.AUTO_UPDATE, true)) return;
        long last = Prefs.sp(a).getLong(Prefs.UPDATE_LAST_CHECK, 0);
        if (Math.abs(System.currentTimeMillis() - last) < INTERVAL_MS) return;
        if (!online(a)) return;
        check(a, false);
    }

    /** manual = the user asked: always show a result (also "latest version" and errors). */
    public static void check(final Activity a, final boolean manual) {
        if (running) {
            if (manual) Util.toast(a, "업데이트를 확인하고 있습니다…");
            return;
        }
        running = true;
        final Context app = a.getApplicationContext();
        final String current = currentVersion(app);
        final String repo = app.getString(R.string.update_repo);
        if (manual) Util.toast(a, "업데이트를 확인하는 중…");
        IO.execute(() -> {
            Release r = null;
            String error = null;
            try {
                r = fetchLatest(repo, current);
            } catch (Exception e) {
                error = e.getMessage();
            }
            final Release rel = r;
            final String err = error;
            UI.post(() -> {
                running = false;
                // screen gone (rotation, back): leave the check time alone so the next start asks again
                if (a.isFinishing() || (android.os.Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
                if (rel != null) Prefs.sp(app).edit().putLong(Prefs.UPDATE_LAST_CHECK, System.currentTimeMillis()).apply();
                if (rel == null) {
                    if (manual) Util.toast(a, online(app) ? "업데이트를 확인하지 못했습니다" + (err != null ? " (" + err + ")" : "")
                            : "인터넷에 연결되어 있지 않습니다");
                    return;
                }
                if (compare(rel.version, current) <= 0 || rel.apkUrl == null) {
                    if (manual) Util.toast(a, "최신 버전입니다 (" + current + ")");
                    return;
                }
                if (!manual && rel.version.equals(Prefs.str(a, Prefs.UPDATE_SKIPPED, ""))) return;
                showDialog(a, rel, current);
            });
        });
    }

    private static void showDialog(final Activity a, final Release r, String current) {
        StringBuilder msg = new StringBuilder();
        msg.append("새 버전(").append(r.version).append(")이 나왔습니다. 지금 쓰는 버전은 ").append(current).append("입니다.");
        if (r.apkSize > 0) msg.append("\n파일 크기: ").append(Util.size(r.apkSize));
        if (r.notes != null && !r.notes.isEmpty()) msg.append("\n\n").append(r.notes);
        msg.append("\n\n[다운로드]를 누르면 브라우저에서 APK를 내려받습니다. 다 받은 뒤 파일을 열어 설치하세요. 설정과 재생 기록은 그대로 남습니다.");
        new AlertDialog.Builder(a)
                .setTitle("업데이트 " + r.version)
                .setMessage(msg.toString())
                .setPositiveButton("다운로드", (d, w) -> open(a, r.apkUrl != null ? r.apkUrl : r.pageUrl))
                .setNegativeButton("나중에", null)
                .setNeutralButton("이 버전 건너뛰기", (d, w) ->
                        Prefs.sp(a).edit().putString(Prefs.UPDATE_SKIPPED, r.version).apply())
                .show();
    }

    private static void open(Activity a, String url) {
        if (url == null) return;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            a.startActivity(i);
        } catch (Exception e) {
            Util.toast(a, "브라우저를 열 수 없습니다");
        }
    }

    /** GET https://api.github.com/repos/{repo}/releases/latest and pick the APK asset. */
    static Release fetchLatest(String repo, String current) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + repo + "/releases/latest").openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "MoaPlayer-Android/" + current);
            int code = c.getResponseCode();
            if (code == 404) throw new Exception("배포된 버전 없음");
            if (code != 200) throw new Exception("HTTP " + code);
            return parse(new JSONObject(readAll(c.getInputStream())));
        } finally {
            c.disconnect();
        }
    }

    static Release parse(JSONObject o) {
        Release r = new Release();
        String tag = o.optString("tag_name", "");
        r.version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        r.pageUrl = o.optString("html_url", null);
        r.notes = cleanNotes(o.optString("body", ""));
        JSONArray assets = o.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject as = assets.optJSONObject(i);
                if (as == null) continue;
                String name = as.optString("name", "");
                if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk")) {
                    r.apkUrl = as.optString("browser_download_url", null);
                    r.apkSize = as.optLong("size", 0);
                    break;
                }
            }
        }
        return r;
    }

    /** Release body without the download section, markdown marks and very long text. */
    static String cleanNotes(String body) {
        if (body == null) return "";
        String s = body.replace("\r", "");
        int cut = s.indexOf("### 다운로드");
        if (cut >= 0) s = s.substring(0, cut);
        s = s.replaceAll("(?m)^#+\\s*", "").replaceAll("\\*\\*|__|`", "").replaceAll("(?m)^[-*]\\s+", "• ").trim();
        if (s.length() > 700) s = s.substring(0, 700) + "…";
        return s;
    }

    /** Compares "1.10.2" style versions numerically; a "-beta" suffix sorts before the plain version. */
    static int compare(String a, String b) {
        String[] pa = a.split("-", 2), pb = b.split("-", 2);
        String[] x = pa[0].split("\\."), y = pb[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? num(x[i]) : 0, yi = i < y.length ? num(y[i]) : 0;
            if (xi != yi) return xi < yi ? -1 : 1;
        }
        boolean preA = pa.length > 1, preB = pb.length > 1;
        if (preA != preB) return preA ? -1 : 1;
        return preA ? pa[1].compareTo(pb[1]) : 0;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("[^0-9]", "")); } catch (NumberFormatException e) { return 0; }
    }

    static String currentVersion(Context c) {
        try {
            return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "0";
        }
    }

    @SuppressWarnings("deprecation")
    static boolean online(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Exception e) {
            return true;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 * 1024 * 1024) throw new Exception("응답이 너무 큽니다");
            }
            return out.toString("UTF-8");
        }
    }
}
