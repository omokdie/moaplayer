package com.moa.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Menu;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Shared toolbar, list, sorting, multi-select and file actions for the two library screens. */
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

    private List<VideoItem> pendingDelete;
    private VideoItem pendingRename;
    private String pendingRenameName;
    private FolderItem pendingFolderDelete;

    // multi-select (MX Player style)
    private View selHeader, selBar, selRename;
    private TextView selTitle;
    private final Set<String> selected = new LinkedHashSet<>();
    private boolean selecting;

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
        setupSelection();
    }

    @Override
    protected void onResume() {
        super.onResume();
        videoAdapter.setThumbs(Prefs.bool(this, Prefs.THUMBS, true));
        // get the next playback ready while browsing (spare player, codec list)
        PlayerEngine.get(this).warmUp();
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

    /** The history changed (marks): the screens recompute the last played video, badges and buttons. */
    protected void onHistoryChanged() {
    }

    // ------------------------------------------------------------------ multi-select (MX Player style)

    private void setupSelection() {
        selHeader = findViewById(R.id.sel_header);
        selBar = findViewById(R.id.sel_bar);
        selTitle = findViewById(R.id.sel_title);
        selRename = findViewById(R.id.sel_rename);
        findViewById(R.id.sel_close).setOnClickListener(v -> exitSelection());
        findViewById(R.id.sel_play).setOnClickListener(v -> playSelected(false));
        findViewById(R.id.sel_info).setOnClickListener(v -> showSelectionInfo());
        findViewById(R.id.sel_more).setOnClickListener(this::showSelectionMenu);
        findViewById(R.id.sel_all).setOnClickListener(v -> selectAllOrNone());
        findViewById(R.id.sel_move).setOnClickListener(v -> moveOrCopySelected(false));
        findViewById(R.id.sel_copy).setOnClickListener(v -> moveOrCopySelected(true));
        findViewById(R.id.sel_mark).setOnClickListener(v -> showMarkDialog());
        selRename.setOnClickListener(v -> renameSelected());
        findViewById(R.id.sel_delete).setOnClickListener(v -> deleteSelected());
    }

    protected boolean selecting() {
        return selecting;
    }

    /** Selection mode started or ended: the screens hide or show their resume button. */
    protected void onSelectionModeChanged(boolean on) {
    }

    /** Long press on a video: selection mode with that video (or one more while selecting). */
    protected void onVideoLongPress(int pos) {
        if (pos < 0 || pos >= videoAdapter.getCount()) return;
        if (!selecting) {
            selecting = true;
            selected.clear();
            hideKeyboard();
            videoAdapter.setSelection(selected);
            selHeader.setVisibility(View.VISIBLE);
            selBar.setVisibility(View.VISIBLE);
            onSelectionModeChanged(true);
        }
        toggleSelected(videoAdapter.getItem(pos));
    }

    /** A tap while selecting picks or drops the video. False when not selecting (the tap plays). */
    protected boolean onVideoTapWhileSelecting(int pos) {
        if (!selecting) return false;
        if (pos >= 0 && pos < videoAdapter.getCount()) toggleSelected(videoAdapter.getItem(pos));
        return true;
    }

    private void toggleSelected(VideoItem v) {
        String k = v.key();
        if (!selected.remove(k)) selected.add(k);
        if (selected.isEmpty()) {
            exitSelection();
            return;
        }
        videoAdapter.notifyDataSetChanged();
        updateSelectionBar();
    }

    protected void exitSelection() {
        if (!selecting) return;
        selecting = false;
        selected.clear();
        videoAdapter.setSelection(null);
        selHeader.setVisibility(View.GONE);
        selBar.setVisibility(View.GONE);
        onSelectionModeChanged(false);
    }

    /** After the list was rebuilt: drop videos that are gone, update the count. */
    protected void syncSelection() {
        if (!selecting) return;
        Set<String> shown = new HashSet<>();
        for (VideoItem v : videoAdapter.getItems()) shown.add(v.key());
        selected.retainAll(shown);
        if (selected.isEmpty()) exitSelection();
        else updateSelectionBar();
    }

    private void updateSelectionBar() {
        selTitle.setText(selected.size() + " / " + videoAdapter.getCount() + " 선택됨");
        selRename.setAlpha(selected.size() == 1 ? 1f : 0.35f);   // like MX: one file at a time
    }

    /** The selected videos in list order. */
    private List<VideoItem> selectedItems() {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem v : videoAdapter.getItems()) if (selected.contains(v.key())) out.add(v);
        return out;
    }

    private void selectAllOrNone() {
        if (selected.size() >= videoAdapter.getCount()) {
            exitSelection();
            return;
        }
        for (VideoItem v : videoAdapter.getItems()) selected.add(v.key());
        videoAdapter.notifyDataSetChanged();
        updateSelectionBar();
    }

    private void invertSelection() {
        Set<String> next = new LinkedHashSet<>();
        for (VideoItem v : videoAdapter.getItems()) if (!selected.contains(v.key())) next.add(v.key());
        selected.clear();
        selected.addAll(next);
        if (selected.isEmpty()) {
            exitSelection();
            return;
        }
        videoAdapter.notifyDataSetChanged();
        updateSelectionBar();
    }

    @Override
    public void onBackPressed() {
        if (selecting) {
            exitSelection();
            return;
        }
        super.onBackPressed();
    }

    private void hideKeyboard() {
        View f = getCurrentFocus();
        if (f == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
        f.clearFocus();
    }

    private void playSelected(boolean fromStart) {
        List<VideoItem> items = selectedItems();
        if (items.isEmpty()) return;
        exitSelection();
        PlayerActivity.play(this, items, 0, fromStart);
    }

    private void showSelectionMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        Menu m = pm.getMenu();
        m.add(0, 1, 0, "처음부터 재생");
        m.add(0, 2, 0, "공유");
        m.add(0, 3, 0, "선택 반전");
        pm.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: playSelected(true); break;
                case 2: share(selectedItems()); break;
                case 3: invertSelection(); break;
                default: return false;
            }
            return true;
        });
        pm.show();
    }

    private void showSelectionInfo() {
        List<VideoItem> items = selectedItems();
        if (items.isEmpty()) return;
        if (items.size() == 1) {
            showInfo(items.get(0));
            return;
        }
        long size = 0, dur = 0;
        Set<String> dirs = new LinkedHashSet<>();
        for (VideoItem v : items) {
            size += Math.max(0, v.size);
            dur += Math.max(0, v.duration);
            String d = v.path != null ? new File(v.path).getParent() : v.folderPath;
            if (d != null) dirs.add(d);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("선택한 동영상: ").append(items.size()).append("개\n");
        if (size > 0) sb.append("전체 크기: ").append(Util.size(size)).append('\n');
        if (dur > 0) sb.append("전체 재생 시간: ").append(Util.time(dur)).append('\n');
        if (dirs.size() == 1) sb.append("위치: ").append(FolderAdapter.shortPath(dirs.iterator().next()));
        else if (dirs.size() > 1) sb.append("위치: 폴더 ").append(dirs.size()).append("곳");
        new AlertDialog.Builder(this).setTitle("정보").setMessage(sb.toString().trim())
                .setPositiveButton("확인", null).show();
    }

    // ------------------------------------------------------------------ 표시 (mark as)

    private static final String[] MARKS = {"마지막 재생 파일", "새 파일", "완료된 파일", "없음"};

    private void showMarkDialog() {
        final List<VideoItem> items = selectedItems();
        if (items.isEmpty()) return;
        int checked = items.size() == 1 ? currentMark(items.get(0)) : -1;
        new AlertDialog.Builder(this)
                .setTitle("표시")
                .setSingleChoiceItems(MARKS, checked, (d, which) -> {
                    d.dismiss();
                    applyMark(items, which);
                })
                .setNegativeButton("취소", null)
                .show();
    }

    /** Which of {@link #MARKS} a video shows now (-1: partly watched, none of them). */
    private int currentMark(VideoItem v) {
        HistoryDb.Entry e = history.get(v.key());
        if (HistoryDb.isNew(e)) return HistoryDb.MARK_NEW;
        if (v.key().equals(videoAdapter.getLastKey())) return HistoryDb.MARK_LAST;
        if (e.finished()) return HistoryDb.MARK_DONE;
        if (e.position <= 3000) return HistoryDb.MARK_NONE;
        return -1;
    }

    private void applyMark(final List<VideoItem> items, final int kind) {
        exitSelection();
        final Context app = getApplicationContext();
        new Thread(() -> {
            HistoryDb db = HistoryDb.get(app);
            // only one file can be the last played one: the first of the selection
            List<VideoItem> targets = kind == HistoryDb.MARK_LAST ? items.subList(0, 1) : items;
            for (VideoItem v : targets) {
                db.mark(v.key(), uriString(v), v.title(), v.folderPath, v.duration, kind);
            }
            final Map<String, HistoryDb.Entry> h = db.all();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                history = h;
                videoAdapter.setHistory(h);
                onHistoryChanged();
                int n = targets.size();
                String what = n == 1 ? "" : n + "개를 ";
                switch (kind) {
                    case HistoryDb.MARK_LAST: Util.toast(this, "마지막 재생 파일로 표시했습니다: " + targets.get(0).title()); break;
                    case HistoryDb.MARK_NEW: Util.toast(this, what + "새 파일로 표시했습니다"); break;
                    case HistoryDb.MARK_DONE: Util.toast(this, what + "완료된 파일로 표시했습니다"); break;
                    default: Util.toast(this, n == 1 ? "표시를 지웠습니다" : n + "개의 표시를 지웠습니다"); break;
                }
            });
        }, "mark").start();
    }

    private static String uriString(VideoItem v) {
        if (v.uri != null) return v.uri.toString();
        return v.path != null ? Uri.fromFile(new File(v.path)).toString() : null;
    }

    // ------------------------------------------------------------------ rename / delete / move / copy (selection)

    private static boolean allHavePaths(List<VideoItem> items) {
        for (VideoItem v : items) if (v.path == null) return false;
        return true;
    }

    private void renameSelected() {
        List<VideoItem> items = selectedItems();
        if (items.size() != 1) {
            Util.toast(this, "이름 변경은 파일을 하나만 선택했을 때 할 수 있습니다");
            return;
        }
        exitSelection();
        askRename(items.get(0));
    }

    private void deleteSelected() {
        final List<VideoItem> items = selectedItems();
        if (items.isEmpty()) return;
        if (canManageFiles() && allHavePaths(items)) {
            String msg = items.size() == 1 ? "'" + items.get(0).name + "' 파일을 삭제할까요?"
                    : "선택한 동영상 " + items.size() + "개를 삭제할까요?";
            new AlertDialog.Builder(this)
                    .setTitle("삭제")
                    .setMessage(msg + "\n\n같은 이름의 자막 파일도 함께 지웁니다. 삭제한 파일은 되살릴 수 없습니다.")
                    .setPositiveButton("삭제", (d, w) -> deleteFiles(items))
                    .setNegativeButton("취소", null)
                    .show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // no direct file access: Android deletes them and asks for confirmation itself
            List<Uri> uris = new ArrayList<>();
            for (VideoItem v : items) if (v.uri != null) uris.add(v.uri);
            if (uris.isEmpty()) {
                Util.toast(this, "삭제할 수 없습니다");
                return;
            }
            pendingDelete = items;
            try {
                PendingIntent pi = MediaStore.createDeleteRequest(getContentResolver(), uris);
                startIntentSenderForResult(pi.getIntentSender(), REQ_DELETE, null, 0, 0, 0);
            } catch (Exception e) {
                pendingDelete = null;
                Util.toast(this, "삭제할 수 없습니다");
            }
            return;
        }
        Util.toast(this, "저장소 권한이 없어 삭제할 수 없습니다");
    }

    private void deleteFiles(final List<VideoItem> items) {
        exitSelection();
        progress.setVisibility(View.VISIBLE);
        final Context app = getApplicationContext();
        new Thread(() -> {
            final FileOps.DeleteResult r = FileOps.delete(items, app.getContentResolver());
            for (String k : r.keys) HistoryDb.get(app).remove(k);
            rescan(r.gone);
            runOnUiThread(() -> {
                progress.setVisibility(View.GONE);
                if (r.failed > 0) Util.toast(this, r.ok + "개를 삭제했습니다. " + r.failed + "개는 지우지 못했습니다");
                else Util.toast(this, r.ok == 1 ? "삭제했습니다" : r.ok + "개를 삭제했습니다");
                refreshAfterFolderChange();
            });
        }, "delete").start();
    }

    private void moveOrCopySelected(final boolean copy) {
        final List<VideoItem> items = selectedItems();
        if (items.isEmpty()) return;
        if (!canManageFiles()) {
            askAllFilesAccess(copy ? "파일을 복사할" : "파일을 옮길");
            return;
        }
        if (!allHavePaths(items)) {
            Util.toast(this, "위치를 알 수 없는 파일이 있어 " + (copy ? "복사할" : "옮길") + " 수 없습니다");
            return;
        }
        String last = Prefs.str(this, Prefs.LAST_TARGET_DIR, "");
        File start = !last.isEmpty() && new File(last).isDirectory() ? new File(last) : new File(items.get(0).path).getParentFile();
        DirPicker.show(this, copy ? "복사할 위치" : "옮길 위치", copy ? "여기에 복사" : "여기로 이동", start,
                dir -> startTransfer(items, dir, copy));
    }

    private void startTransfer(final List<VideoItem> items, final File dest, final boolean copy) {
        Prefs.put(this, Prefs.LAST_TARGET_DIR, dest.getAbsolutePath());
        long need = FileOps.bytesToCopy(items, dest, copy);
        long free = dest.getUsableSpace();
        if (need > 0 && free < need + (32L << 20)) {
            new AlertDialog.Builder(this)
                    .setTitle("저장 공간 부족")
                    .setMessage("필요한 공간: " + Util.size(need) + "\n남은 공간: " + Util.size(free))
                    .setPositiveButton("확인", null)
                    .show();
            return;
        }
        exitSelection();
        final TransferDialog td = new TransferDialog(copy ? "복사하는 중" : "옮기는 중");
        final Context app = getApplicationContext();
        new Thread(() -> {
            final FileOps.Result r = FileOps.transfer(items, dest, copy, td, app.getContentResolver());
            rescan(r.scan);
            runOnUiThread(() -> {
                td.dismiss();
                reportTransfer(r, copy);
                if (r.ok > 0) refreshAfterFolderChange();
            });
        }, copy ? "copy" : "move").start();
    }

    private void reportTransfer(FileOps.Result r, boolean copy) {
        String verb = copy ? "복사" : "이동";
        List<String> parts = new ArrayList<>();
        if (r.ok > 0) parts.add(r.ok + "개를 " + verb + "했습니다");
        if (r.skipped > 0) parts.add(r.skipped + "개는 이미 그 폴더에 있습니다");
        if (r.failed > 0) parts.add(r.failed + "개는 " + verb + "하지 못했습니다");
        if (r.keptOriginal > 0) parts.add(r.keptOriginal + "개는 원본을 지우지 못했습니다");
        if (r.cancelled) parts.add("나머지는 취소했습니다");
        Util.toast(this, parts.isEmpty() ? verb + "할 파일이 없습니다" : TextUtils.join(" · ", parts));
    }

    /** Progress for a copy or move; "취소" stops after the current chunk (the partial copy is removed). */
    private final class TransferDialog implements FileOps.Progress {
        private final AlertDialog dialog;
        private final TextView text;
        private final ProgressBar bar;
        private volatile boolean cancel;
        private long lastPost;

        TransferDialog(String title) {
            LinearLayout box = new LinearLayout(BaseLibraryActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = Util.dp(BaseLibraryActivity.this, 20);
            box.setPadding(p, Util.dp(BaseLibraryActivity.this, 8), p, 0);
            text = new TextView(BaseLibraryActivity.this);
            text.setTextColor(getColor(R.color.text));
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            bar = new ProgressBar(BaseLibraryActivity.this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(ColorStateList.valueOf(getColor(R.color.accent)));
            box.addView(text);
            box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            dialog = new AlertDialog.Builder(BaseLibraryActivity.this)
                    .setTitle(title)
                    .setView(box)
                    .setNegativeButton("취소", null)
                    .setCancelable(false)
                    .create();
            dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                cancel = true;
                text.setText("취소하는 중…");
            }));
            dialog.show();
        }

        @Override
        public void update(final int i, final int n, final long done, final long total, final String name) {
            long now = SystemClock.uptimeMillis();
            if (now - lastPost < 150) return;
            lastPost = now;
            final int permille = total > 0 ? (int) Math.min(1000, done * 1000 / total) : (n > 0 ? i * 1000 / n : 0);
            runOnUiThread(() -> {
                if (cancel) return;
                text.setText((i + 1) + " / " + n + "   " + name);
                bar.setProgress(permille);
            });
        }

        @Override
        public boolean cancelled() {
            return cancel;
        }

        /** The resume point and marks go along with each moved file, right away. */
        @Override
        public void moved(String from, String to) {
            File nf = new File(to);
            HistoryDb.get(getApplicationContext()).renameKey(from, to, Uri.fromFile(nf).toString(),
                    Util.stripExt(nf.getName()), nf.getParent());
        }

        void dismiss() {
            try {
                dialog.dismiss();
            } catch (Exception ignored) {
            }
        }
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
                .setMessage(what + " 수 있으려면 '모든 파일 접근' 권한이 필요합니다.\n\n허용한 뒤 다시 시도해 주세요.")
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
            askAllFilesAccess("폴더 이름을 바꿀");
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

    private void share(List<VideoItem> items) {
        if (items.isEmpty()) return;
        Intent i;
        if (items.size() == 1) {
            VideoItem v = items.get(0);
            i = new Intent(Intent.ACTION_SEND);
            i.setType(v.mime != null ? v.mime : "video/*");
            i.putExtra(Intent.EXTRA_STREAM, v.uri);
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (VideoItem v : items) if (v.uri != null) uris.add(v.uri);
            i = new Intent(Intent.ACTION_SEND_MULTIPLE);
            i.setType("video/*");
            i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
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
            if (newName.equals(old.getName())) return;
            File nf = new File(old.getParentFile(), newName);
            // shared storage ignores upper/lower case: "movie.mp4" -> "Movie.mp4" is the same name there
            boolean caseOnly = newName.equalsIgnoreCase(old.getName());
            if (nf.exists() && !caseOnly) {
                Util.toast(this, "같은 이름의 파일이 이미 있습니다");
                return;
            }
            FileOps.Sidecars subs = FileOps.sidecarsOf(old);
            if (caseOnly ? FileOps.renameCaseOnly(old, nf) : old.renameTo(nf)) {
                FileOps.renameSidecars(old, nf, subs);
                // the resume point and NEW state stay with the file
                HistoryDb.get(this).renameKey(v.key(), nf.getAbsolutePath(), Uri.fromFile(nf).toString(),
                        Util.stripExt(newName), nf.getParent());
                MediaScannerConnection.scanFile(getApplicationContext(),
                        new String[]{old.getAbsolutePath(), nf.getAbsolutePath()}, null, null);
                Util.toast(this, "이름을 변경했습니다");
                refreshAfterFolderChange();
                return;
            }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // no direct file access: MediaStore renames the file once the user allows it
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
        // Android 10 and older: MediaStore would only change the name in its list, not the file
        Util.toast(this, "이름을 변경할 수 없습니다");
    }

    /** Where MediaStore says a file is now (null if unknown). */
    private String mediaStorePath(Uri uri) {
        if (uri == null) return null;
        try (Cursor c = getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * After MediaStore renamed a file: moves its history entry to where the file really is now
     * (MediaStore picks "B (1).mp4" when "B.mp4" exists). False if the file did not move.
     */
    private boolean renamedByMediaStore(VideoItem v) {
        String real = mediaStorePath(v.uri);
        if (real == null || real.equals(v.path) || !new File(real).exists()) return false;
        File nf = new File(real);
        HistoryDb.get(this).renameKey(v.key(), real, v.uri.toString(), Util.stripExt(nf.getName()), nf.getParent());
        return true;
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_DELETE) {
            List<VideoItem> items = pendingDelete;
            pendingDelete = null;
            if (items != null && resultCode == RESULT_OK) {
                exitSelection();
                for (VideoItem v : items) HistoryDb.get(this).remove(v.key());
                Util.toast(this, items.size() == 1 ? "삭제했습니다" : items.size() + "개를 삭제했습니다");
                refreshAfterFolderChange();
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
                if (updateDisplayName(v, n) && renamedByMediaStore(v)) {
                    Util.toast(this, "이름을 변경했습니다");
                } else {
                    Util.toast(this, "이름을 변경할 수 없습니다");
                }
                refreshAfterFolderChange();
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
