package com.moa.player;

import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Bundle;

public class App extends Application {
    public static final String CH_PLAYBACK = "playback";

    static App inst;
    /** Screens of the app that are visible right now (started and not stopped). */
    static int visible;

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel ch = new NotificationChannel(CH_PLAYBACK,
                        getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
                NotificationChannel up = new NotificationChannel(UpdateInstaller.CH_UPDATES,
                        "업데이트", NotificationManager.IMPORTANCE_LOW);
                up.setShowBadge(false);
                nm.createNotificationChannel(up);
            }
        }
        // a downloaded update installs by itself once no screen of the app is visible (Android 12+)
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityStarted(Activity a) {
                if (visible++ == 0) UpdateInstaller.onAppForeground(App.this);
            }

            @Override public void onActivityStopped(Activity a) {
                if (visible > 0) visible--;
                if (visible == 0 && !a.isChangingConfigurations()) UpdateInstaller.onAppBackground(App.this);
            }

            @Override public void onActivityCreated(Activity a, Bundle b) { }

            @Override public void onActivityResumed(Activity a) { }

            @Override public void onActivityPaused(Activity a) { }

            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }

            @Override public void onActivityDestroyed(Activity a) { }
        });
    }
}
