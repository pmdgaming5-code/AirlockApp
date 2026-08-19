package com.pmdgaming.airlock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            if (!((java.util.Set<?>) context.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE)
                    .getStringSet(MainActivity.KEY_PROTECTED, java.util.Collections.emptySet())).isEmpty()) {
                MonitorService.start(context);
            }
        }
    }
}
