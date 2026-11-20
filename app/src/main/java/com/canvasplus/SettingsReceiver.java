package com.canvasplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** 从模块进程读取设置，画布的触摸线程使用缓存值。 */
public final class SettingsReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!Settings.ACTION_READ.equals(intent.getAction()) || !isOrderedBroadcast()
                || !Settings.CANVAS_PACKAGE.equals(getSentFromPackage())) return;
        Bundle values = new Bundle();
        values.putBoolean(Settings.NO_CLICK_ZOOM, Settings.get(context));
        setResultExtras(values);
    }
}
