package com.moa.player;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.SearchView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Videos inside one folder. */
public class VideoListActivity extends BaseLibraryActivity {
    public static final String EXTRA_FOLDER = "folder";
    public static final String EXTRA_NAME = "name";

    private String folderPath;
    private List<VideoItem> videos = new ArrayList<>();
    private String query = "";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private boolean firstResume = true;
    private MenuItem viewItem;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        folderPath = getIntent().getStringExtra(EXTRA_FOLDER);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        if (folderPath == null) {
            finish();
            return;
        }
        toolbar.setTitle(name != null ? name : folderPath);
        toolbar.setNavigationIcon(R.drawable.ic_back);
        toolbar.setNavigationOnClickListener(v -> finish());
        grid.setAdapter(videoAdapter);
        grid.setOnItemClickListener((parent, view, pos, id) ->
                PlayerActivity.play(this, videoAdapter.getItems(), pos, false));
        grid.setOnItemLongClickListener((parent, view, pos, id) -> {
            showVideoActions(videoAdapter.getItem(pos), videoAdapter.getItems(), pos);
            return true;
        });
        fab.setOnClickListener(v -> playLastInFolder());
        List<VideoItem> cached = MediaRepo.cached();
        if (cached != null) {
            videos = MediaRepo.inFolder(cached, folderPath);
            refreshHistory();
            rebuild();
        } else {
            reload();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (firstResume) {
            firstResume = false;
        } else {
            refreshHistory();
            rebuild();
        }
    }

    @Override
    protected boolean showingVideoGrid() {
        return "grid".equals(Prefs.str(this, Prefs.VIEW, "list"));
    }

    @Override
    protected void reload() {
        progress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final List<VideoItem> list = MediaRepo.inFolder(MediaRepo.load(getApplicationContext()), folderPath);
            final Map<String, HistoryDb.Entry> h = HistoryDb.get(getApplicationContext()).all();
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                videos = list;
                history = h;
                videoAdapter.setHistory(h);
                rebuild();
            });
        }, "media-load").start();
    }

    private void rebuild() {
        List<VideoItem> shown = query.isEmpty() ? new ArrayList<>(videos) : MediaRepo.search(videos, query);
        MediaRepo.sortVideos(shown, sortBy(), sortDesc());
        videoAdapter.setGrid(showingVideoGrid());
        videoAdapter.setItems(shown);
        applyColumns();
        toolbar.setSubtitle("동영상 " + videos.size() + "개");
        if (shown.isEmpty()) showEmpty(query.isEmpty() ? getString(R.string.no_videos) : "검색 결과가 없습니다", false);
        else hideEmpty();
        updateFab();
    }

    private VideoItem lastInFolder() {
        VideoItem best = null;
        long bestT = 0;
        for (VideoItem v : videoAdapter.getItems()) {
            HistoryDb.Entry e = history.get(v.key());
            if (e != null && e.lastPlayed > bestT) {
                bestT = e.lastPlayed;
                best = v;
            }
        }
        return best;
    }

    private void updateFab() {
        fab.setVisibility(lastInFolder() != null ? View.VISIBLE : View.GONE);
    }

    private void playLastInFolder() {
        VideoItem v = lastInFolder();
        if (v == null) return;
        List<VideoItem> items = videoAdapter.getItems();
        PlayerActivity.play(this, items, items.indexOf(v), false);
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
                    rebuild();
                    return true;
                }
            });
        }
        viewItem = menu.findItem(R.id.m_view);
        viewItem.setIcon(showingVideoGrid() ? R.drawable.ic_list : R.drawable.ic_grid);
        menu.findItem(R.id.m_network).setVisible(false);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.m_sort) {
            showSortDialog();
        } else if (id == R.id.m_view) {
            Prefs.put(this, Prefs.VIEW, showingVideoGrid() ? "list" : "grid");
            if (viewItem != null) viewItem.setIcon(showingVideoGrid() ? R.drawable.ic_list : R.drawable.ic_grid);
            rebuild();
        } else if (id == R.id.m_refresh) {
            ThumbLoader.get(this).clearMemory();
            reload();
        } else if (id == R.id.m_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }
}
