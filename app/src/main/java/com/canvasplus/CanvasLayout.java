// 一加分屏增强修改了 CanvasPlus 的此源文件。
package com.canvasplus;

import static com.canvasplus.CanvasApi.Member.*;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 画布：沿用原本的三窗布局，把任务尺寸统一成屏幕可用区。
 *
 * 平板上任务比例接近屏幕比例，所以 M1 算出来刚好。手机上任务数据是方形，
 * 窗口跟着方形，和屏幕差太多。这里把任务 bounds 改成屏幕可用区
 * （宽度 × 高度减状态栏），并同步 FlexibleTaskView.mLaunchBounds 和
 * u1/d 里的布局缓存，M1 才会按手机比例算窗口。
 *
 * 四窗不另写排版，接进官方的 w1 → N2 → M1 链路：只放开数量判断、
 * 给 M1 补 4 个矩形，其余（调节条、焦点缩放、关闭、浮窗）全走原实现。
 */
final class CanvasLayout {

    private static final String TAG = "CanvasPlus";
    private static final String FLEXIBLE_TASK_VIEW =
            "com.oplus.flexiblewindow.FlexibleTaskView";
    private static final String PROPORTIONAL_BOUNDS = "canvasplus.proportionalBounds";
    private static final int DIVIDER_BLUE = 0xffa8d7ff;
    static volatile int forcedMode = 0;
    static final int DEFAULT_ORIENTATION = 7;
    /** 7 表示左边两个小窗、右边一个主窗，0 表示交给系统。 */
    static volatile int forcedOrientation = DEFAULT_ORIENTATION;
    private static final ThreadLocal<Float> layoutColumnShare = new ThreadLocal<>();
    private static final ThreadLocal<int[]> layoutPaneOrder = new ThreadLocal<>();
    private static final ThreadLocal<CanvasDisplay> layoutDisplay = new ThreadLocal<>();
    private static final java.util.WeakHashMap<Object, List<Object>> tasksBySlot =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, MainPaneSwap> pendingMainSwaps =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<Object, ValueAnimator> mainPaneAnimations =
            new java.util.WeakHashMap<>();
    private static int attempts = 0;
    private static volatile boolean relayoutScheduled = false;
    private static final java.util.WeakHashMap<Object, String> layoutKeys =
            new java.util.WeakHashMap<>();
    /** 活着画布实例，切换布局方向时需要用。 */
    private static final java.util.WeakHashMap<Object, Boolean> containers =
            new java.util.WeakHashMap<>();
    private static volatile int skipped = 0;

    /** 布局参数指纹：任务数、应用标识、taskId、bounds，用来跳过没变化的重排。 */
    private static String layoutKey(Object container) {
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null) return "";
            int count = count(adapter);
            StringBuilder sb = new StringBuilder();
            sb.append(forcedOrientation).append(':').append(count).append(':');
            if (CanvasApi.isColorOs17() && count >= 2) {
                sb.append(canvasArea((View) container)).append(':');
                if (count <= 3) {
                    sb.append(preferredMainPaneMode(((View) container).getContext())).append(':');
                }
            }
            for (int i = 0; i < count; i++) {
                Object task = XposedHelpers.callMethod(adapter, "getItem", i);
                if (task == null) continue;
                Object rect = XposedHelpers.callMethod(task, "o");
                Object source = null;
                Object taskId = null;
                try {
                    source = XposedHelpers.callMethod(task, "k");
                } catch (Throwable ignored) {
                }
                try {
                    taskId = XposedHelpers.callMethod(task, "t");
                } catch (Throwable ignored) {
                }
                // 替换应用后 bounds 一样，必须靠应用标识区分，否则这次重排会被跳过
                sb.append(source).append('@').append(taskId).append(rect);
            }
            return sb.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private CanvasLayout() {}

    private static volatile boolean multiWindowMode = false;
    /** 只在三屏布局那一瞬间锁定 o()。 */
    private static volatile boolean oLock = false;
    private static final java.util.WeakHashMap<Object, Integer> taskCounts =
            new java.util.WeakHashMap<>();

    static void install(ClassLoader loader) {
        useTabletPolicy(loader);
        forceSplitMode(loader);
        allowMoreThanThree(loader);
        ignoreLineLayout(loader);
        hookTaskDataBounds(loader);
        hookFullscreen(loader);
        hookTaskInfo(loader);
        hookResume(loader);
        hookScreenOrientation(loader);
        hookClick(loader);
        hookDragLock(loader);
        hookOrientation(loader);
        hookAddLimit(loader);
        hookFlexibleUiArray(loader);
        hookRectOrientation(loader);
        hookNativeFour(loader);
        hookLayoutCalc(loader);
        hookZoomFour(loader);
        hookFourIntents(loader);
        hookExchange(loader);
        if (CanvasApi.isColorOs17()) {
            hookModernWindowMenu(loader);
            hookMainPaneSwap(loader);
            hookPaneResizeTouch(loader);
        } else hookWindowMenu(loader);
        hookFourZoom(loader);
        hookTouchRegion(loader);
    }

    /**
     * 系统只给完整落入画布的任务让出触摸区域。键盘位移或焦点缩放裁掉窗口边缘时，
     * 用焦点窗的可见部分补上，让应用继续接收手势。
     */
    private static void hookTouchRegion(ClassLoader loader) {
        if (CanvasApi.isColorOs17()) {
            hookCanvasTouchRegion(loader);
            return;
        }
        try {
            Class<?> listener = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.FlexibleTaskView$FlexibleOnComputeInternalInsetsListener",
                    loader);
            XposedBridge.hookAllMethods(listener, "onComputeInternalInsets",
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!imeActive) return;
                                Object view = XposedHelpers.getObjectField(
                                        param.thisObject, "this$0");
                                if (view == null) return;
                                if (XposedHelpers.getBooleanField(view, "mInterceptInputEvent")) {
                                    return;
                                }
                                Rect rect = new Rect();
                                XposedHelpers.callMethod(view, "getBoundsOnScreen", rect);
                                if (rect.isEmpty()) return;
                                Object info = param.args[0];
                                XposedHelpers.callMethod(info, "setTouchableInsets", 3);
                                android.graphics.Region region = (android.graphics.Region)
                                        XposedHelpers.getObjectField(info, "touchableRegion");
                                region.op(rect, android.graphics.Region.Op.DIFFERENCE);
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("touch region hooked");
        } catch (Throwable error) {
            log("touch region hook failed: " + error);
        }
    }

    private static void hookCanvasTouchRegion(ClassLoader loader) {
        try {
            Class<?> insets = XposedHelpers.findClass(
                    "android.view.ViewTreeObserver$InternalInsetsInfo", loader);
            XposedHelpers.findAndHookMethod(android.view.ViewTreeObserver.class,
                    "dispatchOnComputeInternalInsets", insets, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                for (Object candidate : containers.keySet()) {
                                    android.view.View canvas = (android.view.View) candidate;
                                    if (canvas.isShown()
                                            && canvas.getViewTreeObserver() == param.thisObject) {
                                        allowFocusedTaskTouch(canvas, param.args[0]);
                                        return;
                                    }
                                }
                            } catch (Throwable error) {
                                log("canvas touch region failed: " + error);
                            }
                        }
                    });
            log("canvas touch region hooked");
        } catch (Throwable error) {
            log("canvas touch region hook failed: " + error);
        }
    }

    private static void allowFocusedTaskTouch(android.view.View container, Object info) {
        Object adapter = CanvasApi.adapter(container);
        int total = adapter == null ? 0 : count(adapter);
        if (!usesCanvasLayout(total)) return;
        int focus = (Integer) XposedHelpers.callMethod(adapter, "h");
        if (focus < 0 || focus >= total) return;
        Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), focus);
        if (decor == null) return;
        Object taskView = XposedHelpers.callMethod(decor, "getFlexibleTaskView");
        if (!(taskView instanceof android.view.View) || !((android.view.View) taskView).isShown()
                || XposedHelpers.getBooleanField(taskView, "mInterceptInputEvent")
                || XposedHelpers.getObjectField(taskView, "mTaskInfo") == null) return;

        // 使用系统实际上报给应用的内容矩形，保留窗格外和分隔条的手势区。
        Rect content = (Rect) XposedHelpers.callMethod(taskView, "calculateFrame");
        if (!content.intersect(0, 0, container.getRootView().getWidth(),
                container.getRootView().getHeight())) return;
        android.graphics.Region region = (android.graphics.Region)
                XposedHelpers.getObjectField(info, "touchableRegion");
        if (region.isEmpty()) return;
        XposedHelpers.callMethod(info, "setTouchableInsets", 3);
        region.op(content, android.graphics.Region.Op.DIFFERENCE);
        for (PaneEdge edge : resizeEdges(container)) region.op(edge.hit(), Region.Op.UNION);
        Object listener = XposedHelpers.getObjectField(taskView, "mListener");
        if (listener != null) XposedHelpers.callMethod(listener, "updateTouchRegion", region);
    }

    /**
     * 三窗和四窗都保留窗口菜单里的"放大窗口/缩小窗口"。
     * 四窗的放大走 2×2 网格，三窗的放大走官方 a0 焦点缩放。
     */
    private static void hookWindowMenu(ClassLoader loader) {
        try {
            Class<?> menu = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.t", loader);
            XposedBridge.hookAllMethods(menu, "e0", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    log("window menu built");
                }
            });
            log("window menu hooked");
        } catch (Throwable error) {
            log("window menu hook failed: " + error);
        }
    }

    /** 四窗当前是不是"放大"状态。默认 false = 四等分。 */
    private static final java.util.WeakHashMap<Object, Boolean> fourZoomed =
            new java.util.WeakHashMap<>();

    private static boolean isFourZoomed(Object container) {
        return Boolean.TRUE.equals(fourZoomed.get(container));
    }

    /** 用户从这个菜单缩小过。只有这种状态下点击窗口才不放大。 */
    private static final java.util.WeakHashMap<Object, Boolean> menuShrunk =
            new java.util.WeakHashMap<>();
    private static final java.util.Set<Object> focusing =
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static final java.util.WeakHashMap<Object, Integer> enlargedPanes =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<Object, java.lang.ref.WeakReference<android.animation.Animator>> zoomAnimations =
            new java.util.WeakHashMap<>();

    private static void markEnlarged(Object adapter, int total, int focus) {
        for (int index = 0; index < total; index++) {
            Object task = XposedHelpers.callMethod(adapter, "getItem", index);
            if (task != null) {
                XposedHelpers.callMethod(task, TASK_ENLARGED.value(), index == focus);
            }
        }
    }

    /**
     * 四窗的"放大窗口/缩小窗口"。
     *
     * 默认四等分（画布按 2×2 总尺寸缩放到屏幕），点放大把焦点窗口铺满屏幕，
     * 点缩小回到四等分。都走画布自己的 A2() 缩放，不改窗口矩形。
     */
    private static void hookFourZoom(ClassLoader loader) {
        try {
            Class<?> replacer = XposedHelpers.findClass(
                    "com.oplus.pscanvas.canvasmode.canvas.c", loader);
            XposedBridge.hookAllMethods(replacer, "m", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object container = param.args[0];
                        Object adapter = CanvasApi.adapter(container);
                        if (adapter == null) return;
                        int total = count(adapter);
                        if (!usesCanvasLayout(total)) return;
                        boolean enlarge = (Boolean) param.args[2];
                        if (CanvasApi.isColorOs17()) {
                            int index = (Integer) XposedHelpers.callMethod(adapter, "getPosition", param.args[1]);
                            if (index >= 0) changeZoom((View) container, adapter, index, enlarge);
                            param.setResult(null);
                            return;
                        }
                        if (enlarge) {
                            menuShrunk.remove(container);
                            fourZoomed.put(container, Boolean.TRUE);
                            int focus = 0;
                            Object position = XposedHelpers.callMethod(
                                    adapter, "getPosition", param.args[1]);
                            if (position instanceof Integer && (Integer) position >= 0) {
                                focus = (Integer) position;
                            }
                            markEnlarged(adapter, total, focus);
                            zoomFourFocus(container, adapter, param.args[1]);
                        } else {
                            menuShrunk.put(container, Boolean.TRUE);
                            fourZoomed.put(container, Boolean.FALSE);
                            markEnlarged(adapter, total, -1);
                            if (total == 4) fitFour(container, 450);
                            else fitThree(container, 450);
                        }
                        param.setResult(null);
                        log("four zoom " + (enlarge ? "in" : "out"));
                    } catch (Throwable error) {
                        log("four zoom failed: " + error);
                    }
                }
            });
            log("four zoom hooked");
        } catch (Throwable error) {
            log("four zoom hook failed: " + error);
        }
    }

    private static void changeZoom(View canvas, Object adapter, int index, boolean enlarge) {
        if (!(canvas.getParent() instanceof View parent) || !focusing.add(canvas)) return;
        try {
            int panes = count(adapter);
            Object value = XposedHelpers.callMethod(canvas, "getRectList");
            if (!(value instanceof java.util.List<?> rects) || rects.size() != panes
                    || index < 0 || index >= panes) return;
            Rect bounds = enlarge ? (Rect) rects.get(index) : overviewBounds(canvas, panes);
            if (bounds == null || bounds.isEmpty()) return;
            Rect area = canvasArea(canvas);
            boolean landscape = isLandscape(canvas);
            int margin = landscape ? 0 : marginPx();
            boolean alignTop = !landscape && !enlarge && panes == 4;
            float top = area.top + (alignTop ? 0 : margin);
            float height = area.bottom - top - margin;
            float scale = Math.min((float) area.width() / bounds.width(), height / bounds.height());
            if (!enlarge) scale = Math.min(1f, scale);
            if (scale <= 0f) return;
            float x = Math.round(overviewX(canvas, area, bounds, scale));
            float y = Math.round(overviewY(canvas, bounds, scale, top, height, alignTop));
            if (enlarge && Integer.valueOf(index).equals(enlargedPanes.get(canvas))
                    && (isZoomAnimating(canvas) || (Math.abs(parent.getScaleX() - scale) < 0.0001f
                            && Math.abs(parent.getTranslationX() - x) <= 1f
                            && Math.abs(parent.getTranslationY() - y) <= 1f))) return;
            if (enlarge) enlargedPanes.put(canvas, index);
            else enlargedPanes.remove(canvas);
            fourZoomed.put(canvas, enlarge);
            XposedHelpers.callMethod(adapter, "I", index);
            markEnlarged(adapter, panes, enlarge ? index : -1);
            setAutoTarget(canvas, x, y, scale);
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(canvas, ANIMATE_SCALE.value(),
                    x, y, scale, 240, 0))) {
                android.animation.Animator animation = (android.animation.Animator)
                        XposedHelpers.callMethod(canvas, "getAnimatorSet");
                zoomAnimations.put(canvas, new java.lang.ref.WeakReference<>(animation));
            }
            log("window zoom " + (enlarge ? "in" : "out") + " pane=" + index
                    + " scale=" + scale + " x=" + x + " y=" + y);
        } finally {
            focusing.remove(canvas);
        }
    }

    private static boolean isZoomAnimating(View canvas) {
        java.lang.ref.WeakReference<android.animation.Animator> reference = zoomAnimations.get(canvas);
        android.animation.Animator animation = reference == null ? null : reference.get();
        return animation != null && animation.isStarted();
    }

    private static void cancelZoom(Object canvas) {
        java.lang.ref.WeakReference<android.animation.Animator> reference = zoomAnimations.remove(canvas);
        android.animation.Animator animation = reference == null ? null : reference.get();
        if (animation != null && animation.isStarted()) animation.cancel();
    }

    private static final int MAIN_WINDOW_ACTION = 1025;
    private static final int AUTO_MAIN_WINDOW_ACTION = 1026;
    private static final int MOVE_MAIN_WINDOW_ACTION = 1027;
    private static final int TWO_PANE_LAYOUT_ACTION = 1028;
    private static final int STACKED_WINDOW_WIDTH_ACTION = 1029;
    private static final int LAYOUT_LOCK_ACTION = 1030;
    private static final String LAYOUT_LOCKED = "layout_locked";
    private static final String AUTO_MAIN_WINDOW = "auto_main_window";
    private static final String MAIN_WINDOW_SIDE = "main_window_side";
    private static final String STACKED_TWO_PANES = "stacked_two_panes";
    private static final float DEFAULT_STACKED_SHARE = 1f / 3f;
    private static final int MIN_STACKED_WIDTH = 30;
    private static final int MIN_TWO_PANE_HEIGHT = 25;
    private static final java.util.WeakHashMap<View, PaneEdgeDrag> paneEdgeDrags =
            new java.util.WeakHashMap<>();

    private static SharedPreferences layoutPrefs(Context context) {
        return context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE);
    }

    private static boolean layoutLocked(Context context) {
        return layoutPrefs(context).getBoolean(LAYOUT_LOCKED, false);
    }

    private static void toggleLayoutLock(View canvas) {
        Context context = canvas.getContext();
        boolean locked = !layoutLocked(context);
        layoutPrefs(context).edit().putBoolean(LAYOUT_LOCKED, locked).apply();
        if (locked) {
            cancelPaneEdgeDrag(canvas);
            for (ColumnDrag drag : columnDrags.values()) drag.clearPreview();
            columnDrags.clear();
        }
        requestPaneLayout(canvas);
        log("layout locked=" + locked);
    }

    private static boolean autoMainWindow(Context context) {
        return context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
                .getBoolean(AUTO_MAIN_WINDOW, false);
    }

    private static int preferredMainPaneMode(Context context) {
        return context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
                .getInt(MAIN_WINDOW_SIDE, 0);
    }

    private static int mainPaneModeOnScreen(View canvas, Object adapter) {
        int preferred = preferredMainPaneMode(canvas.getContext());
        return preferred == 0 ? mainPaneMode(canvas, adapter) : preferred;
    }

    private static boolean isMainPaneReversed(int count, int layoutMode, int preferredMode) {
        return preferredMode != 0 && preferredMode != layoutMode
                && CanvasGeometry.mainPane(count, layoutMode) >= 0;
    }

    private static void moveMainWindow(View canvas, Object adapter) {
        if (pendingMainSwaps.containsKey(canvas) || mainPaneAnimations.containsKey(canvas)) return;
        boolean overview = !isFourZoomed(canvas) && !isZoomAnimating(canvas);
        List<PaneMotion> motions = new ArrayList<>();
        if (overview) {
            for (int index = 0; index < count(adapter); index++) {
                View pane = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
                if (pane != null) motions.add(new PaneMotion(pane));
            }
        }
        int target = mainPaneModeOnScreen(canvas, adapter) == 6 ? 7 : 6;
        canvas.getContext().getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit()
                .putInt(MAIN_WINDOW_SIDE, target).apply();
        cancelZoom(canvas);
        enlargedPanes.remove(canvas);
        fourZoomed.remove(canvas);
        markEnlarged(adapter, count(adapter), -1);
        layoutKeys.remove(canvas);
        XposedHelpers.callMethod(canvas, "setNeedUpdateFlexibleTaskViewFrameFlag", true);
        if (!motions.isEmpty()) pendingMainSwaps.put(canvas, new MainPaneSwap(motions));
        canvas.requestLayout();
    }

    private static String twoPaneHeightKey(boolean landscape, boolean main) {
        return "two_pane_height_" + (landscape ? "landscape_" : "portrait_")
                + (main ? "main" : "secondary");
    }

    private static boolean stackedTwoPanes(Context context) {
        return context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
                .getBoolean(STACKED_TWO_PANES, false);
    }

    private static void toggleTwoPaneLayout(View canvas, Object adapter) {
        Context context = canvas.getContext();
        context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(STACKED_TWO_PANES, !stackedTwoPanes(context)).apply();
        refreshTwoPaneOverview(canvas, adapter);
        log("two pane stacked=" + stackedTwoPanes(context));
    }

    private static void refreshTwoPaneOverview(View canvas, Object adapter) {
        cancelPaneEdgeDrag(canvas);
        cancelMainPaneAnimation(canvas);
        cancelZoom(canvas);
        enlargedPanes.remove(canvas);
        fourZoomed.remove(canvas);
        markEnlarged(adapter, 2, -1);
        requestPaneLayout(canvas);
    }

    private static String legacyStackedWidthKey(boolean top) {
        return top ? "stacked_width_top" : "stacked_width_bottom";
    }

    private static String stackedWidthKey(View canvas, boolean top) {
        return paneLayoutKey(2, DEFAULT_ORIENTATION, isLandscape(canvas), true)
                + (top ? "_top_width" : "_bottom_width");
    }

    private static int stackedWindowWidth(View canvas, boolean top, boolean narrow) {
        var prefs = layoutPrefs(canvas.getContext());
        String suffix = narrow ? "_narrow" : "";
        int legacyWidth = prefs.getInt(legacyStackedWidthKey(top) + suffix, narrow ? 50 : 100);
        return prefs.getInt(stackedWidthKey(canvas, top) + suffix, legacyWidth);
    }

    private static void saveStackedWindowWidth(SharedPreferences.Editor editor, String key, int width) {
        editor.putInt(key, width);
        if (width < 100) editor.putInt(key + "_narrow", width);
    }

    private static boolean isTopStackedWindow(View canvas, Object adapter, int index) {
        int[] order = panePositions(canvas, adapter);
        int slot = order == null ? index : order[index];
        return slot == (preferredMainPaneMode(canvas.getContext()) == 6 ? 1 : 0);
    }

    private static void toggleStackedWindowWidth(View canvas, Object adapter, int index) {
        if (layoutLocked(canvas.getContext())) return;
        boolean top = isTopStackedWindow(canvas, adapter, index);
        int width = stackedWindowWidth(canvas, top, false) < 100 ? 100
                : stackedWindowWidth(canvas, top, true);
        var editor = layoutPrefs(canvas.getContext()).edit();
        saveStackedWindowWidth(editor, stackedWidthKey(canvas, top), width);
        editor.apply();
        refreshTwoPaneOverview(canvas, adapter);
    }

    private static String paneLayoutKey(int count, int mode, boolean landscape, boolean stacked) {
        // 横屏三、四窗以及竖屏四窗的几何布局不依赖系统传入的三窗方向。
        int layoutMode = landscape || count == 4 ? 0 : mode;
        return "pane_size_" + count + "_" + layoutMode
                + (landscape ? "_landscape" : "_portrait") + (stacked ? "_stacked" : "");
    }

    private record PaneInsets(float left, float top, float right, float bottom) {
        static final PaneInsets NONE = new PaneInsets(0, 0, 0, 0);

        Rect apply(Rect slot) {
            return new Rect(slot.left + Math.round(left * slot.width()),
                    slot.top + Math.round(top * slot.height()),
                    slot.right - Math.round(right * slot.width()),
                    slot.bottom - Math.round(bottom * slot.height()));
        }

        PaneInsets move(int horizontal, int vertical, float dx, float dy) {
            return new PaneInsets(horizontal < 0 ? clamp(left + dx, right) : left,
                    vertical < 0 ? clamp(top + dy, bottom) : top,
                    horizontal > 0 ? clamp(right - dx, left) : right,
                    vertical > 0 ? clamp(bottom - dy, top) : bottom);
        }

        private static float clamp(float value, float opposite) {
            return Math.max(0f, Math.min(0.60f - opposite, value));
        }
    }

    private static PaneInsets readPaneInsets(SharedPreferences prefs, String layoutKey, int index) {
        String key = layoutKey + "_pane_" + index;
        return new PaneInsets(prefs.getFloat(key + "_left", 0f), prefs.getFloat(key + "_top", 0f),
                prefs.getFloat(key + "_right", 0f), prefs.getFloat(key + "_bottom", 0f));
    }

    private static void savePaneInsets(SharedPreferences.Editor editor, String layoutKey, int index,
                                       PaneInsets insets) {
        String key = layoutKey + "_pane_" + index;
        editor.putFloat(key + "_left", insets.left())
                .putFloat(key + "_top", insets.top())
                .putFloat(key + "_right", insets.right())
                .putFloat(key + "_bottom", insets.bottom());
    }

    private record PaneEdge(Rect hit, int index, boolean main, boolean leftPane, int horizontal,
                            int vertical, boolean divider) {}

    private static List<PaneEdge> resizeEdges(View canvas) {
        if (layoutLocked(canvas.getContext())) return List.of();
        Object adapter = CanvasApi.adapter(canvas);
        int count = adapter == null ? 0 : count(adapter);
        if (count < 2 || count > 4 || isFourZoomed(canvas)
                || isZoomAnimating(canvas) || mainPaneAnimations.containsKey(canvas)) return List.of();
        WindowInsets insets = canvas.getRootWindowInsets();
        if (insets != null && insets.isVisible(WindowInsets.Type.ime())) return List.of();
        int main = mainPane(canvas, adapter);
        Rect[] panes = new Rect[count];
        for (int index = 0; index < panes.length; index++) {
            View pane = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
            if (pane == null || !pane.isShown()) return List.of();
            Object taskView = XposedHelpers.callMethod(pane, "getFlexibleTaskView");
            if (taskView == null) return List.of();
            Rect bounds = (Rect) XposedHelpers.callMethod(taskView, "calculateFrame");
            if (bounds.isEmpty()) return List.of();
            panes[index] = bounds;
        }
        boolean stacked = count == 2 && stackedTwoPanes(canvas.getContext());
        float density = canvas.getResources().getDisplayMetrics().density;
        int radius = Math.round(7 * density);
        List<PaneEdge> edges = new ArrayList<>(8 * count + 1);
        if (count == 2) addTwoPaneDivider(edges, panes, stacked, radius, density);
        for (int index = 0; index < panes.length; index++) {
            Rect pane = panes[index];
            boolean first = count == 2 && (stacked ? pane.top < panes[1 - index].top
                    : pane.left < panes[1 - index].left);
            boolean isMain = index == main;
            for (int horizontal : new int[]{-1, 1}) {
                int hitLeft = horizontal < 0 ? pane.left : pane.right - radius;
                int hitRight = hitLeft + radius;
                for (int vertical : new int[]{-1, 1}) {
                    int y = vertical < 0 ? pane.top : pane.bottom - 1;
                    edges.add(new PaneEdge(new Rect(hitLeft, y - radius, hitRight, y + radius),
                            index, isMain, first, horizontal, vertical, false));
                }
                edges.add(new PaneEdge(new Rect(hitLeft, pane.top + radius,
                        hitRight, pane.bottom - radius), index, isMain, first, horizontal, 0, false));
            }
            edges.add(new PaneEdge(new Rect(pane.left + radius, pane.top - radius,
                    pane.right - radius, pane.top + radius), index, isMain, first, 0, -1, false));
            edges.add(new PaneEdge(new Rect(pane.left + radius, pane.bottom - radius,
                    pane.right - radius, pane.bottom + radius), index, isMain, first, 0, 1, false));
        }
        return edges;
    }

    private static void addTwoPaneDivider(List<PaneEdge> edges, Rect[] panes, boolean stacked,
                                         int radius, float density) {
        boolean firstInOrder = stacked ? panes[0].top < panes[1].top : panes[0].left < panes[1].left;
        Rect first = panes[firstInOrder ? 0 : 1];
        Rect second = panes[firstInOrder ? 1 : 0];
        int start = stacked ? Math.max(first.left, second.left) : Math.max(first.top, second.top);
        int end = stacked ? Math.min(first.right, second.right) : Math.min(first.bottom, second.bottom);
        int middle = (start + end) / 2;
        int dividerRadius = Math.round(32 * density);
        if (start < end) {
            int low = Math.max(start, middle - dividerRadius);
            int high = Math.min(end, middle + dividerRadius);
            int seam = stacked ? (first.bottom + second.top) / 2 : (first.right + second.left) / 2;
            Rect hit = stacked ? new Rect(low, seam - radius, high, seam + radius)
                    : new Rect(seam - radius, low, seam + radius, high);
            edges.add(new PaneEdge(hit, -1, false, true, stacked ? 0 : 1, stacked ? 1 : 0, true));
        }
    }

    private static void hookPaneResizeTouch(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!CanvasApi.ACTIVITY.equals(param.thisObject.getClass().getName())) return;
                        View canvas = (View) XposedHelpers.callMethod(param.thisObject, ACTIVITY_VIEW.value());
                        if (canvas != null && resizeFromPaneEdge(canvas, (MotionEvent) param.args[0], false)) {
                            param.setResult(true);
                        }
                    }
                });

        Class<?> gestures = XposedHelpers.findClass(CanvasApi.PACKAGE + "A", loader);
        XposedHelpers.findAndHookMethod(gestures, "T", MotionEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View canvas = (View) XposedHelpers.getObjectField(param.thisObject, "h");
                MotionEvent event = (MotionEvent) param.args[0];
                if (canvas == null || !resizeFromPaneEdge(canvas, event, true)) return;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // 蓝条手势由系统画布的输入监听器独占，后续事件会继续走此入口。
                    Object host = XposedHelpers.getObjectField(param.thisObject, "b");
                    XposedHelpers.callMethod(host, "O1");
                }
                param.setResult(true);
            }
        });
    }

    private static void hookPaneContentFit(ClassLoader loader) {
        Class<?> decor = XposedHelpers.findClass(CanvasApi.PACKAGE + "view.EmbeddedViewDecor", loader);
        XposedHelpers.findAndHookMethod(decor, "onLayout", boolean.class,
                int.class, int.class, int.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        View pane = (View) param.thisObject;
                        if (!(pane.getParent() instanceof View canvas)) return;
                        Object adapter = CanvasApi.adapter(canvas);
                        if (adapter == null || count(adapter) < 2 || count(adapter) > 4
                                || Boolean.TRUE.equals(XposedHelpers.callMethod(pane, "K"))) return;
                        View taskView = (View) XposedHelpers.callMethod(pane, "getFlexibleTaskView");
                        Rect launch = (Rect) XposedHelpers.callMethod(pane, "getLaunchRect");
                        if (taskView == null || launch == null || launch.isEmpty()) return;
                        int left = (Integer) param.args[1];
                        int top = (Integer) param.args[2];
                        int width = (Integer) param.args[3] - left;
                        int height = (Integer) param.args[4] - top;
                        if (width <= 0 || height <= 0) return;
                        taskView.layout(0, 0, width, height);
                        Rect content = new Rect(left, top, left + width, top + height);
                        Rect target = new Rect(launch);
                        XposedHelpers.callMethod(pane, "i0", content, target,
                                XposedHelpers.callMethod(pane, "getTaskData"));
                        XposedHelpers.callMethod(taskView, "resize", target);
                        param.setResult(null);
                    }
                });
    }

    private static final class PaneEdgeDrag {
        final PaneEdge edge;
        final CanvasDisplay display;
        final int count;
        final String layoutKey;
        final int slot;
        final Rect slotBounds;
        final PaneInsets startInsets;
        final float startX;
        final float startY;
        final float startShare;
        final int startHeight;
        final int startWidth;
        final float scale;
        boolean resizing;
        PaneInsets insets;
        float share;
        int height;
        int width;

        PaneEdgeDrag(View canvas, PaneEdge edge, MotionEvent event) {
            this.edge = edge;
            display = canvasDisplay(canvas);
            Object adapter = CanvasApi.adapter(canvas);
            count = count(adapter);
            int mode = layoutMode(adapter);
            int index = edge.index();
            if (index >= 0 && CanvasGeometry.mainPane(count, mainPaneMode(canvas, adapter)) >= 0) {
                int[] order = panePositions(canvas, adapter);
                if (order != null) index = order[index];
            }
            layoutKey = paneLayoutKey(count, mode, isLandscape(canvas), display.stacked());
            slot = index;
            List<Rect> slots = basePanes(count, mode, columnShare(canvas, count, mode), display);
            slotBounds = slot >= 0 && slot < slots.size() ? slots.get(slot) : new Rect();
            startInsets = count >= 3 && slot >= 0 ? readPaneInsets(layoutPrefs(canvas.getContext()),
                    layoutKey, slot) : PaneInsets.NONE;
            insets = startInsets;
            startX = event.getRawX();
            startY = event.getRawY();
            startShare = display.stacked() ? display.stackedShare() : columnShare(canvas, count, mode);
            share = startShare;
            startHeight = twoPaneHeightPercent(display, edge.main());
            height = startHeight;
            startWidth = display.stacked() && !edge.divider()
                    ? stackedWindowWidth(canvas, edge.leftPane(), false) : 100;
            width = startWidth;
            scale = (Float) XposedHelpers.callMethod(canvas, "getParentScaleX");
            resizing = !edge.divider();
        }

        boolean matches(View canvas, int currentCount, int mode, boolean stacked) {
            return count == currentCount
                    && layoutKey.equals(paneLayoutKey(currentCount, mode, isLandscape(canvas), stacked));
        }
    }

    private static boolean resizeFromPaneEdge(View canvas, MotionEvent event, boolean dividerStream) {
        int action = event.getActionMasked();
        PaneEdgeDrag drag = paneEdgeDrags.get(canvas);
        if (action == MotionEvent.ACTION_DOWN) {
            for (PaneEdge edge : resizeEdges(canvas)) {
                if (edge.hit().contains((int) event.getRawX(), (int) event.getRawY())) {
                    if (edge.divider() != dividerStream) return false;
                    paneEdgeDrags.put(canvas, new PaneEdgeDrag(canvas, edge, event));
                    return true;
                }
            }
            return false;
        }
        if (drag == null || drag.edge.divider() != dividerStream) return false;
        Object adapter = CanvasApi.adapter(canvas);
        int count = adapter == null ? 0 : count(adapter);
        boolean stacked = count == 2 && stackedTwoPanes(canvas.getContext());
        if (count != drag.count || layoutLocked(canvas.getContext())
                || !drag.matches(canvas, count, layoutMode(adapter), stacked)) {
            cancelPaneEdgeDrag(canvas);
            requestPaneLayout(canvas);
            return true;
        }
        if (!drag.resizing) {
            int slop = android.view.ViewConfiguration.get(canvas.getContext()).getScaledTouchSlop();
            float distance = drag.display.stacked() ? event.getRawY() - drag.startY : event.getRawX() - drag.startX;
            if (action == MotionEvent.ACTION_MOVE && Math.abs(distance) > slop) {
                drag.resizing = true;
            } else {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_POINTER_DOWN) {
                    paneEdgeDrags.remove(canvas);
                    if (action == MotionEvent.ACTION_UP && edgeContainsTap(drag, event, slop)) {
                        showTwoPaneDividerMenu(canvas, event);
                    }
                }
                return true;
            }
        }
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            updatePaneEdgeSize(canvas, drag, event);
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            paneEdgeDrags.remove(canvas);
            if (action == MotionEvent.ACTION_UP) savePaneResize(canvas, drag);
            requestPaneLayout(canvas);
        }
        return true;
    }

    private static void savePaneResize(View canvas, PaneEdgeDrag drag) {
        var editor = layoutPrefs(canvas.getContext()).edit();
        if (drag.share != drag.startShare && drag.count == 2) {
            editor.putFloat(drag.layoutKey + "_share", drag.share);
        }
        if (!drag.insets.equals(drag.startInsets)) {
            savePaneInsets(editor, drag.layoutKey, drag.slot, drag.insets);
        }
        if (drag.count == 2 && !drag.display.stacked() && drag.height != drag.startHeight) {
            editor.putInt(twoPaneHeightKey(drag.display.landscape(), drag.edge.main()), drag.height);
        }
        if (drag.display.stacked() && drag.width != drag.startWidth) {
            saveStackedWindowWidth(editor, stackedWidthKey(canvas, drag.edge.leftPane()), drag.width);
        }
        editor.apply();
        log("window resize count=" + drag.count + " stacked=" + drag.display.stacked()
                + " pane=" + drag.edge.index() + " divider=" + drag.edge.divider());
    }

    private static boolean edgeContainsTap(PaneEdgeDrag drag, MotionEvent event, int slop) {
        return Math.abs(event.getRawX() - drag.startX) <= slop
                && Math.abs(event.getRawY() - drag.startY) <= slop;
    }

    private static void showTwoPaneDividerMenu(View canvas, MotionEvent event) {
        Object bar = fieldOfTypeDeep(canvas, SPLIT_BAR.value());
        Object handle = bar == null ? null : XposedHelpers.callMethod(bar, "X", event);
        if (handle != null) XposedHelpers.callMethod(bar, "q0", handle);
    }

    private static void updatePaneEdgeSize(View canvas, PaneEdgeDrag drag, MotionEvent event) {
        if (drag.scale <= 0f || drag.display.availableHeight() <= 0) return;
        if (drag.count >= 3) {
            if (drag.slotBounds.isEmpty()) return;
            float dx = (event.getRawX() - drag.startX) / (drag.slotBounds.width() * drag.scale);
            float dy = (event.getRawY() - drag.startY) / (drag.slotBounds.height() * drag.scale);
            PaneInsets resize = drag.startInsets.move(drag.edge.horizontal(), drag.edge.vertical(), dx, dy);
            if (!resize.equals(drag.insets)) {
                drag.insets = resize;
                requestPaneLayout(canvas);
            }
            return;
        }
        if (drag.display.stacked()) {
            int previousWidth = drag.width;
            if (drag.edge.horizontal() != 0 && !drag.slotBounds.isEmpty()) {
                float dx = (event.getRawX() - drag.startX) / (drag.slotBounds.width() * drag.scale);
                int width = drag.startWidth + Math.round(200f * drag.edge.horizontal() * dx);
                drag.width = Math.max(MIN_STACKED_WIDTH, Math.min(100, width));
            }
            boolean changed = drag.width != previousWidth;
            if (drag.edge.vertical() == 0) {
                if (changed) requestPaneLayout(canvas);
                return;
            }
            float direction = drag.display.preferredMainPaneMode() == 6 ? -1f : 1f;
            if (!drag.edge.leftPane()) direction = -direction;
            float share = CanvasGeometry.clampLeftShare(drag.startShare + direction * drag.edge.vertical()
                    * (event.getRawY() - drag.startY) / ((drag.display.availableHeight() - columnGap()) * drag.scale));
            if (share != drag.share) {
                drag.share = share;
                changed = true;
            }
            if (changed) requestPaneLayout(canvas);
            return;
        }
        int beforeHeight = drag.height;
        float beforeShare = drag.share;
        int heightChange = Math.round(drag.edge.vertical() * 200f * (event.getRawY() - drag.startY)
                / (drag.display.availableHeight() * drag.scale));
        drag.height = Math.max(MIN_TWO_PANE_HEIGHT, Math.min(100, drag.startHeight + heightChange));
        if (drag.edge.horizontal() != 0) {
            float direction = drag.display.preferredMainPaneMode() == 6 ? -1f : 1f;
            if (!drag.edge.leftPane()) direction = -direction;
            int width = drag.display.area().width() - columnGap();
            if (width > 0) {
                float delta = direction * drag.edge.horizontal() * (event.getRawX() - drag.startX)
                        / (width * drag.scale);
                drag.share = CanvasGeometry.clampLeftShare(drag.startShare + delta);
            }
        }
        if (drag.height != beforeHeight || drag.share != beforeShare) requestPaneLayout(canvas);
    }

    private static void requestPaneLayout(View canvas) {
        layoutKeys.remove(canvas);
        XposedHelpers.callMethod(canvas, "setNeedUpdateFlexibleTaskViewFrameFlag", true);
        canvas.requestLayout();
    }

    private static void hookModernWindowMenu(ClassLoader loader) {
        try {
            Class<?> header = XposedHelpers.findClass(CanvasApi.PACKAGE + "v", loader);
            Class<?> builder = XposedHelpers.findClass("com.coui.appcompat.poplist.L$a", loader);
            Class<?> strings = XposedHelpers.findClass("com.oplus.pscanvas.j", loader);
            int enlargeText = XposedHelpers.getStaticIntField(strings, "W");
            int shrinkText = XposedHelpers.getStaticIntField(strings, "X");
            XposedHelpers.findAndHookMethod(header, "U0", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object owner = param.thisObject;
                    View canvas = (View) XposedHelpers.getObjectField(owner, "n");
                    Object adapter = CanvasApi.adapter(canvas);
                    if (adapter == null || count(adapter) < 2) return;
                    int index = (Integer) XposedHelpers.callMethod(adapter, "getPosition",
                            XposedHelpers.getObjectField(owner, "o"));
                    if (index < 0) return;
                    java.util.List<Object> items = (java.util.List<Object>) XposedHelpers.getObjectField(owner, "X");
                    String enlarge = canvas.getResources().getString(enlargeText);
                    String shrink = canvas.getResources().getString(shrinkText);
                    items.removeIf(item -> {
                        Object title = XposedHelpers.callMethod(item, "t");
                        return enlarge.equals(title) || shrink.equals(title);
                    });
                    boolean enlarged = Integer.valueOf(index).equals(enlargedPanes.get(canvas));
                    items.add(0, windowMenuItem(builder, enlarged ? shrink : enlarge, 0,
                            android.R.drawable.ic_menu_zoom));
                    boolean locked = layoutLocked(canvas.getContext());
                    int main = mainPane(canvas, adapter);
                    if (main >= 0) {
                        items.add(1, autoMainMenuItem(builder, canvas.getContext(),
                                autoMainWindow(canvas.getContext())));
                        boolean onLeft = mainPaneModeOnScreen(canvas, adapter) == 6;
                        boolean stacked = count(adapter) == 2 && stackedTwoPanes(canvas.getContext());
                        items.add(2, windowMenuItem(builder,
                                stacked ? (onLeft ? "主屏移到下方" : "主屏移到上方")
                                        : (onLeft ? "主屏移到右边" : "主屏移到左边"),
                                MOVE_MAIN_WINDOW_ACTION,
                                android.R.drawable.ic_menu_sort_by_size));
                        if (main != index) {
                            items.add(3, windowMenuItem(builder, "设为主窗口", MAIN_WINDOW_ACTION,
                                    android.R.drawable.btn_star_big_off));
                        }
                        if (count(adapter) == 2) {
                            int layoutRow = main == index ? 3 : 4;
                            items.add(layoutRow, windowMenuItem(builder,
                                    stacked ? "切换为左右布局" : "切换为上下布局",
                                    TWO_PANE_LAYOUT_ACTION, android.R.drawable.ic_menu_sort_by_size));
                            if (stacked && !locked) {
                                boolean top = isTopStackedWindow(canvas, adapter, index);
                                int width = stackedWindowWidth(canvas, top, false);
                                items.add(layoutRow + 1, windowMenuItem(builder,
                                        width == 100 ? "切换为窄窗口" : "切换为宽窗口",
                                        STACKED_WINDOW_WIDTH_ACTION, android.R.drawable.ic_menu_crop));
                            }
                        }
                    }
                    items.add(1, windowMenuItem(builder, locked ? "解锁布局" : "锁定布局",
                            LAYOUT_LOCK_ACTION, android.R.drawable.ic_lock_lock));
                }
            });
            XposedHelpers.findAndHookMethod(CanvasApi.PACKAGE + "v$d", loader, "onItemClick",
                    android.widget.AdapterView.class, View.class, int.class, long.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object owner = XposedHelpers.getObjectField(param.thisObject, "c");
                            List<?> items = (List<?>) XposedHelpers.getObjectField(owner, "X");
                            int row = (Integer) param.args[2];
                            if (row < 0 || row >= items.size()) return;
                            int action = (Integer) XposedHelpers.callMethod(items.get(row), "m");
                            if (action != MAIN_WINDOW_ACTION && action != AUTO_MAIN_WINDOW_ACTION
                                    && action != MOVE_MAIN_WINDOW_ACTION && action != TWO_PANE_LAYOUT_ACTION
                                    && action != STACKED_WINDOW_WIDTH_ACTION && action != LAYOUT_LOCK_ACTION) return;
                            param.setResult(null);
                            View canvas = (View) XposedHelpers.getObjectField(owner, "n");
                            Object task = XposedHelpers.getObjectField(owner, "o");
                            Object adapter = CanvasApi.adapter(canvas);
                            if (adapter == null || count(adapter) < 2) return;
                            android.widget.PopupWindow popup = (android.widget.PopupWindow)
                                    XposedHelpers.getObjectField(owner, "p");
                            popup.dismiss();
                            if (action == LAYOUT_LOCK_ACTION) {
                                toggleLayoutLock(canvas);
                                return;
                            }
                            if (mainPane(canvas, adapter) < 0) return;
                            if (action == AUTO_MAIN_WINDOW_ACTION) {
                                Context context = canvas.getContext();
                                boolean enabled = !autoMainWindow(context);
                                context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE).edit()
                                        .putBoolean(AUTO_MAIN_WINDOW, enabled).apply();
                                log("auto main window " + (enabled ? "on" : "off"));
                                return;
                            }
                            if (action == MOVE_MAIN_WINDOW_ACTION) {
                                moveMainWindow(canvas, adapter);
                                return;
                            }
                            if (action == TWO_PANE_LAYOUT_ACTION) {
                                if (count(adapter) == 2) toggleTwoPaneLayout(canvas, adapter);
                                return;
                            }
                            int index = (Integer) XposedHelpers.callMethod(adapter, "getPosition", task);
                            if (index < 0) return;
                            if (action == STACKED_WINDOW_WIDTH_ACTION) {
                                if (count(adapter) == 2 && stackedTwoPanes(canvas.getContext())) {
                                    toggleStackedWindowWidth(canvas, adapter, index);
                                }
                                return;
                            }
                            selectMainPane(canvas, adapter, index);
                        }
                    });
            log("modern window menu hooked");
        } catch (Throwable error) {
            log("modern window menu failed: " + error);
        }
    }

    private static Object windowMenuItem(Class<?> type, String label, int id, int icon) {
        return XposedHelpers.callMethod(windowMenuBuilder(type, label, id, icon), "y");
    }

    private static Object windowMenuBuilder(Class<?> type, String label, int id, int icon) {
        Object builder = XposedHelpers.newInstance(type);
        XposedHelpers.callMethod(builder, "D", icon);
        XposedHelpers.callMethod(builder, "E", id);
        XposedHelpers.callMethod(builder, "J", label);
        XposedHelpers.callMethod(builder, "G", true);
        return builder;
    }

    private static Object autoMainMenuItem(Class<?> type, Context context, boolean enabled) {
        int uiMode = context.getResources().getConfiguration().uiMode;
        boolean dark = (uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int color = enabled ? (dark ? 0xfff5f5f5 : 0xff000000)
                : (dark ? 0xffa7a7a7 : 0xff808080);
        Object builder = windowMenuBuilder(type, "自动切换主屏", AUTO_MAIN_WINDOW_ACTION, 0);
        XposedHelpers.setObjectField(builder, "m", ColorStateList.valueOf(color));
        android.graphics.drawable.Drawable icon = context.getDrawable(android.R.drawable.ic_menu_rotate);
        if (icon != null) {
            icon = icon.mutate();
            icon.setTint(color);
            XposedHelpers.callMethod(builder, "C", icon);
            XposedHelpers.setIntField(builder, "f", XposedHelpers.getIntField(builder, "f") & ~1);
        }
        return XposedHelpers.callMethod(builder, "y");
    }

    private static int mainPane(Object canvas, Object adapter) {
        int slot = CanvasGeometry.mainPane(count(adapter), mainPaneMode((View) canvas, adapter));
        if (slot < 0) return slot;
        return (Integer) XposedHelpers.callMethod(adapter, "getPosition", paneOrder(canvas, adapter).get(slot));
    }

    private static int mainPaneMode(View canvas, Object adapter) {
        return isLandscape(canvas) ? DEFAULT_ORIENTATION : layoutMode(adapter);
    }

    private static List<Object> paneOrder(Object canvas, Object adapter) {
        int count = count(adapter);
        List<Object> current = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            current.add(XposedHelpers.callMethod(adapter, "getItem", index));
        }
        List<Object> saved = tasksBySlot.get(canvas);
        if (saved != null && saved.size() == count && current.containsAll(saved)) return saved;
        tasksBySlot.put(canvas, current);
        return current;
    }

    private static int[] panePositions(Object canvas, Object adapter) {
        List<Object> tasks = paneOrder(canvas, adapter);
        int[] result = new int[tasks.size()];
        for (int slot = 0; slot < tasks.size(); slot++) {
            int index = (Integer) XposedHelpers.callMethod(adapter, "getPosition", tasks.get(slot));
            if (index < 0 || index >= tasks.size()) return null;
            result[index] = slot;
        }
        return result;
    }

    private static boolean selectMainPane(View canvas, Object adapter, int index) {
        if (pendingMainSwaps.containsKey(canvas) || mainPaneAnimations.containsKey(canvas)) return false;
        int main = mainPane(canvas, adapter);
        if (main < 0 || main == index) return false;
        List<Object> slots = paneOrder(canvas, adapter);
        Object task = XposedHelpers.callMethod(adapter, "getItem", index);
        int slot = slots.indexOf(task);
        boolean overview = !isFourZoomed(canvas) && !isZoomAnimating(canvas);
        View selected = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
        View previous = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), main);
        if (!CanvasGeometry.swapMainPane(slots, mainPaneMode(canvas, adapter), slot)) return false;
        cancelZoom(canvas);
        if (overview && selected != null && previous != null) {
            pendingMainSwaps.put(canvas, new MainPaneSwap(
                    List.of(new PaneMotion(selected), new PaneMotion(previous))));
        }
        enlargedPanes.remove(canvas);
        fourZoomed.remove(canvas);
        XposedHelpers.callMethod(adapter, "I", index);
        markEnlarged(adapter, count(adapter), -1);
        layoutKeys.remove(canvas);
        canvas.requestLayout();
        log("main pane selected index=" + index + " previous=" + main);
        return true;
    }

    private static void hookMainPaneSwap(ClassLoader loader) {
        try {
            Class<?> gestures = XposedHelpers.findClass(CanvasApi.PACKAGE + "A", loader);
            XposedHelpers.findAndHookMethod(gestures, "M", MotionEvent.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    MotionEvent touch = (MotionEvent) param.args[0];
                    if (touch.getActionMasked() != MotionEvent.ACTION_DOWN
                            || touch.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS) return;
                    View canvas = (View) XposedHelpers.getObjectField(param.thisObject, "h");
                    Object adapter = CanvasApi.adapter(canvas);
                    if (adapter == null || CanvasGeometry.mainPane(count(adapter), mainPaneMode(canvas, adapter)) < 0
                            || !autoMainWindow(canvas.getContext()) || isFourZoomed(canvas)
                            || XposedHelpers.getBooleanField(canvas, "T")) return;
                    for (PaneEdge edge : resizeEdges(canvas)) {
                        if (edge.hit().contains((int) touch.getRawX(), (int) touch.getRawY())) return;
                    }
                    int index = XposedHelpers.getIntField(param.thisObject, "o");
                    if (index < 0 || index >= count(adapter) || index == mainPane(canvas, adapter)) return;
                    int x = (int) touch.getRawX();
                    int y = (int) touch.getRawY();
                    Object popup = XposedHelpers.getObjectField(canvas, "W");
                    if (popup instanceof android.widget.PopupWindow menu && menu.isShowing()) return;
                    int hit = (Integer) XposedHelpers.callMethod(canvas, "k0",
                            x, y);
                    if (index != hit) return;
                    Object decor = XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
                    if (decor == null) return;
                    Object taskView = XposedHelpers.callMethod(decor, "getFlexibleTaskView");
                    if (taskView == null) return;
                    XposedHelpers.callMethod(taskView, "calculateFrame");
                    Rect header = (Rect) XposedHelpers.callMethod(taskView, "calculateHandleFrame");
                    if (header.contains(x, y) || !selectMainPane(canvas, adapter, index)) return;
                    // 手指落下时已换窗，后续的同一笔触交给画布结束。
                    Object activity = XposedHelpers.getObjectField(param.thisObject, "b");
                    XposedHelpers.callMethod(activity, "O1");
                    XposedHelpers.setIntField(param.thisObject, "o", -2);
                    log("auto main window selected on touch down index=" + index);
                }
            });
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedHelpers.findAndHookMethod(view, "onLayout", boolean.class,
                    int.class, int.class, int.class, int.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            View canvas = (View) param.thisObject;
                            MainPaneSwap swap = pendingMainSwaps.remove(canvas);
                            if (swap != null) swap.start(canvas);
                        }
                    });
            log("automatic main window hooked");
        } catch (Throwable error) {
            log("automatic main window hook failed: " + error);
        }
    }

    private static void cancelMainPaneAnimation(Object canvas) {
        pendingMainSwaps.remove(canvas);
        ValueAnimator animation = mainPaneAnimations.remove(canvas);
        if (animation != null) animation.cancel();
    }

    private record MainPaneSwap(List<PaneMotion> panes) {
        void start(View canvas) {
            List<PaneMotion> moving = new ArrayList<>(panes.size());
            for (PaneMotion pane : panes) {
                if (pane.prepare(canvas)) moving.add(pane);
            }
            if (moving.isEmpty()) return;
            Object adapter = CanvasApi.adapter(canvas);
            boolean uniform = adapter != null && count(adapter) == 2;
            ValueAnimator animation = ValueAnimator.ofFloat(0f, 1f);
            animation.setDuration(260);
            animation.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
            animation.addUpdateListener(animator -> {
                float progress = (Float) animator.getAnimatedValue();
                for (PaneMotion pane : moving) pane.apply(progress, uniform);
            });
            animation.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animator) {
                    for (PaneMotion pane : moving) pane.reset();
                    if (mainPaneAnimations.remove(canvas, animation) && canvas.isAttachedToWindow()) {
                        XposedHelpers.callMethod(canvas, "z2");
                        XposedHelpers.callMethod(canvas, "v2");
                        log("main pane swap animation finished");
                    }
                }
            });
            mainPaneAnimations.put(canvas, animation);
            for (PaneMotion pane : moving) pane.apply(0f, uniform);
            log("main pane swap animation started panes=" + moving.size());
            animation.start();
        }
    }

    private static final class PaneMotion {
        private final View pane;
        private final Rect start;
        private Rect end;
        private final boolean pivotSet;
        private final float pivotX;
        private final float pivotY;

        PaneMotion(View pane) {
            this.pane = pane;
            start = bounds(pane);
            pivotSet = pane.isPivotSet();
            pivotX = pane.getPivotX();
            pivotY = pane.getPivotY();
        }

        private static Rect bounds(View view) {
            return new Rect(view.getLeft(), view.getTop(), view.getRight(), view.getBottom());
        }

        boolean prepare(View canvas) {
            end = bounds(pane);
            return pane.getParent() == canvas && !start.isEmpty() && !end.isEmpty() && !start.equals(end);
        }

        void apply(float progress, boolean uniform) {
            float remaining = 1f - progress;
            // 从交换前的窗格边缘移动到交换后的边缘。
            float scaleX = 1f + ((float) start.width() / end.width() - 1f) * remaining;
            float scaleY = 1f + ((float) start.height() / end.height() - 1f) * remaining;
            if (uniform) scaleX = scaleY = Math.min(scaleX, scaleY);
            pane.setPivotX(0f);
            pane.setPivotY(0f);
            pane.setScaleX(scaleX);
            pane.setScaleY(scaleY);
            pane.setTranslationX((start.exactCenterX() - end.exactCenterX()) * remaining
                    + end.width() * (1f - scaleX) / 2f);
            pane.setTranslationY((start.exactCenterY() - end.exactCenterY()) * remaining
                    + end.height() * (1f - scaleY) / 2f);
        }

        void reset() {
            apply(1f, false);
            if (pivotSet) {
                pane.setPivotX(pivotX);
                pane.setPivotY(pivotY);
            } else {
                pane.resetPivot();
            }
        }
    }

    /** 把焦点窗口缩放到铺满屏幕。 */
    private static void zoomFourFocus(Object container, Object adapter, Object task) {
        try {
            int focus = 0;
            Object position = XposedHelpers.callMethod(adapter, "getPosition", task);
            if (position instanceof Integer && (Integer) position >= 0) {
                focus = (Integer) position;
            }
            Object rects = XposedHelpers.callMethod(container, "getRectList");
            android.content.Context context = ((android.view.View) container).getContext();
            Object list = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(UTIL.value(), container.getClass().getClassLoader()),
                    SCALE_TARGET.value(), context, rects,
                    XposedHelpers.callMethod(adapter, "n"),
                    XposedHelpers.callMethod(adapter, "v"),
                    focus);
            if (!(list instanceof java.util.List) || ((java.util.List<?>) list).isEmpty()) {
                log("four zoom: a0 empty");
                return;
            }
            java.util.List<?> points = (java.util.List<?>) list;
            Object last = points.get(points.size() - 1);
            float tx = XposedHelpers.getFloatField(last, "left");
            float ty = XposedHelpers.getFloatField(last, "top");
            float scale = XposedHelpers.getFloatField(last, "right");
            XposedHelpers.callMethod(adapter, "I", focus);
            XposedHelpers.callMethod(container, ANIMATE_SCALE.value(), tx, ty, scale, 450, 0);
        } catch (Throwable error) {
            log("four zoom focus failed: " + error);
        }
    }

    /** 画布内容的整体矩形。 */
    private static Rect contentRect(Object container) {
        try {
            Object rects = XposedHelpers.callMethod(container, "getRectList");
            if (!(rects instanceof List<?> panes)) return null;
            Rect union = new Rect();
            for (Object item : panes) {
                if (item instanceof Rect pane) union.union(pane);
            }
            return union.isEmpty() ? null : union;
        } catch (Throwable error) {
            return null;
        }
    }

    /** 放大时按焦点窗口的位置算平移量，装不下就顶部对齐。 */
    private static float focusAlign(Object container, Object adapter, Rect union,
                                    float scale, int viewTop, float topInset,
                                    float bottomInset, int parentHeight) {
        try {
            Object focus = XposedHelpers.callMethod(adapter, "h");
            if (!(focus instanceof Integer)) return Float.NaN;
            Object rects = XposedHelpers.callMethod(container, "getRectList");
            if (!(rects instanceof java.util.List)) return Float.NaN;
            java.util.List<?> list = (java.util.List<?>) rects;
            int index = (Integer) focus;
            if (index < 0 || index >= list.size()) return Float.NaN;
            Object item = list.get(index);
            if (!(item instanceof Rect)) return Float.NaN;
            Rect rect = (Rect) item;
            float top = (viewTop + rect.top) * scale;
            float height = (rect.bottom - rect.top) * scale;
            float avail = parentHeight - topInset - bottomInset;
            if (height <= avail) {
                return topInset + (avail - height) / 2f - top;
            }
            return topInset - top;
        } catch (Throwable error) {
            return Float.NaN;
        }
    }

    /** 布局后的基准平移量，输入法偏移以它为原点。 */
    private static final java.util.WeakHashMap<Object, Float> layoutTy =
            new java.util.WeakHashMap<>();

    /** 输入法正把画布抬高。只有这时才需要补挖洞，平时别碰框架原本的触摸区域。 */
    private static volatile boolean imeActive;

    /**
     * 输入法高度变化：o1(int) 是画布自己从 window insets 里读到的 IME 高度，
     * 键盘弹出时把画布往上抬到焦点窗口底边露出来，收起回到基准位置。
     */
    private static void shiftForIme(XC_MethodHook.MethodHookParam param) {
        try {
            int ime = (Integer) param.args[0];
            Object view = XposedHelpers.callMethod(param.thisObject, ACTIVITY_VIEW.value());
            if (view == null) return;
            Float base = layoutTy.get(view);
            if (base == null) return;
            android.view.View parent = (android.view.View)
                    ((android.view.View) view).getParent();
            if (parent == null) return;
            int shift = 0;
            // 二窗是系统原生分屏，键盘交给系统自己处理，这里只增强三窗和四窗
            Object adapter = CanvasApi.adapter(view);
            if (ime > 200 && adapter != null && count(adapter) >= 3) {
                shift = coveredByIme(view, parent, base, ime);
                if (shift < 0) shift = ime;
                shift = Math.min(shift, ime);
            }
            parent.setTranslationY(base - shift);
            moveFocusTask(view, shift, ime);
            imeActive = shift > 0;
            log("ime shift " + shift);
        } catch (Throwable error) {
            log("ime shift failed: " + error);
        }
    }

    /** 键盘弹起前的窗口位置，恢复时按它回去。 */
    private static final java.util.WeakHashMap<Object, Rect> taskOrigin =
            new java.util.WeakHashMap<>();

    /**
     * 把焦点任务的窗口整体上移，让窗口和键盘不再重叠。
     *
     * 系统给嵌入式窗口算 IME inset 只看它自己的 frame，和我们挪 View 无关。
     * 窗口一旦移出键盘区域，inset 就是 0，微信、抖音这些会自己处理键盘的
     * 应用就不会再调整一遍，画布抬起只做一次。
     *
     * 基准用 mLaunchBounds（窗口逻辑尺寸），getBoundsOnScreen 是缩放后的
     * 视觉矩形，拿它当 bounds 会把应用窗口改小，画面直接变形。
     */
    private static void moveFocusTask(Object container, int shift, int ime) {
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null) return;
            Object focus = XposedHelpers.callMethod(adapter, "h");
            int index = focus instanceof Integer ? (Integer) focus : 0;
            Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), index);
            if (decor == null) return;
            Object taskView = XposedHelpers.callMethod(decor, "getFlexibleTaskView");
            if (taskView == null) return;
            Object token = XposedHelpers.getObjectField(taskView, "mTaskToken");
            if (token == null) return;
            Rect origin = taskOrigin.get(taskView);
            if (origin == null) {
                Object launch = launchBoundsOf(taskView);
                if (!(launch instanceof Rect) || ((Rect) launch).isEmpty()) return;
                origin = new Rect((Rect) launch);
                taskOrigin.put(taskView, origin);
            }
            Rect bounds = new Rect(origin);
            android.view.View parent = (android.view.View)
                    ((android.view.View) container).getParent();
            if (shift > 0) {
                // 窗口底边直接对齐到键盘顶边，别再按缩放比例换算，误差会被放大
                int keyboardTop = parent == null ? bounds.bottom : parent.getHeight() - ime;
                int offset = Math.max(0, bounds.bottom - keyboardTop);
                bounds.offset(0, -offset);
                log("task move " + offset + " parent=" + (parent == null ? -1 : parent.getHeight()));
            } else {
                taskOrigin.remove(taskView);
            }
            Object wct = XposedHelpers.newInstance(XposedHelpers.findClass(
                    "android.window.WindowContainerTransaction",
                    container.getClass().getClassLoader()));
            XposedHelpers.callMethod(wct, "setBounds", token, bounds);
            Object organizer = XposedHelpers.callMethod(taskView, "getTaskOrganizer");
            if (organizer != null) {
                XposedHelpers.callMethod(organizer, "applyTransaction", wct);
            }
            log("task bounds " + bounds);
        } catch (Throwable error) {
            log("task bounds failed: " + error);
        }
    }

    /**
     * 焦点窗口被键盘挡住的高度。view 可能已经停在上一轮的抬升位置上，
     * 先减掉当前的偏移量换回原位再算。算不出来返回 -1，调用方按抬满处理。
     */
    private static int coveredByIme(Object container, android.view.View parent,
                                    float base, int ime) {
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null) return -1;
            int total = count(adapter);
            if (total < 2) return -1;
            Object focus = XposedHelpers.callMethod(adapter, "h");
            int index = focus instanceof Integer ? (Integer) focus : 0;
            if (index < 0 || index >= total) index = 0;
            Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), index);
            if (decor == null) return -1;
            Object taskView = XposedHelpers.callMethod(decor, "getFlexibleTaskView");
            if (!(taskView instanceof android.view.View)) return -1;
            Rect rect = new Rect();
            XposedHelpers.callMethod(taskView, "getBoundsOnScreen", rect);
            if (rect.isEmpty()) return -1;
            float delta = parent.getTranslationY() - base;
            int bottom = Math.round(rect.bottom - delta);
            int keyboardTop = parent.getHeight() - ime;
            return Math.max(0, bottom - keyboardTop);
        } catch (Throwable error) {
            return -1;
        }
    }

    /** 画布上下留的边距。 */
    private static int marginPx() {
        float margin = CanvasApi.isColorOs17() ? 4f : 20f;
        return Math.round(margin * android.content.res.Resources
                .getSystem().getDisplayMetrics().density);
    }

    /** 三窗总览：内容整体缩放到可用区域。 */
    private static void fitThree(Object container, int duration) {
        fitOverview(container, duration, 3);
    }

    private static void fitOverview(Object container, int duration, int panes) {
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null || count(adapter) != panes) return;
            Rect union = contentRect(container);
            if (union == null) return;
            android.view.View view = (android.view.View) container;
            android.view.View parent = (android.view.View) view.getParent();
            if (parent == null) return;
            float scale = overviewScale(parent.getWidth(), union,
                    parent.getHeight() - statusBarHeight() - marginPx() * 2f);
            if (scale <= 0f) return;
            // 平移量由 A2 的 hook 统一算
            XposedHelpers.callMethod(container, ANIMATE_SCALE.value(),
                    0f, 0f, scale, duration, 0);
            log("fit " + panes + " windows scale=" + scale);
        } catch (Throwable error) {
            log("fit " + panes + " windows failed: " + error);
        }
    }

    private static float overviewScale(int width, Rect union, float availableHeight) {
        if (union.isEmpty()) return 0f;
        return Math.min(1f, Math.min((float) width / union.width(),
                availableHeight / union.height()));
    }

    /** 四等分：画布缩放到 2×2 总尺寸正好放进屏幕。 */
    private static void fitFour(Object container, int duration) {
        if (CanvasApi.isColorOs17()) {
            fitOverview(container, duration, 4);
            return;
        }
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null || count(adapter) != 4) return;
            Rect cell = phoneSize();
            int gap = Math.round(10f * android.content.res.Resources
                    .getSystem().getDisplayMetrics().density);
            android.view.View view = (android.view.View) container;
            android.view.View parent = (android.view.View) view.getParent();
            if (parent == null) return;
            // 上下各留一点，别贴着状态栏和屏幕底
            float avail = parent.getHeight() - statusBarHeight() - marginPx() * 2;
            float fit = Math.min(
                    (float) view.getWidth() / (2 * cell.width() + gap),
                    avail / (2 * cell.height() + gap));
            if (fit <= 0f || fit > 1f) return;
            // 平移量交给 A2 的 hook 统一算
            XposedHelpers.callMethod(container, ANIMATE_SCALE.value(), 0f, 0f, fit, duration, 4);
        } catch (Throwable error) {
            log("four fit failed: " + error);
        }
    }

    private static float overviewX(View canvas, Rect area, Rect union, float scale) {
        return area.left + (area.width() - union.width() * scale) / 2f
                - (canvas.getLeft() + union.left) * scale;
    }

    private static float overviewY(View canvas, Rect union, float scale,
                                   float topInset, float availableHeight, boolean alignTop) {
        float top = alignTop ? topInset : topInset + (availableHeight - union.height() * scale) / 2f;
        return top
                - (canvas.getTop() + union.top) * scale;
    }

    private static Rect overviewBounds(View canvas, int count) {
        if (count < 3) return contentRect(canvas);
        Object adapter = CanvasApi.adapter(canvas);
        if (adapter == null) return contentRect(canvas);
        int mode = layoutMode(adapter);
        CanvasDisplay display = canvasDisplay(canvas);
        for (PaneInsets insets : display.paneInsets()) {
            if (insets == null) continue;
            // 缩小单窗时仍用原来的总览坐标，其他窗格不随拖动平移。
            Rect union = new Rect();
            for (Rect slot : basePanes(count, mode, columnShare(canvas, count, mode), display)) {
                union.union(slot);
            }
            if (!union.isEmpty()) return union;
        }
        return contentRect(canvas);
    }

    /** 在同一次布局里提交窗格和画布坐标，避免新窗格先用上一套平移量画一帧。 */
    private static void applyOverview(View canvas, int panes) {
        if (!(canvas.getParent() instanceof View parent)) return;
        Rect union = overviewBounds(canvas, panes);
        if (union == null) return;
        Rect area = canvasArea(canvas);
        boolean landscape = isLandscape(canvas);
        int margin = landscape ? 0 : marginPx();
        boolean alignTop = !landscape && panes == 4;
        float topInset = area.top + (alignTop ? 0 : margin);
        float availableHeight = area.bottom - topInset - margin;
        float scale = overviewScale(area.width(), union, availableHeight);
        if (scale <= 0f) return;
        float x = Math.round(overviewX(canvas, area, union, scale));
        float y = Math.round(overviewY(canvas, union, scale, topInset, availableHeight, alignTop));
        setAutoTarget(canvas, x, y, scale);

        android.view.WindowInsets insets = canvas.getRootWindowInsets();
        boolean keyboardVisible = insets != null && insets.isVisible(android.view.WindowInsets.Type.ime());
        if (!keyboardVisible && parent.getTranslationY() != y) parent.setTranslationY(y);
        if (parent.getTranslationX() != x) parent.setTranslationX(x);
        if (parent.getScaleX() != scale) parent.setScaleX(scale);
        if (parent.getScaleY() != scale) parent.setScaleY(scale);
        XposedHelpers.setFloatField(canvas, "A0", x);
        XposedHelpers.setFloatField(canvas, "B0", parent.getTranslationY());
        XposedHelpers.setFloatField(canvas, "C0", scale);
        XposedHelpers.callMethod(canvas, "setNeedUpdateFlexibleTaskViewFrameFlag", true);
    }

    private static void setAutoTarget(View canvas, float x, float y, float scale) {
        XposedHelpers.setFloatField(canvas, "o0", x);
        XposedHelpers.setFloatField(canvas, "p0", y);
        XposedHelpers.setFloatField(canvas, "q0", scale);
    }

    /**
     * 拖拽条上的"交换"走 ContainerView.k0(E.c)，里面对 getRectList() 的每个下标
     * 取 q0(i) 再 B2()。矩形数比子 view 多的时候 q0 返回 null，直接 NPE 把画布
     * 干掉（表现是当前应用突然全屏）。这里先补一次子 view 再让它继续。
     */
    private static void hookExchange(ClassLoader loader) {
        try {
            Class<?> container = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            Class<?> barItem = XposedHelpers.findClass(
                    SPLIT_BAR_ITEM.value(), loader);
            XposedHelpers.findAndHookMethod(container, EXCHANGE_TASKS.value(), barItem, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        Object rects = XposedHelpers.callMethod(self, "getRectList");
                        int want = rects instanceof java.util.List
                                ? ((java.util.List<?>) rects).size() : 0;
                        int have = embeddedCount(self);
                        if (have < want) {
                            log("exchange fill views " + have + "/" + want);
                            XposedHelpers.callMethod(self, PREPARE_VIEWS.value());
                            have = embeddedCount(self);
                        }
                        if (have < want) {
                            param.setResult(Boolean.FALSE);
                            log("exchange skipped " + have + "/" + want);
                        }
                    } catch (Throwable error) {
                        log("exchange guard failed: " + error);
                    }
                }
            });
            log("exchange hooked");
        } catch (Throwable error) {
            log("exchange hook failed: " + error);
        }
    }

    private static int embeddedCount(Object container) {
        try {
            Object list = XposedHelpers.callMethod(container, "getChildEmbeddedViewList");
            return list instanceof java.util.List ? ((java.util.List<?>) list).size() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * 快捷方式/广播带 4 个 intent 启动画布时，ContainerActivity 会把整个 list
     * 交给 FlexibleWindowManager.calculateFlexibleWindowBounds(List, int, Bundle)，
     * 服务端 calculateFlexibleWindowBoundList 里写死 if (size > 3) 返回空 Bundle，
     * 画布就当成"应用不支持"把自己关掉（闪一下就没了）。
     * 四窗时绕开那条链路，逐个拿单个 intent 的 bounds 再拼回去。
     */
    private static void hookFourIntents(ClassLoader loader) {
        try {
            Class<?> manager = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.FlexibleWindowManager", loader);
            XposedHelpers.findAndHookMethod(manager, "calculateFlexibleWindowBounds",
                    java.util.List.class, int.class, android.os.Bundle.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object input = param.args[0];
                                if (!(input instanceof java.util.List)) return;
                                java.util.List<?> intents = (java.util.List<?>) input;
                                if (intents.size() <= 3) return;
                                java.util.ArrayList<android.os.Bundle> bundles =
                                        new java.util.ArrayList<>();
                                for (Object item : intents) {
                                    if (!(item instanceof Intent)) return;
                                    Intent mirror = new Intent((Intent) item);
                                    mirror.putExtra("androidx.activity.LaunchScenario", 2);
                                    Object single = XposedHelpers.callMethod(param.thisObject,
                                            "calculateFlexibleWindowBounds", mirror, -2, 0);
                                    if (!(single instanceof android.os.Bundle)) return;
                                    bundles.add((android.os.Bundle) single);
                                }
                                android.os.Bundle out = new android.os.Bundle();
                                out.putParcelableArrayList(
                                        "androidx.flexible.layout.info.list", bundles);
                                param.setResult(out);
                                log("four intents bounds " + bundles.size());
                            } catch (Throwable error) {
                                log("four intents bounds failed: " + error);
                            }
                        }
                    });
            log("four intents hooked");
        } catch (Throwable error) {
            log("four intents hook failed: " + error);
        }
    }

    /**
     * 浮窗（switchToZoom）只写了 2/3 窗：
     * I.k0() 里 if (i4 != 3) return false，I.i0() 也只认 2/3，
     * I.n0() 还要求 adapter count == 传入的窗数。
     * 四窗补一条等价分支：其余三个窗口缩到三窗布局，焦点窗口转浮窗。
     */
    private static void hookZoomFour(ClassLoader loader) {
        try {
            final Class<?> zoom = XposedHelpers.findClass(
                    TO_FLOATING.value(), loader);
            final Class<?> taskData = XposedHelpers.findClass(TASK.value(), loader);
            final Class<?> taskView = XposedHelpers.findClass(
                    "com.oplus.flexiblewindow.FlexibleTaskView", loader);
            final Class<?> animClass = XposedHelpers.findClass(
                    TO_FLOATING.value() + "$j", loader);
            java.lang.reflect.Constructor<?> ctor = null;
            try {
                ctor = animClass.getDeclaredConstructor(
                        zoom, Rect.class, Rect.class, taskView, taskData, boolean.class);
                ctor.setAccessible(true);
            } catch (Throwable error) {
                log("zoom anim ctor failed: " + error);
            }
            final java.lang.reflect.Constructor<?> animCtor = ctor;

            XposedHelpers.findAndHookMethod(zoom, FLOATING_CAN_ANIMATE.value(), int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int index = (Integer) param.args[0];
                                Object container = XposedHelpers.getObjectField(
                                        param.thisObject, "e");
                                Object adapter = XposedHelpers.callMethod(container, "getAdapter");
                                int total = (Integer) XposedHelpers.callMethod(adapter, "getCount");
                                log("n0 " + index + "/" + param.args[1] + " total=" + total);
                                if (total == 4 && index >= 0 && index < 4) {
                                    param.setResult(Boolean.TRUE);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            // i0/k0 在同类里会被 ART 内联，hook 不到，改在最外层 y0 改参数：
            // 四窗按三窗动画流程走，焦点在 3 时正好对上。
            if (!CanvasApi.isColorOs17()) XposedHelpers.findAndHookMethod(zoom, "y0", int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object self = param.thisObject;
                                Object container = XposedHelpers.getObjectField(
                                        self, "e");
                                Object adapter = XposedHelpers.callMethod(container, "getAdapter");
                                int total = (Integer) XposedHelpers.callMethod(adapter, "getCount");
                                if (total < 4) return;
                                log("y0 four focus=" + param.args[0] + " total=" + total);
                                param.args[1] = 3;
                            } catch (Throwable ignored) {
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(zoom, FLOATING_START_RECTS.value(), int.class, int.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if ((Integer) param.args[1] != 4) return;
                                Object result = param.getResult();
                                log("i0 four focus=" + param.args[0] + " result=" + result);
                                if (result instanceof java.util.List
                                        && !((java.util.List<?>) result).isEmpty()) return;
                                int focus = (Integer) param.args[0];
                                Object self = param.thisObject;
                                Object container = XposedHelpers.getObjectField(
                                        self, "e");
                                java.util.ArrayList<Rect> rects = new java.util.ArrayList<>();
                                for (int i = 0; i < 4; i++) {
                                    if (i == focus) {
                                        rects.add(new Rect());
                                        continue;
                                    }
                                    Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), i);
                                    Object rect = XposedHelpers.callMethod(self, FLOATING_SOURCE_RECT.value(), decor);
                                    log("i0 rect[" + i + "]=" + rect);
                                    if (!(rect instanceof Rect)
                                            || ((Rect) rect).width() <= 0
                                            || ((Rect) rect).height() <= 0) return;
                                    rects.add(new Rect((Rect) rect));
                                }
                                param.setResult(rects);
                            } catch (Throwable error) {
                                log("i0 four failed: " + error);
                            }
                        }
                    });

            XposedHelpers.findAndHookMethod(zoom, FLOATING_ANIMATION.value(), java.util.List.class,
                    int.class, int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                int total = (Integer) param.args[2];
                                if (total != 4) return;
                                if (animCtor == null) return;
                                int focus = (Integer) param.args[1];
                                java.util.List<?> starts = (java.util.List<?>) param.args[0];
                                Object self = param.thisObject;
                                Object container = XposedHelpers.getObjectField(
                                        self, "e");
                                Object adapter = XposedHelpers.callMethod(container, "getAdapter");
                                Object controller = XposedHelpers.callMethod(
                                        container, "getContainerController");
                                Object context = XposedHelpers.getObjectField(self, "c");
                                ClassLoader appLoader = container.getClass().getClassLoader();
                                java.util.ArrayList<Object> rest = new java.util.ArrayList<>();
                                for (int i = 0; i < 4; i++) {
                                    if (i != focus) {
                                        rest.add(XposedHelpers.callMethod(controller, "l", i));
                                    }
                                }
                                XposedHelpers.callMethod(container, REFRESH_TASK_DATA.value(), context, rest, 0);
                                Object computed = XposedHelpers.callStaticMethod(
                                        XposedHelpers.findClass(UTIL.value(), appLoader),
                                        CALCULATE_RECTS.value(), context, rest, 0, false);
                                if (!(computed instanceof java.util.List)
                                        || ((java.util.List<?>) computed).size() != 3) {
                                    param.setResult(Boolean.FALSE);
                                    return;
                                }
                                java.util.List<Object> targets =
                                        new java.util.ArrayList<Object>((java.util.List<?>) computed);
                                targets.add(focus, new Rect());
                                Object sources = XposedHelpers.getObjectField(self, "O");
                                for (int i = 0; i < 4; i++) {
                                    if (!(Boolean) XposedHelpers.callMethod(self, FLOATING_CAN_ANIMATE.value(), i, 4)) {
                                        param.setResult(Boolean.FALSE);
                                        return;
                                    }
                                    if (i == focus) continue;
                                    Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), i);
                                    Object view = XposedHelpers.callMethod(
                                            decor, "getFlexibleTaskView");
                                    Object anim = animCtor.newInstance(
                                            self,
                                            new Rect((Rect) starts.get(i)),
                                            new Rect((Rect) targets.get(i)),
                                            view,
                                            XposedHelpers.callMethod(adapter, "getItem", i),
                                            false);
                                    synchronized (sources) {
                                        ((java.util.List<Object>) sources).add(anim);
                                    }
                                }
                                param.setResult(Boolean.TRUE);
                                log("zoom four patched");
                            } catch (Throwable error) {
                                log("zoom four k0 failed: " + error);
                            }
                        }
                    });
            log("zoom four hooked");
        } catch (Throwable error) {
            log("zoom four hook failed: " + error);
        }
    }

    /**
     * 关闭窗口时 B1.l.a1 会调 M() 重算布局，四窗时 M 返回空列表，
     * 紧接着 M3.get(0) 就 IndexOutOfBounds。这里给四窗补一份结果。
     */
    private static void hookLayoutCalc(ClassLoader loader) {
        try {
            Class<?> util = XposedHelpers.findClass(UTIL.value(), loader);
            XposedHelpers.findAndHookMethod(util, "M",
                    android.content.Context.class, java.util.List.class,
                    int.class, int.class, int.class, boolean.class,
                    android.os.Bundle.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object result = param.getResult();
                                Object input = param.args[1];
                                if (!(input instanceof java.util.List)) return;
                                java.util.List<?> list = (java.util.List<?>) input;
                                if (CanvasApi.isColorOs17() && list.size() == 2 && !(Boolean) param.args[5]) {
                                    List<Rect> panes = twoPaneTransitionBounds(loader, (Context) param.args[0]);
                                    if (panes.size() == 2) param.setResult(panes);
                                    return;
                                }
                                if (list.size() != 4) return;
                                if (result instanceof java.util.List
                                        && !((java.util.List<?>) result).isEmpty()) return;
                                param.setResult(fourPanes());
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            log("layout calc patched");
        } catch (Throwable error) {
            log("layout calc hook failed: " + error);
        }
    }

    private static List<Rect> twoPaneTransitionBounds(ClassLoader loader, Context context) {
        Class<?> util = XposedHelpers.findClass(UTIL.value(), loader);
        Object host = XposedHelpers.callStaticMethod(util, ACTIVITY_FROM_CONTEXT.value(), context);
        if (host == null) return List.of();
        View canvas = (View) XposedHelpers.callMethod(host, ACTIVITY_VIEW.value());
        if (canvas == null) return List.of();
        Object adapter = CanvasApi.adapter(canvas);
        CanvasDisplay display = canvasDisplay(canvas);
        int mode = DEFAULT_ORIENTATION;
        List<Rect> panes = proportionalPanes(2, mode, columnShare(canvas, 2, mode), display);
        if (panes.size() != 2) return panes;

        Rect union = new Rect();
        for (Rect pane : panes) union.union(pane);
        int x = display.area().centerX() - union.centerX();
        int y = display.area().centerY() - union.centerY();
        // 系统的遮罩调用方会补上状态栏偏移。
        Class<?> policy = XposedHelpers.findClass("i2.s", loader);
        if (Boolean.TRUE.equals(XposedHelpers.callStaticMethod(policy, "C"))) {
            y -= (Integer) XposedHelpers.callMethod(host, "T0");
        }
        for (Rect pane : panes) pane.offset(x, y);
        return adapter != null && count(adapter) == 2
                ? inTaskOrder(panes, panePositions(canvas, adapter)) : panes;
    }

    /**
     * 四窗时任务矩形方向回调会崩：EmbeddedViewDecor.j0 取一个长度 0 的列表，
     * 抛 IndexOutOfBounds 直接把画布进程干掉。四窗先跳过这条链路。
     */
    private static void hookRectOrientation(ClassLoader loader) {
        try {
            Class<?> activity = XposedHelpers.findClass(
                    CanvasApi.ACTIVITY, loader);
            XposedBridge.hookAllMethods(activity, RECT_CHANGED.value(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (CanvasApi.isColorOs17() && (param.args.length != 2
                                || !(param.args[0] instanceof android.app.ActivityManager.RunningTaskInfo)
                                || !(param.args[1] instanceof Rect))) return;
                        Object view = XposedHelpers.callMethod(param.thisObject, ACTIVITY_VIEW.value());
                        Object adapter = view == null ? null : CanvasApi.adapter(view);
                        if (adapter != null && count(adapter) >= 4) {
                            param.setResult(null);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            if (CanvasApi.isColorOs17()) {
                Class<?> decor = XposedHelpers.findClass(
                        CanvasApi.PACKAGE + "view.EmbeddedViewDecor", loader);
                XposedHelpers.findAndHookMethod(decor, "E", Rect.class, boolean.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                View pane = (View) param.thisObject;
                                if (!(pane.getParent() instanceof View canvas)) return;
                                Object adapter = CanvasApi.adapter(canvas);
                                int total = adapter == null ? 0 : count(adapter);
                                if (total != 2 && total != 3) return;
                                Rect requested = (Rect) param.args[0];
                                Rect launch = (Rect) XposedHelpers.callMethod(pane, "getLaunchRect");
                                if (requested == null || requested.isEmpty() || launch == null
                                        || launch.isEmpty()
                                        || (!isLandscape(canvas) && (requested.width() > requested.height())
                                                != (launch.width() > launch.height()))
                                        || (requested.width() == launch.width()
                                                && requested.height() == launch.height())) return;
                                // 页面切换请求沿用了系统分屏尺寸，固定窗格继续使用当前的启动尺寸。
                                param.setResult(null);
                                log("kept pane size on page change " + launch.width() + "x"
                                        + launch.height() + " (requested " + requested.width()
                                        + "x" + requested.height() + ")");
                            }
                        });
            }
            log("rect orientation guarded");
        } catch (Throwable error) {
            log("rect orientation hook failed: " + error);
        }
    }

    /**
     * 四窗不自己排版，接进官方三窗那套链路：
     * 1. w1() 里只对 count==3 调 N2()，四窗补一次一样的调用；
     * 2. N2() 拿 B1.l.M1() 的结果按个数摆窗口，M1() 原本只算 3 个，四窗补 4 个矩形；
     * 3. 之后 layoutChildren 末尾的 E.V()/E.H() 自然出调节条，onLayout 的 T() 自然处理点击放大；
     * 4. u1() 原本只处理 2/3 窗，四窗照三窗走 X1()。
     */
    private static void hookNativeFour(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedHelpers.findAndHookMethod(view, LAYOUT_CHILDREN.value(), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        if (adapter == null || count(adapter) != 4) return;
                        if (CanvasApi.isColorOs17()) {
                            Object rects = XposedHelpers.callMethod(param.thisObject, "getRectList");
                            if (rects instanceof java.util.List
                                    && ((java.util.List<?>) rects).size() == 4) return;
                        }
                        XposedHelpers.callMethod(param.thisObject, LAYOUT.value());
                    } catch (Throwable error) {
                        log("native four layout failed: " + error);
                    }
                }
            });
            log("native four layout hooked");
        } catch (Throwable error) {
            log("native four layout hook failed: " + error);
        }

        if (CanvasApi.isColorOs17()) {
            try {
                hookPaneContentFit(loader);
                Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
                XposedHelpers.findAndHookMethod(view, "X2", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        if (adapter == null || count(adapter) != 2) return;
                        XposedHelpers.callMethod(param.thisObject, LAYOUT.value());
                        param.setResult(null);
                    }
                });
            } catch (Throwable error) {
                log("two pane layout hook failed: " + error);
            }
        }

        // N2() 会往 getRectList() 里 addAll，但只有 w1() 会先清。resume、
        // 直接调 N2 的路径没清，矩形会累加到 6、9 个，交换和调节条全错位。
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedHelpers.findAndHookMethod(view, LAYOUT.value(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object rects = XposedHelpers.callMethod(param.thisObject, "getRectList");
                        if (rects instanceof java.util.List) {
                            ((java.util.List<?>) rects).clear();
                        }
                        Object union = CanvasApi.isColorOs17()
                                ? XposedHelpers.callMethod(param.thisObject, "getRectListUnion")
                                : XposedHelpers.getObjectField(param.thisObject, "m");
                        if (union instanceof Rect) {
                            ((Rect) union).setEmpty();
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("rect list reset hooked");
        } catch (Throwable error) {
            log("rect list reset hook failed: " + error);
        }

        try {
            Class<?> util = XposedHelpers.findClass(UTIL.value(), loader);
            XposedHelpers.findAndHookMethod(util, THREE_LAYOUT.value(),
                    java.util.List.class, int.class, float.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object input = param.args[0];
                        if (!(input instanceof java.util.List)) return;
                        int count = ((java.util.List<?>) input).size();
                        if (CanvasApi.isColorOs17() && (count == 3 || count == 4
                                || (count == 2 && layoutDisplay.get() != null))) {
                            int mode = count == 2 ? DEFAULT_ORIENTATION : (Integer) param.args[1];
                            Float share = layoutColumnShare.get();
                            CanvasDisplay display = layoutDisplay.get();
                            if (display == null) display = fallbackCanvasDisplay();
                            java.util.List<Rect> panes = proportionalPanes(count, mode,
                                    share != null ? share : display.landscape() ? CanvasGeometry.LANDSCAPE_LEFT_SHARE
                                            : CanvasGeometry.defaultLeftShare(count, mode), display);
                            if (panes.size() != count) return;
                            int[] order = layoutPaneOrder.get();
                            param.setResult(inTaskOrder(panes, order));
                            return;
                        }
                        if (count != 4) return;
                        Object result = param.getResult();
                        if (result instanceof java.util.List
                                && !((java.util.List<?>) result).isEmpty()) return;
                        param.setResult(fourPanes());
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("multi-window rect layout hooked");
        } catch (Throwable error) {
            log("four rect layout hook failed: " + error);
        }

        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            Class<?> taskData = XposedHelpers.findClass(TASK.value(), loader);
            XposedHelpers.findAndHookMethod(view, CLOSE_TASK.value(), taskData, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        int total = adapter == null ? -1 : count(adapter);
                        // u1() 会被 ART 内联进 T1，hook 不到，直接在这里补四窗的关闭
                        if (adapter == null || total != 4) return;
                        XposedHelpers.callMethod(param.thisObject, REMOVE_TASK.value(), param.args[0]);
                        if (CanvasApi.isColorOs17() && count(adapter) == 3) {
                            Object context = XposedHelpers.callMethod(param.thisObject, "getContext");
                            Object activity = XposedHelpers.callStaticMethod(
                                    XposedHelpers.findClass(UTIL.value(), loader),
                                    ACTIVITY_FROM_CONTEXT.value(), context);
                            Object manager = XposedHelpers.callStaticMethod(XposedHelpers.findClass(
                                    "com.oplus.flexiblewindow.FlexibleWindowManager", loader),
                                    "getInstance");
                            XposedHelpers.callMethod(manager, "removeEmbeddedContainerTask",
                                    XposedHelpers.callMethod(param.args[0], "t"),
                                    XposedHelpers.callMethod(activity, "E0"));
                            param.setResult(null);
                        }
                        log("close window four");
                    } catch (Throwable error) {
                        log("close four failed: " + error);
                    }
                }
            });
            log("four close hooked");
        } catch (Throwable error) {
            log("four close hook failed: " + error);
        }
    }

    /** o2() 往 A1.b.l() 的 Rect[] 里按下标写，数组按三窗分配，四窗会越界。 */
    private static void hookFlexibleUiArray(ClassLoader loader) {
        try {
            Class<?> ui = XposedHelpers.findClass(UI_MANAGER.value(), loader);
            XposedBridge.hookAllMethods(ui, "l", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object result = param.getResult();
                    if (!(result instanceof Rect[])) return;
                    Rect[] rects = (Rect[]) result;
                    if (rects.length >= 4) return;
                    Rect[] bigger = new Rect[4];
                    System.arraycopy(rects, 0, bigger, 0, rects.length);
                    if (CanvasApi.isColorOs17()) {
                        XposedHelpers.setObjectField(param.thisObject, "r", bigger);
                    }
                    param.setResult(bigger);
                }
            });
            log("flexible ui array hooked");
        } catch (Throwable error) {
            log("flexible ui array hook failed: " + error);
        }
    }

    /** s0.d() 里写死 count >= 3 就拒绝，四窗要放行一次。 */
    private static final ThreadLocal<Boolean> addingTask = new ThreadLocal<>();

    private static void hookAddLimit(ClassLoader loader) {
        try {
            Class<?> controller = XposedHelpers.findClass(
                    CONTROLLER.value(), loader);
            Class<?> taskData = XposedHelpers.findClass(TASK.value(), loader);
            XposedHelpers.findAndHookMethod(controller, "d", taskData, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    addingTask.set(Boolean.TRUE);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    addingTask.remove();
                }
            });
            Class<?> adapter = XposedHelpers.findClass(ADAPTER.value(), loader);
            XposedHelpers.findAndHookMethod(adapter, "getCount", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (addingTask.get() == null) return;
                    // 只放行上限判断这一次，后面的 getCount 保持真实值
                    addingTask.remove();
                    if (Integer.valueOf(3).equals(param.getResult())) param.setResult(2);
                }
            });
            log("add limit lifted");
        } catch (Throwable error) {
            log("add limit hook failed: " + error);
        }
    }

    /** 三分屏布局方向可控：N2 会拿自己的字段覆盖方向，这里直接改传给 M1 的参数。 */
    private static void hookOrientation(ClassLoader loader) {
        try {
            Class<?> util = XposedHelpers.findClass(UTIL.value(), loader);
            XposedHelpers.findAndHookMethod(util, THREE_LAYOUT.value(),
                    java.util.List.class, int.class, float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            int forced = forcedOrientation;
                            if (forced == 0) return;
                            Object list = param.args[0];
                            if (!(list instanceof java.util.List)) return;
                            if (((java.util.List<?>) list).size() != 3) return;
                            param.args[1] = forced;
                            log("force orientation " + forced);
                        }
                    });
            log("orientation hooked");
        } catch (Throwable error) {
            log("orientation hook failed: " + error);
        }
    }

    /** 切换布局方向并立刻重排。 */
    static void applyOrientation(int orientation) {
        forcedOrientation = orientation;
        log("orientation -> " + orientation);
        for (Object container : containers.keySet()) {
            try {
                layoutKeys.remove(container);
                XposedHelpers.callMethod(container, LAYOUT.value());
            } catch (Throwable error) {
                log("apply orientation failed: " + error);
            }
        }
    }

    /**
     * 按指定方向重新摆三个窗口。
     *
     * 矩形仍然交给官方的 B1.l.M1 算，这里只是把方向换掉，
     * 排布规则和原版平板完全一致。
     */
    private static void relayoutDirected(Object container, ClassLoader loader) {
        // W2 已直接使用 V1 的结果，额外摆一次会与系统布局状态脱节。
        if (CanvasApi.isColorOs17()) return;
        int orientation = forcedOrientation;
        if (orientation == 0) return;
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null) return;
            int total = count(adapter);
            if (total < 3) return;
            if (total == 4) {
                // 四窗交给官方 N2()/M1() 链路，这里不插手
                return;
            }
            java.util.List<Object> tasks = new java.util.ArrayList<>();
            for (int i = 0; i < total; i++) {
                Object task = XposedHelpers.callMethod(adapter, "getItem", i);
                if (task != null) tasks.add(task);
            }
            if (tasks.size() != 3) return;
            float density = android.content.res.Resources.getSystem()
                    .getDisplayMetrics().density;
            Object rects = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(UTIL.value(), loader), THREE_LAYOUT.value(), tasks, orientation, density);
            if (!(rects instanceof java.util.List)) return;
            java.util.List<?> list = (java.util.List<?>) rects;
            if (list.size() != 3) {
                log("directed layout empty for " + orientation);
                return;
            }
            Object scale = XposedHelpers.getFloatField(container, "t");
            for (int i = 0; i < 3; i++) {
                Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), i);
                if (!(decor instanceof android.view.View)) continue;
                Object rect = list.get(i);
                if (!(rect instanceof Rect)) continue;
                Rect r = new Rect((Rect) rect);
                Object task = tasks.get(i);
                try {
                    XposedHelpers.callMethod(decor, "k0", task,
                            XposedHelpers.callMethod(task, "o"), scale);
                } catch (Throwable ignored) {
                }
                ((android.view.View) decor).layout(r.left, r.top, r.right, r.bottom);
            }
            log("directed layout " + orientation + " -> " + list);
        } catch (Throwable error) {
            log("directed layout failed: " + error);
        }
    }

    /**
     * 双窗拖动时更新应用布局，其他布局松手时提交窗格尺寸。
     */
    private static void hookDragLock(ClassLoader loader) {
        try {
            Class<?> bar = XposedHelpers.findClass(
                    SPLIT_BAR.value(), loader);
            Class<?> item = XposedHelpers.findClass(
                    SPLIT_BAR_ITEM.value(), loader);
            XposedHelpers.findAndHookMethod(bar, BAR_DRAG.value(), item,
                    android.view.MotionEvent.class, android.view.MotionEvent.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object container = XposedHelpers.getObjectField(
                                        param.thisObject, "h");
                                Object adapter = CanvasApi.adapter(container);
                                if (adapter != null && usesCanvasLayout(count(adapter))) {
                                    param.setResult(null);
                                    if (CanvasApi.isColorOs17()) {
                                        previewColumnDrag(param.thisObject, (View) container, adapter,
                                                param.args[0], (MotionEvent) param.args[1],
                                                (MotionEvent) param.args[2]);
                                    }
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            // r0/s0 最后都落到 q0 去算新位置，这里再兜一层
            XposedHelpers.findAndHookMethod(bar, BAR_SCROLL.value(), item,
                    float.class, float.class, float.class, float.class, boolean.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object container = XposedHelpers.getObjectField(
                                        param.thisObject, "h");
                                Object adapter = CanvasApi.adapter(container);
                                if (adapter != null && usesCanvasLayout(count(adapter))) {
                                    param.setResult(null);
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            if (CanvasApi.isColorOs17()) {
                hookColumnDragEnd(loader, bar);
                hookColumnHandles(bar);
                hookWindowHeaderDrag(loader);
                hookTwoPaneDividerMenu(bar, item);
            }
            log("drag lock hooked");
        } catch (Throwable error) {
            log("drag lock hook failed: " + error);
        }
    }

    private static void hookTwoPaneDividerMenu(Class<?> bar, Class<?> item) {
        XposedHelpers.findAndHookMethod(bar, "U", item, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object canvas = XposedHelpers.getObjectField(param.thisObject, "h");
                Object adapter = CanvasApi.adapter(canvas);
                if (adapter == null || count(adapter) != 2) return;
                List<Object> items = (List<Object>) XposedHelpers.getObjectField(param.thisObject, "K");
                items.removeIf(entry -> {
                    int action = (Integer) XposedHelpers.callMethod(entry, "m");
                    return action == 3 || action == 4;
                });
            }
        });
    }

    private static void hookColumnHandles(Class<?> bar) {
        XposedHelpers.findAndHookMethod(bar, "w", Rect.class, Rect.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object handle = param.getResult();
                if (handle == null) return;
                ImageView icon = (ImageView) XposedHelpers.callMethod(handle, "h");
                if (icon != null) icon.setImageTintList(ColorStateList.valueOf(DIVIDER_BLUE));
            }

            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object canvas = XposedHelpers.getObjectField(param.thisObject, "h");
                Object adapter = CanvasApi.adapter(canvas);
                if (adapter == null || count(adapter) < 2) return;
                Rect first = (Rect) param.args[0];
                Rect second = (Rect) param.args[1];
                if (count(adapter) == 2 && stackedTwoPanes(((View) canvas).getContext())) {
                    int left = Math.max(first.left, second.left);
                    int right = Math.min(first.right, second.right);
                    if (left >= right) param.setResult(null);
                    else {
                        param.args[0] = new Rect(left, first.top, right, first.bottom);
                        param.args[1] = new Rect(left, second.top, right, second.bottom);
                    }
                    return;
                }
                if (isLandscape((View) canvas)) {
                    int main = mainPane(canvas, adapter);
                    List<?> panes = (List<?>) XposedHelpers.callMethod(canvas, "getRectList");
                    if (main < 0 || panes.size() <= main) {
                        param.setResult(null);
                        return;
                    }
                    int mainLeft = ((Rect) panes.get(main)).left;
                    if (mainLeft != first.left && mainLeft != second.left) {
                        param.setResult(null);
                    }
                    return;
                }
                if (!CanvasGeometry.hasPhoneRatioColumns(
                        count(adapter), layoutMode(adapter))) return;
                if (first.right >= second.left && second.right >= first.left) return;
                int top = Math.max(first.top, second.top);
                int bottom = Math.min(first.bottom, second.bottom);
                if (bottom <= top) {
                    param.setResult(null);
                    return;
                }
                // 系统按相同上下边缘定位竖分隔条，等比窗格以实际重叠区为准。
                param.args[0] = new Rect(first.left, top, first.right, bottom);
                param.args[1] = new Rect(second.left, top, second.right, bottom);
            }
        });
    }

    private static void hookWindowHeaderDrag(ClassLoader loader) {
        Class<?> header = XposedHelpers.findClass(CanvasApi.PACKAGE + "v", loader);
        XposedHelpers.findAndHookMethod(header, "L0", View.class, MotionEvent.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object canvas = XposedHelpers.getObjectField(param.thisObject, "n");
                        Object adapter = CanvasApi.adapter(canvas);
                        if (adapter != null && count(adapter) >= 2) {
                            // 多窗通过分隔条调节，标题按钮继续走原生点击菜单。
                            param.setResult(Boolean.FALSE);
                        }
                    }
                });
    }

    private static final java.util.WeakHashMap<Object, ColumnDrag> columnDrags =
            new java.util.WeakHashMap<>();

    private record ColumnDrag(View canvas, View handle, long downTime, int panes, int mode,
                              boolean landscape, boolean reversed, float initialShare, float screenWidth) {
        float shareAt(MotionEvent event, float startX) {
            return CanvasGeometry.clampLeftShare(initialShare
                    + direction() * (event.getRawX() - startX) / screenWidth);
        }

        int direction() {
            return reversed ? -1 : 1;
        }

        void clearPreview() {
            handle.setTranslationX(0f);
        }
    }

    private static void previewColumnDrag(Object bar, View canvas, Object adapter, Object item,
                                          MotionEvent start, MotionEvent current) {
        int total = count(adapter);
        if (total == 2 || layoutLocked(canvas.getContext())) return;
        int mode = layoutMode(adapter);
        boolean landscape = isLandscape(canvas);
        boolean reversed = isMainPaneReversed(total, landscape ? DEFAULT_ORIENTATION : mode,
                preferredMainPaneMode(canvas.getContext()));
        if (isFourZoomed(canvas) || current.getPointerCount() != 1
                || (landscape ? total != 3 : !CanvasGeometry.hasPhoneRatioColumns(total, mode))
                || !Boolean.TRUE.equals(XposedHelpers.callMethod(item, "k"))) return;

        ColumnDrag drag = columnDrags.get(bar);
        if (drag == null || drag.downTime() != start.getDownTime()) {
            if (drag != null) drag.clearPreview();
            float scale = (Float) XposedHelpers.callMethod(canvas, "getParentScaleX");
            Rect bounds = contentRect(canvas);
            if (bounds == null) return;
            int width = bounds.width() - columnGap() * (landscape ? 2 : 1);
            if (width <= 0 || scale <= 0f) return;
            View handle = (View) XposedHelpers.callMethod(item, "g");
            drag = new ColumnDrag(canvas, handle, start.getDownTime(), total, mode,
                    landscape, reversed, columnShare(canvas, total, mode), width * scale);
            columnDrags.put(bar, drag);
        }
        float scale = (Float) XposedHelpers.callMethod(canvas, "getParentScaleX");
        if (scale > 0f) {
            float shift = (drag.shareAt(current, start.getRawX()) - drag.initialShare())
                    * drag.direction() * drag.screenWidth() / scale;
            drag.handle().setTranslationX(shift);
        }
    }

    private static void hookColumnDragEnd(ClassLoader loader, Class<?> bar) {
        XposedHelpers.findAndHookMethod(bar, "u0", MotionEvent.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object canvas = XposedHelpers.getObjectField(param.thisObject, "h");
                            Object adapter = CanvasApi.adapter(canvas);
                            if (adapter == null || count(adapter) < 2) return;
                            param.setResult(null);
                            ColumnDrag drag = columnDrags.remove(param.thisObject);
                            if (drag == null) return;
                            drag.clearPreview();
                            MotionEvent end = (MotionEvent) param.args[0];
                            if (layoutLocked(drag.canvas().getContext())
                                    || end.getActionMasked() != MotionEvent.ACTION_UP
                                    || end.getDownTime() != drag.downTime()
                                    || count(adapter) != drag.panes() || isFourZoomed(canvas)
                                    || isLandscape(drag.canvas()) != drag.landscape()
                                    || layoutMode(adapter) != drag.mode()
                                    || drag.reversed() != isMainPaneReversed(drag.panes(),
                                            drag.landscape() ? DEFAULT_ORIENTATION : drag.mode(),
                                            preferredMainPaneMode(drag.canvas().getContext()))) return;
                            float share = drag.shareAt(end, (Integer) param.args[1]);
                            if (Math.abs(share - drag.initialShare()) < 0.002f) return;
                            String key = paneLayoutKey(drag.panes(), drag.mode(), drag.landscape(), false);
                            layoutPrefs(drag.canvas().getContext()).edit().putFloat(key + "_share", share).apply();
                            requestPaneLayout(drag.canvas());
                            log("column resize " + drag.panes() + " windows left=" + share);
                        } catch (Throwable error) {
                            log("column resize failed: " + error);
                        }
                    }
                });
        Class<?> gestures = XposedHelpers.findClass(CanvasApi.PACKAGE + "A", loader);
        XposedHelpers.findAndHookMethod(gestures, "T", MotionEvent.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                MotionEvent event = (MotionEvent) param.args[0];
                if (event.getActionMasked() != MotionEvent.ACTION_CANCEL
                        && event.getActionMasked() != MotionEvent.ACTION_UP
                        && event.getActionMasked() != MotionEvent.ACTION_POINTER_DOWN) return;
                for (java.util.Iterator<ColumnDrag> iterator = columnDrags.values().iterator();
                        iterator.hasNext(); ) {
                    ColumnDrag drag = iterator.next();
                    if (drag.downTime() != event.getDownTime()) continue;
                    drag.clearPreview();
                    iterator.remove();
                }
            }
        });
    }


    /**
     * 让 u1/d.o() 永远返回目标尺寸。
     *
     * 任务数据重建时 a 字段会被改回方形，M1 跟着算出方形窗口，
     * 就是那个重排。这里直接锁死返回值，布局指纹才不会变。
     */
    private static void hookTaskDataBounds(ClassLoader loader) {
        try {
            Class<?> taskData = XposedHelpers.findClass(TASK.value(), loader);
            XposedHelpers.findAndHookMethod(taskData, "o", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (CanvasApi.isColorOs17()) {
                        Object bounds = XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, PROPORTIONAL_BOUNDS);
                        if (bounds instanceof Rect) param.setResult(new Rect((Rect) bounds));
                        return;
                    }
                    if (!multiWindowMode) return;
                    param.setResult(currentTarget());
                }
            });
            log("taskData bounds locked");
        } catch (Throwable error) {
            log("taskData bounds hook failed: " + error);
        }
    }

    // ---------------------------------------------------------------- 尺寸

    private static void hookFullscreen(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedHelpers.findAndHookMethod(view, LAYOUT.value(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    syncTaskData(param.thisObject);
                    if (CanvasApi.isColorOs17()) {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        param.setObjectExtra("canvasplus.columnShare", layoutColumnShare.get());
                        param.setObjectExtra("canvasplus.paneOrder", layoutPaneOrder.get());
                        param.setObjectExtra("canvasplus.display", layoutDisplay.get());
                        if (adapter != null) {
                            int panes = count(adapter);
                            int mode = layoutMode(adapter);
                            layoutColumnShare.set(columnShare(param.thisObject, panes, mode));
                            layoutPaneOrder.set(CanvasGeometry.mainPane(panes,
                                    mainPaneMode((View) param.thisObject, adapter)) >= 0
                                    ? panePositions(param.thisObject, adapter) : null);
                            CanvasDisplay display = canvasDisplay((View) param.thisObject);
                            layoutDisplay.set(display);
                            if (panes >= 2 && panes <= 4) {
                                // 先更新已有任务的比例，再把它们摆进新窗格。
                                forcePhoneSize(param.thisObject, true);
                            }
                        }
                    }
                    oLock = multiWindowMode;
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (CanvasApi.isColorOs17()) {
                        Float previous = (Float) param.getObjectExtra("canvasplus.columnShare");
                        if (previous == null) layoutColumnShare.remove();
                        else layoutColumnShare.set(previous);
                        int[] previousOrder = (int[]) param.getObjectExtra("canvasplus.paneOrder");
                        if (previousOrder == null) layoutPaneOrder.remove();
                        else layoutPaneOrder.set(previousOrder);
                        CanvasDisplay previousDisplay = (CanvasDisplay) param.getObjectExtra("canvasplus.display");
                        if (previousDisplay == null) layoutDisplay.remove();
                        else layoutDisplay.set(previousDisplay);
                    }
                    forcePhoneSize(param.thisObject);
                    oLock = false;
                    if (forcedOrientation != 0) {
                        relayoutDirected(param.thisObject,
                                param.thisObject.getClass().getClassLoader());
                    }
                    try {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        if (adapter == null) return;
                        int panes = count(adapter);
                        if (usesCanvasLayout(panes)) dumpLayout(param.thisObject);
                        if (!usesCanvasLayout(panes) || isFourZoomed(param.thisObject)) return;
                        if (CanvasApi.isColorOs17()) {
                            View canvas = (View) param.thisObject;
                            if (!isZoomAnimating(canvas)) applyOverview(canvas, panes);
                            return;
                        }
                        // 布局完成时画布可能还停在 1.0（尤其是从二窗加进第三个应用），
                        // 补一次总览缩放，别等用户碰一下才恢复
                        final Object canvas = param.thisObject;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> {
                                    if (panes == 4) fitFour(canvas, 0);
                                    else fitThree(canvas, 0);
                                });
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedHelpers.findAndHookMethod(view, "layoutChildren", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        // 先同步缓存，任务数据重建时 bounds 被改回也能拉回来
                        syncTaskData(param.thisObject);
                        if (!multiWindowMode) return;
                        String key = layoutKey(param.thisObject);
                        String last = layoutKeys.get(param.thisObject);
                        if (!key.isEmpty() && key.equals(last)) {
                            // 布局参数没变，跳过这次重新摆放，画面不动
                            param.setResult(null);
                            skipped++;
                            if (skipped % 20 == 1) {
                                log("layoutChildren skipped " + skipped);
                            }
                            return;
                        }
                        layoutKeys.put(param.thisObject, key);
                    } catch (Throwable ignored) {
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (forcedOrientation == 0) return;
                    try {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        if (adapter == null) return;
                        if (count(adapter) == 4) return; // 四窗由 w1() 里的 N2() 处理
                        relayoutDirected(param.thisObject,
                                param.thisObject.getClass().getClassLoader());
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("fullscreen hook ready");
        } catch (Throwable error) {
            log("fullscreen hook failed: " + error);
        }
    }

    /** 应用内换页时任务信息会变，一有变化立刻重新套尺寸。 */
    private static void hookTaskInfo(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(FLEXIBLE_TASK_VIEW, loader);
            XposedBridge.hookAllMethods(view, "onTaskInfoChanged", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object info = param.args[0];
                        Object bounds = XposedHelpers.callMethod(info, "getBounds");
                        Object launch = launchBoundsOf(param.thisObject);
                        Object activity = null;
                        try {
                            activity = XposedHelpers.callMethod(info, "getTopActivity");
                        } catch (Throwable ignored) {
                        }
                        log("taskChanged " + activity + " bounds=" + bounds
                                + " launch=" + launch);
                        Object container = containerOf(param.thisObject);
                        if (container == null) return;
                        Object finalContainer = container;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .post(() -> {
                                    syncTaskData(finalContainer);
                                    forcePhoneSize(finalContainer);
                                });
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(view, "onTaskVanished", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object info = param.args[0];
                        log("taskVanished " + XposedHelpers.getIntField(info, "taskId"));
                    } catch (Throwable ignored) {
                    }
                    layoutKeys.clear();
                }
            });
            XposedBridge.hookAllMethods(view, "onTaskAppeared", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    layoutKeys.clear();
                    try {
                        Object info = param.args[0];
                        int id = XposedHelpers.getIntField(info, "taskId");
                        Object bounds = XposedHelpers.callMethod(info, "getBounds");
                        log("taskAppeared " + id + " bounds=" + bounds);
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("task info hooked");
        } catch (Throwable error) {
            log("task info hook failed: " + error);
        }
    }

    /** 画布可见性广播给侧边栏，侧边栏据此决定点应用是加画布还是开小窗。 */
    /** 当前画布里有多少个窗口，没有画布返回 -1。 */
    private static int currentCanvasCount() {
        for (Object container : containers.keySet()) {
            try {
                Object adapter = CanvasApi.adapter(container);
                if (adapter != null) return count(adapter);
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static int canvasScreenOrientation() {
        return CanvasApi.isColorOs17() ? ActivityInfo.SCREEN_ORIENTATION_USER
                : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
    }

    private static void updateCanvasScreenOrientation(Activity activity) {
        int wanted = canvasScreenOrientation();
        if (!activity.isFinishing() && activity.getRequestedOrientation() != wanted) {
            activity.setRequestedOrientation(wanted);
        }
    }

    private static void hookScreenOrientation(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Activity", loader,
                    "setRequestedOrientation", int.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!CanvasApi.ACTIVITY.equals(
                                    param.thisObject.getClass().getName())) {
                                return;
                            }
                            param.args[0] = canvasScreenOrientation();
                        }
                    });
            if (CanvasApi.isColorOs17()) {
                XposedHelpers.findAndHookMethod(CanvasApi.ACTIVITY, loader, "onConfigurationChanged",
                        Configuration.class, new XC_MethodHook() {
                            @Override protected void afterHookedMethod(MethodHookParam param) {
                                View canvas = (View) XposedHelpers.callMethod(param.thisObject, ACTIVITY_VIEW.value());
                                Object adapter = CanvasApi.adapter(canvas);
                                if (adapter == null || count(adapter) < 2) return;
                                layoutKeys.remove(canvas);
                                View root = canvas.getRootView();
                                boolean wasLandscape = root.getWidth() > root.getHeight();
                                boolean landscape = ((Configuration) param.args[0]).orientation
                                        == Configuration.ORIENTATION_LANDSCAPE;
                                if (wasLandscape != landscape) {
                                    cancelPaneEdgeDrag(canvas);
                                    fourZoomed.remove(canvas);
                                    enlargedPanes.remove(canvas);
                                    cancelMainPaneAnimation(canvas);
                                    cancelZoom(canvas);
                                    markEnlarged(adapter, count(adapter), -1);
                                }
                                canvas.requestLayout();
                            }
                        });
            }
            log("canvas screen orientation hooked");
        } catch (Throwable error) {
            log("canvas screen orientation hook failed: " + error);
        }
    }

    /** 画布被切走再回来时任务已经恢复成全屏，重新套一遍。 */
    private static void hookResume(ClassLoader loader) {
        try {
            Class<?> activity = XposedHelpers.findClass(
                    CanvasApi.ACTIVITY, loader);
            XposedHelpers.findAndHookMethod(activity, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        updateCanvasScreenOrientation((Activity) param.thisObject);
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object view = XposedHelpers.callMethod(param.thisObject, ACTIVITY_VIEW.value());
                        if (view == null) return;
                        new android.os.Handler(android.os.Looper.getMainLooper())
                                .postDelayed(() -> {
                                    try {
                                        attempts = 0;
                                        syncTaskData(view);
                                        Object adapter = CanvasApi.adapter(view);
                                        if (adapter == null || !usesCanvasLayout(count(adapter))) return;
                                        forcePhoneSize(view);
                                        if (!CanvasApi.isColorOs17()
                                                || contentRect(view) == null) {
                                            XposedHelpers.callMethod(view, LAYOUT.value());
                                        }
                                        startWatchdog(view);
                                    } catch (Throwable error) {
                                        log("resume reapply failed: " + error);
                                    }
                                }, 800);
                    } catch (Throwable ignored) {
                    }
                }
            });
            if (!CanvasApi.isColorOs17()) {
                XposedHelpers.findAndHookMethod(activity, IME_HEIGHT.value(), int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                shiftForIme(param);
                            }
                        });
            }
            log("resume hooked");
        } catch (Throwable error) {
            log("resume hook failed: " + error);
        }
    }

    /** 点击窗口前刷新一次布局，保证 T() 拿到的矩形是最新的。 */
    private static void hookClick(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedBridge.hookAllMethods(view, ANIMATE_SCALE.value(), new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    log("A2 tx=" + param.args[0] + " ty=" + param.args[1]
                            + " scale=" + param.args[2] + " mode=" + param.args[4]);
                    try {
                        int mode = (Integer) param.args[4];
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        if (adapter == null) return;
                        int total = count(adapter);
                        if (!usesCanvasLayout(total)) return;
                        if (CanvasApi.isColorOs17()) {
                            if ((mode == 0 || mode == 1) && !focusing.contains(param.thisObject)) {
                                param.setResult(Boolean.FALSE);
                            }
                            return;
                        }
                        if (mode == 3) return;
                        android.view.View self = (android.view.View) param.thisObject;
                        android.view.View parent = (android.view.View) self.getParent();
                        Rect union = contentRect(param.thisObject);
                        if (union == null || union.isEmpty() || parent == null) return;

                        int margin = marginPx();
                        float topInset = statusBarHeight() + margin;
                        float bottomInset = margin;
                        float avail = parent.getHeight() - topInset - bottomInset;
                        float scale = (Float) param.args[2];
                        if (scale <= 0f) return;
                        float base = topInset - self.getTop() * scale;
                        float drawn = union.height() * scale;
                        float ty;
                        if (drawn <= avail + 1f) {
                            // 整体放得下：上下左右都居中
                            ty = base + (avail - drawn) / 2f - union.top * scale;
                            float width = union.width() * scale;
                            if (width < parent.getWidth()) {
                                param.args[0] = (parent.getWidth() - width) / 2f;
                            }
                        } else {
                            // 有窗口放大：按焦点窗口摆，别让它的底边跑出屏幕
                            ty = focusAlign(param.thisObject, adapter, union, scale,
                                    self.getTop(), topInset, bottomInset,
                                    parent.getHeight());
                            if (Float.isNaN(ty)) {
                                ty = (Float) param.args[1] + base;
                            }
                        }
                        param.args[1] = ty;
                        layoutTy.put(param.thisObject, ty);
                        log("A2 align ty=" + Math.round(ty) + " drawn=" + Math.round(drawn)
                                + " avail=" + Math.round(avail));
                    } catch (Throwable error) {
                        log("A2 align failed: " + error);
                    }
                }
            });
            XposedHelpers.findAndHookMethod(view, FOCUS_SCALE.value(),
                    int.class, int.class, int.class, boolean.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object canvas = param.thisObject;
                            int index = (Integer) param.args[0];
                            int duration = (Integer) param.args[1];
                            boolean animate = (Boolean) param.args[3];
                            try {
                                Object adapter = CanvasApi.adapter(canvas);
                                int total = adapter == null ? 0 : count(adapter);
                                if (!usesCanvasLayout(total) || index < 0 || index >= total) return;
                                if (CanvasApi.isColorOs17()) {
                                    param.setResult(Boolean.FALSE);
                                    if (duration != 450 || !animate || focusing.contains(canvas)) return;
                                    int mode = (Integer) param.args[2];
                                    boolean autoSwap = CanvasGeometry.mainPane(total,
                                            mainPaneMode((View) canvas, adapter)) >= 0
                                            && autoMainWindow(((View) canvas).getContext());
                                    if (isFourZoomed(canvas) || (!isLandscape((View) canvas)
                                            && !autoSwap && !Settings.preventClickZoom())) {
                                        changeZoom((View) canvas, adapter, index, true);
                                    } else if (mode != 2) {
                                        XposedHelpers.callMethod(adapter, "I", index);
                                    }
                                    return;
                                }
                                if (duration == 0 && !isFourZoomed(canvas)) {
                                    param.setResult(Boolean.FALSE);
                                    return;
                                }
                                if (duration != 450 || !animate) return;
                                // 从菜单缩小之后，点击窗口只切焦点，放大走窗口菜单
                                if (Boolean.TRUE.equals(menuShrunk.get(canvas))
                                        && Settings.preventClickZoom()) {
                                    int mode = (Integer) param.args[2];
                                    if (mode != 2) {
                                        XposedHelpers.callMethod(adapter, "I", index);
                                    }
                                    param.setResult(Boolean.FALSE);
                                    log("click zoom skipped after shrink, focus=" + index);
                                    return;
                                }
                                menuShrunk.remove(canvas);
                                fourZoomed.put(canvas, Boolean.TRUE);
                                markEnlarged(adapter, total, index);
                                if (total == 4) return;
                                layoutKeys.remove(canvas);
                                XposedHelpers.callMethod(canvas, LAYOUT.value());
                            } catch (Throwable error) {
                                log("click refresh failed: " + error);
                            }
                        }

                    });
            log("click hooked");
        } catch (Throwable error) {
            log("click hook failed: " + error);
        }
    }

    private static final java.util.WeakHashMap<Object, Boolean> watchdogs =
            new java.util.WeakHashMap<>();

    /** 定期检查任务尺寸，画布切回来以后任务可能被系统恢复成全屏。 */
    private static void startWatchdog(Object view) {
        if (watchdogs.containsKey(view)) return;
        if (!(view instanceof android.view.View)) return;
        watchdogs.put(view, Boolean.TRUE);
        java.lang.ref.WeakReference<android.view.View> reference =
                new java.lang.ref.WeakReference<>((android.view.View) view);
        final android.os.Handler handler =
                new android.os.Handler(android.os.Looper.getMainLooper());
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                android.view.View canvas = reference.get();
                if (canvas == null || !canvas.isAttachedToWindow()) {
                    watchdogs.remove(canvas);
                    return;
                }
                try {
                    if (canvas.isShown()) {
                        syncTaskData(canvas);
                        forcePhoneSize(canvas);
                    }
                } catch (Throwable ignored) {
                }
                handler.postDelayed(this, 1000);
            }
        }, 500);
        log("watchdog started");
    }

    /** 三窗四窗都用整屏比例的任务，窗口大小由布局决定。 */
    private static Rect currentTarget() {
        return phoneSize();
    }

    private static volatile int windowCount = 0;

    /** 任务尺寸直接用屏幕可用区域，不乘任何系数。 */
    private static Rect phoneSize() {
        android.util.DisplayMetrics dm = android.content.res.Resources
                .getSystem().getDisplayMetrics();
        int availH = dm.heightPixels - statusBarHeight();
        return new Rect(0, 0, dm.widthPixels, availH);
    }

    private static int columnGap() {
        return Math.max(1, Math.round(2f * android.content.res.Resources
                .getSystem().getDisplayMetrics().density));
    }

    private static float columnShare(Object canvas, int count, int mode) {
        View view = (View) canvas;
        boolean landscape = isLandscape(view);
        float initial = landscape && count != 2 ? CanvasGeometry.LANDSCAPE_LEFT_SHARE
                : CanvasGeometry.defaultLeftShare(count, mode);
        if (count < 2 || count > 4) return initial;
        PaneEdgeDrag drag = paneEdgeDrags.get(view);
        if (count == 2 && drag != null && drag.matches(view, count, mode, false)) return drag.share;
        String key = paneLayoutKey(count, mode, landscape, false);
        return layoutPrefs(view.getContext()).getFloat(key + "_share", initial);
    }

    private static int layoutMode(Object adapter) {
        if (count(adapter) == 2) return DEFAULT_ORIENTATION;
        return forcedOrientation != 0 ? forcedOrientation : (Integer) XposedHelpers.callMethod(adapter, "n");
    }

    private record CanvasDisplay(Rect area, Rect portraitTask, boolean landscape,
                                 int preferredMainPaneMode, int twoPaneSecondaryHeight,
                                 int twoPaneMainHeight, boolean stacked, float stackedShare,
                                 PaneInsets[] paneInsets) {
        int margin() {
            return landscape ? 0 : marginPx();
        }

        int availableWidth() {
            return area.width() - 2 * margin();
        }

        int availableHeight() {
            return area.height() - 2 * margin();
        }
    }

    private static boolean isLandscape(View canvas) {
        return canvas.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private static Rect canvasArea(View canvas) {
        return canvasDisplay(canvas).area();
    }

    private static CanvasDisplay canvasDisplay(View canvas) {
        WindowManager manager = canvas.getContext().getSystemService(WindowManager.class);
        android.view.WindowMetrics metrics = manager.getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        WindowInsets insets = canvas.getRootWindowInsets();
        CanvasDisplay display = canvasDisplay(bounds.width(), bounds.height(),
                insets == null ? metrics.getWindowInsets() : insets,
                canvas.getContext());
        Object adapter = CanvasApi.adapter(canvas);
        int count = adapter == null ? 0 : count(adapter);
        boolean stacked = count == 2 && display.stacked();
        int mode = count < 2 ? DEFAULT_ORIENTATION : layoutMode(adapter);
        boolean landscape = isLandscape(canvas);
        String key = paneLayoutKey(count, mode, landscape, stacked);
        PaneEdgeDrag drag = paneEdgeDrags.get(canvas);
        if (drag != null && (drag.display.landscape() != display.landscape()
                || !drag.matches(canvas, count, mode, stacked))) drag = null;
        PaneInsets[] geometry = new PaneInsets[count];
        var prefs = layoutPrefs(canvas.getContext());
        float stackedShare = DEFAULT_STACKED_SHARE;
        if (stacked) {
            stackedShare = drag == null ? prefs.getFloat(key + "_share", DEFAULT_STACKED_SHARE) : drag.share;
            for (int slot = 0; slot < count; slot++) {
                boolean top = slot == (display.preferredMainPaneMode() == 6 ? 1 : 0);
                int width = drag != null && !drag.edge.divider() && drag.edge.leftPane() == top
                        ? drag.width : stackedWindowWidth(canvas, top, false);
                float inset = (100 - width) / 200f;
                if (inset > 0f) geometry[slot] = new PaneInsets(inset, 0f, inset, 0f);
            }
        } else if (count >= 3 && count <= 4) {
            for (int index = 0; index < count; index++) {
                PaneInsets pane = drag != null && drag.slot == index
                        ? drag.insets : readPaneInsets(prefs, key, index);
                if (!pane.equals(PaneInsets.NONE)) geometry[index] = pane;
            }
        }
        int main = display.twoPaneMainHeight();
        int secondary = display.twoPaneSecondaryHeight();
        if (drag != null && count == 2 && !stacked && !drag.edge.divider()) {
            if (drag.edge.main()) main = drag.height;
            else secondary = drag.height;
        }
        return new CanvasDisplay(display.area(), display.portraitTask(), display.landscape(),
                display.preferredMainPaneMode(), secondary, main, stacked, stackedShare, geometry);
    }

    private static CanvasDisplay fallbackCanvasDisplay() {
        android.util.DisplayMetrics metrics = android.content.res.Resources.getSystem().getDisplayMetrics();
        return canvasDisplay(metrics.widthPixels, metrics.heightPixels, null, null);
    }

    private static CanvasDisplay canvasDisplay(int width, int height, WindowInsets windowInsets,
                                               Context context) {
        boolean landscape = width > height;
        int mainSide = 0;
        int secondaryHeight = 0;
        int mainHeight = 0;
        boolean stacked = context != null && stackedTwoPanes(context);
        if (context != null) {
            var prefs = context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE);
            mainSide = prefs.getInt(MAIN_WINDOW_SIDE, 0);
            secondaryHeight = prefs.getInt(twoPaneHeightKey(landscape, false), 0);
            mainHeight = prefs.getInt(twoPaneHeightKey(landscape, true), 0);
        }
        Rect portraitTask = new Rect(0, 0, Math.min(width, height),
                Math.max(width, height) - statusBarHeight());
        if (!landscape) {
            return new CanvasDisplay(new Rect(0, statusBarHeight(), width, height),
                    portraitTask, false, mainSide, secondaryHeight, mainHeight, stacked,
                    DEFAULT_STACKED_SHARE, new PaneInsets[0]);
        }
        // 横屏画布可进入挖孔区域，系统栏仍按实际占用空间计算。
        Insets insets = windowInsets == null ? Insets.NONE : windowInsets.getInsets(
                WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
        Rect area = new Rect(insets.left, insets.top, width - insets.right, height - insets.bottom);
        return new CanvasDisplay(area, portraitTask, true, mainSide, secondaryHeight, mainHeight, stacked,
                DEFAULT_STACKED_SHARE, new PaneInsets[0]);
    }

    private static int twoPaneHeightPercent(CanvasDisplay display, boolean main) {
        int saved = main ? display.twoPaneMainHeight() : display.twoPaneSecondaryHeight();
        if (saved > 0) return saved;
        if (display.landscape()) return 100;
        float baselineShare = CanvasGeometry.defaultLeftShare(2, DEFAULT_ORIENTATION);
        float width = (display.availableWidth() - columnGap()) * (main ? 1f - baselineShare : baselineShare);
        Rect phone = display.portraitTask();
        int percent = Math.round(100f * width * phone.height() / phone.width() / display.availableHeight());
        return Math.max(MIN_TWO_PANE_HEIGHT, Math.min(100, percent));
    }

    private static List<Rect> proportionalPanes(int count, int mode, float leftShare, CanvasDisplay display) {
        List<Rect> panes = basePanes(count, mode, leftShare, display);
        for (int index = 0; index < Math.min(panes.size(), display.paneInsets().length); index++) {
            PaneInsets insets = display.paneInsets()[index];
            if (insets != null) panes.set(index, insets.apply(panes.get(index)));
        }
        return panes;
    }

    private static List<Rect> basePanes(int count, int mode, float leftShare, CanvasDisplay display) {
        int width = count == 2 ? display.area().width() : display.availableWidth();
        int height = display.availableHeight();
        List<Rect> panes;
        if (count == 2 && display.stacked()) {
            return CanvasGeometry.twoStackedPanes(width, height, columnGap(), display.stackedShare(),
                    display.preferredMainPaneMode() == 6);
        }
        if (count == 2) {
            int secondaryHeight = Math.round(height * twoPaneHeightPercent(display, false) / 100f);
            int mainHeight = Math.round(height * twoPaneHeightPercent(display, true) / 100f);
            panes = CanvasGeometry.twoPanes(width, height, columnGap(), leftShare,
                    secondaryHeight, mainHeight);
        } else if (display.landscape()) {
            panes = CanvasGeometry.landscapeRow(width, height, columnGap(), count, leftShare);
        } else if (CanvasGeometry.hasPhoneRatioColumns(count, mode)) {
            panes = CanvasGeometry.phoneRatioColumns(width, height, columnGap(), count, mode,
                    leftShare, display.portraitTask());
        } else {
            panes = CanvasGeometry.panes(width, height, columnGap(), count, mode, leftShare);
        }
        int mainMode = display.landscape() ? DEFAULT_ORIENTATION : mode;
        if (panes.size() == count && isMainPaneReversed(count, mainMode, display.preferredMainPaneMode())) {
            CanvasGeometry.moveMainPaneToOppositeSide(panes, CanvasGeometry.mainPane(count, mainMode));
        }
        return panes;
    }

    private static java.util.List<Rect> fourPanes() {
        if (CanvasApi.isColorOs17()) {
            CanvasDisplay display = layoutDisplay.get();
            return proportionalPanes(4, 0, 0.5f, display == null ? fallbackCanvasDisplay() : display);
        }
        Rect cell = phoneSize();
        int gap = Math.round(10f * android.content.res.Resources
                .getSystem().getDisplayMetrics().density);
        return CanvasGeometry.panes(2 * cell.width() + gap, 2 * cell.height() + gap, gap, 4, 0);
    }

    private static java.util.List<Rect> taskTargets(Object canvas, Object adapter, int count) {
        if (!CanvasApi.isColorOs17()) return java.util.Collections.nCopies(count, phoneSize());

        CanvasDisplay display = canvasDisplay((View) canvas);
        Rect target = display.portraitTask();
        int mode = layoutMode(adapter);
        if (count == 2) {
            List<Rect> panes = proportionalPanes(count, mode, columnShare(canvas, count, mode), display);
            if (panes.size() != count) return List.of(target, target);
            float baseTaskScale = display.landscape() ? 1f : 1.5f;
            List<Rect> targets = new ArrayList<>(count);
            float referenceWidth = display.stacked() ? display.area().width()
                    : (display.area().width() - columnGap()) / 2f;
            int availableHeight = display.availableHeight();
            for (int slot = 0; slot < count; slot++) {
                float referenceHeight;
                if (display.stacked()) {
                    referenceHeight = (availableHeight - columnGap()) * (slot == 0 ? 1f / 3f : 2f / 3f);
                } else {
                    referenceHeight = display.landscape() ? availableHeight
                            : Math.min(availableHeight, referenceWidth * target.height() / target.width());
                }
                targets.add(responsiveTaskBounds(panes.get(slot), baseTaskScale, referenceWidth, referenceHeight));
            }
            return inTaskOrder(targets, panePositions(canvas, adapter));
        }
        float initialShare = display.landscape() ? CanvasGeometry.LANDSCAPE_LEFT_SHARE
                : CanvasGeometry.defaultLeftShare(count, mode);
        List<Rect> baseline = basePanes(count, mode, initialShare, display);
        if (baseline.size() != count) return java.util.Collections.nCopies(count, target);
        List<Rect> panes = proportionalPanes(count, mode, columnShare(canvas, count, mode), display);
        if (panes.size() != count) return java.util.Collections.nCopies(count, target);
        int mainSlot = CanvasGeometry.mainPane(count, mainPaneMode((View) canvas, adapter));
        int minWidth = Math.round(240f * android.content.res.Resources
                .getSystem().getDisplayMetrics().density);
        if (display.landscape()) minWidth = target.width();
        java.util.ArrayList<Rect> targets = new java.util.ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            Rect pane = panes.get(index);
            Rect original = baseline.get(index);
            float taskScale;
            if (display.landscape() && index == mainSlot) {
                taskScale = Math.min(1.6f, (float) display.area().width() / original.width());
            } else {
                Rect originalTarget = !display.landscape() && CanvasGeometry.hasPhoneRatioColumns(count, mode)
                        ? target : CanvasGeometry.taskBounds(original, target, minWidth);
                taskScale = Math.max((float) originalTarget.width() / original.width(),
                        (float) originalTarget.height() / original.height());
            }
            targets.add(responsiveTaskBounds(pane, taskScale, original.width(), original.height()));
        }
        return inTaskOrder(targets, mainSlot >= 0 ? panePositions(canvas, adapter) : null);
    }

    private static Rect responsiveTaskBounds(Rect pane, float taskScale, float referenceWidth,
                                              float referenceHeight) {
        double areaRatio = (pane.width() / referenceWidth) * (pane.height() / referenceHeight);
        taskScale /= (float) Math.min(1d, Math.pow(areaRatio, 0.6));
        return new Rect(0, 0, Math.max(1, Math.round(pane.width() * taskScale)),
                Math.max(1, Math.round(pane.height() * taskScale)));
    }

    private static List<Rect> inTaskOrder(List<Rect> panes, int[] order) {
        if (order == null || order.length != panes.size()) return panes;
        List<Rect> ordered = new ArrayList<>(order.length);
        for (int slot : order) ordered.add(panes.get(slot));
        return ordered;
    }

    private static int statusBarHeight() {
        try {
            android.content.res.Resources res = android.content.res.Resources.getSystem();
            int id = res.getIdentifier("status_bar_height", "dimen", "android");
            return id > 0 ? res.getDimensionPixelSize(id) : 0;
        } catch (Throwable error) {
            return 0;
        }
    }

    /** 布局前把 u1/d 缓存统一成目标尺寸。 */
    private static void syncTaskData(Object container) {
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null) return;
            containers.put(container, Boolean.TRUE);
            int count = count(adapter);
            Integer last = taskCounts.put(container, count);
            boolean restoreSystemSplit = !CanvasApi.isColorOs17() && last != null
                    && last >= 3 && count == 2;
            if (last != null && last != count) {
                layoutKeys.remove(container);
                if (CanvasApi.isColorOs17()) {
                    cancelPaneEdgeDrag((View) container);
                    fourZoomed.remove(container);
                    enlargedPanes.remove(container);
                    cancelMainPaneAnimation(container);
                    tasksBySlot.remove(container);
                    cancelZoom(container);
                    if (count >= 2) markEnlarged(adapter, count, -1);
                    else menuShrunk.remove(container);
                }
                // 四窗总览是 2×2 的缩放值，关掉一个窗口后画布还停在这个比例上，
                // 三窗矩形塞不进去画面就是坏的。这里按三窗重新算一次总览。
                if (!CanvasApi.isColorOs17() && last == 4 && count == 3 && !isFourZoomed(container)) {
                    final Object canvas = container;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(() -> fitThree(canvas, 0));
                }
            }
            windowCount = count;
            multiWindowMode = count == 3;
            if (!usesCanvasLayout(count)) {
                if (restoreSystemSplit) restoreTwoSplit(container, adapter, count);
                return;
            }
            java.util.List<Rect> targets = taskTargets(container, adapter, count);
            for (int i = 0; i < count; i++) {
                updateTaskData(adapter, i, targets.get(i), true);
            }
        } catch (Throwable error) {
            log("sync taskData failed: " + error);
        }
    }

    /** 两窗尺寸：和系统原版一致。 */
    private static Rect splitSize() {
        android.util.DisplayMetrics dm = android.content.res.Resources
                .getSystem().getDisplayMetrics();
        return new Rect(0, 0, dm.widthPixels, Math.round(dm.heightPixels * 0.4698f));
    }

    /** 退回两窗时把任务尺寸还原，避免继续按长条缩放。 */
    private static void restoreTwoSplit(Object container, Object adapter, int count) {
        try {
            if (count < 2) return;
            Rect target = splitSize();
            ClassLoader loader = container.getClass().getClassLoader();
            for (int i = 0; i < count; i++) {
                Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), i);
                if (decor == null) continue;
                Object taskView = fieldOfTypeDeep(decor, FLEXIBLE_TASK_VIEW);
                if (taskView == null) continue;
                Object launch = launchBoundsOf(taskView);
                if (launch instanceof Rect
                        && ((Rect) launch).width() == target.width()
                        && ((Rect) launch).height() == target.height()) {
                    continue;
                }
                Object token = XposedHelpers.getObjectField(taskView, "mTaskToken");
                if (token == null) continue;
                try {
                    Object wct = XposedHelpers.newInstance(XposedHelpers.findClass(
                            "android.window.WindowContainerTransaction", loader));
                    XposedHelpers.callMethod(wct, "setBounds", token, target);
                    try {
                        XposedHelpers.callMethod(wct, "setAppBounds", token, target);
                    } catch (Throwable ignored) {
                    }
                    Object organizer = XposedHelpers.callMethod(
                            taskView, "getTaskOrganizer");
                    if (organizer != null) {
                        XposedHelpers.callMethod(organizer, "applyTransaction", wct);
                    }
                    setLaunchBounds(taskView, target);
                    updateTaskData(adapter, i, target, false);
                    log("restore two split " + target.width() + "x" + target.height());
                } catch (Throwable error) {
                    log("restore two split failed: " + error);
                }
            }
        } catch (Throwable error) {
            log("restore pass failed: " + error);
        }
    }

    private static void forcePhoneSize(Object container) {
        forcePhoneSize(container, false);
    }

    private static void forcePhoneSize(Object container, boolean duringLayout) {
        startWatchdog(container);
        boolean pending = false;
        boolean changed = false;
        boolean fixedPaneSizes = false;
        try {
            Object adapter = CanvasApi.adapter(container);
            if (adapter == null) return;
            int count = count(adapter);
            windowCount = count;
            multiWindowMode = count == 3;
            if (!usesCanvasLayout(count)) return;

            fixedPaneSizes = CanvasApi.isColorOs17() && count >= 2 && count <= 4;
            java.util.List<Rect> targets = taskTargets(container, adapter, count);
            ClassLoader loader = container.getClass().getClassLoader();

            for (int i = 0; i < count; i++) {
                Rect target = targets.get(i);
                Object decor = XposedHelpers.callMethod(container, EMBEDDED_VIEW.value(), i);
                if (decor == null) { pending = true; continue; }
                Object taskView = fieldOfTypeDeep(decor, FLEXIBLE_TASK_VIEW);
                if (taskView == null) { pending = true; continue; }

                int taskId = -1;
                try {
                    Object info = XposedHelpers.getObjectField(taskView, "mTaskInfo");
                    if (info != null) taskId = XposedHelpers.getIntField(info, "taskId");
                } catch (Throwable ignored) {
                }
                if (taskId <= 0) { pending = true; continue; }

                Object token = XposedHelpers.getObjectField(taskView, "mTaskToken");
                if (token == null) { pending = true; continue; }

                Object launch = launchBoundsOf(taskView);
                if (launch instanceof Rect
                        && ((Rect) launch).width() == target.width()
                        && ((Rect) launch).height() == target.height()) {
                    continue;
                }

                changed = true;
                try {
                    Object wct = XposedHelpers.newInstance(XposedHelpers.findClass(
                            "android.window.WindowContainerTransaction", loader));
                    XposedHelpers.callMethod(wct, "setBounds", token, target);
                    try {
                        XposedHelpers.callMethod(wct, "setAppBounds", token, target);
                    } catch (Throwable ignored) {
                    }
                    Object organizer = XposedHelpers.callMethod(
                            taskView, "getTaskOrganizer");
                    if (organizer != null) {
                        XposedHelpers.callMethod(organizer, "applyTransaction", wct);
                    }
                    setLaunchBounds(taskView, target);
                    updateTaskData(adapter, i, target, true);
                    log("phone size " + taskId + " -> " + target.width() + "x" + target.height());
                } catch (Throwable error) {
                    log("phone size " + taskId + " failed: " + error);
                }
            }
        } catch (Throwable error) {
            log("phone size pass failed: " + error);
        }

        if (duringLayout) return;
        if (changed && fixedPaneSizes) {
            XposedHelpers.callMethod(container, "z2");
        } else if (changed && !relayoutScheduled) {
            relayoutScheduled = true;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> {
                        try {
                            XposedHelpers.callMethod(container, LAYOUT.value());
                        } catch (Throwable error) {
                            log("relayout failed: " + error);
                        } finally {
                            relayoutScheduled = false;
                        }
                    }, 900);
        }

        if (pending && attempts < 8) {
            attempts++;
            new android.os.Handler(android.os.Looper.getMainLooper())
                    .postDelayed(() -> forcePhoneSize(container), 700);
        } else if (!pending) {
            attempts = 0;
        }
    }

    private static Object containerOf(Object view) {
        try {
            Object context = XposedHelpers.callMethod(view, "getContext");
            Object activity = XposedHelpers.callStaticMethod(
                    XposedHelpers.findClass(UTIL.value(),
                            view.getClass().getClassLoader()), ACTIVITY_FROM_CONTEXT.value(), context);
            if (activity == null) return null;
            return XposedHelpers.callMethod(activity, ACTIVITY_VIEW.value());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object launchBoundsOf(Object taskView) {
        try {
            java.lang.reflect.Field field = taskView.getClass()
                    .getDeclaredField("mLaunchBounds");
            field.setAccessible(true);
            return field.get(taskView);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setLaunchBounds(Object taskView, Rect target) {
        try {
            java.lang.reflect.Field field = taskView.getClass()
                    .getDeclaredField("mLaunchBounds");
            field.setAccessible(true);
            field.set(taskView, new Rect(target));
        } catch (Throwable ignored) {
        }
    }

    /** M1 用的是 u1/d 里缓存的 bounds，改完任务要同步它。 */
    private static void updateTaskData(Object adapter, int index, Rect target, boolean proportional) {
        try {
            Object taskData = XposedHelpers.callMethod(adapter, "getItem", index);
            if (taskData == null) return;
            java.lang.reflect.Field field = taskData.getClass().getDeclaredField("a");
            field.setAccessible(true);
            Rect bounds = new Rect(target);
            field.set(taskData, bounds);
            if (CanvasApi.isColorOs17()) {
                if (proportional) {
                    XposedHelpers.setAdditionalInstanceField(taskData, PROPORTIONAL_BOUNDS, bounds);
                } else {
                    XposedHelpers.removeAdditionalInstanceField(taskData, PROPORTIONAL_BOUNDS);
                }
            }
        } catch (Throwable error) {
            log("update taskData failed: " + error);
        }
    }

    // ---------------------------------------------------------------- 原版策略

    private static void useTabletPolicy(ClassLoader loader) {
        try {
            Class<?> selector = XposedHelpers.findClass(POLICY_SELECTOR.value(), loader);
            XposedHelpers.findAndHookMethod(selector, "b", android.content.Context.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 两窗保持原版 PhonePolicy，不能动原版二分屏
                                if (!multiWindowMode) return;
                                Object tablet = fieldOfType(param.thisObject, TABLET_POLICY.value());
                                if (tablet != null) {
                                    param.setResult(tablet);
                                    log("layout policy -> tablet");
                                }
                            } catch (Throwable error) {
                                log("tablet policy switch failed: " + error);
                            }
                        }
                    });
            log("policy selector hooked");
        } catch (Throwable error) {
            log("policy selector unavailable: " + error);
        }
    }

    private static void forceSplitMode(ClassLoader loader) {
        try {
            Class<?> adapterType = XposedHelpers.findClass(ADAPTER.value(), loader);
            XC_MethodHook modeHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = param.args[0];
                        int count = count(adapter);
                        if (count != 3) return;
                        int mode;
                        if (CanvasApi.isColorOs17() && forcedOrientation >= 3 && forcedOrientation <= 7) {
                            mode = forcedOrientation;
                        } else if (forcedMode >= 3 && forcedMode <= 7) {
                            mode = forcedMode;
                        } else {
                            Object context = XposedHelpers.callMethod(adapter, "getContext");
                            int orientation = ((android.content.Context) context).getResources()
                                    .getConfiguration().orientation;
                            mode = orientation
                                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                                    ? 6 : 4;
                        }
                        param.setResult(mode);
                        log("split mode for " + count + " windows -> " + mode);
                    } catch (Throwable error) {
                        log("split mode failed: " + error);
                    }
                }
            };
            Class<?> policy = XposedHelpers.findClass(TABLET_POLICY.value(), loader);
            XposedHelpers.findAndHookMethod(policy, "c", adapterType,
                    int.class, int.class, boolean.class, modeHook);
            if (CanvasApi.isColorOs17()) {
                // 添加第三个任务时，控制器会在布局回调之前用手机策略求拆分模式。
                Class<?> phone = XposedHelpers.findClass("i2.o", loader);
                XposedHelpers.findAndHookMethod(phone, "c", adapterType,
                        int.class, int.class, boolean.class, modeHook);
            }
            log("split mode hooked");
        } catch (Throwable error) {
            log("split mode unavailable: " + error);
        }
    }

    private static void allowMoreThanThree(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedHelpers.findAndHookMethod(view, THREE_WINDOWS.value(), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        if (adapter == null) return;
                        int count = count(adapter);
                        if (count > 3) {
                            param.setResult(Boolean.TRUE);
                            log("canvas accepts " + count + " windows");
                        }
                    } catch (Throwable error) {
                        log("canvas count check failed: " + error);
                    }
                }
            });
            log("container view hooked");
        } catch (Throwable error) {
            log("container view unavailable: " + error);
        }
    }

    private static void ignoreLineLayout(ClassLoader loader) {
        try {
            Class<?> adapter = XposedHelpers.findClass(ADAPTER.value(), loader);
            XposedBridge.hookAllMethods(adapter, "j", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // 二窗是系统原生分屏，布局方向交给原版
                    if (count(param.thisObject) < 3) return;
                    param.setResult(Boolean.FALSE);
                }
            });
            log("line layout disabled");
        } catch (Throwable error) {
            log("line layout unavailable: " + error);
        }
    }

    // ---------------------------------------------------------------- 工具

    private static boolean usesCanvasLayout(int count) {
        return count >= (CanvasApi.isColorOs17() ? 2 : 3);
    }

    private static void cancelPaneEdgeDrag(View canvas) {
        paneEdgeDrags.remove(canvas);
    }

    private static int count(Object adapter) {
        try {
            Object value = XposedHelpers.callMethod(adapter, "getCount");
            return value instanceof Integer ? (Integer) value : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static Object fieldOfType(Object owner, String className) {
        for (java.lang.reflect.Field field : owner.getClass().getDeclaredFields()) {
            if (!field.getType().getName().equals(className)) continue;
            try {
                field.setAccessible(true);
                return field.get(owner);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Object fieldOfTypeDeep(Object owner, String className) {
        Class<?> type = owner.getClass();
        while (type != null && type != Object.class) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (!field.getType().getName().equals(className)) continue;
                try {
                    field.setAccessible(true);
                    return field.get(owner);
                } catch (Throwable ignored) {
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    /** 只打日志：多个窗口在屏幕上到底落在哪，圆角是不是被裁。 */
    /** 只打关键信息：窗口数、每个窗口的布局矩形。 */
    private static void dumpLayout(Object view) {
        try {
            Object adapter = CanvasApi.adapter(view);
            int total = adapter == null ? 0 : count(adapter);
            if (total == lastDumpedCount) return;
            lastDumpedCount = total;
            StringBuilder sb = new StringBuilder("layout windows=").append(total);
            for (int i = 0; i < total; i++) {
                Object decor = XposedHelpers.callMethod(view, EMBEDDED_VIEW.value(), i);
                if (!(decor instanceof android.view.View)) continue;
                android.view.View d = (android.view.View) decor;
                sb.append(" [").append(d.getLeft()).append(",").append(d.getTop())
                        .append(",").append(d.getRight()).append(",")
                        .append(d.getBottom()).append("]");
            }
            log(sb.toString());
        } catch (Throwable error) {
            log("dump failed: " + error);
        }
    }

    private static volatile int lastDumpedCount = -1;
}
