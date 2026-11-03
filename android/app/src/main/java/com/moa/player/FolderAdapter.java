package com.moa.player;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

public class FolderAdapter extends BaseAdapter {
    private final LayoutInflater inf;
    private List<FolderItem> items = new ArrayList<>();

    public FolderAdapter(Context c) {
        inf = LayoutInflater.from(c);
    }

    public void setItems(List<FolderItem> list) {
        items = list == null ? new ArrayList<FolderItem>() : list;
        notifyDataSetChanged();
    }

    @Override public int getCount() { return items.size(); }
    @Override public FolderItem getItem(int i) { return items.get(i); }
    @Override public long getItemId(int i) { return i; }

    static final class Holder {
        TextView name, info, badge;
    }

    @Override
    public View getView(int pos, View v, ViewGroup parent) {
        Holder h = v != null ? (Holder) v.getTag(R.id.tag_holder) : null;
        if (h == null) {
            v = inf.inflate(R.layout.item_folder, parent, false);
            h = new Holder();
            h.name = v.findViewById(R.id.name);
            h.info = v.findViewById(R.id.info);
            h.badge = v.findViewById(R.id.badge);
            v.setTag(R.id.tag_holder, h);
        }
        FolderItem f = items.get(pos);
        h.name.setText(f.name);
        String info = "동영상 " + f.videos.size() + "개";
        String sz = Util.size(f.totalSize);
        if (!sz.isEmpty()) info += " · " + sz;
        if (f.path != null && f.path.startsWith("/")) info += " · " + shortPath(f.path);
        h.info.setText(info);
        if (f.newCount > 0) {
            h.badge.setText(f.newCount + " NEW");
            h.badge.setVisibility(View.VISIBLE);
        } else {
            h.badge.setVisibility(View.GONE);
        }
        return v;
    }

    static String shortPath(String p) {
        String s = p.replaceFirst("^/storage/emulated/0", "내장 메모리");
        s = s.replaceFirst("^/storage/([0-9A-F]{4}-[0-9A-F]{4})", "SD카드");
        return s;
    }
}
