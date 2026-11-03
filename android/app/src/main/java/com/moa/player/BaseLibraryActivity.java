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
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared toolbar, list, sorting and file actions for the two library screens. */
public abstract class BaseLibraryActivity extends Activity {
    protected static final int REQ_DELETE = 41;
    protected static final int REQ_WRITE = 42;

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
