package com.yuhua.voicebox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String a = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            Intent i = new Intent(ctx, FloatService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
            Intent t = new Intent(ctx, TunnelService.class);
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(t);
            else ctx.startService(t);
        }
    }
}
