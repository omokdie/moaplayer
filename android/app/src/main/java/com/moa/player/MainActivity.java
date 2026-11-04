package com.moa.player;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SearchView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Folder list: the first screen, like MX Player's folder view. */
public class MainActivity extends BaseLibraryActivity {
    private static final int REQ_PERM = 11;

    private FolderAdapter folderAdapter;
    private List<VideoItem> all = new ArrayList<>();
    private List<FolderItem> folders = new ArrayList<>();
    private boolean searchMode;
    private String query = "";
    private long lastLoad;
    private boolean loading;
    private MenuItem viewItem;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        toolbar.setTitle(R.string.app_name);
        folderAdapter = new FolderAdapter(this);
        videoAdapter.setShowFolder(true);
        grid.setAdapter(folderAdapter);
        grid.setOnItemClickListener((parent, view, pos, id) -> {
            if (searchMode) {
                PlayerActivity.play(this, videoAdapter.getItems(), pos, false);
            } else {
                openFolder(folderAdapter.getItem(pos));
            }
        });
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            if (searchMode) showVideoActions(videoAdapter.getItem(pos), videoAdapter.getItems(), pos);
            else showFolderActions(folderAdapter.getItem(pos));
            return true;
        });
        emptyButton.setOnClickListener(v -> requestMediaPermission());
        fab.setOnClickListener(v -> resumeLast());
        if (hasMediaPermission()) {
            reload();
        } else {
            requestMediaPermission();
        }
    }

    /** New version on GitHub? (quietly, a few seconds after the folder screen shows; at most every 3 h) */
    private final Runnable updateCheck = () -> { if (!isFinishing()) UpdateChecker.maybeCheck(this); };

    @Override
    protected void onResume() {
        super.onResume();
        if (hasMediaPermission()) {
            if (System.currentTimeMillis() - lastLoad > 4000) reload();
            else refreshHistoryAndFolders();
        }
        updateFab();
        // "업데이트했습니다" after an update, or offer a downloaded one
        UpdateInstaller.onForeground(this);
        ui.removeCallbacks(updateCheck);
        ui.postDelayed(updateCheck, 3000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(updateCheck);
    }

    @Override
    protected boolean showingVideoGrid() {
        return searchMode && "grid".equals(Prefs.str(this, Prefs.VIEW, "list"));
    }

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            return checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
                    || (Build.VERSION.SDK_INT >= 34 && checkSelfPermission("android.permission.READ_MEDIA_VISUAL_USER_SELECTED") == PackageManager.PERMISSION_GRANTED);
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaPermission() {
        List<String> perms = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 29) perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        requestPermissions(perms.toArray(new String[0]), REQ_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) return;
        if (hasMediaPermission()) {
            reload();
            maybeAskAllFiles();
        } else {
            showEmpty(getString(R.string.perm_needed), true);
        }
    }

    /** Android 11+: subtitle files (.smi/.srt) are not media, so reading them needs "all files" access. */
    private void maybeAskAllFiles() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return;
        if (Prefs.bool(this, Prefs.ASKED_ALL_FILES, false)) return;
        Prefs.put(this, Prefs.ASKED_ALL_FILES, true);
        new AlertDialog.Builder(this)
                .setTitle("자막 자동 불러오기")
                .setMessage("동영상과 같은 폴더에 있는 자막 파일(SMI, SRT, ASS)을 자동으로 불러오려면 '모든 파일 접근' 권한이 필요합니다.\n\n지금 허용하시겠습니까? (나중에 설정에서도 바꿀 수 있습니다)")
                .setPositiveButton("허용하러 가기", (d, w) -> openAllFilesSettings())
                .setNegativeButton("나중에", null)
                .show();
    }

    void openAllFilesSettings() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void reload() {
        if (loading) return;
        loading = true;
        if (folders.isEmpty()) progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final List<VideoItem> list = MediaRepo.load(getApplicationContext());
            final Map<String, HistoryDb.Entry> h = HistoryDb.get(getApplicationContext()).all();
            ui.post(() -> {
                loading = false;
                lastLoad = System.currentTimeMillis();
                progress.setVisibility(View.GONE);
                all = list;
                history = h;
                videoAdapter.setHistory(h);
                rebuild();
                updateFab();
            });
        }, "media-load").start();
    }

    private void refreshHistoryAndFolders() {
        refreshHistory();
        rebuild();
    }

    private void rebuild() {
        folders = MediaRepo.folders(all, history);
        MediaRepo.sortFolders(folders, sortBy(), sortDesc());
        folderAdapter.setItems(folders);
        if (searchMode) {
            List<VideoItem> res = MediaRepo.search(all, query);
            MediaRepo.sortVideos(res, sortBy(), sortDesc());
            videoAdapter.setGrid(showingVideoGrid());
            videoAdapter.setItems(res);
        }
        applyColumns();
        if (all.isEmpty()) showEmpty(getString(R.string.no_videos), false);
        else if (searchMode && videoAdapter.getCount() == 0) showEmpty("검색 결과가 없습니다", false);
        else hideEmpty();
        toolbar.setSubtitle(searchMode ? null : (all.isEmpty() ? null : "폴더 " + folders.size() + "개 · 동영상 " + all.size() + "개"));
    }

    private void setSearchMode(boolean on) {
        if (searchMode == on) return;
        searchMode = on;
        grid.setAdapter(on ? videoAdapter : folderAdapter);
        // Do not invalidate the options menu here: that would collapse the open search box.
        updateViewItem();
        rebuild();
    }

    private void updateFab() {
        HistoryDb.Entry e = HistoryDb.get(this).lastPlayed();
        fab.setVisibility(e != null && e.uri != null && !searchMode ? View.VISIBLE : View.GONE);
    }

    private void resumeLast() {
        final HistoryDb.Entry e = HistoryDb.get(this).lastPlayed();
        if (e == null || e.uri == null) return;
        // Prefer the folder playlist so that next/previous work (may query MediaStore: off the UI thread).
        new Thread(() -> {
            final List<VideoItem> sib = e.key != null && e.key.startsWith("/")
                    ? MediaRepo.siblings(getApplicationContext(), e.key) : new ArrayList<VideoItem>();
            ui.post(() -> {
                for (int i = 0; i < sib.size(); i++) {
                    if (e.key.equals(sib.get(i).key())) {
                        PlayerActivity.play(this, sib, i, false);
                        return;
                    }
                }
                PlayerActivity.playUri(this, Uri.parse(e.uri));
            });
        }, "resume-last").start();
    }

    private void updateViewItem() {
        if (viewItem == null) return;
        viewItem.setVisible(searchMode);
        viewItem.setIcon("grid".equals(Prefs.str(this, Prefs.VIEW, "list")) ? R.drawable.ic_list : R.drawable.ic_grid);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        MenuItem si = menu.findItem(R.id.m_search);
        SearchView sv = (SearchView) si.getActionView();
        if (sv != null) {
            sv.setQueryHint(getString(R.string.search_hint));
            sv.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override public boolean onQueryTextSubmit(String q) { sv.clearFocus(); return true; }
                @Override public boolean onQueryTextChange(String q) {
                    query = q == null ? "" : q.trim();
                    if (!query.isEmpty()) {
                        if (!searchMode) setSearchMode(true); else rebuild();
                    } else if (searchMode) {
                        rebuild();
                    }
                    return true;
                }
            });
        }
        si.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
            @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }
            @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                query = "";
                setSearchMode(false);
                return true;
            }
        });
        viewItem = menu.findItem(R.id.m_view);
        updateViewItem();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.m_sort) {
            showSortDialog();
        } else if (id == R.id.m_view) {
            boolean g = !"grid".equals(Prefs.str(this, Prefs.VIEW, "list"));
            Prefs.put(this, Prefs.VIEW, g ? "grid" : "list");
            updateViewItem();
            rebuild();
        } else if (id == R.id.m_refresh) {
            ThumbLoader.get(this).clearMemory();
            reload();
        } else if (id == R.id.m_network) {
            showNetworkDialog();
        } else if (id == R.id.m_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }
}
