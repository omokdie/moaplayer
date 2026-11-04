package com.moa.player;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Results of a silent update (PackageInstaller) and "this app was replaced by a new version". */
public class UpdateReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        String a = i.getAction();
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(a)) {
            UpdateInstaller.onReplaced(c);
        } else if (UpdateInstaller.ACTION_STATUS.equals(a)) {
            UpdateInstaller.onStatus(c, i);
        }
    }
}
