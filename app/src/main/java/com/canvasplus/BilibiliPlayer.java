package com.canvasplus;

import android.app.Application;
import android.content.Context;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Keep Bilibili's fullscreen player and its video within the current app window. */
final class BilibiliPlayer {
    private static final String FULLSCREEN_WIDGET =
            "com.bilibili.app.gemini.player.widget.story.GeminiPlayerFullscreenWidget";
    private static final String CONTROL_TYPE = "tv.danmaku.biliplayerv2.ControlContainerType";
    private static final String INSTALLED = "canvasplus.bilibiliFullscreenInstalled";

    private final Map<Object, WeakReference<Object>> containers = new WeakHashMap<>();
    private final Map<Object, Object> previousRatios = new WeakHashMap<>();

    static void install(ClassLoader loader) {
        if (hookFullscreenWidget(loader)) return;
        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            private boolean installed;

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (!installed) {
                    Application app = (Application) param.thisObject;
                    installed = hookFullscreenWidget(app.getClassLoader());
                }
            }
        });
    }

    private static boolean hookFullscreenWidget(ClassLoader loader) {
        Class<?> widget = XposedHelpers.findClassIfExists(FULLSCREEN_WIDGET, loader);
        if (widget == null) return false;
        synchronized (widget) {
            if (Boolean.TRUE.equals(XposedHelpers.getAdditionalStaticField(widget, INSTALLED))) return true;
            try {
                new BilibiliPlayer().hook(widget, loader);
                XposedHelpers.setAdditionalStaticField(widget, INSTALLED, true);
                ModuleEntry.log("Bilibili player hook ready");
                return true;
            } catch (Throwable error) {
                ModuleEntry.log("Bilibili player hook unavailable: " + error);
                return false;
            }
        }
    }

    private void hook(Class<?> widget, ClassLoader loader) {
        Class<?> controlType = XposedHelpers.findClass(CONTROL_TYPE, loader);
        Class<?> controlsClass = XposedHelpers.findClass("tv.danmaku.biliplayerimpl.controlcontainer.l", loader);
        Class<?> container = XposedHelpers.findClass("tv.danmaku.biliplayerv2.PlayerContainer", loader);
        Class<?> ratio = XposedHelpers.findClass("tv.danmaku.videoplayer.core.videoview.AspectRatio", loader);
        Class<?> renderer = XposedHelpers.findClass("so5.r", loader);
        Object fullscreen = XposedHelpers.getStaticObjectField(controlType, "VERTICAL_FULLSCREEN");
        Object fitToWindow = XposedHelpers.getStaticObjectField(ratio, "RATIO_ADJUST_CONTENT");

        XposedHelpers.findMethodExact(widget, "n2");
        XposedHelpers.findMethodExact(widget, "Y0");
        XposedHelpers.findMethodExact(renderer, "setAspectRatio", ratio);
        XposedHelpers.findMethodExact(controlsClass, "switchTo", controlType);

        XposedHelpers.findAndHookMethod("no5.a", loader, "bindPlayerContainer", container, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (controlsClass.isInstance(param.thisObject)) {
                    containers.put(param.thisObject, new WeakReference<>(param.args[0]));
                }
            }
        });

        XposedHelpers.findAndHookMethod(renderer, "setAspectRatio", ratio, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (previousRatios.containsKey(param.thisObject)) param.args[0] = fitToWindow;
            }
        });

        XposedHelpers.findAndHookMethod(controlsClass, "switchTo", controlType, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.args[0] != fullscreen && Boolean.TRUE.equals(param.getResult())) {
                    restoreAspectRatio(rendererFor(param.thisObject));
                }
            }
        });

        XposedHelpers.findAndHookMethod(controlsClass, "onStop", new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                restoreAspectRatio(rendererFor(param.thisObject));
            }
        });

        XposedHelpers.findAndHookMethod(widget, "Y0", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object button = param.thisObject;
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(button, "n2"))) return;
                Object controls = XposedHelpers.callMethod(button, "getControlContainerService");
                Object actions = XposedHelpers.callMethod(button, "getActionDelegate");
                if (controls == null || actions == null) return;
                Object render = rendererFor(controls);
                if (render == null) {
                    ModuleEntry.log("Bilibili pane fullscreen: render service unavailable");
                    return;
                }

                Object previous = XposedHelpers.callMethod(render, "getAspectRatio");
                previousRatios.putIfAbsent(render, previous);
                try {
                    XposedHelpers.callMethod(controls, "hide");
                    XposedHelpers.callMethod(actions, "Z");
                    if (!Boolean.TRUE.equals(XposedHelpers.callMethod(controls, "switchTo", fullscreen))) {
                        restoreAspectRatio(render);
                        return;
                    }
                    XposedHelpers.callMethod(render, "setAspectRatio", fitToWindow);
                    param.setResult(null);
                    ModuleEntry.log("Bilibili player fullscreen in pane, ratio " + previous + " -> " + fitToWindow);
                } catch (Throwable error) {
                    restoreAspectRatio(render);
                    ModuleEntry.log("Bilibili pane fullscreen failed: " + error);
                }
            }
        });
    }

    private Object rendererFor(Object controls) {
        WeakReference<Object> reference = containers.get(controls);
        Object container = reference == null ? null : reference.get();
        return container == null ? null : XposedHelpers.callMethod(container, "getRenderContainerService");
    }

    private void restoreAspectRatio(Object renderer) {
        Object ratio = previousRatios.remove(renderer);
        if (ratio != null) XposedHelpers.callMethod(renderer, "setAspectRatio", ratio);
    }
}
