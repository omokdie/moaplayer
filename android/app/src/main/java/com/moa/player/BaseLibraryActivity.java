package com.moa.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.Configuration;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toolbar;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared toolbar, list, sorting and file actions for the two library screens. */
public abstract class BaseLibraryActivity extends Activity {
    protected static final int REQ_DELETE = 41;
    protected static final int REQ_WRITE = 42;
    protected static final int REQ_DELETE_FOLDER = 43;

    protected Toolbar toolbar;
    protected GridView grid;
    protected View empty;
    protected TextView emptyText;
    protected Button emptyButton;
    protected ProgressBar progress;
    protected ImageButton fab;
    protected VideoAdapter videoAdapter;
    protected Map<String, HistoryDb.Entry> history = new HashMap<>();

    private VideoItem pendingDelete;
    private VideoItem pendingRename;
    private String pendingRenameName;
    private FolderItem pendingFolderDelete;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_list);
        toolbar = findViewById(R.id.toolbar);
        setActionBar(toolbar);
        grid = findViewById(R.id.grid);
        empty = findViewById(R.id.empty);
        emptyText = findViewById(R.id.empty_text);
        emptyButton = findViewById(R.id.empty_button);
        progress = findViewById(R.id.progress);
        fab = findViewById(R.id.fab);
        videoAdapter = new VideoAdapter(this);
        videoAdapter.setThumbs(Prefs.bool(this, Prefs.THUMBS, true));
    }

    @Override
    protected void onResume() {
        super.onResume();
        videoAdapter.setThumbs(Prefs.bool(this, Prefs.THUMBS, true));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyColumns();
    }

    protected abstract void reload();

    protected abstract boolean showingVideoGrid();

    protected void applyColumns() {
        if (showingVideoGrid()) {
            int wdp = getResources().getConfiguration().screenWidthDp;
            int cols = Math.max(2, wdp / 180);
            grid.setNumColumns(cols);
            int pad = Util.dp(this, 6);
            grid.setPadding(pad, pad, pad, Util.dp(this, 96));
        } else {
            grid.setNumColumns(1);
            grid.setPadding(0, 0, 0, Util.dp(this, 96));
        }
    }

    protected String sortBy() {
        return Prefs.str(this, Prefs.SORT, "name");
    }

    protected boolean sortDesc() {
        return Prefs.bool(this, Prefs.SORT_DESC, false);
    }

    protected void showSortDialog() {
        final String[] keys = {"name", "date", "size", "duration"};
        final String[] labels = {"이름", "날짜", "크기", "재생 시간"};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Util.dp(this, 20);
        box.setPadding(p, Util.dp(this, 8), p, 0);
        final RadioGroup rg = new RadioGroup(this);
        String cur = sortBy();
        for (int i = 0; i < keys.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setText(labels[i]);
            rb.setId(1000 + i);
            rb.setTextSize(16);
            rg.addView(rb);
            if (keys[i].equals(cur)) rb.setChecked(true);
        }
        box.addView(rg);
        final CheckBox desc = new CheckBox(this);
        desc.setText("내림차순 (큰 것 / 최근 것 먼저)");
        desc.setChecked(sortDesc());
        desc.setPadding(0, Util.dp(this, 8), 0, 0);
        box.addView(desc);
        new AlertDialog.Builder(this)
                .setTitle("정렬")
                .setView(box)
                .setPositiveButton("확인", (d, w) -> {
                    int id = rg.getCheckedRadioButtonId();
                    int idx = id >= 1000 ? id - 1000 : 0;
                    Prefs.put(this, Prefs.SORT, keys[Util.clamp(idx, 0, keys.length - 1)]);
                    Prefs.put(this, Prefs.SORT_DESC, desc.isChecked());
                    reload();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    protected void showNetworkDialog() {
        final EditText et = new EditText(this);
        et.setHint("https://example.com/video.mp4  또는 .m3u8");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        int p = Util.dp(this, 20);
        box.setPadding(p, Util.dp(this, 8), p, 0);
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("네트워크 스트림 열기")
                .setView(box)
                .setPositiveButton("재생", (d, w) -> {
                    String url = et.getText().toString().trim();
                    if (url.isEmpty()) return;
                    if (!url.contains("://")) url = "http://" + url;
                    PlayerActivity.playUri(this, Uri.parse(url));
                })
                .setNegativeButton("취소", null)
                .show();
    }

    protected void refreshHistory() {
        history = HistoryDb.get(this).all();
        videoAdapter.setHistory(history);
    }

    /** Long-press menu for a video. */
    protected void showVideoActions(final VideoItem v, final List<VideoItem> list, final int index) {
        final String[] items = {"재생", "처음부터 재생", "정보", "공유", "이름 변경", "삭제"};
        new AlertDialog.Builder(this)
                .setTitle(v.title())
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: PlayerActivity.play(this, list, index, false); break;
                        case 1: PlayerActivity.play(this, list, index, true); break;
                        case 2: showInfo(v); break;
                        case 3: share(v); break;
                        case 4: askRename(v); break;
                        case 5: askDelete(v); break;
                        default: break;
                    }
                })
                .show();
    }

    // ------------------------------------------------------------------ folders (rename / delete)

    /** Long-press menu for a folder in the folder list. */
    protected void showFolderActions(final FolderItem f) {
        final String[] items = {"열기", "전체 재생", "이름 변경", "삭제"};
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: openFolder(f); break;
                        case 1: playFolder(f); break;
                        case 2: askRenameFolder(f); break;
                        case 3: askDeleteFolder(f); break;
                        default: break;
                    }
                })
                .show();
    }

    protected void openFolder(FolderItem f) {
        Intent i = new Intent(this, VideoListActivity.class);
        i.putExtra(VideoListActivity.EXTRA_FOLDER, f.path);
        i.putExtra(VideoListActivity.EXTRA_NAME, f.name);
        startActivity(i);
    }

    private void playFolder(FolderItem f) {
        if (f.videos.isEmpty()) return;
        List<VideoItem> list = new ArrayList<>(f.videos);
        MediaRepo.sortVideos(list, Prefs.str(this, Prefs.SORT, "name"), Prefs.bool(this, Prefs.SORT_DESC, false));
        PlayerActivity.play(this, list, 0, false);
    }

    /** Real directory behind a folder entry (null when MediaStore only gave a relative path). */
    private static File folderDir(FolderItem f) {
        if (f.path == null || !f.path.startsWith("/")) return null;
        File d = new File(f.path);
        return d.isDirectory() ? d : null;
    }

    /** Storage roots, the standard folders right under them (DCIM, Download, Movies…) and Android/ are left alone. */
    private static boolean isProtectedDir(File dir) {
        String p = dir.getAbsolutePath();
        File root = Environment.getExternalStorageDirectory();
        if (p.equals(root.getAbsolutePath()) || p.equals("/storage") || p.equals("/storage/emulated")) return true;
        if (p.contains("/Android/data") || p.contains("/Android/obb") || p.endsWith("/Android")) return true;
        File parent = dir.getParentFile();
        if (parent == null) return true;
        boolean underRoot = parent.getAbsolutePath().equals(root.getAbsolutePath())
                || parent.getAbsolutePath().matches("/storage/[^/]+");           // SD card root
        if (!underRoot) return false;
        String[] std = {"DCIM", "Download", "Downloads", "Movies", "Pictures", "Music", "Documents", "Podcasts",
                "Ringtones", "Alarms", "Notifications", "Audiobooks", "Recordings", "Android"};
        for (String s : std) if (s.equalsIgnoreCase(dir.getName())) return true;
        return false;
    }

    /** Direct file access: Android 11+ needs "all files" access, older versions the storage permission. */
    private boolean canManageFiles() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void askAllFilesAccess(String what) {
        if (Build.VERSION.SDK_INT < 30) {
            Util.toast(this, "저장소 권한이 없어 " + what + " 수 없습니다");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("모든 파일 접근 권한 필요")
                .setMessage("폴더 " + what + " 수 있으려면 '모든 파일 접근' 권한이 필요합니다.\n\n허용한 뒤 다시 시도해 주세요.")
                .setPositiveButton("허용하러 가기", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        try { startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                        catch (Exception ignored) { }
                    }
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void askRenameFolder(final FolderItem f) {
        final File dir = folderDir(f);
        if (dir == null) {
            Util.toast(this, "이 폴더는 이름을 바꿀 수 없습니다");
            return;
        }
        if (isProtectedDir(dir)) {
            Util.toast(this, "기본 폴더(DCIM·Download·Movies 등)는 이름을 바꿀 수 없습니다");
            return;
        }
        if (!canManageFiles()) {
            askAllFilesAccess("이름을 바꿀");
            return;
        }
        final EditText et = new EditText(this);
        et.setText(dir.getName());
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        int p = Util.dp(this, 20);
        box.setPadding(p, Util.dp(this, 8), p, 0);
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("폴더 이름 변경")
                .setView(box)
                .setPositiveButton("변경", (d, w) -> renameFolder(f, dir, et.getText().toString().trim()))
                .setNegativeButton("취소", null)
                .show();
    }

    private void renameFolder(FolderItem f, File dir, String name) {
        if (name.isEmpty() || name.equals(dir.getName())) return;
        if (name.matches(".*[\\\\/:*?\"<>|].*") || name.startsWith(".") || name.length() > 200) {
            Util.toast(this, "폴더 이름에 쓸 수 없는 글자가 있습니다");
            return;
        }
        final File target = new File(dir.getParentFile(), name);
        // shared storage ignores upper/lower case: "drama" -> "Drama" is the same folder there
        final boolean caseOnly = name.equalsIgnoreCase(dir.getName());
        if (target.exists() && !caseOnly) {
            Util.toast(this, "같은 이름의 폴더가 이미 있습니다");
            return;
        }
        final List<String> oldPaths = new ArrayList<>();
        for (VideoItem v : f.videos) if (v.path != null) oldPaths.add(v.path);
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            boolean ok;
            if (caseOnly) {
                File tmp = new File(dir.getParentFile(), dir.getName() + ".moa-rename");
                ok = false;
                if (dir.renameTo(tmp)) {
                    ok = tmp.renameTo(target);
                    //noinspection ResultOfMethodCallIgnored
                    if (!ok) tmp.renameTo(dir);   // put it back
                }
            } else {
                ok = dir.renameTo(target);
            }
            if (ok) {
                String from = dir.getAbsolutePath() + "/", to = target.getAbsolutePath() + "/";
                HistoryDb.get(this).renamePrefix(from, to, target.getName());
                List<String> scan = new ArrayList<>(oldPaths);
                for (String op : oldPaths) if (op.startsWith(from)) scan.add(to + op.substring(from.length()));
                rescan(scan);
            }
            final boolean done = ok;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                Util.toast(this, done ? "폴더 이름을 바꿨습니다" : "폴더 이름을 바꾸지 못했습니다");
                if (done) refreshAfterFolderChange();
            });
        }).start();
    }

    private void askDeleteFolder(final FolderItem f) {
        final File dir = folderDir(f);
        final int n = f.videos.size();
        if (dir == null || !canManageFiles()) {
            // no direct file access: let the system delete the videos (it asks for confirmation itself)
            if (Build.VERSION.SDK_INT >= 30) {
                List<Uri> uris = new ArrayList<>();
                for (VideoItem v : f.videos) if (v.uri != null) uris.add(v.uri);
                if (uris.isEmpty()) return;
                pendingFolderDelete = f;
                try {
                    PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                    startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE_FOLDER, null, 0, 0, 0);
                } catch (Exception e) {
                    pendingFolderDelete = null;
                    Util.toast(this, "삭제할 수 없습니다");
                }
            } else {
                Util.toast(this, "저장소 권한이 없어 삭제할 수 없습니다");
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("폴더 삭제")
                .setMessage("'" + f.name + "' 폴더의 동영상 " + n + "개를 삭제할까요?\n\n같은 이름의 자막 파일도 함께 지우고, 폴더에 다른 파일이 없으면 폴더도 지웁니다. 삭제한 파일은 되살릴 수 없습니다.")
                .setPositiveButton("삭제", (d, w) -> deleteFolderFiles(f, dir))
                .setNegativeButton("취소", null)
                .show();
    }

    private void deleteFolderFiles(final FolderItem f, final File dir) {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            List<String> gone = new ArrayList<>();
            int failed = 0;
            List<String> bases = new ArrayList<>();
            for (VideoItem v : f.videos) {
                File file = v.path != null ? new File(v.path) : new File(dir, v.name);
                bases.add(Util.stripExt(file.getName()).toLowerCase(java.util.Locale.ROOT));
                if (!file.exists() || file.delete()) {
                    gone.add(file.getAbsolutePath());
                    HistoryDb.get(this).remove(v.key());
                } else {
                    failed++;
                }
            }
            File[] rest = dir.listFiles();
            if (rest != null) {
                for (File x : rest) {
                    String nm = x.getName().toLowerCase(java.util.Locale.ROOT);
                    if (!x.isFile() || !SubtitleParser.isSubtitleFile(x.getName())) continue;
                    for (String b : bases) {
                        if (nm.startsWith(b + ".")) {
                            if (x.delete()) gone.add(x.getAbsolutePath());
                            break;
                        }
                    }
                }
            }
            boolean folderGone = false;
            if (!isProtectedDir(dir)) {
                File[] left = dir.listFiles();
                boolean onlyJunk = true;
                if (left != null) {
                    for (File x : left) {
                        if (x.isFile() && (x.getName().equals(".nomedia") || x.getName().equalsIgnoreCase("thumbs.db"))) continue;
                        onlyJunk = false;
                        break;
                    }
                }
                if (onlyJunk) {
                    if (left != null) for (File x : left) //noinspection ResultOfMethodCallIgnored
                        x.delete();
                    folderGone = dir.delete();
                }
            }
            rescan(gone);
            final int bad = failed;
            final boolean removed = folderGone;
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (bad > 0) Util.toast(this, (f.videos.size() - bad) + "개를 삭제했습니다. " + bad + "개는 지우지 못했습니다");
                else Util.toast(this, removed ? "폴더를 삭제했습니다" : "동영상을 삭제했습니다 (폴더에 다른 파일이 있어 폴더는 남겼습니다)");
                refreshAfterFolderChange();
            });
        }).start();
    }

    /** Tells MediaStore about moved / deleted files so the lists are right straight away. */
    private void rescan(List<String> paths) {
        if (paths.isEmpty()) return;
        MediaScannerConnection.scanFile(getApplicationContext(), paths.toArray(new String[0]), null, null);
    }

    private void refreshAfterFolderChange() {
        refreshHistory();
        // MediaStore needs a moment to apply the scan; reload now and once more shortly after
        reload();
        grid.postDelayed(this::reload, 1500);
    }

    private void showInfo(VideoItem v) {
        StringBuilder sb = new StringBuilder();
        sb.append("파일: ").append(v.name).append('\n');
        if (v.path != null) sb.append("위치: ").append(new File(v.path).getParent()).append('\n');
        else if (v.folderPath != null) sb.append("위치: ").append(v.folderPath).append('\n');
        sb.append("크기: ").append(Util.size(v.size)).append('\n');
        if (v.duration > 0) sb.append("재생 시간: ").append(Util.time(v.duration)).append('\n');
        if (v.width > 0) sb.append("해상도: ").append(v.width).append(" × ").append(v.height).append('\n');
        if (v.mime != null) sb.append("형식: ").append(v.mime).append('\n');
        long t = Math.max(v.dateModified, v.dateAdded);
        if (t > 0) sb.append("수정한 날짜: ").append(DateFormat.getDateTimeInstance().format(new Date(t * 1000))).append('\n');
        HistoryDb.Entry e = history.get(v.key());
        if (e != null) sb.append("마지막 재생 위치: ").append(Util.time(e.position)).append('\n');
        new AlertDialog.Builder(this).setTitle("정보").setMessage(sb.toString().trim())
                .setPositiveButton("확인", null).show();
    }

    private void share(VideoItem v) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(v.mime != null ? v.mime : "video/*");
        i.putExtra(Intent.EXTRA_STREAM, v.uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "공유"));
        } catch (Exception e) {
            Util.toast(this, "공유할 수 있는 앱이 없습니다");
        }
    }

    private boolean canWriteFiles() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return true;
    }

    private void askRename(final VideoItem v) {
        final EditText et = new EditText(this);
        et.setText(Util.stripExt(v.name));
        et.setSingleLine(true);
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        int p = Util.dp(this, 20);
        box.setPadding(p, Util.dp(this, 8), p, 0);
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("이름 변경")
                .setView(box)
                .setPositiveButton("변경", (d, w) -> {
                    String n = et.getText().toString().trim().replaceAll("[\\\\/:*?\"<>|]", "_");
                    if (n.isEmpty()) return;
                    String ext = Util.ext(v.name);
                    rename(v, ext.isEmpty() ? n : n + "." + ext);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private void rename(final VideoItem v, final String newName) {
        if (v.path != null && canWriteFiles()) {
            File old = new File(v.path);
            File nf = new File(old.getParentFile(), newName);
            if (nf.exists()) {
                Util.toast(this, "같은 이름의 파일이 이미 있습니다");
                return;
            }
            if (old.renameTo(nf)) {
                renameSidecars(old, nf);
                HistoryDb.get(this).remove(v.key());
                MediaScannerConnection.scanFile(getApplicationContext(),
                        new String[]{old.getAbsolutePath(), nf.getAbsolutePath()}, null,
                        (path, uri) -> runOnUiThread(this::reload));
                Util.toast(this, "이름을 변경했습니다");
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            pendingRename = v;
            pendingRenameName = newName;
            try {
                PendingIntent pi = MediaStore.createWriteRequest(getContentResolver(), Collections.singletonList(v.uri));
                startIntentSenderForResult(pi.getIntentSender(), REQ_WRITE, null, 0, 0, 0);
            } catch (Exception e) {
                Util.toast(this, "이름을 변경할 수 없습니다");
            }
            return;
        }
        if (updateDisplayName(v, newName)) {
            Util.toast(this, "이름을 변경했습니다");
            reload();
        } else {
            Util.toast(this, "이름을 변경할 수 없습니다");
        }
    }

    private static void renameSidecars(File oldVideo, File newVideo) {
        File dir = oldVideo.getParentFile();
        if (dir == null) return;
        String ob = Util.stripExt(oldVideo.getName()), nb = Util.stripExt(newVideo.getName());
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            if (!n.startsWith(ob + ".")) continue;
            if (!SubtitleParser.isSubtitleFile(n)) continue;
            //noinspection ResultOfMethodCallIgnored
            f.renameTo(new File(dir, nb + n.substring(ob.length())));
        }
    }

    private boolean updateDisplayName(VideoItem v, String newName) {
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Video.Media.DISPLAY_NAME, newName);
            return getContentResolver().update(v.uri, cv, null, null) > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void askDelete(final VideoItem v) {
        if (Build.VERSION.SDK_INT >= 30) {
            pendingDelete = v;
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), Collections.singletonList(v.uri));
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                Util.toast(this, "삭제할 수 없습니다");
            }
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("삭제")
                .setMessage("'" + v.name + "' 파일을 삭제할까요?")
                .setPositiveButton("삭제", (d, w) -> deleteLegacy(v))
                .setNegativeButton("취소", null)
                .show();
    }

    private void deleteLegacy(VideoItem v) {
        boolean ok = false;
        try {
            if (v.path != null) ok = new File(v.path).delete();
            int n = getContentResolver().delete(v.uri, null, null);
            ok = ok || n > 0;
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 29 && e instanceof RecoverableSecurityException) {
                pendingDelete = v;
                try {
                    IntentSender is = ((RecoverableSecurityException) e).getUserAction().getActionIntent().getIntentSender();
                    startIntentSenderForResult(is, REQ_DELETE, null, 0, 0, 0);
                } catch (Exception ignored) {
                }
                return;
            }
        }
        afterDelete(v, ok);
    }

    private void afterDelete(VideoItem v, boolean ok) {
        if (ok) {
            HistoryDb.get(this).remove(v.key());
            Util.toast(this, "삭제했습니다");
            reload();
        } else {
            Util.toast(this, "삭제할 수 없습니다");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE) {
            VideoItem v = pendingDelete;
            pendingDelete = null;
            if (v == null) return;
            if (resultCode == RESULT_OK) {
                if (Build.VERSION.SDK_INT >= 30) {
                    afterDelete(v, true);
                } else {
                    deleteLegacy(v);
                }
            }
        } else if (requestCode == REQ_DELETE_FOLDER) {
            FolderItem f = pendingFolderDelete;
            pendingFolderDelete = null;
            if (f != null && resultCode == RESULT_OK) {
                for (VideoItem v : f.videos) HistoryDb.get(this).remove(v.key());
                Util.toast(this, "동영상을 삭제했습니다");
                refreshAfterFolderChange();
            }
        } else if (requestCode == REQ_WRITE) {
            VideoItem v = pendingRename;
            String n = pendingRenameName;
            pendingRename = null;
            if (v != null && resultCode == RESULT_OK && n != null) {
                if (updateDisplayName(v, n)) {
                    HistoryDb.get(this).remove(v.key());
                    Util.toast(this, "이름을 변경했습니다");
                    reload();
                } else {
                    Util.toast(this, "이름을 변경할 수 없습니다");
                }
            }
        }
    }

    protected void showEmpty(String msg, boolean button) {
        empty.setVisibility(View.VISIBLE);
        emptyText.setText(msg);
        emptyButton.setVisibility(button ? View.VISIBLE : View.GONE);
    }

    protected void hideEmpty() {
        empty.setVisibility(View.GONE);
    }
}
