package com.canvasplus;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.util.Arrays;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 智能侧边栏：画布在前台时，点应用加进画布，否则照旧开小窗。
 *
 * 侧边栏点应用走 PageRoutUtils.startActivityAsZoomWindow(Intent, ...)。这里拦下来，
 * 发一条有序广播问画布进程"能不能接一个新窗口"。画布自己看 Activity 是否可见、
 * 窗口数是否到 4，用 resultCode 回答：
 *
 *   1 → 画布接了，随后异步加窗口
 *   0 → 画布不在前台、已经满了、或者进程没在，回退到原来的小窗
 *
 * 判断交给画布自己做，侧边栏不用跨进程保存任何状态。
 */
final class SidebarEntry {

    private static final String ROUTE =
            "com.oplus.smartsidebar.panelview.edgepanel.utils.PageRoutUtils";
    private static final String CANVAS = "com.oplus.pscanvas";
    private static final String IMAGE_HANDLER =
            "com.oplus.smartsidebar.panelview.edgepanel.data.viewdatahandlers.ImageDataHandleImpl";
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String ACTION_ADD = "com.canvasplus.ADD";

    /** 兜底重放开小窗时，别让 hook 再拦一次。 */
    private static final ThreadLocal<Boolean> replaying = new ThreadLocal<>();

    private static final ThreadLocal<Object> loadingToolIcon = new ThreadLocal<>();

    private SidebarEntry() {}

    static void install(final ClassLoader loader) {
        installToolIconFallback(loader);
        try {
            Class<?> route = XposedHelpers.findClass(ROUTE, loader);
            boolean hasUserOverload = Arrays.stream(route.getDeclaredMethods()).anyMatch(method ->
                    method.getName().equals("startActivityAsZoomWindow")
                            && method.getParameterCount() >= 2 && method.getParameterTypes()[1] == int.class);
            XposedBridge.hookAllMethods(route, "startActivityAsZoomWindow",
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (Boolean.TRUE.equals(replaying.get())) return;
                            if (param.args.length == 0 || !(param.args[0] instanceof Intent launch)) return;
                            // 短签名会转调带用户 ID 的签名，避免拦截两次。
                            boolean explicitUser = param.args.length >= 2 && param.args[1] instanceof Integer;
                            if (hasUserOverload && !explicitUser) return;
                            int userId = explicitUser ? (Integer) param.args[1] : 0;
                            String pkg = packageOf(launch);
                            if (pkg == null || SYSTEM_UI.equals(pkg)) return;

                            param.setResult(null);
                            Context ctx = application(loader);
                            if (ctx == null) {
                                replay(param);
                                return;
                            }
                            ask(ctx, pkg, new Intent(launch), userId, param);
                        }
                    });
            log("sidebar hooked");
        } catch (Throwable error) {
            log("sidebar hook failed: " + error);
        }
    }

    private static void installToolIconFallback(ClassLoader loader) {
        try {
            Class<?> imageHandler = XposedHelpers.findClass(IMAGE_HANDLER, loader);
            XposedBridge.hookAllMethods(imageHandler, "getToolAppIcon", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra("previousTool", loadingToolIcon.get());
                    loadingToolIcon.set(param.args[0]);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object previous = param.getObjectExtra("previousTool");
                    if (previous == null) loadingToolIcon.remove();
                    else loadingToolIcon.set(previous);
                }
            });
            XposedHelpers.findAndHookMethod(Context.class, "getDrawable", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object tool = loadingToolIcon.get();
                    if (tool == null || !(param.getThrowable() instanceof Resources.NotFoundException)) return;
                    Context context = (Context) param.thisObject;
                    int iconId = (Integer) param.args[0];
                    String packageName = (String) XposedHelpers.callMethod(tool, "getPkg");
                    String alias = (String) XposedHelpers.callMethod(tool, "getAlias");
                    param.setResult(resolveToolIcon(context, packageName, alias, iconId));
                    log("sidebar missing tool icon " + Integer.toHexString(iconId) + " for " + packageName);
                }
            });
        } catch (RuntimeException | LinkageError error) {
            log("sidebar tool icon fallback unavailable: " + error);
        }
    }

    private static Drawable resolveToolIcon(Context context, String packageName, String alias, int iconId) {
        if (packageName != null) {
            try {
                return context.getPackageManager().getResourcesForApplication(packageName)
                        .getDrawable(iconId, null);
            } catch (PackageManager.NameNotFoundException | Resources.NotFoundException ignored) {
                // 卡片保存的资源可能来自另一版系统，使用系统自带的占位图标。
            }
        }
        if ("clean_storage".equals(alias) && iconId == 0x64080081) {
            int size = Math.round(48 * context.getResources().getDisplayMetrics().density);
            return new BackgroundRunningIcon(size);
        }
        return context.getDrawable(android.R.drawable.ic_menu_recent_history);
    }

    private static final class BackgroundRunningIcon extends Drawable {
        private final int size;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path play = new Path();

        BackgroundRunningIcon(int size) {
            this.size = size;
            play.moveTo(23f, 20f);
            play.lineTo(31f, 25f);
            play.lineTo(23f, 30f);
            play.close();
        }

        @Override public void draw(Canvas canvas) {
            int saved = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 48f, getBounds().height() / 48f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xfff5fafc);
            canvas.drawRoundRect(0f, 0f, 48f, 48f, 12f, 12f, paint);
            paint.setColor(0xff36788a);
            canvas.drawRoundRect(9f, 10f, 35f, 29f, 5f, 5f, paint);
            paint.setColor(0xff184d60);
            canvas.drawRoundRect(15f, 16f, 39f, 36f, 5f, 5f, paint);
            paint.setColor(0xffffffff);
            canvas.drawPath(play, paint);
            canvas.restoreToCount(saved);
        }

        @Override public int getIntrinsicWidth() { return size; }
        @Override public int getIntrinsicHeight() { return size; }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** 问画布能不能加，回执不是 1 就还回去开小窗。 */
    private static void ask(Context ctx, String pkg, Intent launch, int userId,
                            XC_MethodHook.MethodHookParam param) {
        Intent add = new Intent(ACTION_ADD);
        add.setPackage(CANVAS);
        add.putExtra("pkg", pkg);
        add.putExtra(Trigger.EXTRA_LAUNCH, launch);
        add.putExtra(Trigger.EXTRA_USER, userId);
        add.putExtra("ordered", true);
        try {
            ctx.sendOrderedBroadcast(add, null, Settings.shareIdentity(), new BroadcastReceiver() {
                @Override public void onReceive(Context c, Intent result) {
                    if (getResultCode() == 1) {
                        log("sidebar add " + pkg + " user=" + userId + " -> canvas");
                        return;
                    }
                    log("sidebar add " + pkg + " user=" + userId + " -> zoom fallback");
                    new Handler(Looper.getMainLooper()).post(() -> replay(param));
                }
            }, null, 0, null, null);
        } catch (Throwable error) {
            log("sidebar ask failed: " + error);
            replay(param);
        }
    }

    /** 画布没接，按原参数再调一次，让系统开小窗。 */
    private static void replay(XC_MethodHook.MethodHookParam param) {
        replaying.set(Boolean.TRUE);
        try {
            // 参数里有 null，XposedHelpers.callMethod 会按参数类型查方法而炸，直接反射
            ((java.lang.reflect.Method) param.method).invoke(param.thisObject, param.args);
        } catch (Throwable error) {
            log("zoom fallback failed: " + error);
        } finally {
            replaying.remove();
        }
    }

    private static String packageOf(Intent intent) {
        String pkg = intent.getPackage();
        if (pkg != null) return pkg;
        return intent.getComponent() == null
                ? null : intent.getComponent().getPackageName();
    }

    private static Context application(ClassLoader loader) {
        try {
            Object app = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass("android.app.ActivityThread", loader),
                    "currentApplication");
            return app instanceof Context ? (Context) app : null;
        } catch (Throwable error) {
            log("no application: " + error);
            return null;
        }
    }

    private static void log(String message) {
        ModuleEntry.log(message);
    }
}
