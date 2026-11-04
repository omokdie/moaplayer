package com.moa.player;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-update from GitHub Releases (the app is not installed from a store).
 *
 * <ol>
 *   <li>{@link UpdateChecker} finds a newer release; its APK is downloaded here in the background.</li>
 *   <li>The file is checked before anything is offered: size, SHA-256 (when GitHub lists it), package
 *       name, version, minSdk and the same signing certificate as the installed app. A version that can
 *       never install here (other signer, too new for this Android...) is remembered as blocked: it is
 *       explained once and not downloaded again.</li>
 *   <li>Installing:
 *     <ul>
 *       <li>Silently (no question) when Android allows it — Android 12+, "install unknown apps" allowed for
 *           this app, and the new APK's targetSdk high enough for this Android version — once the app has
 *           been left alone for a while (no screen visible, no player at all): a JobScheduler job (it
 *           also runs in a frozen background process) writes a PackageInstaller session and commits it
 *           with USER_ACTION_NOT_REQUIRED after checking again. The next start says "업데이트했습니다".</li>
 *       <li>Otherwise, or when Android still wants a confirmation: a dialog on the folder screen opens
 *           Android's installer ("업데이트" → "열기"). The very first time Android also asks to allow
 *           this app as a source.</li>
 *     </ul>
 *   </li>
 * </ol>
 */
public final class UpdateInstaller {
    private UpdateInstaller() {}

    static final String APK_MIME = "application/vnd.android.package-archive";
    static final String ACTION_STATUS = "com.moa.player.UPDATE_STATUS";
    static final String CH_UPDATES = "updates";

    // why a version can never be installed on this device
    static final String KIND_SIGNER = "signer";
    static final String KIND_INCOMPATIBLE = "incompatible";
    static final String KIND_OLD = "old";
    static final String KIND_INVALID = "invalid";

    /** onError message while a download is still running (not a failure). */
    static final String BUSY = "이미 새 버전을 내려받고 있습니다. 잠시만 기다려 주세요";
    private static final long MAX_APK = 200L * 1024 * 1024;
    private static final int MAX_SILENT_TRIES = 2;
    private static final long DAY = 24L * 60 * 60 * 1000;
    /** Left alone this long (screen on) before installing silently: a quick switch away and back is not "left". */
    private static final long IDLE_SCREEN_ON_MS = 60_000;
    private static final long IDLE_SCREEN_OFF_MS = 5_000;
    static final int JOB_ID = 4711;
    /** An install shown or started less than this ago is not followed by another one. */
    private static final long INSTALL_GAP_MS = 10L * 60 * 1000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final Handler UI = new Handler(Looper.getMainLooper());
    private static volatile boolean downloading;
    private static boolean promptedThisRun;

    interface Listener {
        void onReady(String version);

        /** blockedKind: one of the KIND_ constants when this version can never install here, else null. */
        void onError(String message, String blockedKind);
    }

    // ------------------------------------------------------------------ files & state

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "update");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    static File file(Context c, String version) {
        return new File(dir(c), "MoaPlayer-" + version.replaceAll("[^0-9A-Za-z._-]", "_") + ".apk");
    }

    /** Version of the downloaded and checked update, or null (stale downloads are removed). */
    static String readyVersion(Context c) {
        String v = Prefs.str(c, Prefs.UPDATE_READY, "");
        if (v.isEmpty()) return null;
        if (!file(c, v).isFile() || UpdateChecker.compare(v, UpdateChecker.currentVersion(c)) <= 0) {
            clearDownload(c);
            return null;
        }
        return v;
    }

    static void clearDownload(Context c) {
        Prefs.sp(c).edit().remove(Prefs.UPDATE_READY).remove(Prefs.UPDATE_READY_NOTES)
                .remove(Prefs.UPDATE_READY_TARGET).remove(Prefs.UPDATE_READY_AT).apply();
        File[] old = dir(c).listFiles();
        if (old != null) for (File f : old) f.delete();
    }

    static boolean isBlocked(Context c, String version) {
        return version != null && version.equals(Prefs.str(c, Prefs.UPDATE_BLOCKED, ""));
    }

    /**
     * This version can never install here: remember it (no more downloads) and explain it once.
     * dropDownload: also delete the downloaded file (when it is that version's file).
     */
    private static void block(Context c, String version, String kind, boolean dropDownload) {
        Prefs.sp(c).edit().putString(Prefs.UPDATE_BLOCKED, version).putString(Prefs.UPDATE_BLOCKED_KIND, kind)
                .putString(Prefs.UPDATE_ERROR, version).apply();
        if (dropDownload) clearDownload(c);
    }

    private static int silentTries(Context c, String v) {
        String s = Prefs.str(c, Prefs.UPDATE_SILENT_TRIES, "");
        int i = s.lastIndexOf(':');
        if (i <= 0 || !s.substring(0, i).equals(v)) return 0;
        try { return Integer.parseInt(s.substring(i + 1)); } catch (NumberFormatException e) { return 0; }
    }

    private static void setSilentTries(Context c, String v, int n) {
        Prefs.sp(c).edit().putString(Prefs.UPDATE_SILENT_TRIES, v + ":" + n).apply();
    }

    /** Android 12+ and the user allowed this app to install apps. */
    static boolean canInstallSilently(Context c) {
        return Build.VERSION.SDK_INT >= 31 && c.getPackageManager().canRequestPackageInstalls();
    }

    /**
     * Whether Android installs an APK with this targetSdk without asking (documented in
     * PackageInstaller.SessionParams#setRequireUserAction; the bar rises with each Android version).
     */
    static boolean targetAllowsSilent(int targetSdk) {
        int s = Build.VERSION.SDK_INT;
        int need;
        if (s < 31) return false;
        else if (s <= 32) need = 29;
        else if (s == 33) need = 30;
        else if (s == 34) need = 31;
        else if (s == 35) need = 33;
        else if (s == 36) need = 34;
        else if (s == 37) need = 35;
        else return false; // a newer Android with an unknown bar: use the dialog
        return targetSdk >= need;
    }

    private static boolean installsByItself(Context c, String v) {
        return Prefs.bool(c, Prefs.AUTO_UPDATE, true) && canInstallSilently(c)
                && targetAllowsSilent(Prefs.rawInt(c, Prefs.UPDATE_READY_TARGET, 0))
                && silentTries(c, v) < MAX_SILENT_TRIES;
    }

    // ------------------------------------------------------------------ download

    /** Downloads and checks the APK of a release in the background; the listener runs on the main thread. */
    static void fetch(Context ctx, final UpdateChecker.Release r, final Listener l) {
        final Context c = ctx.getApplicationContext();
        if (r.version.equals(readyVersion(c))) {
            UI.post(() -> l.onReady(r.version));
            return;
        }
        if (isBlocked(c, r.version)) {
            final String kind = Prefs.str(c, Prefs.UPDATE_BLOCKED_KIND, KIND_INVALID);
            UI.post(() -> l.onError(null, kind));
            return;
        }
        if (r.apkUrl == null) {
            UI.post(() -> l.onError("이 버전에는 APK가 없습니다", null));
            return;
        }
        if (downloading) {
            UI.post(() -> l.onError(BUSY, null));
            return;
        }
        downloading = true;
        IO.execute(() -> {
            String err = null, kind = null;
            File part = new File(dir(c), "download-tmp.apk");
            try {
                download(r.apkUrl, part);
                if (r.apkSize > 0 && part.length() != r.apkSize) throw new IOException("파일 크기가 맞지 않습니다");
                if (r.sha256 != null && !r.sha256.equalsIgnoreCase(sha256(part))) {
                    throw new IOException("내려받은 파일이 손상되었습니다");
                }
                Verdict vd = check(c, part, r.sha256 != null);
                if (vd.problem != null) {
                    kind = vd.kind;
                    throw new IOException(vd.problem);
                }
                File out = file(c, r.version);
                File[] old = dir(c).listFiles();
                if (old != null) for (File f : old) if (!f.equals(part)) f.delete();
                if (!part.renameTo(out)) throw new IOException("파일을 저장할 수 없습니다");
                Prefs.sp(c).edit()
                        .putString(Prefs.UPDATE_READY, r.version)
                        .putString(Prefs.UPDATE_READY_NOTES, r.notes != null ? r.notes : "")
                        .putInt(Prefs.UPDATE_READY_TARGET, vd.targetSdk)
                        .putLong(Prefs.UPDATE_READY_AT, System.currentTimeMillis())
                        .apply();
            } catch (Exception e) {
                part.delete();
                err = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
            if (kind != null) block(c, r.version, kind, false); // an older download waiting may still install
            downloading = false;
            final String fe = err, fk = kind;
            UI.post(() -> {
                if (fe == null) l.onReady(r.version);
                else l.onError(fe, fk);
            });
        });
    }

    private static void download(String url, File out) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true); // github.com -> release asset host (both https)
            c.setRequestProperty("Accept", "application/octet-stream");
            c.setRequestProperty("User-Agent", "MoaPlayer-Android");
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            long total = 0;
            try (InputStream in = new BufferedInputStream(c.getInputStream()); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    total += n;
                    if (total > MAX_APK) throw new IOException("파일이 너무 큽니다");
                }
            }
        } finally {
            c.disconnect();
        }
    }

    static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    private static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) s.append(String.format(Locale.ROOT, "%02x", x));
        return s.toString();
    }

    // ------------------------------------------------------------------ checks

    static final class Verdict {
        String problem; // null: may replace this app
        String kind;    // KIND_*: can never install here (null: try again later)
        int targetSdk;
    }

    /** digestOk: the file matched the SHA-256 that GitHub lists, so it arrived intact. */
    @SuppressWarnings("deprecation")
    static Verdict check(Context c, File apk, boolean digestOk) {
        Verdict v = new Verdict();
        PackageManager pm = c.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo n = pm.getPackageArchiveInfo(apk.getPath(), flags);
        PackageInfo cur;
        try {
            cur = pm.getPackageInfo(c.getPackageName(), flags);
        } catch (PackageManager.NameNotFoundException e) {
            v.problem = "설치 정보를 읽을 수 없습니다";
            return v;
        }
        if (n == null) {
            // Android cannot even read it: an intact file then needs a newer Android (minSdk)
            v.problem = digestOk ? "이 기기의 Android 버전에서는 열 수 없는 설치 파일입니다" : "설치 파일을 읽을 수 없습니다";
            v.kind = digestOk ? KIND_INCOMPATIBLE : KIND_INVALID;
            return v;
        }
        if (!c.getPackageName().equals(n.packageName)) {
            v.problem = "올바른 모아 플레이어 APK가 아닙니다";
            v.kind = KIND_INVALID;
            return v;
        }
        ApplicationInfo ai = n.applicationInfo;
        if (ai != null && Build.VERSION.SDK_INT >= 24 && ai.minSdkVersion > Build.VERSION.SDK_INT) {
            v.problem = "이 기기의 Android 버전에서는 설치할 수 없는 버전입니다";
            v.kind = KIND_INCOMPATIBLE;
            return v;
        }
        // Android takes an equal versionCode too (1.4.1-beta.1 -> 1.4.1 share 10401), never a lower one
        long nv = versionCode(n), cv = versionCode(cur);
        if (nv < cv || (nv == cv && String.valueOf(n.versionName).equals(cur.versionName))) {
            v.problem = "지금 버전보다 새 버전이 아닙니다";
            v.kind = KIND_OLD;
            return v;
        }
        // (if a certificate cannot be read here, Android's installer still refuses a foreign one)
        Set<String> a = signers(n), b = signers(cur);
        if (!a.isEmpty() && !b.isEmpty() && !a.equals(b)) {
            v.problem = "지금 설치된 앱과 서명이 달라 그 위에 설치할 수 없습니다";
            v.kind = KIND_SIGNER;
            return v;
        }
        v.targetSdk = ai != null ? ai.targetSdkVersion : 0;
        return v;
    }

    @SuppressWarnings("deprecation")
    private static long versionCode(PackageInfo p) {
        return Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode;
    }

    @SuppressWarnings("deprecation")
    private static Set<String> signers(PackageInfo p) {
        Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28) {
            SigningInfo si = p.signingInfo;
            if (si != null) sigs = si.getApkContentsSigners();
        } else {
            sigs = p.signatures;
        }
        Set<String> out = new HashSet<>();
        if (sigs == null) return out;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Signature s : sigs) out.add(hex(md.digest(s.toByteArray())));
        } catch (Exception ignored) {
        }
        return out;
    }

    // ------------------------------------------------------------------ installing

    /** Opens Android's installer for the downloaded update ("업데이트", then "열기"). */
    static boolean installInteractive(Activity a) {
        String v = readyVersion(a);
        if (v == null) {
            Util.toast(a, "내려받은 업데이트가 없습니다");
            return false;
        }
        Uri uri = UpdateFileProvider.uriFor(a, file(a, v));
        // written at once: Android stops this app while it replaces it
        Prefs.sp(a).edit().putString(Prefs.UPDATE_INSTALLING, v).putBoolean(Prefs.UPDATE_SILENT, false)
                .putLong(Prefs.UPDATE_INSTALL_AT, System.currentTimeMillis()).commit();
        @SuppressWarnings("deprecation")
        Intent i = new Intent(Intent.ACTION_INSTALL_PACKAGE).setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            a.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            try {
                a.startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
                return true;
            } catch (Exception e2) {
                Util.toast(a, "설치 화면을 열 수 없습니다");
            }
        } catch (Exception e) {
            Util.toast(a, "설치 화면을 열 수 없습니다");
        }
        return false;
    }

    /**
     * Nothing of the app is in use: no screen visible, no background playback and no player at all.
     * (A paused player left behind with Home or a turned-off screen counts as in use: updating would
     * close that video. Such a device gets the dialog once the update has waited a day.)
     */
    private static boolean idleNow(Context c) {
        return App.visible == 0 && !PlaybackService.isRunning() && PlayerEngine.get(c).player() == null;
    }

    /**
     * Replaces the app without asking. The APK is written into the session in the background, and
     * the session is committed on the main thread only if the app is still unused at that moment.
     */
    private static void installSilently(Context ctx, final Runnable done) {
        final Context c = ctx.getApplicationContext();
        final String v = readyVersion(c);
        if (v == null || Build.VERSION.SDK_INT < 31) {
            done.run();
            return;
        }
        IO.execute(() -> {
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            int id = -1;
            PackageInstaller.Session s = null;
            try {
                File f = file(c, v);
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(c.getPackageName());
                sp.setSize(f.length());
                sp.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
                id = pi.createSession(sp);
                s = pi.openSession(id);
                try (InputStream in = new FileInputStream(f); OutputStream out = s.openWrite("base.apk", 0, f.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    s.fsync(out);
                }
            } catch (Exception e) {
                if (s != null) s.close();
                if (id >= 0) {
                    try { pi.abandonSession(id); } catch (Exception ignored) { }
                }
                UI.post(done);
                return;
            }
            final PackageInstaller.Session session = s;
            final int sessionId = id;
            UI.post(() -> {
                try {
                    if (!idleNow(c)) {
                        session.abandon(); // came back in the meantime: next time
                        return;
                    }
                    // written at once: Android stops this app while it replaces it
                    Prefs.sp(c).edit().putString(Prefs.UPDATE_SILENT_TRIES, v + ":" + (silentTries(c, v) + 1))
                            .putString(Prefs.UPDATE_INSTALLING, v).putBoolean(Prefs.UPDATE_SILENT, true)
                            .putLong(Prefs.UPDATE_INSTALL_AT, System.currentTimeMillis()).commit();
                    Intent i = new Intent(c, UpdateReceiver.class).setAction(ACTION_STATUS).setPackage(c.getPackageName());
                    PendingIntent p = PendingIntent.getBroadcast(c, sessionId, i,
                            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                    session.commit(p.getIntentSender());
                } catch (Exception e) {
                    try { session.abandon(); } catch (Exception ignored) { }
                } finally {
                    session.close();
                    done.run();
                }
            });
        });
    }

    /** Result of a silent install (from {@link UpdateReceiver}). */
    static void onStatus(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        int session = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        if (status == PackageInstaller.STATUS_SUCCESS) {
            onReplaced(c);
            return;
        }
        String v = Prefs.str(c, Prefs.UPDATE_INSTALLING, "");
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // Android wants a confirmation after all: nobody is looking now, so leave it to the
            // dialog on the next start instead of popping up the installer out of nowhere
            if (session >= 0) {
                try { c.getPackageManager().getPackageInstaller().abandonSession(session); } catch (Exception ignored) { }
            }
            if (!v.isEmpty()) setSilentTries(c, v, MAX_SILENT_TRIES);
        } else if (!v.isEmpty()) {
            if (status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                block(c, v, KIND_INCOMPATIBLE, true);
            } else if (status == PackageInstaller.STATUS_FAILURE_INVALID) {
                clearDownload(c); // e.g. a damaged copy: download it afresh at the next check
            } else if (status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                // the signer was checked already: let Android's own installer show what clashes
                setSilentTries(c, v, MAX_SILENT_TRIES);
            }
            // other failures (storage, aborted...) count as a try; after that the dialog takes over
        }
        Prefs.sp(c).edit().remove(Prefs.UPDATE_INSTALLING).apply();
    }

    /** The app was replaced by a new version (runs in the new version). */
    static void onReplaced(Context c) {
        String inst = Prefs.str(c, Prefs.UPDATE_INSTALLING, "");
        String cur = UpdateChecker.currentVersion(c);
        boolean silent = Prefs.bool(c, Prefs.UPDATE_SILENT, false);
        if (inst.isEmpty() || UpdateChecker.compare(cur, inst) < 0) return;
        Prefs.sp(c).edit()
                .remove(Prefs.UPDATE_INSTALLING).remove(Prefs.UPDATE_SILENT).remove(Prefs.UPDATE_SILENT_TRIES)
                .remove(Prefs.UPDATE_ERROR).remove(Prefs.UPDATE_LATER_AT)
                .putString(Prefs.UPDATE_DONE, cur)
                .apply();
        clearDownload(c);
        if (silent) notifyUpdated(c, cur);
    }

    private static void notifyUpdated(Context c, String version) {
        if (Build.VERSION.SDK_INT >= 33
                && c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, 7, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, CH_UPDATES) : new Notification.Builder(c);
        b.setSmallIcon(R.drawable.ic_play_circle)
                .setContentTitle("모아 플레이어 업데이트 완료")
                .setContentText(version + " 버전으로 업데이트했습니다")
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { nm.notify(31, b.build()); } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------------ when to install

    /**
     * Runs as {@link UpdateJobService} (main thread). Returns true while installing; done is then
     * called once the session is committed or given up.
     */
    static boolean runSilentJob(Context ctx, Runnable done) {
        Context c = ctx.getApplicationContext();
        if (!idleNow(c)) return false;
        if (Math.abs(System.currentTimeMillis() - Prefs.sp(c).getLong(Prefs.UPDATE_INSTALL_AT, 0)) < INSTALL_GAP_MS) return false;
        String v = readyVersion(c);
        if (v == null || v.equals(Prefs.str(c, Prefs.UPDATE_SKIPPED, "")) || !installsByItself(c, v)) return false;
        installSilently(c, done);
        return true;
    }

    /**
     * No screen of the app is visible any more: a ready update may install by itself after a while.
     * A job instead of a timer: Android freezes cached background processes, a job still runs.
     */
    static void onAppBackground(Context c) {
        String v = readyVersion(c);
        if (v == null || !installsByItself(c, v)) return;
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        boolean screenOn = pm == null || pm.isInteractive();
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js == null) return;
            js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, UpdateJobService.class))
                    .setMinimumLatency(screenOn ? IDLE_SCREEN_ON_MS : IDLE_SCREEN_OFF_MS)
                    .build());
        } catch (Exception ignored) {
        }
    }

    /** A screen of the app is visible again: no silent install now. */
    static void onAppForeground(Context c) {
        try {
            JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
            if (js != null) js.cancel(JOB_ID);
        } catch (Exception ignored) {
        }
    }

    /** Folder screen shown: report a finished update, explain a blocked one, or offer a ready one. */
    static void onForeground(Activity a) {
        String done = Prefs.str(a, Prefs.UPDATE_DONE, "");
        String inst = Prefs.str(a, Prefs.UPDATE_INSTALLING, "");
        String cur = UpdateChecker.currentVersion(a);
        if (done.isEmpty() && !inst.isEmpty() && UpdateChecker.compare(cur, inst) >= 0) done = cur; // missed the broadcast
        if (!done.isEmpty()) {
            Prefs.sp(a).edit().remove(Prefs.UPDATE_DONE).remove(Prefs.UPDATE_INSTALLING).remove(Prefs.UPDATE_SILENT)
                    .remove(Prefs.UPDATE_SILENT_TRIES).remove(Prefs.UPDATE_ERROR).remove(Prefs.UPDATE_LATER_AT).apply();
            clearDownload(a);
            Util.toast(a, "모아 플레이어 " + cur + " 버전으로 업데이트했습니다");
            return;
        }
        String err = Prefs.str(a, Prefs.UPDATE_ERROR, "");
        if (!err.isEmpty()) {
            Prefs.sp(a).edit().remove(Prefs.UPDATE_ERROR).apply(); // once per version
            explain(a, err, Prefs.str(a, Prefs.UPDATE_BLOCKED_KIND, KIND_INVALID));
            return;
        }
        maybePrompt(a, false);
    }

    /** A downloaded update is waiting: tell (toast) or ask (dialog). Unasked: at most once per start. */
    static void maybePrompt(Activity a, boolean manual) {
        String v = readyVersion(a);
        if (v == null || a.isFinishing()) return;
        if (manual) {
            showReadyDialog(a, v);
            return;
        }
        if (promptedThisRun || v.equals(Prefs.str(a, Prefs.UPDATE_SKIPPED, ""))) return;
        long now = System.currentTimeMillis();
        boolean waitedLong = Math.abs(now - Prefs.sp(a).getLong(Prefs.UPDATE_READY_AT, now)) > DAY;
        if (installsByItself(a, v) && !waitedLong) {
            promptedThisRun = true;
            Util.toast(a, "새 버전 " + v + "을(를) 받아 두었습니다. 앱을 쓰지 않을 때 자동으로 설치됩니다");
            return;
        }
        if (Math.abs(now - Prefs.sp(a).getLong(Prefs.UPDATE_LATER_AT, 0)) < DAY) return; // "나중에" today
        promptedThisRun = true;
        showReadyDialog(a, v);
    }

    static void showReadyDialog(final Activity a, final String v) {
        StringBuilder m = new StringBuilder();
        m.append("새 버전 ").append(v).append("을(를) 내려받아 두었습니다. 지금 설치할까요? (지금 버전: ")
                .append(UpdateChecker.currentVersion(a)).append(")");
        String notes = Prefs.str(a, Prefs.UPDATE_READY_NOTES, "");
        if (!notes.isEmpty()) m.append("\n\n").append(notes);
        m.append("\n\n설치해도 설정과 재생 기록은 그대로 남습니다.");
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            m.append("\n\n처음 한 번은 Android가 '이 출처 허용'을 물어봅니다.");
            if (targetAllowsSilent(Prefs.rawInt(a, Prefs.UPDATE_READY_TARGET, 0))) {
                m.append(" 허용해 두면 다음부터는 앱을 쓰지 않을 때 자동으로 설치됩니다.");
            }
        }
        new AlertDialog.Builder(a)
                .setTitle("업데이트 " + v)
                .setMessage(m.toString())
                .setPositiveButton("지금 설치", (d, w) -> installInteractive(a))
                .setNegativeButton("나중에", (d, w) -> later(a))
                .setOnCancelListener(d -> later(a))
                .setNeutralButton("이 버전 건너뛰기", (d, w) ->
                        Prefs.sp(a).edit().putString(Prefs.UPDATE_SKIPPED, v).apply())
                .show();
    }

    private static void later(Context c) {
        Prefs.sp(c).edit().putLong(Prefs.UPDATE_LATER_AT, System.currentTimeMillis()).apply();
    }

    /** Why a version cannot be installed here (kind = KIND_*). */
    static void explain(final Activity a, String version, String kind) {
        if (a.isFinishing() || (Build.VERSION.SDK_INT >= 17 && a.isDestroyed())) return;
        String msg;
        boolean offerGithub = false;
        if (KIND_SIGNER.equals(kind)) {
            msg = "새 버전 " + version + "은(는) 지금 설치된 앱과 서명(만든 곳의 인증서)이 달라서 그 위에 설치할 수 없습니다. "
                    + "브라우저로 받아도 마찬가지입니다. 앱을 삭제한 뒤 GitHub에서 새로 내려받아 설치해 주세요. "
                    + "(삭제하면 재생 기록과 설정도 지워집니다)";
            offerGithub = true;
        } else if (KIND_INCOMPATIBLE.equals(kind)) {
            msg = "새 버전 " + version + "은(는) 이 기기(Android 버전 또는 기기 종류)에 설치할 수 없습니다. 지금 버전을 그대로 쓰시면 됩니다.";
        } else if (KIND_OLD.equals(kind)) {
            msg = "새 버전 " + version + "의 내부 버전 번호가 지금 앱과 같거나 낮아서 업데이트로 설치할 수 없습니다.";
        } else {
            msg = "새 버전 " + version + "의 파일이 올바른 모아 플레이어 설치 파일이 아니어서 설치하지 않았습니다.";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle("업데이트를 설치할 수 없음")
                .setMessage(msg)
                .setNegativeButton("닫기", null);
        if (offerGithub) {
            b.setPositiveButton("GitHub 열기", (d, w) -> {
                try {
                    a.startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/" + a.getString(R.string.update_repo) + "/releases/latest")));
                } catch (Exception ignored) {
                }
            });
        }
        b.show();
    }
}
