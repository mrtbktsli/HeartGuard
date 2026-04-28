package com.heartguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            SharedPreferences prefs = context.getSharedPreferences("HeartGuard", Context.MODE_PRIVATE);
            if (prefs.getBoolean("serviceRunning", false)) {
                Intent s = new Intent(context, HeartMonitorService.class);
                s.setAction("START");
                ContextCompat.startForegroundService(context, s);
            }
        }
    }
}
