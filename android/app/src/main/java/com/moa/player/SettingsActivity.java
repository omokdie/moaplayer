package com.moa.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.provider.Settings;
import android.view.MenuItem;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        if (b == null) {
            getFragmentManager().beginTransaction().replace(android.R.id.content, new SettingsFragment()).commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    public static class SettingsFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle b) {
            super.onCreate(b);
            addPreferencesFromResource(R.xml.settings);
            Preference clear = findPreference("clear_history");
            if (clear != null) clear.setOnPreferenceClickListener(p -> {
                new AlertDialog.Builder(getActivity())
                        .setTitle("재생 기록 지우기")
                        .setMessage("모든 이어보기 위치와 재생 기록을 지울까요?")
                        .setPositiveButton("지우기", (d, w) -> {
                            HistoryDb.get(getActivity()).clear();
                            Util.toast(getActivity(), "재생 기록을 지웠습니다");
                        })
                        .setNegativeButton("취소", null)
                        .show();
                return true;
            });
            Preference all = findPreference("all_files");
            if (all != null) {
                if (Build.VERSION.SDK_INT < 30) {
                    all.setEnabled(false);
                    all.setSummary("이 Android 버전에서는 필요하지 않습니다");
                } else {
                    all.setOnPreferenceClickListener(p -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getActivity().getPackageName())));
                        } catch (Exception e) {
                            try { startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)); }
                            catch (Exception ignored) { }
                        }
                        return true;
                    });
                }
            }
            Preference check = findPreference("check_update");
            if (check != null) {
                check.setOnPreferenceClickListener(p -> {
                    if (UpdateInstaller.readyVersion(getActivity()) != null) UpdateInstaller.maybePrompt(getActivity(), true);
                    else UpdateChecker.check(getActivity(), true);
                    return true;
                });
            }
            Preference about = findPreference("about");
            if (about != null) {
                String v = "1.0";
                try {
                    v = getActivity().getPackageManager().getPackageInfo(getActivity().getPackageName(), 0).versionName;
                } catch (Exception ignored) {
                }
                about.setSummary("모아 플레이어 " + v + " · Media3 ExoPlayer 기반 · 오디오 디코더 FFmpeg (LGPL 2.1, ffmpeg.org)");
            }
        }

        @Override
        public void onResume() {
            super.onResume();
            Preference check = findPreference("check_update");
            if (check != null) {
                String ready = UpdateInstaller.readyVersion(getActivity());
                check.setSummary(ready != null
                        ? "새 버전 " + ready + " 설치 준비됨 — 눌러서 지금 설치"
                        : "지금 버전: " + UpdateChecker.currentVersion(getActivity()));
            }
            Preference all = findPreference("all_files");
            if (all != null && Build.VERSION.SDK_INT >= 30) {
                all.setSummary(Environment.isExternalStorageManager()
                        ? "허용됨 — 같은 폴더의 자막을 자동으로 불러옵니다"
                        : "허용 안 됨 — 눌러서 허용하면 같은 폴더의 자막을 자동으로 불러옵니다");
            }
        }
    }
}
