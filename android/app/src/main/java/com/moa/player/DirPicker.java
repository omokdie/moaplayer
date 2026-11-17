package com.moa.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Folder browser for "이동" and "복사": storages, their folders, and "새 폴더". */
final class DirPicker {
    interface Callback {
        void onPicked(File dir);
    }

    private static final class Row {
        final String label;
        final File target;   // null: the list of storages
        final boolean up;

        Row(String label, File target, boolean up) {
            this.label = label;
            this.target = target;
            this.up = up;
        }
    }

    private final Activity a;
    private final List<File> roots = new ArrayList<>();
    private final List<String> rootNames = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private File cur;   // null while the storages are listed
    private TextView pathView;
    private BaseAdapter adapter;
    private AlertDialog dialog;

    static void show(Activity a, String title, String okLabel, File start, Callback cb) {
        new DirPicker(a).open(title, okLabel, start, cb);
    }

    private DirPicker(Activity a) {
        this.a = a;
        File primary = Environment.getExternalStorageDirectory();
        roots.add(primary);
        rootNames.add("내장 메모리");
        File[] dirs = a.getExternalFilesDirs(null);
        StorageManager sm = (StorageManager) a.getSystemService(Context.STORAGE_SERVICE);
        if (dirs != null) {
            for (File f : dirs) {
                if (f == null) continue;
                String p = f.getAbsolutePath();
                int k = p.indexOf("/Android/data/");
                if (k <= 0) continue;
                File root = new File(p.substring(0, k));
                if (root.getAbsolutePath().equals(primary.getAbsolutePath())) continue;
                String name = "SD카드";
                try {
                    StorageVolume sv = sm != null ? sm.getStorageVolume(root) : null;
                    if (sv != null && !TextUtils.isEmpty(sv.getDescription(a))) name = sv.getDescription(a);
                } catch (Exception ignored) {
                }
                roots.add(root);
                rootNames.add(name);
            }
        }
    }

    private void open(String title, String okLabel, File start, final Callback cb) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(a, 20);
        pathView = new TextView(a);
        pathView.setPadding(pad, Util.dp(a, 4), pad, Util.dp(a, 8));
        pathView.setTextColor(a.getColor(R.color.text2));
        pathView.setTextSize(13);
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.START);
        box.addView(pathView);
        ListView list = new ListView(a);
        list.setDivider(null);
        adapter = new BaseAdapter() {
            @Override public int getCount() { return rows.size(); }
            @Override public Object getItem(int i) { return rows.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                TextView tv = v instanceof TextView ? (TextView) v : new TextView(a);
                Row r = rows.get(i);
                int p = Util.dp(a, 14);
                tv.setPadding(Util.dp(a, 20), p, Util.dp(a, 20), p);
                tv.setTextSize(16);
                tv.setSingleLine(true);
                tv.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                tv.setGravity(Gravity.CENTER_VERTICAL);
                tv.setTextColor(a.getColor(r.up ? R.color.text2 : R.color.text));
                tv.setText(r.label);
                Drawable ic = a.getDrawable(r.up ? R.drawable.ic_back : R.drawable.ic_folder);
                if (ic != null) {
                    ic = ic.mutate();
                    ic.setTint(a.getColor(r.up ? R.color.text2 : R.color.accent));
                }
                tv.setCompoundDrawablesRelativeWithIntrinsicBounds(ic, null, null, null);
                tv.setCompoundDrawablePadding(Util.dp(a, 16));
                return tv;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> load(rows.get(pos).target));
        // fixed height: the dialog does not jump while browsing (at most half the screen, for landscape)
        int h = Math.min(Util.dp(a, 360), a.getResources().getDisplayMetrics().heightPixels / 2);
        box.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));

        dialog = new AlertDialog.Builder(a)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(okLabel, (d, w) -> {
                    if (cur != null) cb.onPicked(cur);
                })
                .setNeutralButton("새 폴더", null)
                .setNegativeButton("취소", null)
                .create();
        dialog.setOnShowListener(x -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> askNewFolder());
            updateButtons();
        });
        dialog.show();
        load(rootOf(start) >= 0 && start.isDirectory() ? start : roots.get(0));
    }

    private int rootOf(File dir) {
        if (dir == null) return -1;
        String p = dir.getAbsolutePath();
        for (int i = 0; i < roots.size(); i++) {
            String r = roots.get(i).getAbsolutePath();
            if (p.equals(r) || p.startsWith(r + "/")) return i;
        }
        return -1;
    }

    private void load(File dir) {
        int ri = rootOf(dir);
        if (dir != null && (ri < 0 || !dir.isDirectory())) dir = null;
        cur = dir;
        rows.clear();
        if (dir == null) {
            for (int i = 0; i < roots.size(); i++) rows.add(new Row(rootNames.get(i), roots.get(i), false));
            pathView.setText("저장소를 고르세요");
        } else {
            boolean isRoot = dir.getAbsolutePath().equals(roots.get(ri).getAbsolutePath());
            if (!isRoot) rows.add(new Row("상위 폴더", dir.getParentFile(), true));
            else if (roots.size() > 1) rows.add(new Row("저장소 목록", null, true));
            File[] subs = dir.listFiles();
            List<File> folders = new ArrayList<>();
            if (subs != null) {
                for (File f : subs) {
                    String n = f.getName();
                    if (!f.isDirectory() || n.startsWith(".")) continue;
                    if (isRoot && n.equalsIgnoreCase("Android")) continue;   // app data: not for videos
                    folders.add(f);
                }
            }
            Collections.sort(folders, (x, y) -> Util.natural(x.getName().toLowerCase(Locale.ROOT), y.getName().toLowerCase(Locale.ROOT)));
            for (File f : folders) rows.add(new Row(f.getName(), f, false));
            String rel = isRoot ? "" : dir.getAbsolutePath().substring(roots.get(ri).getAbsolutePath().length());
            pathView.setText(rootNames.get(ri) + rel + (dir.canWrite() ? "" : "  (여기에는 쓸 수 없음)"));
        }
        adapter.notifyDataSetChanged();
        updateButtons();
    }

    private void updateButtons() {
        if (dialog == null) return;
        Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        Button nf = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        boolean writable = cur != null && cur.canWrite();
        if (ok != null) ok.setEnabled(writable);
        if (nf != null) nf.setEnabled(writable);
    }

    private void askNewFolder() {
        if (cur == null) return;
        final File parent = cur;
        final EditText et = new EditText(a);
        et.setSingleLine(true);
        et.setHint("폴더 이름");
        LinearLayout box = new LinearLayout(a);
        int p = Util.dp(a, 20);
        box.setPadding(p, Util.dp(a, 8), p, 0);
        box.addView(et, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(a)
                .setTitle("새 폴더")
                .setView(box)
                .setPositiveButton("만들기", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (name.matches(".*[\\\\/:*?\"<>|].*") || name.startsWith(".") || name.length() > 200) {
                        Util.toast(a, "폴더 이름에 쓸 수 없는 글자가 있습니다");
                        return;
                    }
                    File f = new File(parent, name);
                    if (f.isDirectory() || f.mkdir()) load(f);
                    else Util.toast(a, "폴더를 만들 수 없습니다");
                })
                .setNegativeButton("취소", null)
                .show();
    }
}
