package com.moa.player;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapter showing videos either as a list (thumbnail on the left) or as a grid. */
public class VideoAdapter extends BaseAdapter {
    private final Context ctx;
    private final LayoutInflater inf;
    private List<VideoItem> items = new ArrayList<>();
    private Map<String, HistoryDb.Entry> history = new HashMap<>();
    private boolean grid;
    private boolean thumbs = true;
    private boolean showFolder;

    public VideoAdapter(Context c) {
        ctx = c;
        inf = LayoutInflater.from(c);
    }

    public void setItems(List<VideoItem> list) {
        items = list == null ? new ArrayList<VideoItem>() : list;
        notifyDataSetChanged();
    }

    public List<VideoItem> getItems() {
        return items;
    }

    public void setHistory(Map<String, HistoryDb.Entry> h) {
        history = h == null ? new HashMap<String, HistoryDb.Entry>() : h;
        notifyDataSetChanged();
    }

    public void setGrid(boolean g) {
        grid = g;
        notifyDataSetChanged();
    }

    public void setThumbs(boolean t) {
        thumbs = t;
    }

    public void setShowFolder(boolean s) {
        showFolder = s;
    }

    @Override public int getCount() { return items.size(); }
    @Override public VideoItem getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return items.get(i).id; }
    @Override public int getViewTypeCount() { return 2; }
    @Override public int getItemViewType(int position) { return grid ? 1 : 0; }

    static final class Holder {
        boolean grid;
        ImageView thumb;
        TextView title, info, duration, badge;
        ProgressBar watched;
    }

    @Override
    public View getView(int pos, View convertView, ViewGroup parent) {
        Holder h = convertView != null ? (Holder) convertView.getTag(R.id.tag_holder) : null;
        if (h == null || h.grid != grid) {
            convertView = inf.inflate(grid ? R.layout.item_video_grid : R.layout.item_video_list, parent, false);
            h = new Holder();
            h.grid = grid;
            h.thumb = convertView.findViewById(R.id.thumb);
            h.title = convertView.findViewById(R.id.title);
            h.info = convertView.findViewById(R.id.info);
            h.duration = convertView.findViewById(R.id.duration);
            h.badge = convertView.findViewById(R.id.badge);
            h.watched = convertView.findViewById(R.id.watched);
            convertView.setTag(R.id.tag_holder, h);
        }
        VideoItem v = items.get(pos);
        h.title.setText(v.title());
        StringBuilder info = new StringBuilder();
        if (showFolder && v.folderName != null) info.append(v.folderName).append(" · ");
        String sz = Util.size(v.size);
        if (!sz.isEmpty()) info.append(sz);
        if (v.width > 0 && v.height > 0) {
            if (info.length() > 0 && !info.toString().endsWith(" · ")) info.append(" · ");
            info.append(resolutionLabel(v.width, v.height));
        }
        h.info.setText(info.toString());
        h.duration.setText(v.duration > 0 ? Util.time(v.duration) : "");
        h.duration.setVisibility(v.duration > 0 ? View.VISIBLE : View.GONE);
        HistoryDb.Entry e = history.get(v.key());
        h.badge.setVisibility(e == null ? View.VISIBLE : View.GONE);
        if (e != null && !e.finished() && e.position > 3000) {
            long dur = e.duration > 0 ? e.duration : v.duration;
            int prog = dur > 0 ? (int) Math.min(1000, e.position * 1000 / dur) : 0;
            h.watched.setProgress(prog);
            h.watched.setVisibility(prog > 0 ? View.VISIBLE : View.GONE);
        } else {
            h.watched.setVisibility(View.GONE);
        }
        if (thumbs) {
            ThumbLoader.get(ctx).load(v, h.thumb);
        } else {
            h.thumb.setTag(R.id.tag_thumb, null);
            h.thumb.setScaleType(ImageView.ScaleType.CENTER);
            h.thumb.setImageAlpha(90);
            h.thumb.setImageResource(R.drawable.ic_movie);
        }
        return convertView;
    }

    static String resolutionLabel(int w, int h) {
        int m = Math.min(w, h);
        if (m >= 2100) return "4K";
        if (m >= 1400) return "1440p";
        if (m >= 1000) return "1080p";
        if (m >= 700) return "720p";
        return m + "p";
    }
}
