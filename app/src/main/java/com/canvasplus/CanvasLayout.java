// 一加分屏增强修改了 CanvasPlus 的此源文件。
package com.canvasplus;

import static com.canvasplus.CanvasApi.Member.*;
import static com.canvasplus.CanvasApi.colorOs16Revision;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

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

    private static final String FLEXIBLE_TASK_VIEW =
            "com.oplus.flexiblewindow.FlexibleTaskView";
    private static final String PROPORTIONAL_BOUNDS = "canvasplus.proportionalBounds";
    private static final int DIVIDER_BLUE = 0xff9bd3ff;
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
            if (CanvasApi.usesScreenSizedOverview() && count >= 2) {
                sb.append(canvasArea((View) container)).append(':');
                if (count <= 3) {
                    sb.append(preferredMainPaneMode(((View) container).getContext())).append(':');
                }
            }
            for (int i = 0; i < count; i++) {
                Object task = XposedHelpers.callMethod(adapter, "getItem", i);
                if (task == null) continue;
                Object rect = XposedHelpers.callMethod(task, TASK_BOUNDS.value());
                Object source = null;
                Object taskId = null;
                try {
                    source = XposedHelpers.callMethod(task, "k");
                } catch (Throwable ignored) {
                }
                try {
                    taskId = XposedHelpers.callMethod(task, TASK_ID_GETTER.value());
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
        if (CanvasApi.usesScreenSizedOverview()) {
            hookModernWindowMenu(loader);
            hookSquarePaneCorners(loader);
            hookPaneResizeTouch(loader);
            hookSavedSplitOrder(loader);
            hookMainPaneSwap(loader);
            hookCanvasTransitionMask(loader);
            if (CanvasApi.hasColorOs16CanvasUpdate()) hookFullscreenCloseCover(loader);
            hookThreeToTwoCloseAnimation(loader);
            hookTaskWindowDrawn(loader);
        } else hookWindowMenu(loader);
        hookFourZoom(loader);
        hookTouchRegion(loader);
    }

    /**
     * 系统只给完整落入画布的任务让出触摸区域。键盘位移或焦点缩放裁掉窗口边缘时，
     * 用焦点窗的可见部分补上，让应用继续接收手势。
     */
    private static void hookTouchRegion(ClassLoader loader) {
        if (CanvasApi.usesScreenSizedOverview()) {
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
            // The alternate ColorOS 16 canvas renamed the native zoom dispatcher
            // from m() to l().  Hooking the dispatcher is essential here: its
            // stock implementation promotes the selected embedded task to a
            // standalone full-screen window.
            XposedBridge.hookAllMethods(replacer,
                    CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("m", "m", "l") : "m",
                    new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        Object container = param.args[0];
                        Object adapter = CanvasApi.adapter(container);
                        if (adapter == null) return;
                        int total = count(adapter);
                        if (!usesCanvasLayout(total)) return;
                        boolean enlarge = (Boolean) param.args[2];
                        if (CanvasApi.usesScreenSizedOverview()) {
                            int index = taskPosition(adapter, param.args[1]);
                            if (index >= 0) changeZoom((View) container, adapter, index, enlarge);
                            param.setResult(null);
                            return;
                        }
                        if (enlarge) {
                            menuShrunk.remove(container);
                            fourZoomed.put(container, Boolean.TRUE);
                            int focus = 0;
                            int position = taskPosition(adapter, param.args[1]);
                            if (position >= 0) {
                                focus = position;
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
            boolean threeStacked = !landscape && !enlarge && panes == 3
                    && layoutMode(adapter) == THREE_STACKED_MODE;
            boolean alignTop = !landscape && !enlarge && (panes == 4 || threeStacked);
            float top = area.top + (alignTop ? 0 : margin);
            float height = area.bottom - top - (threeStacked ? threeStackedBottomMargin() : margin);
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
            XposedHelpers.callMethod(adapter, ADAPTER_FOCUS.value(), index);
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
    private static final int THREE_PANE_LAYOUT_ACTION = 1028;
    private static final int TWO_PANE_LAYOUT_ACTION = 1029;
    private static final int LAYOUT_LOCK_ACTION = 1030;
    private static final int STACKED_WINDOW_WIDTH_ACTION = 1031;
    private static final int CONTENT_SCALE_ACTION = 1032;
    private static final int MIN_CONTENT_SCALE = 60;
    private static final int MAX_CONTENT_SCALE = 160;
    private static final int CONTENT_SCALE_STEP = 5;
    private record ContentScalePreview(String key, int percent) {}
    private static final java.util.WeakHashMap<View, ContentScalePreview> contentScalePreviews =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<Object, Boolean> contentSizedTasks = new java.util.WeakHashMap<>();
    private static final String LAYOUT_LOCKED = "layout_locked";
    private static final String AUTO_MAIN_WINDOW = "auto_main_window";
    private static final String MAIN_WINDOW_SIDE = "main_window_side";
    private static final int THREE_STACKED_MODE = 5;
    private static final String THREE_STACKED = "three_panes_stacked";
    private static boolean restoredThreePaneMode;
    private static final float DEFAULT_STACKED_SHARE = 1f / 3f;
    private static final String TWO_STACKED = "two_panes_stacked";
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
        if (locked) cancelPaneEdgeDrag(canvas);
        requestPaneLayout(canvas);
        log("layout locked=" + locked);
    }

    private static boolean autoMainWindow(Context context) {
        return context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE)
                .getBoolean(AUTO_MAIN_WINDOW, false);
    }

    private static int preferredMainPaneMode(Context context) {
        var prefs = layoutPrefs(context);
        return prefs.getInt(orientedKey(MAIN_WINDOW_SIDE, context), prefs.getInt(MAIN_WINDOW_SIDE, 0));
    }

    private static int mainPaneModeOnScreen(View canvas, Object adapter) {
        int preferred = preferredMainPaneMode(canvas.getContext());
        return preferred == 0 ? mainPaneMode(canvas, adapter) : preferred;
    }

    private static boolean isMainPaneReversed(int count, int layoutMode, int preferredMode) {
        return layoutMode != THREE_STACKED_MODE && preferredMode != 0 && preferredMode != layoutMode
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
                .putInt(orientedKey(MAIN_WINDOW_SIDE, canvas.getContext()), target).apply();
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

    static int savedThreePaneMode(Context context) {
        return layoutPrefs(context).getBoolean(THREE_STACKED, false) ? THREE_STACKED_MODE : DEFAULT_ORIENTATION;
    }

    private static void toggleThreePaneLayout(View canvas, Object adapter) {
        boolean stacked = layoutMode(adapter) != THREE_STACKED_MODE;
        layoutPrefs(canvas.getContext()).edit().putBoolean(THREE_STACKED, stacked).apply();
        forcedOrientation = stacked ? THREE_STACKED_MODE : DEFAULT_ORIENTATION;
        cancelPaneEdgeDrag(canvas);
        cancelMainPaneAnimation(canvas);
        cancelZoom(canvas);
        enlargedPanes.remove(canvas);
        fourZoomed.remove(canvas);
        markEnlarged(adapter, 3, -1);
        tasksBySlot.remove(canvas);
        requestPaneLayout(canvas);
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

    private static String orientedKey(String key, Context context) {
        boolean landscape = context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        return key + (landscape ? "_landscape" : "_portrait");
    }

    private static boolean twoPanesStacked(Context context) {
        var prefs = layoutPrefs(context);
        return prefs.getBoolean(orientedKey(TWO_STACKED, context), prefs.getBoolean(TWO_STACKED, true));
    }

    private static void toggleTwoPaneLayout(View canvas, Object adapter) {
        Context context = canvas.getContext();
        layoutPrefs(context).edit().putBoolean(orientedKey(TWO_STACKED, context), !twoPanesStacked(context)).apply();
        tasksBySlot.remove(canvas);
        refreshTwoPaneOverview(canvas, adapter);
    }

    private static String stackedWidthKey(boolean landscape, boolean top) {
        return paneLayoutKey(2, DEFAULT_ORIENTATION, landscape, true)
                + (top ? "_top_width" : "_bottom_width");
    }

    private static int stackedWindowWidth(Context context, boolean landscape, boolean top, boolean narrow) {
        var prefs = layoutPrefs(context);
        String suffix = narrow ? "_narrow" : "";
        String legacy = top ? "stacked_width_top" : "stacked_width_bottom";
        return prefs.getInt(stackedWidthKey(landscape, top) + suffix,
                prefs.getInt(legacy + suffix, narrow ? 50 : 100));
    }

    private static boolean isTopStackedWindow(View canvas, Object adapter, int index) {
        int[] order = panePositions(canvas, adapter);
        int slot = order == null ? index : order[index];
        return slot == (preferredMainPaneMode(canvas.getContext()) == 6 ? 1 : 0);
    }

    private static void toggleStackedWindowWidth(View canvas, Object adapter, int index) {
        Context context = canvas.getContext();
        if (layoutLocked(context) || !twoPanesStacked(context)) return;
        boolean top = isTopStackedWindow(canvas, adapter, index);
        boolean landscape = isLandscape(canvas);
        int width = stackedWindowWidth(context, landscape, top, false) < 100 ? 100
                : stackedWindowWidth(context, landscape, top, true);
        layoutPrefs(context).edit().putInt(stackedWidthKey(landscape, top), width).apply();
        refreshTwoPaneOverview(canvas, adapter);
    }

    private static String paneLayoutKey(int count, int mode, boolean landscape, boolean stacked) {
        // 横屏三、四窗以及竖屏四窗的几何布局不依赖系统传入的三窗方向。
        int layoutMode = count == 4 || landscape ? 0 : mode;
        return "pane_size_" + count + "_" + layoutMode
                + (landscape ? "_landscape" : "_portrait") + (stacked ? "_stacked" : "");
    }

    private static final int PRIMARY_DIVIDER = -1;
    private static final int UPPER_COLUMNS_DIVIDER = -2;
    private static final int MAIN_HEIGHT_EDGE = -3;
    private static final int SECONDARY_HEIGHT_EDGE = -4;
    private static final android.graphics.Paint DIVIDER_PAINT =
            new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    private record PaneEdge(Rect hit, int index, boolean horizontal, int heightDirection) {
        PaneEdge(Rect hit, int index, boolean horizontal) {
            this(hit, index, horizontal, 0);
        }

        boolean heightEdge() {
            return heightDirection != 0;
        }
    }

    private static Rect paneFrame(View canvas, int index) {
        View pane = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
        if (pane == null || !pane.isShown()) return null;
        Object taskView = XposedHelpers.callMethod(pane, "getFlexibleTaskView");
        if (taskView == null) return null;
        Rect bounds = (Rect) XposedHelpers.callMethod(taskView, "calculateFrame");
        return bounds.isEmpty() ? null : bounds;
    }

    private static Rect paneWindowFrame(View canvas, int index) {
        View pane = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
        Rect frame = new Rect();
        return readPaneWindowFrame(pane, frame) ? frame : null;
    }

    private static boolean readPaneWindowFrame(View pane, Rect frame) {
        if (pane == null || !pane.isShown() || !pane.isLaidOut()) return false;
        XposedHelpers.callMethod(pane, "getBoundsOnScreen", frame);
        return !frame.isEmpty();
    }

    private static final java.util.WeakHashMap<View, List<PaneEdge>> drawnDividers = new java.util.WeakHashMap<>();

    private static void refreshPaneDividers(View canvas) {
        drawnDividers.remove(canvas);
        canvas.postInvalidateOnAnimation();
    }

    private static List<PaneEdge> resizeEdges(View canvas) {
        return paneEdges(canvas, false);
    }

    private static List<PaneEdge> paneEdges(View canvas, boolean drawing) {
        if (isCanvasMaskVisible(canvas)) return List.of();
        if (!drawing && layoutLocked(canvas.getContext())) return List.of();
        PaneResizeCover cover = paneResizeCover(canvas);
        if (drawing && cover != null && cover.previewing()) return cover.dividers;
        Object adapter = CanvasApi.adapter(canvas);
        int count = adapter == null ? 0 : count(adapter);
        if (count < 2 || count > 4 || (count == 4 && !isLandscape(canvas)) || isFourZoomed(canvas)) {
            if (drawing) drawnDividers.remove(canvas);
            return List.of();
        }
        if (isZoomAnimating(canvas) || mainPaneAnimations.containsKey(canvas) || pendingMainSwaps.containsKey(canvas)) {
            return drawing ? drawnDividers.getOrDefault(canvas, List.of()) : List.of();
        }
        WindowInsets insets = canvas.getRootWindowInsets();
        if (!drawing && insets != null && insets.isVisible(WindowInsets.Type.ime())) return List.of();
        Rect[] panes = new Rect[count];
        for (int index = 0; index < panes.length; index++) {
            panes[index] = paneWindowFrame(canvas, index);
            if (panes[index] == null) return drawing ? drawnDividers.getOrDefault(canvas, List.of()) : List.of();
        }
        float density = canvas.getResources().getDisplayMetrics().density;
        boolean stacked = count == 2 && twoPanesStacked(canvas.getContext());
        boolean resizableHeight = count == 2 && !stacked && !isLandscape(canvas);
        int main = count == 3 || resizableHeight ? mainPane(canvas, adapter) : -1;
        boolean threeStacked = count == 3 && layoutMode(adapter) == THREE_STACKED_MODE;
        List<PaneEdge> edges = splitDividers(panes, main, stacked, threeStacked, density);
        if (resizableHeight) addPaneHeightEdges(canvas, edges, panes, main, density);
        if (drawing) drawnDividers.put(canvas, edges);
        return edges;
    }

    private static void addPaneHeightEdges(View canvas, List<PaneEdge> edges, Rect[] panes,
                                           int main, float density) {
        if (main < 0) return;
        WindowInsets insets = canvas.getRootWindowInsets();
        Insets reserved = insets == null ? Insets.NONE : insets.getInsets(
                WindowInsets.Type.mandatorySystemGestures() | WindowInsets.Type.systemBars());
        Rect window = new Rect();
        XposedHelpers.callMethod(canvas.getRootView(), "getBoundsOnScreen", window);
        int safeTop = window.top + reserved.top;
        int safeBottom = window.bottom - reserved.bottom;
        int radius = Math.round(8 * density);
        if (safeBottom - safeTop < 2 * radius) return;
        for (int index = 0; index < panes.length; index++) {
            Rect pane = panes[index];
            int paneEdge = index == main ? MAIN_HEIGHT_EDGE : SECONDARY_HEIGHT_EDGE;
            addPaneHeightEdge(edges, pane, paneEdge, pane.top, Math.max(pane.top, safeTop + radius),
                    -1, radius, density);
            addPaneHeightEdge(edges, pane, paneEdge, pane.bottom, Math.min(pane.bottom, safeBottom - radius),
                    1, radius, density);
        }
    }

    private static void addPaneHeightEdge(List<PaneEdge> edges, Rect pane, int index, int edgeY, int handleY,
                                          int heightDirection, int radius, float density) {
        if (handleY < pane.top || handleY > pane.bottom) return;
        int corner = Math.min(radius, pane.width() / 4);
        int halfWidth = (pane.width() - 2 * corner) / 2;
        // Give an inset handle a smaller touch target so adjacent app controls still receive taps.
        if (handleY != edgeY) halfWidth = Math.min(halfWidth, Math.round(24 * density));
        int innerDepth = handleY != edgeY ? radius : radius / 2;
        int top = handleY - (heightDirection < 0 ? radius : innerDepth);
        int bottom = handleY + (heightDirection < 0 ? innerDepth : radius);
        Rect hit = new Rect(pane.centerX() - halfWidth, top, pane.centerX() + halfWidth, bottom);
        edges.add(new PaneEdge(hit, index, true, heightDirection));
    }

    private static List<PaneEdge> splitDividers(Rect[] panes, int main, boolean stacked,
                                                boolean threeStacked, float density) {
        int count = panes.length;
        int radius = Math.round(8 * density);
        List<PaneEdge> edges = new ArrayList<>(3);
        if (count == 2) {
            addTwoPaneDivider(edges, panes, stacked, radius, density);
        }
        if (threeStacked) {
            if (main < 0) return List.of();
            Rect[] upper = new Rect[2];
            for (int index = 0, next = 0; index < panes.length; index++) {
                if (index != main) upper[next++] = panes[index];
            }
            Rect topRow = new Rect(upper[0]);
            topRow.union(upper[1]);
            addPaneDivider(edges, topRow, panes[main], true, radius, density, PRIMARY_DIVIDER);
            if (upper[0].left > upper[1].left) {
                Rect left = upper[1];
                upper[1] = upper[0];
                upper[0] = left;
            }
            addPaneDivider(edges, upper[0], upper[1], false, radius, density, UPPER_COLUMNS_DIVIDER);
        }
        if (count == 3 && !threeStacked) {
            if (main < 0) return List.of();
            Rect secondary = new Rect();
            for (int index = 0; index < panes.length; index++) {
                if (index != main) secondary.union(panes[index]);
            }
            addTwoPaneDivider(edges, new Rect[]{secondary, panes[main]}, false, radius, density);
        }
        if (count == 4) {
            Rect[] columns = panes.clone();
            java.util.Arrays.sort(columns, java.util.Comparator.comparingInt(rect -> rect.left));
            for (int index = 0; index < columns.length - 1; index++) {
                addPaneDivider(edges, columns[index], columns[index + 1], false, radius, density, index);
            }
        }
        return edges;
    }

    private static void drawPaneControls(View view, android.graphics.Canvas drawing) {
        PaneResizeCover cover = paneResizeCover(view);
        if (cover != null) cover.updateIfNeeded();
        List<PaneEdge> edges = paneEdges(view, true);
        if (cover == null && edges.isEmpty()) return;
        int[] location = new int[2];
        view.getLocationOnScreen(location);
        float scale = (Float) XposedHelpers.callMethod(view, "getParentScaleX");
        if (scale <= 0f) return;
        float density = view.getResources().getDisplayMetrics().density;
        drawing.save();
        drawing.scale(1f / scale, 1f / scale);
        drawing.translate(-location[0], -location[1]);
        if (cover != null) cover.draw(drawing);
        PaneEdgeDrag drag = paneEdgeDrags.get(view);
        for (PaneEdge edge : edges) {
            Rect hit = edge.hit();
            float x = hit.exactCenterX();
            float y = hit.exactCenterY();
            float halfLength = (edge.heightEdge() ? 14f : 22f) * density;
            float halfThickness = 2f * density;
            float border = 0.6f * density;
            float halfWidth = edge.horizontal() ? Math.min(hit.width() / 2f, halfLength) : halfThickness;
            float halfHeight = edge.horizontal() ? halfThickness : Math.min(hit.height() / 2f, halfLength);
            if (halfWidth <= border || halfHeight <= border) continue;
            boolean closing = drag != null && !drag.closeTasks.isEmpty() && drag.edge.index() == edge.index();
            DIVIDER_PAINT.setColor(closing ? 0xffa34139 : 0xff48799f);
            drawing.drawRoundRect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight,
                    halfThickness, halfThickness, DIVIDER_PAINT);
            DIVIDER_PAINT.setColor(closing ? 0xffff9991 : DIVIDER_BLUE);
            float innerRadius = halfThickness - border;
            drawing.drawRoundRect(x - halfWidth + border, y - halfHeight + border,
                    x + halfWidth - border, y + halfHeight - border,
                    innerRadius, innerRadius, DIVIDER_PAINT);
        }
        drawing.restore();
    }

    private static void addTwoPaneDivider(List<PaneEdge> edges, Rect[] panes, boolean stacked,
                                         int radius, float density) {
        boolean firstInOrder = stacked ? panes[0].top < panes[1].top : panes[0].left < panes[1].left;
        Rect first = panes[firstInOrder ? 0 : 1];
        Rect second = panes[firstInOrder ? 1 : 0];
        addPaneDivider(edges, first, second, stacked, radius, density, PRIMARY_DIVIDER);
    }

    private static void addPaneDivider(List<PaneEdge> edges, Rect first, Rect second, boolean horizontal,
                                       int radius, float density, int index) {
        int start = horizontal ? Math.max(first.left, second.left) : Math.max(first.top, second.top);
        int end = horizontal ? Math.min(first.right, second.right) : Math.min(first.bottom, second.bottom);
        int middle = (start + end) / 2;
        int dividerRadius = Math.round(38 * density);
        if (start < end) {
            int low = Math.max(start, middle - dividerRadius);
            int high = Math.min(end, middle + dividerRadius);
            int seam = horizontal ? (first.bottom + second.top) / 2 : (first.right + second.left) / 2;
            Rect hit = horizontal ? new Rect(low, seam - radius, high, seam + radius)
                    : new Rect(seam - radius, low, seam + radius, high);
            edges.add(new PaneEdge(hit, index, horizontal));
        }
    }

    private static String maskManagerClass() {
        return CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("y1.n", "t1.n") : CanvasApi.PACKAGE + "mask.CanvasMaskManager";
    }

    private static final String MASK_LIST_VIEW = CanvasApi.PACKAGE + "mask.CanvasMaskListView";
    private static final String TRANSITION_MASK = "canvasplus.transitionMask";

    private static void hookCanvasTransitionMask(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            Class<?> maskList = XposedHelpers.findClass(MASK_LIST_VIEW, loader);
            XposedHelpers.findAndHookMethod(maskManagerClass(), loader,
                    CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("e0", "c0", "b0") : "f0", view, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            View canvas = (View) param.args[0];
                            Object list = XposedHelpers.getObjectField(param.thisObject, "d");
                            if (!(list instanceof android.view.ViewGroup masks)) return;
                            if (XposedHelpers.getAdditionalInstanceField(masks, TRANSITION_MASK)
                                    instanceof CanvasTransitionMask existing && existing.canvas == canvas) return;
                            CanvasTransitionMask mask = new CanvasTransitionMask(canvas, masks, loader);
                            XposedHelpers.setAdditionalInstanceField(canvas, TRANSITION_MASK, mask);
                            XposedHelpers.setAdditionalInstanceField(masks, TRANSITION_MASK, mask);
                        }
                    });
            XposedHelpers.findAndHookMethod(maskList, CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("m", "m", "k") : "n",
                    new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (XposedHelpers.getAdditionalInstanceField(param.thisObject, TRANSITION_MASK)
                            instanceof CanvasTransitionMask mask) mask.update();
                }
            });
        } catch (Throwable error) {
            log("canvas transition mask hook failed: " + error);
        }
    }

    private static void hookFullscreenCloseCover(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedHelpers.findAndHookMethod(view, TASK_TO_FULLSCREEN.value(), int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    View canvas = (View) param.thisObject;
                    Object adapter = CanvasApi.adapter(canvas);
                    int index = (Integer) param.args[0];
                    if (adapter == null || count(adapter) < 2 || index < 0 || index >= count(adapter)) return;
                    Object task = XposedHelpers.callMethod(adapter, "getItem", index);
                    if (deferWindowCountChange(canvas, ready -> {
                        Object current = CanvasApi.adapter(canvas);
                        int position = current == null ? -1 : taskPosition(current, task);
                        if (ready && position >= 0 && count(current) >= 2) {
                            XposedHelpers.callMethod(canvas, TASK_TO_FULLSCREEN.value(), position);
                        }
                    })) {
                        param.setResult(null);
                    }
                }
            });
        } catch (RuntimeException | LinkageError error) {
            log("fullscreen close cover hook failed: " + error);
        }
    }

    private static final long THREE_TO_TWO_MOTION_MS = 240;
    private static final long THREE_TO_TWO_FADE_MS = 120;
    private static final long THREE_TO_TWO_DRAW_WAIT_MS = 550;
    private static final long THREE_TO_TWO_SURFACE_HANDOFF_MS = 250;

    private static void hookThreeToTwoCloseAnimation(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            boolean colorOs16 = CanvasApi.hasColorOs16CanvasUpdate();
            Class<?> manager = XposedHelpers.findClass(maskManagerClass(), loader);
            Class<?> maskList = XposedHelpers.findClass(MASK_LIST_VIEW, loader);
            var removeWindow = XposedHelpers.findMethodExact(view, colorOs16 ? colorOs16Revision("W1", "T1", "I1") : "d2", int.class);
            var windowMotion = XposedHelpers.findMethodExact(view, colorOs16 ? colorOs16Revision("A1", "z1", "o1") : "H1", int.class, List.class, List.class);
            var maskMotion = XposedHelpers.findMethodExact(manager, colorOs16Revision("w", "v"), boolean.class, Rect.class,
                    Rect.class, int.class, List.class, int.class);
            var hideMasks = CanvasApi.isRevisedColorOs16()
                    ? XposedHelpers.findMethodExact(maskList, colorOs16Revision("p", "p", "n"), long.class, long.class,
                            boolean.class, Animator.AnimatorListener.class, int.class)
                    : XposedHelpers.findMethodExact(maskList, colorOs16 ? "q" : "r", long.class, long.class,
                            boolean.class, Animator.AnimatorListener.class, int.class, long.class);
            var maskFade = XposedHelpers.findField(maskList, colorOs16Revision("p", "l"));

            ThreadLocal<Boolean> closingThreeToTwo = new ThreadLocal<>();
            XC_MethodHook shortenMotion = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(closingThreeToTwo.get())
                            || !(param.getResult() instanceof List<?> motions)) return;
                    for (Object motion : motions) {
                        if (motion instanceof Animator animator && animator.getDuration() > THREE_TO_TWO_MOTION_MS) {
                            animator.setDuration(THREE_TO_TWO_MOTION_MS);
                        }
                    }
                }
            };
            XposedBridge.hookMethod(windowMotion, shortenMotion);
            XposedBridge.hookMethod(maskMotion, shortenMotion);
            XposedBridge.hookMethod(hideMasks, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!Boolean.TRUE.equals(closingThreeToTwo.get())) return;
                    param.args[0] = Math.min((Long) param.args[0], THREE_TO_TWO_FADE_MS);
                    param.args[1] = Math.min((Long) param.args[1], THREE_TO_TWO_FADE_MS);
                    if (param.args.length > 5) param.args[5] = 0L;
                }

                @Override protected void afterHookedMethod(MethodHookParam param) throws IllegalAccessException {
                    if (!Boolean.TRUE.equals(closingThreeToTwo.get()) || param.hasThrowable()
                            || !(maskFade.get(param.thisObject) instanceof AnimatorSet fade)) return;
                    // Keep the native fade after the window motion without its extra 500 ms pause.
                    for (Animator child : fade.getChildAnimations()) {
                        child.setStartDelay(Math.min(child.getStartDelay(), THREE_TO_TWO_MOTION_MS));
                    }
                }
            });
            XposedBridge.hookMethod(removeWindow, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    param.setObjectExtra("canvasplus.closingThreeToTwo", closingThreeToTwo.get());
                    boolean closing = count(CanvasApi.adapter(param.thisObject)) == 3;
                    closingThreeToTwo.set(closing);
                    if (closing && XposedHelpers.getAdditionalInstanceField(param.thisObject, TRANSITION_MASK)
                            instanceof CanvasTransitionMask mask) {
                        mask.waitForThreeToTwoDraw();
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Boolean previous = (Boolean) param.getObjectExtra("canvasplus.closingThreeToTwo");
                    if (previous == null) closingThreeToTwo.remove();
                    else closingThreeToTwo.set(previous);
                }
            });
        } catch (Throwable error) {
            log("three-to-two close animation hook failed: " + error);
        }
    }

    private static void coverWindowCountChange(View canvas) {
        if (XposedHelpers.getAdditionalInstanceField(canvas, TRANSITION_MASK) instanceof CanvasTransitionMask mask) {
            mask.coverLayoutChange();
            PaneResizeCover resize = paneResizeCover(canvas);
            if (resize != null && resize.isClosing() && mask.parent != null && mask.getAlpha() == 1f) {
                resize.remove();
            }
            cancelMainPaneAnimation(canvas);
        }
    }

    static boolean deferWindowCountChange(View canvas, java.util.function.Consumer<Boolean> action) {
        return canvas != null && XposedHelpers.getAdditionalInstanceField(canvas, TRANSITION_MASK)
                instanceof CanvasTransitionMask mask && mask.deferLayoutChange(action);
    }

    private static boolean isCanvasMaskVisible(View canvas) {
        return XposedHelpers.getAdditionalInstanceField(canvas, TRANSITION_MASK)
                instanceof CanvasTransitionMask mask && (mask.parent != null || mask.hasNativeMask());
    }

    private static Drawable loadTaskIcon(Context context, Object task, Class<?> iconLoader) {
        try {
            if (task != null && CanvasApi.hasColorOs16CanvasUpdate()) {
                Object icon = XposedHelpers.callMethod(task, "e");
                if (icon instanceof Drawable drawable) return drawable;
            }
            Intent intent = task != null && iconLoader != null
                    ? (Intent) XposedHelpers.callMethod(task, "f") : null;
            if (intent != null) {
                int user = (Integer) XposedHelpers.callMethod(task, TASK_CONTENT_USER.value());
                Drawable icon = (Drawable) XposedHelpers.callStaticMethod(iconLoader, "Z", context, intent, user);
                if (icon != null) return icon;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // A task without a launch activity can still be covered.
        }
        return context.getPackageManager().getDefaultActivityIcon();
    }

    private static int canvasMaskColor(View view) {
        boolean night = (view.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        return night ? 0xff24272d : 0xfffafbfc;
    }

    private static final long COUNT_MASK_ENTER_MS = 140;
    private static final long COUNT_MASK_WAIT_LIMIT_MS = 220;
    private static final long COLOR_OS_16_COUNT_MASK_WAIT_LIMIT_MS = 720;
    private static final long COLOR_OS_16_SURFACE_HANDOFF_MS = 48;
    private static final long RESIZE_MASK_WAIT_LIMIT_MS = 115;

    private static void hookTaskWindowDrawn(ClassLoader loader) {
        try {
            XposedHelpers.findAndHookMethod(CanvasApi.ACTIVITY + "$EmbeddedWindowCallback", loader,
                    "notifyWindowStateHasDrawn", ActivityManager.RunningTaskInfo.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            if (!(param.args[0] instanceof ActivityManager.RunningTaskInfo info)) return;
                            Object activity = XposedHelpers.getObjectField(param.thisObject, "this$0");
                            View canvas = (View) XposedHelpers.callMethod(activity, ACTIVITY_VIEW.value());
                            if (canvas == null) return;
                            Configuration configuration = (Configuration) XposedHelpers.getObjectField(info, "configuration");
                            Object window = XposedHelpers.getObjectField(configuration, "windowConfiguration");
                            Rect bounds = (Rect) XposedHelpers.callMethod(window, "getBounds");
                            if (bounds == null || bounds.isEmpty()) return;
                            int taskId = info.taskId;
                            long drawnAt = SystemClock.uptimeMillis();
                            var size = new TaskFrameReadiness.Size(bounds.width(), bounds.height());
                            canvas.post(() -> {
                                PaneResizeCover resize = paneResizeCover(canvas);
                                if (resize != null && resize.drawWait != null) resize.drawWait.report(taskId, size, drawnAt);
                                if (XposedHelpers.getAdditionalInstanceField(canvas, TRANSITION_MASK)
                                        instanceof CanvasTransitionMask mask && mask.drawWait != null) {
                                    mask.drawWait.report(taskId, size, drawnAt);
                                }
                            });
                        }
                    });
        } catch (RuntimeException | LinkageError error) {
            log("embedded window draw callback unavailable: " + error);
        }
    }

    private static Map<Integer, TaskFrameReadiness.Size> canvasTaskSizes(View canvas) {
        Object adapter = CanvasApi.adapter(canvas);
        int total = adapter == null ? 0 : count(adapter);
        if (total < 1 || total > 4) return null;
        List<Rect> targets = total == 1 ? List.of() : taskTargets(canvas, adapter, total);
        Map<Integer, TaskFrameReadiness.Size> sizes = new HashMap<>(total);
        for (int index = 0; index < total; index++) {
            Object task = XposedHelpers.callMethod(adapter, "getItem", index);
            if (task == null) return null;
            int id = (Integer) XposedHelpers.callMethod(task, TASK_ID_GETTER.value());
            if (id <= 0) return null;
            if (total == 1) {
                sizes.put(id, TaskFrameReadiness.Size.ANY);
            } else {
                Rect size = targets.get(index);
                sizes.put(id, new TaskFrameReadiness.Size(size.width(), size.height()));
            }
        }
        return sizes;
    }

    private static Map<Integer, TaskFrameReadiness.Size> changedTaskSizes(
            Map<Integer, TaskFrameReadiness.Size> before, Map<Integer, TaskFrameReadiness.Size> after) {
        Map<Integer, TaskFrameReadiness.Size> changed = new HashMap<>(after);
        if (before != null) changed.entrySet().removeIf(task -> task.getValue().equals(before.get(task.getKey())));
        return changed;
    }

    private static final class CanvasDrawWait {
        private final View canvas;
        private final String operation;
        private final Supplier<Map<Integer, TaskFrameReadiness.Size>> resolveTasks;
        private final Runnable onReady;
        private final TaskFrameReadiness readiness = new TaskFrameReadiness();
        private final ViewTreeObserver observer;
        private final ViewTreeObserver.OnPreDrawListener preDraw = this::beforeDraw;
        private final Runnable committed = () -> {
            readiness.markCommitted();
            if (readiness.isReady()) finish("drawn");
        };
        private final Runnable timeout = () -> finish("timeout");
        private final long start = SystemClock.uptimeMillis();
        private boolean active = true;

        CanvasDrawWait(View canvas, String operation, long timeoutMs,
                       Supplier<Map<Integer, TaskFrameReadiness.Size>> resolveTasks, Runnable onReady) {
            this.canvas = canvas;
            this.operation = operation;
            this.resolveTasks = resolveTasks;
            this.onReady = onReady;
            observer = canvas.getViewTreeObserver();
            observer.addOnPreDrawListener(preDraw);
            canvas.postDelayed(timeout, timeoutMs);
            canvas.postInvalidateOnAnimation();
        }

        private boolean beforeDraw() {
            Map<Integer, TaskFrameReadiness.Size> tasks = resolveTasks.get();
            if (tasks == null) return true;
            readiness.expect(tasks);
            observer.removeOnPreDrawListener(preDraw);
            if (canvas.isHardwareAccelerated()) observer.registerFrameCommitCallback(committed);
            else canvas.postOnAnimation(committed);
            return true;
        }

        void report(int taskId, TaskFrameReadiness.Size size, long drawnAt) {
            if (!active || drawnAt < start) return;
            readiness.report(taskId, size);
            if (readiness.isReady()) finish("drawn");
        }

        private void finish(String reason) {
            if (!active) return;
            cancel();
            log(operation + " mask " + reason + " elapsedMs=" + (SystemClock.uptimeMillis() - start));
            onReady.run();
        }

        void cancel() {
            active = false;
            canvas.removeCallbacks(timeout);
            canvas.removeCallbacks(committed);
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(preDraw);
                observer.unregisterFrameCommitCallback(committed);
            }
        }
    }

    private static final class CanvasTransitionMask extends View implements View.OnAttachStateChangeListener {
        private final View canvas;
        private final android.view.ViewGroup masks;
        private final Class<?> iconLoader;
        private final android.graphics.drawable.GradientDrawable card =
                new android.graphics.drawable.GradientDrawable();
        private final List<Drawable> icons = new ArrayList<>(4);
        private final Rect bounds = new Rect();
        private android.view.ViewGroup parent;
        private boolean showing;
        private boolean coveringLayoutChange;
        private boolean applyingLayoutChange;
        private CanvasDrawWait drawWait;
        private final List<java.util.function.Consumer<Boolean>> deferredLayoutChanges = new ArrayList<>(2);
        private final Runnable applyLayoutChanges = () -> finishDeferredChanges(true);
        private final Runnable finishLayoutCover = () -> {
            coveringLayoutChange = false;
            update();
        };

        CanvasTransitionMask(View canvas, android.view.ViewGroup masks, ClassLoader loader) {
            super(canvas.getContext());
            this.canvas = canvas;
            this.masks = masks;
            iconLoader = XposedHelpers.findClass(UTIL.value(), loader);
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            masks.addOnAttachStateChangeListener(this);
        }

        boolean hasNativeMask() {
            if (!masks.isShown()) return false;
            for (int index = 0; index < masks.getChildCount(); index++) {
                View mask = masks.getChildAt(index);
                if (mask.getVisibility() == View.VISIBLE && mask.getAlpha() > 0f) return true;
            }
            return false;
        }

        boolean deferLayoutChange(java.util.function.Consumer<Boolean> action) {
            if (applyingLayoutChange || !canvas.isAttachedToWindow() || !canvas.isShown()) return false;
            coveringLayoutChange = true;
            setShowing(true);
            if (parent == null) {
                coveringLayoutChange = false;
                return false;
            }
            cancelDrawWait();
            deferredLayoutChanges.add(action);
            if (deferredLayoutChanges.size() == 1) {
                animate().cancel();
                animate().alpha(1f).setDuration(COUNT_MASK_ENTER_MS)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator())
                        .withEndAction(() -> canvas.postOnAnimation(applyLayoutChanges)).start();
            }
            return true;
        }

        private void finishDeferredChanges(boolean ready) {
            if (deferredLayoutChanges.isEmpty()) return;
            var changes = new ArrayList<>(deferredLayoutChanges);
            deferredLayoutChanges.clear();
            if (ready && parent != null) waitForWindowCountChange();
            applyingLayoutChange = true;
            try {
                for (var action : changes) {
                    try {
                        action.accept(ready && canvas.isAttachedToWindow() && canvas.isShown());
                    } catch (Throwable error) {
                        log("canvas layout change failed: " + error);
                    }
                }
            } finally {
                applyingLayoutChange = false;
            }
            if (ready && canvas.isAttachedToWindow() && parent != null) coverLayoutChange();
        }

        void coverLayoutChange() {
            if (!deferredLayoutChanges.isEmpty() && !applyingLayoutChange) return;
            coveringLayoutChange = true;
            setShowing(true);
            if (parent == null) {
                coveringLayoutChange = false;
                return;
            }
            // Place the cover before the native task change can move any surface.
            animate().cancel();
            setAlpha(1f);
            if (drawWait == null) waitForWindowCountChange();
        }

        private void waitForWindowCountChange() {
            if (CanvasApi.hasColorOs16CanvasUpdate()) {
                waitForWindowCountChange(COLOR_OS_16_COUNT_MASK_WAIT_LIMIT_MS, COLOR_OS_16_SURFACE_HANDOFF_MS);
            } else {
                waitForWindowCountChange(COUNT_MASK_WAIT_LIMIT_MS, 0);
            }
        }

        void waitForThreeToTwoDraw() {
            if (!coveringLayoutChange || parent == null) return;
            cancelDrawWait();
            waitForWindowCountChange(THREE_TO_TWO_DRAW_WAIT_MS, THREE_TO_TWO_SURFACE_HANDOFF_MS);
        }

        private void waitForWindowCountChange(long timeoutMs, long surfaceHandoffMs) {
            canvas.removeCallbacks(finishLayoutCover);
            Map<Integer, TaskFrameReadiness.Size> before = canvasTaskSizes(canvas);
            drawWait = new CanvasDrawWait(canvas, "window count", timeoutMs, () -> {
                if (canvas.isLayoutRequested()) return null;
                Map<Integer, TaskFrameReadiness.Size> after = canvasTaskSizes(canvas);
                if (after == null || (before != null && before.keySet().equals(after.keySet()))) return null;
                Object frames = XposedHelpers.callMethod(canvas, "getRectList");
                if (after.size() > 1 && (!(frames instanceof List<?> rects) || rects.size() != after.size())) {
                    return null;
                }
                return changedTaskSizes(before, after);
            }, () -> {
                drawWait = null;
                // Embedded windows can report a frame before the compositor shows the new split.
                if (surfaceHandoffMs > 0) canvas.postDelayed(finishLayoutCover, surfaceHandoffMs);
                else finishLayoutCover.run();
            });
        }

        private void cancelDrawWait() {
            canvas.removeCallbacks(finishLayoutCover);
            if (drawWait == null) return;
            drawWait.cancel();
            drawWait = null;
        }

        boolean update() {
            boolean nativeMask = hasNativeMask();
            setShowing(coveringLayoutChange || nativeMask);
            if (parent != null) layout(0, 0, parent.getWidth(), parent.getHeight());
            if (nativeMask) masks.postInvalidateOnAnimation();
            return parent != null && getAlpha() >= 1f;
        }

        void setShowing(boolean visible) {
            if (visible == showing) return;
            if (visible && (!(canvas.getRootView() instanceof android.view.ViewGroup root)
                    || !root.isAttachedToWindow() || root.getWidth() == 0 || root.getHeight() == 0)) return;
            showing = visible;
            animate().cancel();
            if (visible) {
                if (parent == null) {
                    parent = (android.view.ViewGroup) canvas.getRootView();
                    loadIcons();
                    layout(0, 0, parent.getWidth(), parent.getHeight());
                    setAlpha(0f);
                    parent.getOverlay().add(this);
                    refreshPaneDividers(canvas);
                    log("canvas transition card shown icons=" + icons.size());
                }
                animate().alpha(1f).setDuration(COUNT_MASK_ENTER_MS)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
            } else {
                animate().alpha(0f).setDuration(100)
                        .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                        .withEndAction(() -> {
                            if (!showing) removeOverlay();
                        }).start();
            }
        }

        private void removeOverlay() {
            if (parent == null) return;
            parent.getOverlay().remove(this);
            parent = null;
            icons.clear();
            refreshPaneDividers(canvas);
        }

        @Override public void onViewAttachedToWindow(View view) {}

        @Override public void onViewDetachedFromWindow(View view) {
            cancelDrawWait();
            canvas.removeCallbacks(applyLayoutChanges);
            finishDeferredChanges(false);
            coveringLayoutChange = false;
            showing = false;
            animate().cancel();
            removeOverlay();
        }

        @Override protected void onDraw(android.graphics.Canvas drawing) {
            bounds.set(0, 0, getWidth(), getHeight());
            card.setBounds(bounds);
            card.setColor(canvasMaskColor(this));
            card.draw(drawing);
            drawIcons(drawing, getResources().getDisplayMetrics().density);
        }

        private void loadIcons() {
            Object adapter = CanvasApi.adapter(canvas);
            int total = Math.min(4, count(adapter));
            Context context = canvas.getContext();
            for (int index = 0; index < total; index++) {
                Object task = XposedHelpers.callMethod(adapter, "getItem", index);
                if (task == null) continue;
                Drawable icon = loadTaskIcon(context, task, iconLoader);
                if (icon != null) icons.add(icon);
            }
        }

        private void drawIcons(android.graphics.Canvas drawing, float density) {
            int size = icons.size();
            boolean twoRows = size >= 3;
            float diameter = 44f * density;
            float spacing = diameter + 12f * density;
            for (int index = 0; index < size; index++) {
                int row = !twoRows || index < size - 2 ? 0 : 1;
                int rowStart = row == 0 ? 0 : size - 2;
                int columns = !twoRows ? size : row == 0 ? size - 2 : 2;
                float x = bounds.exactCenterX() + (index - rowStart - (columns - 1) / 2f) * spacing;
                float y = bounds.exactCenterY() + (twoRows ? (row - 0.5f) * spacing : 0f);
                android.graphics.drawable.Drawable icon = icons.get(index);
                icon.setBounds(Math.round(x - diameter / 2f), Math.round(y - diameter / 2f),
                        Math.round(x + diameter / 2f), Math.round(y + diameter / 2f));
                icon.draw(drawing);
            }
        }
    }

    private static void hookPaneResizeTouch(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(android.view.ViewGroup.class, "dispatchDraw",
                android.graphics.Canvas.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (MASK_LIST_VIEW.equals(param.thisObject.getClass().getName())
                                && XposedHelpers.getAdditionalInstanceField(param.thisObject, TRANSITION_MASK)
                                instanceof CanvasTransitionMask mask) {
                            boolean opaque = mask.update();
                            // Keep the native cover until our overlay fully covers the task surfaces.
                            if (!CanvasApi.hasColorOs16CanvasUpdate() || opaque) param.setResult(null);
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (CanvasApi.VIEW.equals(param.thisObject.getClass().getName())) {
                            drawPaneControls((View) param.thisObject, (android.graphics.Canvas) param.args[0]);
                        }
                    }
                });
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

        Class<?> gestures = XposedHelpers.findClass(GESTURES.value(), loader);
        XposedHelpers.findAndHookMethod(gestures, "T", MotionEvent.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                View canvas = (View) XposedHelpers.getObjectField(param.thisObject, GESTURE_CANVAS.value());
                MotionEvent event = (MotionEvent) param.args[0];
                if (canvas == null || !resizeFromPaneEdge(canvas, event, true)) return;
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // 蓝条手势由系统画布的输入监听器独占，后续事件会继续走此入口。
                    Object host = XposedHelpers.getObjectField(param.thisObject, "b");
                    XposedHelpers.callMethod(host, CLAIM_INPUT.value());
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

    private static final String PANE_RESIZE_COVER = "canvasplus.paneResizeCover";

    private static PaneResizeCover paneResizeCover(View canvas) {
        return (PaneResizeCover) XposedHelpers.getAdditionalInstanceField(canvas, PANE_RESIZE_COVER);
    }

    private static final class PaneResizeCover implements View.OnAttachStateChangeListener {
        private record CoveredPane(int index, View pane, View task, Drawable icon,
                                   float paneAlpha, float taskAlpha) {
            void setHidden(boolean hidden) {
                pane.setAlpha(hidden ? 0f : paneAlpha);
                if (task != null) task.setAlpha(hidden ? 0f : taskAlpha);
            }
        }

        private final View canvas;
        private final List<CoveredPane> panes;
        private final PaneEdgeDrag drag;
        private final Rect[] initialFrames;
        private final Rect[] frames;
        private final Rect[] initialProjection;
        private final int[] slots;
        private final int main;
        private final float density;
        private final Rect frame = new Rect();
        private final Paint fill = new Paint();
        private List<PaneEdge> dividers;
        private boolean previewDirty;
        private boolean previewing = true;
        private boolean committingResize;
        private int closingPanes;
        private boolean closeTowardStart;
        private float closeProgress;
        private ValueAnimator closeAnimator;
        private final Runnable revealAfterCloseTimeout = () -> reveal(null);
        private CanvasDrawWait drawWait;
        private ValueAnimator fade;

        private PaneResizeCover(View canvas, List<CoveredPane> panes, PaneEdgeDrag drag,
                                Rect[] initialFrames, List<Rect> initialLayout, int[] slots, int main) {
            this.canvas = canvas;
            this.panes = panes;
            this.drag = drag;
            this.initialFrames = initialFrames;
            this.slots = slots;
            this.main = main;
            initialProjection = project(initialLayout, drag.display);
            density = canvas.getResources().getDisplayMetrics().density;
            frames = new Rect[initialFrames.length];
            for (int index = 0; index < frames.length; index++) frames[index] = new Rect(initialFrames[index]);
            updateDividers();
            fill.setColor(canvasMaskColor(canvas));
        }

        static void show(View canvas, Object adapter, PaneEdgeDrag drag) {
            PaneResizeCover previous = paneResizeCover(canvas);
            if (previous != null) previous.remove();

            int[] slots = panePositions(canvas, adapter);
            List<Rect> initialLayout = basePanes(drag.count, drag.mode, drag.startColumnShare, drag.display);
            if (slots == null || initialLayout.size() != drag.count) return;

            Rect[] initialFrames = new Rect[drag.count];
            List<CoveredPane> panes = new ArrayList<>(drag.count);
            int main = drag.count == 4 ? -1 : mainPane(canvas, adapter);
            int singlePane = drag.edge.heightEdge() ? (drag.resizesHeight(true) ? main : 1 - main) : -1;
            boolean adjacentOnly = drag.count == 4 || drag.edge.index() == UPPER_COLUMNS_DIVIDER;
            Class<?> icons = XposedHelpers.findClassIfExists(UTIL.value(), canvas.getClass().getClassLoader());
            for (int index = 0; index < drag.count; index++) {
                View pane = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
                Rect initial = new Rect();
                if (!readPaneWindowFrame(pane, initial)) return;
                initialFrames[index] = initial;
                if (singlePane >= 0 && index != singlePane) continue;
                Object task = XposedHelpers.callMethod(adapter, "getItem", index);
                if (adjacentOnly && !drag.startTasks.contains(task) && !drag.endTasks.contains(task)) continue;
                View taskView = (View) XposedHelpers.callMethod(pane, "getFlexibleTaskView");
                Drawable icon = loadTaskIcon(canvas.getContext(), task, icons);
                panes.add(new CoveredPane(index, pane, taskView, icon == null ? null : icon.mutate(),
                        pane.getAlpha(), taskView == null ? 1f : taskView.getAlpha()));
            }
            if (panes.isEmpty()) return;
            PaneResizeCover cover = new PaneResizeCover(canvas, panes, drag, initialFrames, initialLayout, slots, main);
            XposedHelpers.setAdditionalInstanceField(canvas, PANE_RESIZE_COVER, cover);
            canvas.addOnAttachStateChangeListener(cover);
            for (CoveredPane pane : panes) pane.setHidden(true);
            canvas.postInvalidateOnAnimation();
        }

        boolean previewing() {
            return previewing;
        }

        boolean isClosing() {
            return closingPanes != 0;
        }

        boolean hasThreePaneResizeFeedback(Rect requested) {
            return committingResize && drag.count == 3 && drag.mode == THREE_STACKED_MODE
                    && drag.edge.horizontal() && requested.width() == drag.display.portraitTask().width();
        }

        void animateClose(int panes, boolean towardStart) {
            closingPanes = panes;
            closeTowardStart = towardStart;
            closeAnimator = ValueAnimator.ofFloat(0f, 1f);
            closeAnimator.setDuration(COUNT_MASK_ENTER_MS);
            closeAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            closeAnimator.addUpdateListener(animation -> {
                closeProgress = (Float) animation.getAnimatedValue();
                canvas.postInvalidateOnAnimation();
            });
            closeAnimator.start();
            // The count transition normally takes over as soon as its cover is opaque.
            canvas.postDelayed(revealAfterCloseTimeout, 600);
        }

        void invalidatePreview() {
            previewDirty = true;
            canvas.postInvalidateOnAnimation();
        }

        void updateIfNeeded() {
            if (!previewing || !previewDirty) return;
            previewDirty = false;
            CanvasDisplay display = drag.previewDisplay();
            float columnShare = drag.edge.horizontal() || drag.count == 4 ? drag.startColumnShare : drag.share;
            List<Rect> layout = basePanes(drag.count, drag.mode, columnShare, display);
            if (layout.size() != frames.length) return;

            // Project the same geometry used for the final layout. Anchor the delta to the
            // current on-screen frames so rounding and framework offsets do not move the drag origin.
            Rect[] after = project(layout, display);
            for (int index = 0; index < frames.length; index++) {
                Rect initial = initialFrames[index];
                Rect start = initialProjection[slots[index]];
                Rect next = after[slots[index]];
                frames[index].set(initial.left + next.left - start.left, initial.top + next.top - start.top,
                        initial.right + next.right - start.right, initial.bottom + next.bottom - start.bottom);
            }
            updateDividers();
        }

        private Rect[] project(List<Rect> layout, CanvasDisplay display) {
            Rect union = new Rect();
            for (Rect rect : layout) union.union(rect);
            if (drag.count == 2 && !display.landscape() && !display.stacked()) {
                union.top = 0;
                union.bottom = display.availableHeight();
            }
            Rect area = display.area();
            boolean alignTop = drag.count == 3 && drag.mode == THREE_STACKED_MODE;
            float top = area.top + (alignTop ? 0 : display.margin());
            float height = area.bottom - top - (alignTop ? threeStackedBottomMargin() : display.margin());
            float scale = overviewScale(area.width(), union, height);
            float x = area.exactCenterX() - union.exactCenterX() * scale;
            float y = alignTop ? top - union.top * scale : top + height / 2f - union.exactCenterY() * scale;
            Rect[] projected = new Rect[layout.size()];
            for (int index = 0; index < projected.length; index++) {
                Rect rect = layout.get(index);
                projected[index] = new Rect(Math.round(x + rect.left * scale), Math.round(y + rect.top * scale),
                        Math.round(x + rect.right * scale), Math.round(y + rect.bottom * scale));
            }
            return projected;
        }

        private void updateDividers() {
            dividers = splitDividers(frames, main, drag.display.stacked(),
                    drag.count == 3 && drag.mode == THREE_STACKED_MODE, density);
            if (drag.count == 2 && !drag.display.stacked() && !drag.display.landscape()) {
                addPaneHeightEdges(canvas, dividers, frames, main, density);
            }
        }

        void draw(android.graphics.Canvas drawing) {
            for (CoveredPane pane : panes) {
                if (previewing) frame.set(frames[pane.index()]);
                else if (!readPaneWindowFrame(pane.pane(), frame)) continue;
                boolean closing = (closingPanes & (1 << pane.index())) != 0;
                if (closing) {
                    if (drag.edge.horizontal()) {
                        int distance = Math.round(frame.height() * closeProgress);
                        if (closeTowardStart) frame.bottom -= distance;
                        else frame.top += distance;
                    } else {
                        int distance = Math.round(frame.width() * closeProgress);
                        if (closeTowardStart) frame.right -= distance;
                        else frame.left += distance;
                    }
                }
                if (frame.isEmpty()) continue;
                drawing.drawRect(frame, fill);

                Drawable icon = pane.icon();
                if (icon == null) continue;
                int radius = Math.min(Math.round(22f * density), Math.min(frame.width(), frame.height()) / 4);
                int x = frame.centerX();
                int y = frame.centerY();
                if (closing) icon.setAlpha(Math.round(255 * (1f - closeProgress)));
                icon.setBounds(x - radius, y - radius, x + radius, y + radius);
                icon.draw(drawing);
            }
        }

        void reveal(Map<Integer, TaskFrameReadiness.Size> before) {
            if (drawWait != null || fade != null) return;
            drawWait = new CanvasDrawWait(canvas, "window resize", RESIZE_MASK_WAIT_LIMIT_MS, () -> {
                if (canvas.isLayoutRequested()) return null;
                if (before == null) {
                    showTasksUnderCover();
                    return Map.of();
                }
                Map<Integer, TaskFrameReadiness.Size> after = canvasTaskSizes(canvas);
                if (after == null) return null;
                showTasksUnderCover();
                return changedTaskSizes(before, after);
            }, () -> {
                drawWait = null;
                showTasksUnderCover();
                fadeOut();
            });
        }

        private void showTasksUnderCover() {
            if (!previewing) return;
            previewing = false;
            closingPanes = 0;
            for (CoveredPane pane : panes) pane.setHidden(false);
        }

        private void fadeOut() {
            fade = ValueAnimator.ofInt(255, 0);
            fade.setDuration(90);
            fade.addUpdateListener(animation -> {
                int alpha = (Integer) animation.getAnimatedValue();
                fill.setAlpha(alpha);
                for (CoveredPane pane : panes) {
                    if (pane.icon() != null) pane.icon().setAlpha(alpha);
                }
                canvas.postInvalidateOnAnimation();
            });
            fade.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    remove();
                }
            });
            fade.start();
        }

        void remove() {
            if (drawWait != null) drawWait.cancel();
            canvas.removeCallbacks(revealAfterCloseTimeout);
            if (closeAnimator != null) closeAnimator.cancel();
            XposedHelpers.removeAdditionalInstanceField(canvas, PANE_RESIZE_COVER);
            canvas.removeOnAttachStateChangeListener(this);
            showTasksUnderCover();
            if (fade != null) {
                fade.removeAllListeners();
                fade.cancel();
            }
            canvas.invalidate();
        }

        @Override public void onViewAttachedToWindow(View view) {}

        @Override public void onViewDetachedFromWindow(View view) {
            cancelPaneEdgeDrag(canvas);
        }
    }

    private static final class PaneEdgeDrag {
        final PaneEdge edge;
        final CanvasDisplay display;
        final int count;
        final int mode;
        final String layoutKey;
        final boolean dividerStream;
        final long downTime;
        final float startX;
        final float startY;
        final float startShare;
        final float startColumnShare;
        final float direction;
        final int dividerSpan;
        final float scale;
        final List<Object> startTasks;
        final List<Object> endTasks;
        List<Object> closeTasks = List.of();
        Runnable closeHold;
        boolean resizing;
        int heightDelta;
        float share;

        PaneEdgeDrag(View canvas, PaneEdge edge, MotionEvent event, boolean dividerStream) {
            this.edge = edge;
            this.dividerStream = dividerStream;
            downTime = event.getDownTime();
            display = canvasDisplay(canvas);
            Object adapter = CanvasApi.adapter(canvas);
            count = count(adapter);
            mode = layoutMode(adapter);
            startColumnShare = columnShare(canvas, count, mode);
            layoutKey = paneLayoutKey(count, mode, isLandscape(canvas), display.stacked());
            startX = event.getRawX();
            startY = event.getRawY();
            int[] pair = edge.heightEdge() ? new int[]{-1, -1} : panesAtDivider(canvas, adapter, edge, event);
            startTasks = count == 3 && mode == THREE_STACKED_MODE && edge.horizontal()
                    ? upperRowTasks(adapter, pair[1]) : dividerTask(adapter, pair[0]);
            endTasks = dividerTask(adapter, pair[1]);
            if (count == 4) {
                startShare = display.fourDividers()[edge.index()];
                dividerSpan = display.availableWidth() - 3 * columnGap();
            } else if (edge.horizontal()) {
                startShare = display.stackedShare();
                dividerSpan = (count == 3 ? display.threeStackedHeight() : display.availableHeight()) - columnGap();
            } else {
                startShare = startColumnShare;
                int gaps = count == 3 && display.landscape() ? 2 : 1;
                dividerSpan = display.paneLayoutWidth(count, mode) - gaps * columnGap();
            }
            int mainMode = display.landscape() ? DEFAULT_ORIENTATION : mode;
            direction = isMainPaneReversed(count, mainMode, display.preferredMainPaneMode()) ? -1f : 1f;
            share = startShare;
            scale = (Float) XposedHelpers.callMethod(canvas, "getParentScaleX");
        }

        private static List<Object> dividerTask(Object adapter, int index) {
            Object task = index < 0 ? null : XposedHelpers.callMethod(adapter, "getItem", index);
            return task == null ? List.of() : List.of(task);
        }

        private static List<Object> upperRowTasks(Object adapter, int bottomIndex) {
            if (bottomIndex < 0) return List.of();
            List<Object> tasks = new ArrayList<>(2);
            for (int index = 0; index < 3; index++) {
                if (index != bottomIndex) tasks.addAll(dividerTask(adapter, index));
            }
            return tasks.size() == 2 ? List.copyOf(tasks) : List.of();
        }

        CanvasDisplay previewDisplay() {
            float[] dividers = display.fourDividers();
            if (count == 4) {
                dividers = dividers.clone();
                dividers[edge.index()] = share;
            }
            return new CanvasDisplay(display.area(), display.portraitTask(), display.landscape(),
                    display.preferredMainPaneMode(),
                    resizesHeight(false) ? resizedHeight(false) : display.twoPaneSecondaryHeight(),
                    resizesHeight(true) ? resizedHeight(true) : display.twoPaneMainHeight(),
                    display.stacked(), edge.horizontal() && !edge.heightEdge() ? share : display.stackedShare(),
                    display.topWidth(), display.bottomWidth(), dividers);
        }

        boolean matches(View canvas, int currentCount, int mode, boolean stacked) {
            return count == currentCount
                    && layoutKey.equals(paneLayoutKey(currentCount, mode, isLandscape(canvas), stacked));
        }

        float distance(MotionEvent event) {
            return edge.horizontal() ? event.getRawY() - startY : event.getRawX() - startX;
        }

        boolean resizesHeight(boolean main) {
            return edge.index() == (main ? MAIN_HEIGHT_EDGE : SECONDARY_HEIGHT_EDGE);
        }

        int resizedHeight(boolean main) {
            int height = twoPaneHeightPercent(display, main) + heightDelta;
            return Math.max(MIN_TWO_PANE_HEIGHT, Math.min(100, height));
        }

        boolean hasSizeChange() {
            if (!edge.heightEdge()) return share != startShare;
            boolean main = resizesHeight(true);
            return resizedHeight(main) != twoPaneHeightPercent(display, main);
        }

        String preference() {
            if (count == 4) return layoutKey + "_divider_" + edge.index();
            return layoutKey + (edge.index() == UPPER_COLUMNS_DIVIDER ? "_upper_columns" : "_share");
        }
    }

    private static final long DIVIDER_CLOSE_HOLD_MS = 500;
    private static final java.util.WeakHashMap<View, Long> closedDividerGestures = new java.util.WeakHashMap<>();

    private static boolean resizeFromPaneEdge(View canvas, MotionEvent event, boolean dividerStream) {
        int action = event.getActionMasked();
        Long closedDownTime = closedDividerGestures.get(canvas);
        if (closedDownTime != null) {
            if (closedDownTime == event.getDownTime()) return true;
            closedDividerGestures.remove(canvas);
        }
        PaneEdgeDrag drag = paneEdgeDrags.get(canvas);
        if (action == MotionEvent.ACTION_DOWN) {
            if (drag != null && drag.downTime == event.getDownTime()) return drag.dividerStream == dividerStream;
            if (drag != null) cancelPaneEdgeDrag(canvas);
            if (CanvasApi.hasColorOs16CanvasUpdate() && isPaneMenuShowing(canvas)) return false;
            for (PaneEdge edge : resizeEdges(canvas)) {
                if (edge.hit().contains((int) event.getRawX(), (int) event.getRawY())
                        && (edge.heightEdge() || (edge.index() < 0) == dividerStream)) {
                    paneEdgeDrags.put(canvas, new PaneEdgeDrag(canvas, edge, event, dividerStream));
                    return true;
                }
            }
            return false;
        }
        if (drag == null || drag.dividerStream != dividerStream || drag.downTime != event.getDownTime()) {
            return false;
        }
        Object adapter = CanvasApi.adapter(canvas);
        int count = adapter == null ? 0 : count(adapter);
        boolean stacked = count == 2 && twoPanesStacked(canvas.getContext());
        if (count != drag.count || layoutLocked(canvas.getContext())
                || !drag.matches(canvas, count, layoutMode(adapter), stacked)) {
            cancelPaneEdgeDrag(canvas);
            requestPaneLayout(canvas);
            return true;
        }
        if (!drag.resizing) {
            int slop = android.view.ViewConfiguration.get(canvas.getContext()).getScaledTouchSlop();
            if (action == MotionEvent.ACTION_MOVE && Math.abs(drag.distance(event)) > slop) {
                drag.resizing = true;
            } else {
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                        || action == MotionEvent.ACTION_POINTER_DOWN) {
                    cancelPaneEdgeDrag(canvas);
                    if (!drag.edge.heightEdge() && action == MotionEvent.ACTION_UP
                            && edgeContainsTap(drag, event, slop)) {
                        showDividerMenu(canvas, drag.edge, event);
                    }
                }
                return true;
            }
        }
        PaneResizeCover cover = paneResizeCover(canvas);
        if (cover != null && cover.drag != drag) cover = null;
        if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
            boolean changed = updatePaneEdgeSize(canvas, drag, event);
            // Dragging past a resize limit can still arm a close gesture without resizing a pane.
            if (changed && action == MotionEvent.ACTION_MOVE && cover == null && drag.hasSizeChange()) {
                PaneResizeCover.show(canvas, adapter, drag);
                cover = paneResizeCover(canvas);
            }
            if (changed && cover != null) cover.invalidatePreview();
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_POINTER_DOWN) {
            stopPaneEdgeDrag(canvas);
            Map<Integer, TaskFrameReadiness.Size> before = null;
            if (action == MotionEvent.ACTION_UP && drag.hasSizeChange()) {
                if (cover != null) {
                    cover.committingResize = true;
                    before = canvasTaskSizes(canvas);
                }
                savePaneResize(canvas, drag);
                requestPaneLayout(canvas);
            }
            if (cover != null) cover.reveal(before);
        }
        return true;
    }

    private static void updateDividerCloseTarget(View canvas, PaneEdgeDrag drag, MotionEvent event,
                                                 float requestedShare, float limit) {
        float overshoot = Math.abs(requestedShare - limit) * drag.dividerSpan * drag.scale;
        float slop = 12f * canvas.getResources().getDisplayMetrics().density;
        float distance = drag.distance(event);
        boolean canCloseAbove = drag.display.stacked()
                || (drag.count == 3 && drag.mode == THREE_STACKED_MODE);
        List<Object> target = List.of();
        if (overshoot >= slop && (!drag.edge.horizontal() || distance > 0 || canCloseAbove)) {
            target = distance < 0 ? drag.startTasks : drag.endTasks;
        }
        if (target == drag.closeTasks) return;
        if (drag.closeHold != null) canvas.removeCallbacks(drag.closeHold);
        drag.closeTasks = target;
        drag.closeHold = null;
        canvas.invalidate();
        if (target.isEmpty() || event.getActionMasked() != MotionEvent.ACTION_MOVE) return;
        drag.closeHold = () -> {
            Object adapter = CanvasApi.adapter(canvas);
            if (paneEdgeDrags.get(canvas) != drag || !canvas.isShown() || !canvas.isAttachedToWindow()
                    || adapter == null || layoutLocked(canvas.getContext())
                    || !drag.matches(canvas, count(adapter), layoutMode(adapter),
                    count(adapter) == 2 && twoPanesStacked(canvas.getContext()))) return;
            int closedPanes = closePanesAtEdge(canvas, adapter, drag.closeTasks);
            if (closedPanes != 0) {
                closedDividerGestures.put(canvas, drag.downTime);
                stopPaneEdgeDrag(canvas);
                PaneResizeCover cover = paneResizeCover(canvas);
                if (cover != null && isCanvasMaskVisible(canvas)) {
                    cover.animateClose(closedPanes, distance < 0);
                } else if (cover != null) {
                    cover.reveal(null);
                }
            }
        };
        canvas.postDelayed(drag.closeHold, DIVIDER_CLOSE_HOLD_MS);
    }

    private static int closePanesAtEdge(View canvas, Object adapter, List<Object> tasks) {
        try {
            int panes = 0;
            for (Object task : tasks) {
                int index = taskPosition(adapter, task);
                if (index < 0 || index >= count(adapter)) return 0;
                panes |= 1 << index;
            }
            if (tasks.size() == 2) {
                // Use a single native transition from three panes to the remaining app.
                int remaining = 0b111 & ~panes;
                if (count(adapter) != 3 || Integer.bitCount(remaining) != 1) return 0;
                int index = Integer.numberOfTrailingZeros(remaining);
                XposedHelpers.callMethod(canvas, TASK_TO_FULLSCREEN.value(), index);
                log("divider closed upper row, fullscreen index=" + index);
            } else {
                XposedHelpers.callMethod(canvas, CLOSE_TASK.value(), tasks.get(0));
                log("divider closed window index=" + Integer.numberOfTrailingZeros(panes));
            }
            return panes;
        } catch (Throwable error) {
            log("divider close failed: " + error);
            return 0;
        }
    }

    private static void savePaneResize(View canvas, PaneEdgeDrag drag) {
        if (drag.edge.heightEdge()) {
            boolean main = drag.resizesHeight(true);
            int height = drag.resizedHeight(main);
            if (height == twoPaneHeightPercent(drag.display, main)) return;
            layoutPrefs(canvas.getContext()).edit().putInt(twoPaneHeightKey(false, main), height).apply();
            log("window height resized main=" + main + " height=" + height);
            return;
        }
        if (drag.share == drag.startShare) return;
        layoutPrefs(canvas.getContext()).edit().putFloat(drag.preference(), drag.share).apply();
        log("window resize count=" + drag.count + " stacked=" + drag.display.stacked()
                + " divider=" + drag.edge.index() + " share=" + drag.share);
    }

    private static boolean edgeContainsTap(PaneEdgeDrag drag, MotionEvent event, int slop) {
        return Math.abs(event.getRawX() - drag.startX) <= slop
                && Math.abs(event.getRawY() - drag.startY) <= slop;
    }

    private static final java.util.WeakHashMap<Object, int[]> nativeDividerPairs = new java.util.WeakHashMap<>();
    private static final ThreadLocal<PaneEdge> dividerPopupAnchor = new ThreadLocal<>();

    private static void showDividerMenu(View canvas, PaneEdge edge, MotionEvent event) {
        Object adapter = CanvasApi.adapter(canvas);
        if (adapter == null) return;
        int[] pair = panesAtDivider(canvas, adapter, edge, event);
        Object bar = fieldOfTypeDeep(canvas, SPLIT_BAR.value());
        if (bar == null) return;
        dividerPopupAnchor.set(edge);
        try {
            boolean colorOs16 = CanvasApi.hasColorOs16CanvasUpdate();
            Object handle = XposedHelpers.callMethod(bar, colorOs16 ? colorOs16Revision("W", "W", "O") : "X", event);
            if (handle != null) {
                nativeDividerPairs.put(handle, pair);
                XposedHelpers.callMethod(bar, colorOs16 ? colorOs16Revision("o0", "o0", "V") : "q0", handle);
                Object menu = XposedHelpers.getObjectField(bar, "j");
                if (menu instanceof android.widget.PopupWindow popup && popup.isShowing()) return;
                nativeDividerPairs.remove(handle);
            }
            showDividerPopup(canvas, adapter, bar, edge, pair);
        } catch (RuntimeException | LinkageError error) {
            log("divider menu failed: " + error);
        } finally {
            dividerPopupAnchor.remove();
        }
    }

    private static void showDividerPopup(View canvas, Object adapter, Object bar, PaneEdge edge, int[] pair) {
        boolean colorOs16 = CanvasApi.hasColorOs16CanvasUpdate();
        XposedHelpers.callMethod(bar, colorOs16 ? colorOs16Revision("S", "S", "K") : "T");
        android.widget.PopupWindow popup = (android.widget.PopupWindow) XposedHelpers.getObjectField(bar, "j");
        Class<?> builder = XposedHelpers.findClass(WINDOW_MENU_BUILDER.value(), bar.getClass().getClassLoader());
        List<Object> items = new ArrayList<>();
        items.add(windowMenuItem(builder, "交换位置", 2, android.R.drawable.ic_menu_sort_by_size));
        items.add(windowMenuItem(builder, "保存分屏", 1, android.R.drawable.btn_star_big_off));
        XposedHelpers.callMethod(popup, colorOs16 ? "k0" : "Y0", items);
        android.widget.AdapterView.OnItemClickListener select = (list, view, row, id) -> {
            popup.dismiss();
            if (!canvas.isAttachedToWindow() || layoutLocked(canvas.getContext())
                    || CanvasApi.adapter(canvas) != adapter) return;
            if (row == 0) swapDividerPanes(canvas, adapter, pair);
            else if (row == 1) XposedHelpers.callMethod(canvas, colorOs16 ? colorOs16Revision("K1", "I1", "x1") : "R1");
        };
        XposedHelpers.callMethod(popup, colorOs16 ? "p0" : "e1", select);
        XposedHelpers.callMethod(popup, colorOs16 ? "o0" : "d1", true, true);
        Rect anchor = edge.hit();
        XposedHelpers.callMethod(popup, colorOs16 ? "u0" : "l1", canvas.getRootView(), anchor.centerX(), anchor.centerY());
        XposedHelpers.callMethod(canvas, "setCurrentPopWindow", popup);
    }

    private static int[] panesAtDivider(View canvas, Object adapter, PaneEdge edge, MotionEvent event) {
        int[] pair = {-1, -1};
        float[] distances = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
        int seam = edge.horizontal() ? edge.hit().centerY() : edge.hit().centerX();
        float along = edge.horizontal() ? event.getRawX() : event.getRawY();
        for (int index = 0; index < count(adapter); index++) {
            Rect frame = paneWindowFrame(canvas, index);
            if (frame == null) continue;
            int center = edge.horizontal() ? frame.centerY() : frame.centerX();
            int side = center < seam ? 0 : 1;
            int boundary = edge.horizontal()
                    ? (side == 0 ? frame.bottom : frame.top)
                    : (side == 0 ? frame.right : frame.left);
            int start = edge.horizontal() ? frame.left : frame.top;
            int end = edge.horizontal() ? frame.right : frame.bottom;
            float distance = Math.abs(seam - boundary) + Math.max(0f, Math.max(start - along, along - end));
            if (distance < distances[side]) {
                distances[side] = distance;
                pair[side] = index;
            }
        }
        return pair;
    }

    private static void swapDividerPanes(View canvas, Object adapter, int[] pair) {
        int count = count(adapter);
        if (pair[0] < 0 || pair[1] < 0 || pair[0] >= count || pair[1] >= count) return;
        if (layoutLocked(canvas.getContext()) || pendingMainSwaps.containsKey(canvas)
                || mainPaneAnimations.containsKey(canvas)) return;
        int[] positions = panePositions(canvas, adapter);
        if (positions == null) return;
        List<PaneMotion> motions = new ArrayList<>(2);
        for (int index : pair) {
            View pane = (View) XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
            if (pane != null) motions.add(new PaneMotion(pane));
        }
        if (motions.size() == 2) pendingMainSwaps.put(canvas, new MainPaneSwap(motions));
        java.util.Collections.swap(paneOrder(canvas, adapter), positions[pair[0]], positions[pair[1]]);
        requestPaneLayout(canvas);
        log("divider exchanged windows " + pair[0] + " and " + pair[1]);
    }

    private static boolean updatePaneEdgeSize(View canvas, PaneEdgeDrag drag, MotionEvent event) {
        if (drag.scale <= 0f || drag.dividerSpan <= 0) return false;
        int previousHeight = drag.heightDelta;
        float previousShare = drag.share;
        if (drag.edge.heightEdge()) {
            int height = drag.display.availableHeight();
            if (height <= 0) return false;
            // Each edge moves by half the height change with the center fixed.
            drag.heightDelta = Math.round(200f * drag.edge.heightDirection()
                    * drag.distance(event) / (height * drag.scale));
        } else {
            float value = drag.startShare + drag.direction * drag.distance(event) / (drag.dividerSpan * drag.scale);
            float share = drag.count == 4
                    ? CanvasGeometry.clampFourDivider(value, drag.display.fourDividers(), drag.edge.index())
                    : CanvasGeometry.clampLeftShare(value);
            if (drag.count == 3 && drag.edge.horizontal()) share = Math.min(0.60f, share);
            drag.share = share;
            updateDividerCloseTarget(canvas, drag, event, value, share);
        }
        return previousShare != drag.share || previousHeight != drag.heightDelta;
    }

    private static void requestPaneLayout(View canvas) {
        layoutKeys.remove(canvas);
        XposedHelpers.callMethod(canvas, "setNeedUpdateFlexibleTaskViewFrameFlag", true);
        if (CanvasApi.hasColorOs16CanvasUpdate() && CanvasApi.usesScreenSizedOverview()) {
            Object adapter = CanvasApi.adapter(canvas);
            if (adapter != null && usesCanvasLayout(count(adapter))) {
                XposedHelpers.callMethod(canvas, LAYOUT.value());
            }
        }
        canvas.requestLayout();
        canvas.invalidate();
    }

    private static void hookModernWindowMenu(ClassLoader loader) {
        try {
            boolean colorOs16 = CanvasApi.hasColorOs16CanvasUpdate();
            String itemsField = colorOs16 ? colorOs16Revision("W", "V") : "X";
            String canvasField = colorOs16Revision("n", "m");
            String taskField = colorOs16Revision("o", "n");
            String popupField = colorOs16Revision("p", "o");
            Class<?> header = XposedHelpers.findClass(WINDOW_HEADER.value(), loader);
            Class<?> builder = XposedHelpers.findClass(WINDOW_MENU_BUILDER.value(), loader);
            Class<?> strings = XposedHelpers.findClass(colorOs16 ? colorOs16Revision("t1.j", "o1.j") : "com.oplus.pscanvas.j", loader);
            // The alternate ColorOS 16 canvas moved these two labels to P/Q.
            // Keeping the native zoom entries makes a four-pane menu select the
            // system "enlarge" action instead of CanvasPlus' pane-preserving one.
            int enlargeText = XposedHelpers.getStaticIntField(strings,
                    colorOs16Revision("W", "V", "P"));
            int shrinkText = XposedHelpers.getStaticIntField(strings,
                    colorOs16Revision("X", "W", "Q"));
            XposedHelpers.findAndHookMethod(header, colorOs16 ? colorOs16Revision("N0", "G0", "E0") : "U0", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Object owner = param.thisObject;
                    View canvas = (View) XposedHelpers.getObjectField(owner, canvasField);
                    Object adapter = CanvasApi.adapter(canvas);
                    if (adapter == null || count(adapter) < 2) return;
                    int index = taskPosition(adapter, XposedHelpers.getObjectField(owner, taskField));
                    if (index < 0) return;
                    java.util.List<Object> items = (java.util.List<Object>) XposedHelpers.getObjectField(owner, itemsField);
                    String enlarge = canvas.getResources().getString(enlargeText);
                    String shrink = canvas.getResources().getString(shrinkText);
                    items.removeIf(item -> {
                        Object title = XposedHelpers.callMethod(item, "t");
                        return enlarge.equals(title) || shrink.equals(title);
                    });
                    int row = 0;
                    if (Integer.valueOf(index).equals(enlargedPanes.get(canvas))) {
                        items.add(row++, windowMenuItem(builder, shrink, 0, android.R.drawable.ic_menu_zoom));
                    }
                    boolean locked = layoutLocked(canvas.getContext());
                    items.add(row++, windowMenuItem(builder, locked ? "解锁布局" : "锁定布局",
                            LAYOUT_LOCK_ACTION, android.R.drawable.ic_lock_lock));
                    items.add(row++, windowMenuItem(builder, "调节内容大小",
                            CONTENT_SCALE_ACTION, android.R.drawable.ic_menu_zoom));

                    int total = count(adapter);
                    if (total == 2) {
                        boolean stacked = twoPanesStacked(canvas.getContext());
                        items.add(row++, windowMenuItem(builder, stacked ? "切换为左右布局" : "切换为上下布局",
                                TWO_PANE_LAYOUT_ACTION, android.R.drawable.ic_menu_sort_by_size));
                        if (stacked && !locked && !isLandscape(canvas)) {
                            boolean top = isTopStackedWindow(canvas, adapter, index);
                            int width = stackedWindowWidth(canvas.getContext(), isLandscape(canvas), top, false);
                            items.add(row++, windowMenuItem(builder, width == 100 ? "切换为窄窗口" : "切换为宽窗口",
                                    STACKED_WINDOW_WIDTH_ACTION, android.R.drawable.ic_menu_crop));
                        }
                    } else if (total == 3 && !isLandscape(canvas)) {
                        boolean stacked = layoutMode(adapter) == THREE_STACKED_MODE;
                        items.add(row++, windowMenuItem(builder, stacked ? "切换为左右布局" : "切换为上下布局",
                                THREE_PANE_LAYOUT_ACTION, android.R.drawable.ic_menu_sort_by_size));
                    }

                    int main = mainPane(canvas, adapter);
                    if (main < 0) return;
                    items.add(row++, autoMainMenuItem(builder, canvas.getContext(),
                            autoMainWindow(canvas.getContext())));
                    if (layoutMode(adapter) != THREE_STACKED_MODE) {
                        boolean onLeft = mainPaneModeOnScreen(canvas, adapter) == 6;
                        boolean stacked = total == 2 && twoPanesStacked(canvas.getContext());
                        items.add(row++, windowMenuItem(builder,
                                stacked ? (onLeft ? "主屏移到下方" : "主屏移到上方")
                                        : (onLeft ? "主屏移到右边" : "主屏移到左边"),
                                MOVE_MAIN_WINDOW_ACTION, android.R.drawable.ic_menu_sort_by_size));
                    }
                    if (main != index) {
                        items.add(row, windowMenuItem(builder, "设为主窗口", MAIN_WINDOW_ACTION,
                                android.R.drawable.btn_star_big_off));
                    }
                }
            });
            XposedHelpers.findAndHookMethod(WINDOW_HEADER.value() + (colorOs16 ? colorOs16Revision("$c", "$a") : "$d"),
                    loader, "onItemClick",
                    android.widget.AdapterView.class, View.class, int.class, long.class,
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object owner = XposedHelpers.getObjectField(param.thisObject, colorOs16 ? colorOs16Revision("e", "a") : "c");
                            List<?> items = (List<?>) XposedHelpers.getObjectField(owner, itemsField);
                            int row = (Integer) param.args[2];
                            if (row < 0 || row >= items.size()) return;
                            int action = (Integer) XposedHelpers.callMethod(items.get(row), "m");
                            if (action != MAIN_WINDOW_ACTION && action != AUTO_MAIN_WINDOW_ACTION
                                    && action != MOVE_MAIN_WINDOW_ACTION && action != THREE_PANE_LAYOUT_ACTION
                                    && action != TWO_PANE_LAYOUT_ACTION && action != LAYOUT_LOCK_ACTION
                                    && action != STACKED_WINDOW_WIDTH_ACTION
                                    && action != CONTENT_SCALE_ACTION) return;
                            param.setResult(null);
                            View canvas = (View) XposedHelpers.getObjectField(owner, canvasField);
                            Object task = XposedHelpers.getObjectField(owner, taskField);
                            Object adapter = CanvasApi.adapter(canvas);
                            if (adapter == null || count(adapter) < 2) return;
                            android.widget.PopupWindow popup = (android.widget.PopupWindow)
                                    XposedHelpers.getObjectField(owner, popupField);
                            popup.dismiss();
                            if (action == CONTENT_SCALE_ACTION) {
                                int index = taskPosition(adapter, task);
                                if (index >= 0) showContentScale(canvas, adapter, index);
                                return;
                            }
                            if (action == LAYOUT_LOCK_ACTION) {
                                toggleLayoutLock(canvas);
                                return;
                            }
                            if (action == THREE_PANE_LAYOUT_ACTION) {
                                if (count(adapter) == 3 && !isLandscape(canvas)) {
                                    toggleThreePaneLayout(canvas, adapter);
                                }
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
                            int index = taskPosition(adapter, task);
                            if (index < 0) return;
                            if (action == TWO_PANE_LAYOUT_ACTION) {
                                if (count(adapter) == 2) {
                                    toggleTwoPaneLayout(canvas, adapter);
                                }
                                return;
                            }
                            if (action == STACKED_WINDOW_WIDTH_ACTION) {
                                if (count(adapter) == 2) toggleStackedWindowWidth(canvas, adapter, index);
                                return;
                            }
                            selectMainPane(canvas, adapter, index, false);
                        }
                    });
            log("modern window menu hooked");
        } catch (Throwable error) {
            log("modern window menu failed: " + error);
        }
    }

    private static String contentScaleIdentity(Object task) {
        Intent intent = (Intent) XposedHelpers.callMethod(task, "f");
        String name = intent == null ? null
                : intent.getComponent() == null ? intent.getPackage() : intent.getComponent().getPackageName();
        int user = (Integer) XposedHelpers.callMethod(task, TASK_CONTENT_USER.value());
        return user + ":" + (name == null ? "" : name);
    }

    private static String contentScaleAppKey(Object adapter, int index) {
        String identity = contentScaleIdentity(XposedHelpers.callMethod(adapter, "getItem", index));
        int occurrence = 0;
        for (int earlier = 0; earlier < index; earlier++) {
            Object task = XposedHelpers.callMethod(adapter, "getItem", earlier);
            if (identity.equals(contentScaleIdentity(task))) occurrence++;
        }
        return contentScaleAppKey(identity, occurrence);
    }

    private static String contentScaleAppKey(String identity, int occurrence) {
        return "content_scale:" + identity + ":" + occurrence;
    }

    private static String contentScaleLayoutSuffix(View canvas, Object adapter, int count) {
        boolean stacked = count == 2 && twoPanesStacked(canvas.getContext());
        return ":" + paneLayoutKey(count, layoutMode(adapter), isLandscape(canvas), stacked);
    }

    private static int savedContentScale(SharedPreferences prefs, String appKey, String layoutKey) {
        return prefs.getInt(layoutKey, prefs.getInt(appKey, 100));
    }

    private static void showContentScale(View canvas, Object adapter, int index) {
        Context context = canvas.getContext();
        String appKey = contentScaleAppKey(adapter, index);
        String key = appKey + contentScaleLayoutSuffix(canvas, adapter, count(adapter));
        SharedPreferences prefs = layoutPrefs(context);
        int initial = savedContentScale(prefs, appKey, key);
        Rect frame = paneFrame(canvas, index);
        if (frame == null) return;

        Rect available = new Rect();
        canvas.getRootView().getWindowVisibleDisplayFrame(available);
        float density = canvas.getResources().getDisplayMetrics().density;
        int pad = Math.round(16 * density);
        int width = Math.min(Math.round(278 * density), available.width() - 2 * pad);
        if (width <= 0) return;
        boolean night = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        int primary = night ? 0xfff4f4f6 : 0xff202127;
        int accent = night ? 0xff9bd3ff : 0xff1765b1;

        android.app.Dialog dialog = new android.app.Dialog(context, android.R.style.Theme_DeviceDefault_Dialog);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        android.widget.LinearLayout panel = new android.widget.LinearLayout(context);
        panel.setOrientation(android.widget.LinearLayout.VERTICAL);
        panel.setPadding(pad, pad, pad, pad / 2);

        android.widget.LinearLayout heading = new android.widget.LinearLayout(context);
        heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.TextView title = new android.widget.TextView(context);
        title.setText("调节内容大小");
        title.setTextColor(primary);
        title.setTextSize(16);
        heading.addView(title, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        android.widget.TextView value = new android.widget.TextView(context);
        value.setText(initial + "%");
        value.setTextColor(accent);
        value.setTextSize(16);
        heading.addView(value);
        panel.addView(heading, new android.widget.LinearLayout.LayoutParams(-1, -2));

        android.widget.SeekBar slider = new GlassScaleSlider(context, night);
        slider.setContentDescription("内容大小");
        slider.setPadding(0, 0, 0, 0);
        slider.setMax((MAX_CONTENT_SCALE - MIN_CONTENT_SCALE) / CONTENT_SCALE_STEP);
        slider.setProgress((initial - MIN_CONTENT_SCALE) / CONTENT_SCALE_STEP);
        panel.addView(slider, new android.widget.LinearLayout.LayoutParams(-1, Math.round(48 * density)));

        Runnable preview = () -> {
            if (!dialog.isShowing() || !canvas.isAttachedToWindow()) return;
            contentScalePreviews.put(canvas, new ContentScalePreview(key, contentScalePercent(slider.getProgress())));
            requestPaneLayout(canvas);
        };
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar bar, int progress, boolean fromUser) {
                value.setText(contentScalePercent(progress) + "%");
                if (!fromUser) return;
                canvas.removeCallbacks(preview);
                canvas.postDelayed(preview, 80);
            }

            @Override public void onStartTrackingTouch(android.widget.SeekBar bar) {}

            @Override public void onStopTrackingTouch(android.widget.SeekBar bar) {
                canvas.removeCallbacks(preview);
                preview.run();
            }
        });

        android.widget.LinearLayout actions = new android.widget.LinearLayout(context);
        android.widget.Button reset = scaleButton(context, "恢复默认", primary, () -> {
            slider.setProgress((100 - MIN_CONTENT_SCALE) / CONTENT_SCALE_STEP);
            canvas.removeCallbacks(preview);
            preview.run();
        });
        actions.addView(reset, new android.widget.LinearLayout.LayoutParams(0, -2, 1.4f));
        actions.addView(scaleButton(context, "取消", primary, dialog::dismiss),
                new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        actions.addView(scaleButton(context, "保存", accent, () -> {
            int percent = contentScalePercent(slider.getProgress());
            prefs.edit().putInt(key, percent).putInt(appKey, percent).apply();
            log("window content scale saved percent=" + percent + " windows=" + count(adapter)
                    + " landscape=" + isLandscape(canvas));
            dialog.dismiss();
        }), new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        panel.addView(actions, new android.widget.LinearLayout.LayoutParams(-1, -2));

        dialog.setContentView(panel);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(popup -> {
            contentScaleDialogs.remove(canvas);
            canvas.removeCallbacks(preview);
            contentScalePreviews.remove(canvas);
            if (canvas.isAttachedToWindow()) requestPaneLayout(canvas);
        });
        android.view.Window window = dialog.getWindow();
        if (window == null) return;
        android.graphics.drawable.GradientDrawable glass = new android.graphics.drawable.GradientDrawable();
        glass.setColor(night ? 0xe523252d : 0xeef6f8fc);
        glass.setCornerRadius(24f * density);
        glass.setStroke(Math.max(1, Math.round(density)), night ? 0x55ffffff : 0xcaffffff);
        window.setBackgroundDrawable(glass);
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setBackgroundBlurRadius(Math.round(30f * density));
        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        android.view.WindowManager.LayoutParams params = window.getAttributes();
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT;
        params.width = width;
        params.height = -2;
        params.x = Math.max(available.left + pad, Math.min(frame.centerX() - width / 2, available.right - width - pad));
        params.y = Math.max(available.top + pad,
                Math.min(frame.top + 2 * pad, available.bottom - panel.getMeasuredHeight() - pad)) - available.top;
        window.setAttributes(params);
        contentScaleDialogs.put(canvas, new java.lang.ref.WeakReference<>(dialog));
        dialog.show();
        window.setLayout(width, -2);
    }

    private static final class GlassScaleSlider extends android.widget.SeekBar {
        private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path outline = new android.graphics.Path();
        private final android.graphics.RectF track = new android.graphics.RectF();
        private final boolean night;
        private final float density;
        private android.graphics.Shader background;
        private android.graphics.Shader fill;

        GlassScaleSlider(Context context, boolean night) {
            super(context);
            this.night = night;
            density = getResources().getDisplayMetrics().density;
            android.graphics.drawable.GradientDrawable transparentThumb = new android.graphics.drawable.GradientDrawable();
            transparentThumb.setSize(0, 0);
            transparentThumb.setColor(android.graphics.Color.TRANSPARENT);
            setThumb(transparentThumb);
            setSplitTrack(false);
        }

        @Override protected void onSizeChanged(int width, int height, int previousWidth, int previousHeight) {
            super.onSizeChanged(width, height, previousWidth, previousHeight);
            float half = 6f * density;
            track.set(getPaddingLeft(), height / 2f - half, width - getPaddingRight(), height / 2f + half);
            outline.reset();
            outline.addRoundRect(track, half, half, android.graphics.Path.Direction.CW);
            var tile = android.graphics.Shader.TileMode.CLAMP;
            background = new android.graphics.LinearGradient(0, track.top, 0, track.bottom,
                    night ? 0x668ca7be : 0x66869aaa, night ? 0x344c6a82 : 0x3363798c, tile);
            fill = new android.graphics.LinearGradient(0, track.top, 0, track.bottom,
                    night ? 0xffdefaff : 0xffb4edff, night ? 0xff8ed9f1 : 0xff60bce8, tile);
        }

        @Override protected synchronized void onDraw(android.graphics.Canvas canvas) {
            paint.setShader(background);
            float radius = track.height() / 2f;
            canvas.drawRoundRect(track, radius, radius, paint);
            int range = getMax() - getMin();
            if (range <= 0 || getProgress() <= getMin()) return;
            float amount = track.width() * (getProgress() - getMin()) / range;
            paint.setShader(fill);
            int save = canvas.save();
            canvas.clipPath(outline);
            boolean rtl = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            float left = rtl ? track.right - amount : track.left;
            canvas.drawRect(left, track.top, left + amount, track.bottom, paint);
            canvas.restoreToCount(save);
        }
    }

    private static android.widget.Button scaleButton(Context context, String label, int color, Runnable action) {
        android.widget.Button button = new android.widget.Button(context, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setTextColor(color);
        button.setTextSize(13);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(0, 0, 0, 0);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private static void hookGlassPopups(ClassLoader loader) {
        Class<?> popup = XposedHelpers.findClass("com.coui.appcompat.poplist.q", loader);
        XposedHelpers.findAndHookMethod(popup, "J0",
                View.class, int.class, int.class, boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        PaneEdge edge = dividerPopupAnchor.get();
                        if (edge == null || param.hasThrowable()) return;
                        View root = ((View) param.args[0]).getRootView();
                        int gap = Math.round(6 * root.getResources().getDisplayMetrics().density);
                        Rect hit = edge.hit();
                        Rect area = new Rect();
                        root.getWindowVisibleDisplayFrame(area);
                        int x = (Integer) param.args[1];
                        int y = (Integer) param.args[2];
                        if (edge.horizontal()) {
                            int half = XposedHelpers.getIntField(param.thisObject, "I") / 2 + gap;
                            y = area.bottom - hit.bottom >= hit.top - area.top ? hit.bottom + half : hit.top - half;
                        } else {
                            int half = XposedHelpers.getIntField(param.thisObject, "H") / 2 + gap;
                            x = area.right - hit.right >= hit.left - area.left ? hit.right + half : hit.left - half;
                        }
                        XposedHelpers.setIntField(param.thisObject, "P", x);
                        XposedHelpers.setIntField(param.thisObject, "Q", y);
                        XposedBridge.invokeOriginalMethod(param.method, param.thisObject,
                                new Object[]{param.args[0], x, y, param.args[3]});
                    }
                });
    }

    private static int contentScalePercent(int progress) {
        return MIN_CONTENT_SCALE + CONTENT_SCALE_STEP * progress;
    }

    private static Object windowMenuItem(Class<?> type, String label, int id, int icon) {
        return XposedHelpers.callMethod(windowMenuBuilder(type, label, id, icon),
                CanvasApi.hasColorOs16CanvasUpdate() ? "x" : "y");
    }

    private static Object windowMenuBuilder(Class<?> type, String label, int id, int icon) {
        Object builder = XposedHelpers.newInstance(type);
        boolean colorOs16 = CanvasApi.hasColorOs16CanvasUpdate();
        XposedHelpers.callMethod(builder, colorOs16 ? "C" : "D", icon);
        XposedHelpers.callMethod(builder, colorOs16 ? "D" : "E", id);
        XposedHelpers.callMethod(builder, colorOs16 ? "I" : "J", label);
        XposedHelpers.callMethod(builder, colorOs16 ? "F" : "G", true);
        return builder;
    }

    private static Object autoMainMenuItem(Class<?> type, Context context, boolean enabled) {
        int uiMode = context.getResources().getConfiguration().uiMode;
        boolean dark = (uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        int color = enabled ? (dark ? 0xfff5f5f5 : 0xff000000)
                : (dark ? 0xffa7a7a7 : 0xff808080);
        Object builder = windowMenuBuilder(type, "自动切换主屏", AUTO_MAIN_WINDOW_ACTION, 0);
        if (CanvasApi.hasColorOs16CanvasUpdate()) {
            return XposedHelpers.callMethod(builder, "x");
        }
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
        return taskPosition(adapter, paneOrder(canvas, adapter).get(slot));
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

    private static void hookSavedSplitOrder(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            if (CanvasApi.isRevisedColorOs16()) {
                Class<?> util = XposedHelpers.findClass(UTIL.value(), loader);
                XposedHelpers.findAndHookMethod("x1.a", loader, "a", List.class, int.class, int.class,
                        new XC_MethodHook() {
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                Object context = XposedHelpers.getObjectField(param.thisObject, "a");
                                Object activity = XposedHelpers.callStaticMethod(util, ACTIVITY_FROM_CONTEXT.value(), context);
                                if (activity != null) {
                                    applySavedSplitOrder(param, XposedHelpers.callMethod(activity, ACTIVITY_VIEW.value()));
                                }
                            }
                        });
                return;
            }
            XposedHelpers.findAndHookMethod(CanvasApi.hasColorOs16CanvasUpdate() ? "C1.a" : "j2.a",
                    loader, "a",
                    List.class, int.class, int.class, view, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            applySavedSplitOrder(param, param.args[3]);
                        }
                    });
        } catch (Throwable error) {
            log("split shortcut order hook failed: " + error);
        }
    }

    private static void applySavedSplitOrder(XC_MethodHook.MethodHookParam param, Object canvas) {
        if (!(param.args[0] instanceof List<?> nativeOrder)
                || nativeOrder.size() < 2 || nativeOrder.size() > 4) return;
        List<Object> slots = tasksBySlot.get(canvas);
        if (slots == null || slots.size() != nativeOrder.size()
                || slots.equals(nativeOrder) || !nativeOrder.containsAll(slots)) return;
        param.args[0] = new ArrayList<>(slots);
        log("split shortcut saved in canvas slot order, windows=" + slots.size());
    }

    private static int[] panePositions(Object canvas, Object adapter) {
        List<Object> tasks = paneOrder(canvas, adapter);
        int[] result = new int[tasks.size()];
        for (int slot = 0; slot < tasks.size(); slot++) {
            int index = taskPosition(adapter, tasks.get(slot));
            if (index < 0 || index >= tasks.size()) return null;
            result[index] = slot;
        }
        return result;
    }

    private static void hookSquarePaneCorners(ClassLoader loader) {
        try {
            Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
            XposedBridge.hookAllConstructors(view, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    // The canvas uses this field for task surface creation and layer updates.
                    XposedHelpers.setIntField(param.thisObject, colorOs16Revision("t", "o", "m"), 0);
                }
            });
            Class<?> decor = XposedHelpers.findClass(CanvasApi.PACKAGE + "view.EmbeddedViewDecor", loader);
            XposedHelpers.findAndHookMethod(decor, DECOR_CORNER_RADIUS.value(),
                    XposedHelpers.findClass(TASK.value(), loader), Rect.class, float.class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            param.args[2] = 0f;
                        }
                    });
        } catch (Throwable error) {
            log("square pane corners hook failed: " + error);
        }
    }

    private static boolean selectMainPane(View canvas, Object adapter, int index, boolean automatic) {
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
        if (overview && !(automatic && CanvasApi.hasColorOs16CanvasUpdate())
                && selected != null && previous != null) {
            pendingMainSwaps.put(canvas, new MainPaneSwap(
                    List.of(new PaneMotion(selected), new PaneMotion(previous))));
        }
        enlargedPanes.remove(canvas);
        fourZoomed.remove(canvas);
        XposedHelpers.callMethod(adapter, ADAPTER_FOCUS.value(), index);
        markEnlarged(adapter, count(adapter), -1);
        layoutKeys.remove(canvas);
        XposedHelpers.callMethod(canvas, "setNeedUpdateFlexibleTaskViewFrameFlag", true);
        canvas.requestLayout();
        log("main pane selected index=" + index + " previous=" + main);
        return true;
    }

    private static final java.util.WeakHashMap<View, java.lang.ref.WeakReference<android.app.Dialog>>
            contentScaleDialogs = new java.util.WeakHashMap<>();

    private static boolean isPaneMenuShowing(View canvas) {
        Object popup = XposedHelpers.getObjectField(canvas, CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("V", "Q", "N") : "W");
        if (popup instanceof android.widget.PopupWindow menu && menu.isShowing()) return true;
        var reference = contentScaleDialogs.get(canvas);
        android.app.Dialog dialog = reference == null ? null : reference.get();
        return dialog != null && dialog.isShowing();
    }

    private static boolean hitsWindowMenuHandle(Object decor, int x, int y) {
        Object control = XposedHelpers.getObjectField(decor, CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("h", "d") : "g");
        if (control == null) return false;
        View handle = (View) XposedHelpers.getObjectField(control, "d");
        if (handle == null || !handle.isShown()) return false;
        Rect bounds = new Rect();
        XposedHelpers.callMethod(handle, "getBoundsOnScreen", bounds);
        return bounds.contains(x, y);
    }

    private static void hookMainPaneSwap(ClassLoader loader) {
        try {
            boolean colorOs16 = CanvasApi.hasColorOs16CanvasUpdate();
            Class<?> gestures = XposedHelpers.findClass(GESTURES.value(), loader);
            XposedHelpers.findAndHookMethod(gestures, "M", MotionEvent.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    MotionEvent touch = (MotionEvent) param.args[0];
                    if (touch.getActionMasked() != MotionEvent.ACTION_DOWN) return;
                    View canvas = (View) XposedHelpers.getObjectField(param.thisObject, GESTURE_CANVAS.value());
                    if (canvas != null && isPaneMenuShowing(canvas)) {
                        param.setObjectExtra("canvasplus.menuTouch", Boolean.TRUE);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    MotionEvent touch = (MotionEvent) param.args[0];
                    if (touch.getActionMasked() != MotionEvent.ACTION_DOWN
                            || touch.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS
                            || Boolean.TRUE.equals(param.getObjectExtra("canvasplus.menuTouch"))) return;
                    View canvas = (View) XposedHelpers.getObjectField(param.thisObject, GESTURE_CANVAS.value());
                    if (canvas == null || isPaneMenuShowing(canvas)) return;
                    Object adapter = CanvasApi.adapter(canvas);
                    if (adapter == null || CanvasGeometry.mainPane(count(adapter), mainPaneMode(canvas, adapter)) < 0
                            || !autoMainWindow(canvas.getContext()) || isFourZoomed(canvas)
                            || XposedHelpers.getBooleanField(canvas, colorOs16 ? colorOs16Revision("S", "N", "K") : "T")) return;
                    String indexField = colorOs16 ? "n" : "o";
                    int index = XposedHelpers.getIntField(param.thisObject, indexField);
                    if (index < 0 || index >= count(adapter) || index == mainPane(canvas, adapter)) return;
                    int x = (int) touch.getRawX();
                    int y = (int) touch.getRawY();
                    for (PaneEdge edge : resizeEdges(canvas)) {
                        if (edge.hit().contains(x, y)) return;
                    }
                    int hit = (Integer) XposedHelpers.callMethod(canvas, colorOs16 ? colorOs16Revision("g0", "g0", "c0") : "k0",
                            x, y);
                    if (index != hit) return;
                    Object decor = XposedHelpers.callMethod(canvas, EMBEDDED_VIEW.value(), index);
                    if (decor == null || hitsWindowMenuHandle(decor, x, y)) return;
                    Object taskView = XposedHelpers.callMethod(decor, "getFlexibleTaskView");
                    if (taskView == null) return;
                    XposedHelpers.callMethod(taskView, "calculateFrame");
                    Rect header = (Rect) XposedHelpers.callMethod(taskView, "calculateHandleFrame");
                    if (header.contains(x, y) || !selectMainPane(canvas, adapter, index, true)) return;
                    // 手指落下时已换窗，后续的同一笔触交给画布结束。
                    Object activity = XposedHelpers.getObjectField(param.thisObject, "b");
                    XposedHelpers.callMethod(activity, CLAIM_INPUT.value());
                    XposedHelpers.setIntField(param.thisObject, indexField, -2);
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
                            if (!mainPaneAnimations.containsKey(canvas)) refreshPaneDividers(canvas);
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
            animation.setDuration(CanvasApi.hasColorOs16CanvasUpdate() ? 185 : 260);
            animation.setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f));
            animation.addUpdateListener(animator -> {
                float progress = (Float) animator.getAnimatedValue();
                for (PaneMotion pane : moving) pane.apply(progress, uniform);
            });
            animation.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animator) {
                    for (PaneMotion pane : moving) pane.reset();
                    refreshPaneDividers(canvas);
                    if (mainPaneAnimations.remove(canvas, animation) && canvas.isAttachedToWindow()) {
                        if (CanvasApi.hasColorOs16CanvasUpdate()) {
                            // Sync the embedded task frames after the transforms settle.
                            XposedHelpers.callMethod(canvas, colorOs16Revision("s2", "p2", "a2"));
                        } else {
                            XposedHelpers.callMethod(canvas, "z2");
                            XposedHelpers.callMethod(canvas, "v2");
                        }
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
            if (pane.getParent() != canvas || start.isEmpty() || end.isEmpty() || start.equals(end)) return false;
            pane.setPivotX(0f);
            pane.setPivotY(0f);
            return true;
        }

        void apply(float progress, boolean uniform) {
            float remaining = 1f - progress;
            // Move from the old screen rectangle to the new pane.
            float scaleX = 1f + ((float) start.width() / end.width() - 1f) * remaining;
            float scaleY = 1f + ((float) start.height() / end.height() - 1f) * remaining;
            if (uniform) scaleX = scaleY = Math.min(scaleX, scaleY);
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
            int position = taskPosition(adapter, task);
            if (position >= 0) {
                focus = position;
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
            XposedHelpers.callMethod(adapter, ADAPTER_FOCUS.value(), focus);
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
        float margin = CanvasApi.usesScreenSizedOverview() ? 4f : 20f;
        return Math.round(margin * android.content.res.Resources
                .getSystem().getDisplayMetrics().density);
    }

    private static int threeStackedBottomMargin() {
        return CanvasApi.usesScreenSizedOverview() ? 0 : marginPx();
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

    private static Rect overviewBounds(View canvas, int panes) {
        Rect bounds = contentRect(canvas);
        if (bounds == null || panes != 2 || isLandscape(canvas) || twoPanesStacked(canvas.getContext())) {
            return bounds;
        }
        return new Rect(bounds.left, 0, bounds.right, canvasDisplay(canvas).availableHeight());
    }

    /** 在同一次布局里提交窗格和画布坐标，避免新窗格先用上一套平移量画一帧。 */
    private static void applyOverview(View canvas, int panes) {
        if (!(canvas.getParent() instanceof View parent)) return;
        Rect union = overviewBounds(canvas, panes);
        if (union == null) return;
        Rect area = canvasArea(canvas);
        boolean landscape = isLandscape(canvas);
        int margin = landscape ? 0 : marginPx();
        Object adapter = CanvasApi.adapter(canvas);
        boolean threeStacked = !landscape && panes == 3 && adapter != null
                && layoutMode(adapter) == THREE_STACKED_MODE;
        boolean alignTop = !landscape && (panes == 4 || threeStacked);
        float topInset = area.top + (alignTop ? 0 : margin);
        float availableHeight = area.bottom - topInset - (threeStacked ? threeStackedBottomMargin() : margin);
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
        if (CanvasApi.hasColorOs16CanvasUpdate()) {
            layoutTy.put(canvas, y);
            XposedHelpers.setFloatField(canvas, colorOs16Revision("z0", "u0", "q0"), x);
            XposedHelpers.setFloatField(canvas, colorOs16Revision("A0", "v0", "r0"), parent.getTranslationY());
            XposedHelpers.setFloatField(canvas, colorOs16Revision("B0", "w0", "s0"), scale);
        } else {
            XposedHelpers.setFloatField(canvas, "A0", x);
            XposedHelpers.setFloatField(canvas, "B0", parent.getTranslationY());
            XposedHelpers.setFloatField(canvas, "C0", scale);
        }
        XposedHelpers.callMethod(canvas, "setNeedUpdateFlexibleTaskViewFrameFlag", true);
    }

    private static void setAutoTarget(View canvas, float x, float y, float scale) {
        boolean colorOs16 = CanvasApi.hasColorOs16CanvasUpdate();
        XposedHelpers.setFloatField(canvas, colorOs16 ? colorOs16Revision("n0", "i0", "f0") : "o0", x);
        XposedHelpers.setFloatField(canvas, colorOs16 ? colorOs16Revision("o0", "j0", "g0") : "p0", y);
        XposedHelpers.setFloatField(canvas, colorOs16 ? colorOs16Revision("p0", "k0", "h0") : "q0", scale);
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
                        int[] pair = nativeDividerPairs.remove(param.args[0]);
                        if (pair != null && self instanceof View canvas) {
                            Object adapter = CanvasApi.adapter(canvas);
                            if (adapter != null) swapDividerPanes(canvas, adapter, pair);
                            param.setResult(Boolean.TRUE);
                            return;
                        }
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
                    TO_FLOATING.value() + FLOATING_ANIMATOR.value(), loader);
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
            if (!CanvasApi.isColorOs17()) XposedHelpers.findAndHookMethod(zoom,
                    colorOs16Revision("y0", "x0", "u0"), int.class, int.class,
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
                                        rest.add(XposedHelpers.callMethod(controller, colorOs16Revision("l", "j"), i));
                                    }
                                }
                                if (!CanvasApi.isRevisedColorOs16()) {
                                    XposedHelpers.callMethod(container, REFRESH_TASK_DATA.value(), context, rest, 0);
                                }
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
                                Object sources = XposedHelpers.getObjectField(self, colorOs16Revision("O", "N"));
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
            XposedHelpers.findAndHookMethod(util, colorOs16Revision("M", "J"),
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
        List<Rect> panes = basePanes(2, mode, columnShare(canvas, 2, mode), display);
        if (panes.size() != 2) return panes;

        Rect union = new Rect();
        for (Rect pane : panes) union.union(pane);
        int x = display.area().centerX() - union.centerX();
        int y = display.area().centerY() - union.centerY();
        // 系统的遮罩调用方会补上状态栏偏移。
        Class<?> policy = XposedHelpers.findClass("i2.s", loader);
        if (Boolean.TRUE.equals(XposedHelpers.callStaticMethod(policy, "C"))) {
            y -= (Integer) XposedHelpers.callMethod(host, ACTIVITY_MASK_OFFSET.value());
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
                        if (CanvasApi.usesScreenSizedOverview() && (param.args.length != 2
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
            if (CanvasApi.usesScreenSizedOverview()) {
                Class<?> decor = XposedHelpers.findClass(
                        CanvasApi.PACKAGE + "view.EmbeddedViewDecor", loader);
                XposedHelpers.findAndHookMethod(decor, colorOs16Revision("E", "B", "z"), Rect.class, boolean.class,
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
                                        || (requested.width() == launch.width()
                                                && requested.height() == launch.height())) return;
                                if (!isLandscape(canvas) && (requested.width() > requested.height())
                                        != (launch.width() > launch.height())) {
                                    PaneResizeCover cover = paneResizeCover(canvas);
                                    if (cover == null || !cover.hasThreePaneResizeFeedback(requested)) return;
                                }
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

        if (CanvasApi.usesScreenSizedOverview()) {
            try {
                if (CanvasApi.isColorOs17()) hookPaneContentFit(loader);
                Class<?> view = XposedHelpers.findClass(CanvasApi.VIEW, loader);
                XposedHelpers.findAndHookMethod(view, LAYOUT_TWO.value(), new XC_MethodHook() {
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
                        Object union = CanvasApi.usesScreenSizedOverview()
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
                        CanvasDisplay display = layoutDisplay.get();
                        if (CanvasApi.usesScreenSizedOverview()
                                && (count == 3 || count == 4 || (count == 2 && display != null))) {
                            int mode = count == 2 ? DEFAULT_ORIENTATION : (Integer) param.args[1];
                            Float share = layoutColumnShare.get();
                            if (display == null) display = fallbackCanvasDisplay();
                            mode = effectiveThreePaneMode(mode, display.landscape());
                            java.util.List<Rect> panes = basePanes(count, mode,
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
                        if (CanvasApi.usesScreenSizedOverview() && total >= 2
                                && taskPosition(adapter, param.args[0]) >= 0) {
                            View canvas = (View) param.thisObject;
                            Object task = param.args[0];
                            if (deferWindowCountChange(canvas, ready -> {
                                Object current = CanvasApi.adapter(canvas);
                                if (ready && current != null && taskPosition(current, task) >= 0) {
                                    XposedHelpers.callMethod(canvas, CLOSE_TASK.value(), task);
                                }
                            })) {
                                param.setResult(null);
                                return;
                            }
                            coverWindowCountChange(canvas);
                        }
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
                                    XposedHelpers.callMethod(activity, ACTIVITY_FOCUS_TASK_ID.value()));
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
            XposedHelpers.findAndHookMethod(controller, CONTROLLER_ADD.value(), taskData, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (CanvasApi.isColorOs17() && param.args[0] != null) {
                        View canvas = (View) XposedHelpers.getObjectField(param.thisObject, "a");
                        Object adapter = canvas == null ? null : CanvasApi.adapter(canvas);
                        int total = adapter == null ? 0 : count(adapter);
                        if (total >= 2 && total < 4 && canvas.isShown()
                                && !isFourZoomed(canvas) && !isZoomAnimating(canvas)) {
                            coverWindowCountChange(canvas);
                        }
                    }
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
                            CanvasDisplay display = layoutDisplay.get();
                            boolean landscape = display != null ? display.landscape()
                                    : android.content.res.Resources.getSystem().getConfiguration().orientation
                                            == Configuration.ORIENTATION_LANDSCAPE;
                            int mode = CanvasApi.usesScreenSizedOverview()
                                    ? effectiveThreePaneMode(forced, landscape) : forced;
                            param.args[1] = mode;
                            log("force orientation " + mode);
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
        // 新版画布由系统布局方法提交指定方向的矩形。
        if (!CanvasApi.needsDirectedRelayout()) return;
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
                            XposedHelpers.callMethod(task, TASK_BOUNDS.value()), scale);
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
     * 由 CanvasPlus 的分隔条处理尺寸变化，阻止系统提交另一套布局。
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
                hookColumnDragEnd(bar);
                hookColumnHandles(bar);
                hookWindowHeaderDrag(loader);
                hookTwoPaneDividerMenu(bar, item);
                hookGlassPopups(loader);
            } else if (CanvasApi.usesScreenSizedOverview()) {
                hookColumnHandles(bar);
                hookTwoPaneDividerMenu(bar, item);
            }
            log("drag lock hooked");
        } catch (Throwable error) {
            log("drag lock hook failed: " + error);
        }
    }

    private static void hookTwoPaneDividerMenu(Class<?> bar, Class<?> item) {
        XposedHelpers.findAndHookMethod(bar, CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("T", "T", "L") : "U",
                item, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object canvas = XposedHelpers.getObjectField(param.thisObject, "h");
                Object adapter = CanvasApi.adapter(canvas);
                if (adapter == null || count(adapter) != 2) return;
                List<Object> items = (List<Object>) XposedHelpers.getObjectField(param.thisObject, colorOs16Revision("K", "K", "H"));
                items.removeIf(entry -> {
                    int action = (Integer) XposedHelpers.callMethod(entry, "m");
                    return action == 3 || action == 4;
                });
            }
        });
    }

    private static void hookColumnHandles(Class<?> bar) {
        XposedHelpers.findAndHookMethod(bar, CanvasApi.hasColorOs16CanvasUpdate() ? colorOs16Revision("v", "v", "r") : "w",
                Rect.class, Rect.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object handle = param.getResult();
                if (handle == null) return;
                ImageView icon = (ImageView) XposedHelpers.callMethod(handle, "h");
                if (icon != null) icon.setAlpha(0f);
            }

            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object canvas = XposedHelpers.getObjectField(param.thisObject, "h");
                Object adapter = CanvasApi.adapter(canvas);
                if (adapter == null || count(adapter) < 2) return;
                Rect first = (Rect) param.args[0];
                Rect second = (Rect) param.args[1];
                if (count(adapter) == 2 && twoPanesStacked(((View) canvas).getContext())) {
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
                            // 窗口菜单继续使用系统点击动作。
                            param.setResult(Boolean.FALSE);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(header, "l0", Rect.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Object canvas = XposedHelpers.getObjectField(param.thisObject, "n");
                Object adapter = CanvasApi.adapter(canvas);
                if (adapter != null && count(adapter) >= 2 && count(adapter) <= 4) param.setResult(1f);
            }
        });
    }

    private static void hookColumnDragEnd(Class<?> bar) {
        XposedHelpers.findAndHookMethod(bar, "u0", MotionEvent.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object canvas = XposedHelpers.getObjectField(param.thisObject, "h");
                        Object adapter = CanvasApi.adapter(canvas);
                        if (adapter != null && count(adapter) >= 2) param.setResult(null);
                    }
                });
    }

    private static void hookTaskDataBounds(ClassLoader loader) {
        try {
            Class<?> taskData = XposedHelpers.findClass(TASK.value(), loader);
            XposedHelpers.findAndHookMethod(taskData, TASK_BOUNDS.value(), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (CanvasApi.usesScreenSizedOverview()) {
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
                    if (CanvasApi.usesScreenSizedOverview()) {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        param.setObjectExtra("canvasplus.columnShare", layoutColumnShare.get());
                        param.setObjectExtra("canvasplus.paneOrder", layoutPaneOrder.get());
                        param.setObjectExtra("canvasplus.display", layoutDisplay.get());
                        if (adapter != null) {
                            int panes = count(adapter);
                            int mode = layoutMode(adapter);
                            layoutColumnShare.set(columnShare(param.thisObject, panes, mode));
                            layoutPaneOrder.set(panes >= 2 && panes <= 4
                                    ? panePositions(param.thisObject, adapter) : null);
                            CanvasDisplay display = canvasDisplay((View) param.thisObject);
                            layoutDisplay.set(display);
                            if (usesCanvasLayout(panes) && panes <= 4) {
                                // 先更新已有任务的比例，再把它们摆进新窗格。
                                forcePhoneSize(param.thisObject, true);
                            }
                        }
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (CanvasApi.usesScreenSizedOverview()) {
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
                    if (forcedOrientation != 0) {
                        relayoutDirected(param.thisObject,
                                param.thisObject.getClass().getClassLoader());
                    }
                    try {
                        Object adapter = CanvasApi.adapter(param.thisObject);
                        if (adapter == null) return;
                        int panes = count(adapter);
                        if (usesCanvasLayout(panes) && !paneEdgeDrags.containsKey(param.thisObject)) dumpLayout(param.thisObject);
                        if (!usesCanvasLayout(panes) || isFourZoomed(param.thisObject)) return;
                        if (CanvasApi.usesScreenSizedOverview()) {
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

    private static int canvasScreenOrientation() {
        return CanvasApi.usesScreenSizedOverview() ? ActivityInfo.SCREEN_ORIENTATION_USER
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
            if (CanvasApi.usesScreenSizedOverview()) {
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
                        if (CanvasApi.usesScreenSizedOverview()) {
                            if ((mode == 0 || mode == 1) && !focusing.contains(param.thisObject)) {
                                if (CanvasApi.hasColorOs16CanvasUpdate() && mode == 0 && !isFourZoomed(param.thisObject)) {
                                    applyOverview((View) param.thisObject, total);
                                }
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
                                if (CanvasApi.usesScreenSizedOverview()) {
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
                                        XposedHelpers.callMethod(adapter, ADAPTER_FOCUS.value(), index);
                                    }
                                    return;
                                }
                                if (duration == 0 && !isFourZoomed(canvas)) {
                                    param.setResult(Boolean.FALSE);
                                    return;
                                }
                                if (duration != 450 || !animate) return;
                                if (Boolean.TRUE.equals(menuShrunk.get(canvas)) && Settings.preventClickZoom()) {
                                    int mode = (Integer) param.args[2];
                                    if (mode != 2) {
                                        XposedHelpers.callMethod(adapter, ADAPTER_FOCUS.value(), index);
                                    }
                                    param.setResult(Boolean.FALSE);
                                    log("click zoom skipped, focus=" + index);
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
        if (count < 2 || count > 4 || (count == 4 && !landscape)) return initial;
        boolean upperColumns = count == 3 && mode == THREE_STACKED_MODE;
        String key = paneLayoutKey(count, mode, landscape, false);
        return layoutPrefs(view.getContext()).getFloat(key + (upperColumns ? "_upper_columns" : "_share"),
                initial);
    }

    private static int effectiveThreePaneMode(int mode, boolean landscape) {
        return landscape && mode == THREE_STACKED_MODE ? DEFAULT_ORIENTATION : mode;
    }

    private static int layoutMode(Object adapter) {
        int count = count(adapter);
        if (count == 2) return DEFAULT_ORIENTATION;
        int mode = forcedOrientation != 0 ? forcedOrientation : (Integer) XposedHelpers.callMethod(adapter, "n");
        return count == 3 ? effectiveThreePaneModeForAdapter(mode, adapter) : mode;
    }

    private static int effectiveThreePaneModeForAdapter(int mode, Object adapter) {
        if (mode != THREE_STACKED_MODE || !CanvasApi.usesScreenSizedOverview()) return mode;
        Context context = (Context) XposedHelpers.callMethod(adapter, "getContext");
        return effectiveThreePaneMode(mode,
                context.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);
    }

    private record CanvasDisplay(Rect area, Rect portraitTask, boolean landscape,
                                 int preferredMainPaneMode, int twoPaneSecondaryHeight,
                                 int twoPaneMainHeight, boolean stacked, float stackedShare,
                                 int topWidth, int bottomWidth, float[] fourDividers) {
        int margin() {
            return landscape ? 0 : marginPx();
        }

        int availableWidth() {
            return area.width() - 2 * margin();
        }

        int paneLayoutWidth(int count, int mode) {
            boolean fullWidth = count == 2 || (!landscape && count == 3
                    && (mode == THREE_STACKED_MODE || CanvasGeometry.hasPhoneRatioColumns(count, mode)));
            return fullWidth ? area.width() : availableWidth();
        }

        int availableHeight() {
            return area.height() - 2 * margin();
        }

        int threeStackedHeight() {
            return area.height() - threeStackedBottomMargin();
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
        var prefs = layoutPrefs(canvas.getContext());
        float stackedShare = DEFAULT_STACKED_SHARE;
        if (stacked || (count == 3 && mode == THREE_STACKED_MODE)) {
            stackedShare = prefs.getFloat(key + "_share", DEFAULT_STACKED_SHARE);
        }
        float[] fourDividers = null;
        if (count == 4 && landscape) {
            fourDividers = new float[]{0.25f, 0.5f, 0.75f};
            for (int index = 0; index < fourDividers.length; index++) {
                fourDividers[index] = prefs.getFloat(key + "_divider_" + index, fourDividers[index]);
            }
        }
        boolean allowNarrow = stacked && !landscape;
        return new CanvasDisplay(display.area(), display.portraitTask(), display.landscape(),
                display.preferredMainPaneMode(),
                display.twoPaneSecondaryHeight(), display.twoPaneMainHeight(),
                stacked, stackedShare,
                allowNarrow ? stackedWindowWidth(canvas.getContext(), false, true, false) : 100,
                allowNarrow ? stackedWindowWidth(canvas.getContext(), false, false, false) : 100,
                fourDividers);
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
        boolean stacked = context != null && twoPanesStacked(context);
        if (context != null) {
            var prefs = context.getSharedPreferences(Settings.PREFS, Context.MODE_PRIVATE);
            mainSide = preferredMainPaneMode(context);
            if (!landscape) {
                secondaryHeight = prefs.getInt(twoPaneHeightKey(false, false), 0);
                mainHeight = prefs.getInt(twoPaneHeightKey(false, true), 0);
            }
        }
        Rect portraitTask = new Rect(0, 0, Math.min(width, height),
                Math.max(width, height) - statusBarHeight());
        if (!landscape) {
            int inset = windowInsets == null ? 0 : windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout()).top;
            int safeTop = inset > 0 ? inset : statusBarHeight();
            return new CanvasDisplay(new Rect(0, safeTop, width, height),
                    portraitTask, false, mainSide, secondaryHeight, mainHeight, stacked,
                    DEFAULT_STACKED_SHARE, 100, 100, null);
        }
        // 横屏画布可进入挖孔区域，系统栏仍按实际占用空间计算。
        Insets insets = windowInsets == null ? Insets.NONE : windowInsets.getInsets(
                WindowInsets.Type.navigationBars() | WindowInsets.Type.statusBars());
        Rect area = new Rect(insets.left, insets.top, width - insets.right, height - insets.bottom);
        return new CanvasDisplay(area, portraitTask, true, mainSide, secondaryHeight, mainHeight, stacked,
                DEFAULT_STACKED_SHARE, 100, 100, null);
    }

    private static int twoPaneHeightPercent(CanvasDisplay display, boolean main) {
        if (display.landscape()) return 100;
        int saved = main ? display.twoPaneMainHeight() : display.twoPaneSecondaryHeight();
        if (saved > 0) return saved;
        float baselineShare = CanvasGeometry.defaultLeftShare(2, DEFAULT_ORIENTATION);
        float width = (display.availableWidth() - columnGap()) * (main ? 1f - baselineShare : baselineShare);
        Rect phone = display.portraitTask();
        int percent = Math.round(100f * width * phone.height() / phone.width() / display.availableHeight());
        return Math.max(MIN_TWO_PANE_HEIGHT, Math.min(100, percent));
    }

    private static List<Rect> basePanes(int count, int mode, float leftShare, CanvasDisplay display) {
        boolean threeStacked = count == 3 && !display.landscape() && mode == THREE_STACKED_MODE;
        int width = display.paneLayoutWidth(count, mode);
        int height = threeStacked ? display.threeStackedHeight() : display.availableHeight();
        List<Rect> panes;
        if (count == 2 && display.stacked()) {
            boolean mainOnTop = display.preferredMainPaneMode() == 6;
            List<Rect> rows = CanvasGeometry.twoStackedPanes(width, height, columnGap(),
                    display.stackedShare(), mainOnTop);
            if (rows.size() == 2) {
                narrowStackedPane(rows.get(mainOnTop ? 1 : 0), display.topWidth());
                narrowStackedPane(rows.get(mainOnTop ? 0 : 1), display.bottomWidth());
            }
            return rows;
        }
        if (count == 2) {
            int secondaryHeight = Math.round(height * twoPaneHeightPercent(display, false) / 100f);
            int mainHeight = Math.round(height * twoPaneHeightPercent(display, true) / 100f);
            panes = CanvasGeometry.twoPanes(width, height, columnGap(), leftShare,
                    secondaryHeight, mainHeight);
        } else if (threeStacked) {
            panes = CanvasGeometry.threeStackedPanes(width, height, columnGap(), display.stackedShare(), leftShare);
        } else if (display.landscape()) {
            panes = CanvasGeometry.landscapeRow(width, height, columnGap(), count, leftShare, display.fourDividers());
        } else if (CanvasGeometry.hasPhoneRatioColumns(count, mode)) {
            panes = CanvasGeometry.phoneRatioColumns(width, height, columnGap(), count, mode,
                    count == 4 ? 0.5f : leftShare, display.portraitTask());
        } else {
            panes = CanvasGeometry.panes(width, height, columnGap(), count, mode, leftShare);
        }
        int mainMode = display.landscape() ? DEFAULT_ORIENTATION : mode;
        if (panes.size() == count && isMainPaneReversed(count, mainMode, display.preferredMainPaneMode())) {
            CanvasGeometry.moveMainPaneToOppositeSide(panes, CanvasGeometry.mainPane(count, mainMode));
        }
        return panes;
    }

    private static void narrowStackedPane(Rect pane, int widthPercent) {
        int width = Math.round(pane.width() * Math.max(30, Math.min(100, widthPercent)) / 100f);
        pane.left += (pane.width() - width) / 2;
        pane.right = pane.left + width;
    }

    private static java.util.List<Rect> fourPanes() {
        if (CanvasApi.usesScreenSizedOverview()) {
            CanvasDisplay display = layoutDisplay.get();
            return basePanes(4, 0, 0.5f, display == null ? fallbackCanvasDisplay() : display);
        }
        Rect cell = phoneSize();
        int gap = Math.round(10f * android.content.res.Resources
                .getSystem().getDisplayMetrics().density);
        return CanvasGeometry.panes(2 * cell.width() + gap, 2 * cell.height() + gap, gap, 4, 0);
    }

    private static List<Rect> taskTargets(Object canvas, Object adapter, int count) {
        if (!CanvasApi.usesScreenSizedOverview()) return java.util.Collections.nCopies(count, phoneSize());
        View view = (View) canvas;
        List<Rect> targets = baseTaskTargets(view, adapter, count);
        if (targets.isEmpty()) return targets;
        SharedPreferences prefs = layoutPrefs(view.getContext());
        String suffix = contentScaleLayoutSuffix(view, adapter, count);
        ContentScalePreview preview = contentScalePreviews.get(view);
        String[] identities = new String[targets.size()];
        List<Rect> scaled = null;
        for (int index = 0; index < targets.size(); index++) {
            Object task = XposedHelpers.callMethod(adapter, "getItem", index);
            String identity = contentScaleIdentity(task);
            int occurrence = 0;
            for (int earlier = 0; earlier < index; earlier++) {
                if (identity.equals(identities[earlier])) occurrence++;
            }
            identities[index] = identity;
            String appKey = contentScaleAppKey(identity, occurrence);
            int percent = contentScale(prefs, preview, appKey, appKey + suffix);
            if (percent == 100) continue;
            if (scaled == null) scaled = new ArrayList<>(targets);
            Rect target = targets.get(index);
            float factor = 100f / Math.max(MIN_CONTENT_SCALE, Math.min(MAX_CONTENT_SCALE, percent));
            scaled.set(index, new Rect(0, 0, Math.max(1, Math.round(target.width() * factor)),
                    Math.max(1, Math.round(target.height() * factor))));
        }
        return scaled == null ? targets : scaled;
    }

    private static int contentScale(View canvas, Object adapter, int index) {
        String appKey = contentScaleAppKey(adapter, index);
        String key = appKey + contentScaleLayoutSuffix(canvas, adapter, count(adapter));
        return contentScale(layoutPrefs(canvas.getContext()), contentScalePreviews.get(canvas), appKey, key);
    }

    private static int contentScale(SharedPreferences prefs, ContentScalePreview preview,
                                    String appKey, String key) {
        return preview != null && key.equals(preview.key()) ? preview.percent()
                : savedContentScale(prefs, appKey, key);
    }

    private static List<Rect> baseTaskTargets(View canvas, Object adapter, int count) {
        CanvasDisplay display = canvasDisplay(canvas);
        Rect target = display.portraitTask();
        int mode = layoutMode(adapter);
        boolean threeStacked = count == 3 && mode == THREE_STACKED_MODE;
        if (count == 2 || threeStacked) {
            List<Rect> panes = basePanes(count, mode, columnShare(canvas, count, mode), display);
            if (panes.size() != count) return java.util.Collections.nCopies(count, target);
            float baseTaskScale = display.landscape() ? 1f : 1.5f;
            List<Rect> targets = new ArrayList<>(count);
            float referenceWidth = display.stacked() ? display.area().width()
                    : (display.area().width() - columnGap()) / 2f;
            int availableHeight = display.availableHeight();
            for (int slot = 0; slot < count; slot++) {
                float paneReferenceWidth = referenceWidth;
                float referenceHeight;
                if (threeStacked) {
                    paneReferenceWidth = slot == 2 ? display.area().width() : (display.area().width() - columnGap()) / 2f;
                    referenceHeight = (display.threeStackedHeight() - columnGap())
                            * (slot == 2 ? 2f / 3f : 1f / 3f);
                } else if (display.stacked()) {
                    referenceHeight = (availableHeight - columnGap()) * (slot == 0 ? 1f / 3f : 2f / 3f);
                } else {
                    referenceHeight = display.landscape() ? availableHeight
                            : Math.min(availableHeight, referenceWidth * target.height() / target.width());
                }
                float taskScale = threeStacked && slot < 2 ? baseTaskScale * 1.20f : baseTaskScale;
                targets.add(responsiveTaskBounds(panes.get(slot), taskScale, paneReferenceWidth, referenceHeight));
            }
            return inTaskOrder(targets, panePositions(canvas, adapter));
        }
        float initialShare = display.landscape() ? CanvasGeometry.LANDSCAPE_LEFT_SHARE
                : CanvasGeometry.defaultLeftShare(count, mode);
        List<Rect> baseline = basePanes(count, mode, initialShare, display);
        if (baseline.size() != count) return java.util.Collections.nCopies(count, target);
        List<Rect> panes = basePanes(count, mode, columnShare(canvas, count, mode), display);
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
        return inTaskOrder(targets, panePositions(canvas, adapter));
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
            if (CanvasApi.usesScreenSizedOverview() && !restoredThreePaneMode) {
                restoredThreePaneMode = true;
                if (forcedMode < 3 || forcedMode > 7) {
                    forcedOrientation = savedThreePaneMode(((View) container).getContext());
                }
            }
            int count = count(adapter);
            Integer last = taskCounts.put(container, count);
            boolean restoreSystemSplit = !CanvasApi.usesScreenSizedOverview() && last != null
                    && last >= 3 && count == 2;
            if (last != null && last != count) {
                layoutKeys.remove(container);
                if (CanvasApi.usesScreenSizedOverview()) {
                    cancelPaneEdgeDrag((View) container);
                    fourZoomed.remove(container);
                    enlargedPanes.remove(container);
                    cancelMainPaneAnimation(container);
                    refreshPaneDividers((View) container);
                    tasksBySlot.remove(container);
                    cancelZoom(container);
                    if (count >= 2) markEnlarged(adapter, count, -1);
                    else menuShrunk.remove(container);
                }
                // 四窗总览是 2×2 的缩放值，关掉一个窗口后画布还停在这个比例上，
                // 三窗矩形塞不进去画面就是坏的。这里按三窗重新算一次总览。
                if (!CanvasApi.usesScreenSizedOverview() && last == 4 && count == 3 && !isFourZoomed(container)) {
                    final Object canvas = container;
                    new android.os.Handler(android.os.Looper.getMainLooper())
                            .post(() -> fitThree(canvas, 0));
                }
            }
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
            multiWindowMode = count == 3;
            if (!usesCanvasLayout(count)) return;

            fixedPaneSizes = CanvasApi.usesScreenSizedOverview() && count >= 2 && count <= 4;
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
                    boolean customContentSize = fixedPaneSizes && contentScale((View) container, adapter, i) != 100;
                    if (customContentSize || contentSizedTasks.containsKey(taskView)) {
                        // The native resize updates the surface transform together with the task bounds.
                        XposedHelpers.callMethod(taskView, "resize", new Rect(target));
                        updateTaskData(adapter, i, target, true);
                        if (customContentSize) contentSizedTasks.put(taskView, Boolean.TRUE);
                        else contentSizedTasks.remove(taskView);
                        if (!paneEdgeDrags.containsKey(container)) {
                            log("window content resized " + target.width() + "x" + target.height());
                        }
                        continue;
                    }
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
                    if (!paneEdgeDrags.containsKey(container)) {
                        log("phone size " + taskId + " -> " + target.width() + "x" + target.height());
                    }
                } catch (Throwable error) {
                    log("phone size " + taskId + " failed: " + error);
                }
            }
        } catch (Throwable error) {
            log("phone size pass failed: " + error);
        }

        if (duringLayout) return;
        if (changed && fixedPaneSizes) {
            if (CanvasApi.hasColorOs16CanvasUpdate()) ((View) container).requestLayout();
            else XposedHelpers.callMethod(container, "z2");
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
            if (CanvasApi.usesScreenSizedOverview()) {
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
                        if (CanvasApi.usesScreenSizedOverview() && forcedOrientation >= 3 && forcedOrientation <= 7) {
                            mode = effectiveThreePaneModeForAdapter(forcedOrientation, adapter);
                        } else if (forcedMode >= 3 && forcedMode <= 7) {
                            mode = effectiveThreePaneModeForAdapter(forcedMode, adapter);
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
            XposedHelpers.findAndHookMethod(policy, colorOs16Revision("c", "f", "c"), adapterType,
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
        return count >= (CanvasApi.usesScreenSizedOverview() ? 2 : 3);
    }

    private static void cancelPaneEdgeDrag(View canvas) {
        stopPaneEdgeDrag(canvas);
        PaneResizeCover cover = paneResizeCover(canvas);
        if (cover != null) cover.remove();
    }

    private static void stopPaneEdgeDrag(View canvas) {
        PaneEdgeDrag drag = paneEdgeDrags.remove(canvas);
        if (drag == null) return;
        if (drag.closeHold != null) canvas.removeCallbacks(drag.closeHold);
    }

    private static int taskPosition(Object adapter, Object task) {
        if (!CanvasApi.hasColorOs16CanvasUpdate()) {
            return (Integer) XposedHelpers.callMethod(adapter, "getPosition", task);
        }
        int total = count(adapter);
        for (int index = 0; index < total; index++) {
            if (XposedHelpers.callMethod(adapter, "getItem", index) == task) return index;
        }
        return -1;
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
        ModuleEntry.log(message);
    }

    /** 记录有变化的窗格坐标和布局状态，方便还原用户的操作现场。 */
    private static void dumpLayout(Object view) {
        try {
            Object adapter = CanvasApi.adapter(view);
            int total = adapter == null ? 0 : count(adapter);
            StringBuilder sb = new StringBuilder("layout windows=").append(total);
            if (CanvasApi.usesScreenSizedOverview() && view instanceof View canvas && total >= 2) {
                Context context = canvas.getContext();
                sb.append(" landscape=").append(isLandscape(canvas))
                        .append(" mode=").append(layoutMode(adapter))
                        .append(" stacked=").append(total == 2 && twoPanesStacked(context))
                        .append(" locked=").append(layoutLocked(context));
            }
            for (int i = 0; i < total; i++) {
                Object decor = XposedHelpers.callMethod(view, EMBEDDED_VIEW.value(), i);
                if (!(decor instanceof android.view.View)) continue;
                android.view.View d = (android.view.View) decor;
                sb.append(" [").append(d.getLeft()).append(",").append(d.getTop())
                        .append(",").append(d.getRight()).append(",")
                        .append(d.getBottom()).append("]");
            }
            String layout = sb.toString();
            if (layout.equals(lastDumpedLayout)) return;
            lastDumpedLayout = layout;
            log(layout);
        } catch (Throwable error) {
            log("dump failed: " + error);
        }
    }

    private static volatile String lastDumpedLayout;
}
