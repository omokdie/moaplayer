package com.moa.player;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Audio sync per output. Each Bluetooth device (and the phone speaker, wired earphones, USB audio…)
 * keeps its own delay, which applies by itself whenever that output plays. The delay moves the
 * picture and subtitles against the sound ({@link AudioDelaySink}).
 */
public final class AudioSync {
    public static final int MIN_MS = -500, MAX_MS = 1500;
    private static final String KEY = "audio_sync:";
    private static final String TIP = "audio_sync_tip:";

    private static AudioSync inst;

    public static synchronized AudioSync get(Context c) {
        if (inst == null) inst = new AudioSync(c.getApplicationContext());
        return inst;
    }

    private final Context app;
    private final AudioManager am;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> listeners = new ArrayList<>();
    /** When each output appeared (device id): of several plugged in, Android plays media on the newest. */
    private final Map<Integer, Long> seenAt = new HashMap<>();
    private String key = "speaker";
    private String label = "휴대폰 스피커";
    private boolean bluetooth;

    private AudioSync(Context c) {
        app = c;
        am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        refresh();
        if (am != null) {
            am.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override
                public void onAudioDevicesAdded(AudioDeviceInfo[] added) {
                    long now = SystemClock.elapsedRealtime();
                    for (AudioDeviceInfo d : added) if (!seenAt.containsKey(d.getId())) seenAt.put(d.getId(), now);
                    refresh();
                }

                @Override
                public void onAudioDevicesRemoved(AudioDeviceInfo[] removed) {
                    for (AudioDeviceInfo d : removed) seenAt.remove(d.getId());
                    refresh();
                }
            }, main);
            // output switched while the same devices stay connected (Android's media output picker)
            if (Build.VERSION.SDK_INT >= 33) watchPlayback();
        }
    }

    private void watchPlayback() {
        am.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback() {
            @Override
            public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
                refresh();
            }
        }, main);
    }

    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build();

    /** 0: not a media output. When outputs appeared together, the higher one plays. */
    private static int rank(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_HEARING_AID:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
                return 4;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return 3;
            case AudioDeviceInfo.TYPE_HDMI:
            case AudioDeviceInfo.TYPE_HDMI_ARC:
            case AudioDeviceInfo.TYPE_HDMI_EARC:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
            case AudioDeviceInfo.TYPE_AUX_LINE:
            case AudioDeviceInfo.TYPE_DOCK:
                return 2;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:
                return 1;
            default:
                return 0;
        }
    }

    /** Looks at where media sound goes now (also called when playback starts). Main thread. */
    public void refresh() {
        AudioDeviceInfo best = null;
        int bestRank = 0;
        long bestAt = -1;
        if (am != null && Build.VERSION.SDK_INT >= 33) {
            // Android 13+ says which device media really plays on (also after a switch in its output picker)
            try {
                for (AudioDeviceInfo d : am.getAudioDevicesForAttributes(MEDIA)) {
                    int r = rank(d.getType());
                    if (r > bestRank) {
                        best = d;
                        bestRank = r;
                    }
                }
            } catch (RuntimeException ignored) {
            }
        }
        AudioDeviceInfo[] devices = null;
        try {
            if (best == null && am != null) devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        } catch (RuntimeException ignored) {
        }
        if (devices != null) {
            for (AudioDeviceInfo d : devices) {
                int r = rank(d.getType());
                if (r == 0) continue;
                Long seen = seenAt.get(d.getId());
                long at = r >= 2 && seen != null ? seen : 0;   // the speaker is always there
                if (best == null || at > bestAt || (at == bestAt && r > bestRank)) {
                    best = d;
                    bestRank = r;
                    bestAt = at;
                }
            }
        }
        String k, l;
        boolean bt = false;
        CharSequence pn = best != null ? best.getProductName() : null;
        String name = pn != null ? pn.toString().trim() : "";
        int type = best != null ? best.getType() : AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;
        if (bestRank == 4) {
            bt = true;
            if (name.isEmpty()) name = "블루투스 기기";
            k = "bt:" + name;
            l = name + " (블루투스)";
        } else if (bestRank == 3 && (type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_ACCESSORY
                || type == AudioDeviceInfo.TYPE_USB_HEADSET)) {
            k = "usb:" + name;
            l = name.isEmpty() ? "USB 오디오" : name + " (USB)";
        } else if (bestRank == 3) {
            k = "wired";
            l = "유선 이어폰";
        } else if (bestRank == 2) {
            k = "external";
            l = "HDMI·외부 출력";
        } else {
            k = "speaker";
            l = "휴대폰 스피커";
        }
        boolean changed = !k.equals(key);
        key = k;
        label = l;
        bluetooth = bt;
        AudioDelaySink.setOffsetMs(delayMs());
        if (changed) for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    /** The output media plays on now, e.g. "Galaxy Buds2 Pro (블루투스)". */
    public String label() {
        return label;
    }

    public boolean isBluetooth() {
        return bluetooth;
    }

    /** Delay for the current output: positive when the sound is heard late. */
    public int delayMs() {
        return Util.clamp(Prefs.rawInt(app, KEY + key, 0), MIN_MS, MAX_MS);
    }

    public void setDelayMs(int ms) {
        Prefs.putInt(app, KEY + key, Util.clamp(ms, MIN_MS, MAX_MS));
        AudioDelaySink.setOffsetMs(delayMs());
    }

    public void addListener(Runnable r) {
        if (!listeners.contains(r)) listeners.add(r);
    }

    public void removeListener(Runnable r) {
        listeners.remove(r);
    }

    /** True once per Bluetooth device that has no delay set yet: worth a one-line tip. */
    public boolean takeBluetoothTip() {
        if (!bluetooth || delayMs() != 0 || Prefs.bool(app, TIP + key, false)) return false;
        Prefs.put(app, TIP + key, true);
        return true;
    }

    public static String format(int ms) {
        return ms == 0 ? "0초" : String.format(Locale.US, "%+.2f초", ms / 1000f);
    }

    /** "블루투스 +0.15초" for menus. */
    public String summary() {
        return (bluetooth ? "블루투스 " : "") + format(delayMs());
    }

    /**
     * The sync dialog, for the player and the settings screen. {@code onChange} (may be null) runs
     * after every change and when the dialog closes.
     */
    public static AlertDialog dialog(Activity a, final Runnable onChange) {
        final AudioSync s = get(a);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Util.dp(a, 16);
        box.setPadding(pad, Util.dp(a, 4), pad, 0);
        final TextView out = new TextView(a);
        out.setGravity(Gravity.CENTER);
        out.setTextColor(0xFFAAAAAA);
        out.setTextSize(13);
        final TextView value = new TextView(a);
        value.setTextSize(24);
        value.setGravity(Gravity.CENTER);
        value.setTextColor(0xFFFFFFFF);
        value.setPadding(0, Util.dp(a, 8), 0, Util.dp(a, 2));
        final TextView hint = new TextView(a);
        hint.setGravity(Gravity.CENTER);
        hint.setTextColor(0xFFAAAAAA);
        box.addView(out);
        box.addView(value);
        box.addView(hint);
        final Runnable refresh = () -> {
            int ms = s.delayMs();
            out.setText("출력: " + s.label());
            value.setText(format(ms));
            String sec = String.format(Locale.US, "%.2f", Math.abs(ms) / 1000f);
            hint.setText(ms > 0 ? "영상과 자막을 " + sec + "초 늦춰 소리에 맞춥니다"
                    : ms < 0 ? "영상과 자막을 " + sec + "초 앞당겨 소리에 맞춥니다"
                    : "소리가 영상보다 늦게 들리면 + 를 누르세요");
        };
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setPadding(0, pad, 0, 0);
        final int[] steps = {-100, -50, 50, 100};
        final String[] names = {"-0.1초", "-0.05초", "+0.05초", "+0.1초"};
        for (int i = 0; i < steps.length; i++) {
            final int st = steps[i];
            Button b = new Button(a);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setOnClickListener(v -> {
                s.setDelayMs(s.delayMs() + st);
                refresh.run();
                if (onChange != null) onChange.run();
            });
            row.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        }
        box.addView(row);
        TextView note = new TextView(a);
        note.setText("블루투스 이어폰·스피커 등 출력 기기마다 따로 기억하고, 그 기기로 들을 때 저절로 적용합니다.");
        note.setTextColor(0xFF8A8F98);
        note.setTextSize(12);
        note.setPadding(Util.dp(a, 4), Util.dp(a, 10), Util.dp(a, 4), 0);
        box.addView(note);
        refresh.run();
        final AlertDialog d = new AlertDialog.Builder(a)
                .setTitle("오디오 싱크")
                .setView(box)
                .setNeutralButton("초기화", null)
                .setPositiveButton("닫기", null)
                .create();
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
            s.setDelayMs(0);
            refresh.run();
            if (onChange != null) onChange.run();
        }));
        s.refresh();
        // Bluetooth connected or gone while the dialog is open. Removed when the dialog's views go
        // away: also when the screen closes without dismissing it (no leaked activity in the singleton).
        box.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                s.addListener(refresh);
                refresh.run();
            }

            @Override public void onViewDetachedFromWindow(View v) {
                s.removeListener(refresh);
            }
        });
        d.setOnDismissListener(x -> {
            if (onChange != null) onChange.run();
        });
        return d;
    }
}
