// 一加分屏增强修改了 CanvasPlus 的此源文件。
package com.canvasplus;

import android.app.BroadcastOptions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/** 设置页和画布共同使用的默认值和存储。 */
final class Settings {
    private static final String MODULE_PACKAGE = "com.canvasplus";
    static final String CANVAS_PACKAGE = "com.oplus.pscanvas";
    static final String ACTION_READ = "com.canvasplus.READ_SETTINGS";
    static final String ACTION_CHANGED = "com.canvasplus.SETTINGS_CHANGED";

    static final String PREFS = "canvasplus";
    static final String DIAGNOSTICS_ENABLED = "diagnostics_enabled";

    static boolean diagnosticsEnabled(Context context) {
        return prefs(context).getBoolean(DIAGNOSTICS_ENABLED, false);
    }

    static boolean setDiagnosticsEnabled(Context context, boolean enabled) {
        return prefs(context).edit().putBoolean(DIAGNOSTICS_ENABLED, enabled).commit();
    }
    /** 从菜单缩小过之后，点击小窗口不再自动放大。 */
    static final String NO_CLICK_ZOOM = "no_click_zoom";
    private static final boolean NO_CLICK_ZOOM_DEFAULT = true;
    private static boolean preventClickZoom = NO_CLICK_ZOOM_DEFAULT;
    private static int settingsRevision;

    private Settings() {}

    static SharedPreferences prefs(Context context) {
        try {
            return context.getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException error) {
            // 没有开启 LSPosed 的 xposedsharedprefs 特性时退回私有存储
            return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }
    }

    static boolean get(Context context) {
        return prefs(context).getBoolean(NO_CLICK_ZOOM, NO_CLICK_ZOOM_DEFAULT);
    }

    static boolean put(Context context, boolean value) {
        if (!prefs(context).edit().putBoolean(NO_CLICK_ZOOM, value).commit()) return false;
        Intent update = new Intent(ACTION_CHANGED).setPackage(CANVAS_PACKAGE)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                .putExtra(NO_CLICK_ZOOM, value);
        try {
            context.sendBroadcast(update, null, shareIdentity());
        } catch (RuntimeException unavailable) {
            android.util.Log.w("CanvasPlus", "Could not notify canvas about settings change", unavailable);
        }
        return true;
    }

    static boolean preventClickZoom() {
        return preventClickZoom;
    }

    static void refresh(Context context) {
        int revision = settingsRevision;
        Intent request = new Intent(ACTION_READ)
                .setClassName(MODULE_PACKAGE, SettingsReceiver.class.getName())
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES | Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            context.sendOrderedBroadcast(request, null, shareIdentity(), new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    Bundle values = getResultExtras(false);
                    if (values != null && revision == settingsRevision) {
                        preventClickZoom = values.getBoolean(NO_CLICK_ZOOM, NO_CLICK_ZOOM_DEFAULT);
                        ModuleEntry.log("click zoom preference read: " + preventClickZoom);
                    }
                }
            }, null, 0, null, null);
        } catch (RuntimeException unavailable) {
            ModuleEntry.log("zoom setting unavailable: " + unavailable);
        }
    }

    static void receiveUpdate(String sender, Intent intent) {
        if (!MODULE_PACKAGE.equals(sender) || !intent.hasExtra(NO_CLICK_ZOOM)) return;
        settingsRevision++;
        preventClickZoom = intent.getBooleanExtra(NO_CLICK_ZOOM, NO_CLICK_ZOOM_DEFAULT);
        ModuleEntry.log("click zoom preference updated: " + preventClickZoom);
    }

    static Bundle shareIdentity() {
        return BroadcastOptions.makeBasic().setShareIdentityEnabled(true)
                .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE).toBundle();
    }
}
