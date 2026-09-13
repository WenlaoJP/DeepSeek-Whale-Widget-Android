package com.deepseek.whalepet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** 开机 / 覆盖安装后按设置自动拉起小鲸鱼。 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Prefs.autoStart(context)) {
            return;
        }
        if (!Settings.canDrawOverlays(context)) {
            return;
        }
        Intent service = new Intent(context, PetService.class);
        service.setAction(PetService.ACTION_START);
        try {
            context.startForegroundService(service);
        } catch (Throwable ignored) {
        }
    }
}
