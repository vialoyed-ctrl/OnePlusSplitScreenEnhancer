package com.canvasplus;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/** 私有接口随画布 APK 变化，用实际签名识别接口版本。 */
final class CanvasApi {
    static final String PACKAGE = "com.oplus.pscanvas.canvasmode.canvas.";
    static final String ACTIVITY = PACKAGE + "ContainerActivity";
    static final String VIEW = PACKAGE + "view.ContainerView";

    private enum Profile { LEGACY, UPDATED_COLOR_OS_16, REVISED_COLOR_OS_16, COLOR_OS_17, UPDATED_COLOR_OS_17 }

    private static Profile profile = Profile.LEGACY;
    private static boolean updatedColorOs16Overview;

    enum Member {
        ADAPTER("u1.c", "c2.c", "c2.c", "u1.c", "p1.c"),
        TASK("u1.d", "c2.d", "c2.d", "u1.d", "p1.d"),
        CONTROLLER(PACKAGE + "s0", PACKAGE + "v0", PACKAGE + "w0", PACKAGE + "r0", PACKAGE + "p0"),
        POLICY_SELECTOR("B1.n", "i2.m", "i2.m", "B1.n", "w1.n"),
        TABLET_POLICY("B1.r", "i2.r", "i2.r", "B1.r", "w1.r"),
        UTIL("B1.l", PACKAGE + "util.FlexibleWindowUtil", PACKAGE + "util.FlexibleWindowUtil", "B1.l", "w1.l"),
        UI_MANAGER("A1.b", "h2.b", "h2.b", "A1.b", "v1.b"),
        SPLIT_BAR(PACKAGE + "E", PACKAGE + "G", PACKAGE + "G", PACKAGE + "E", PACKAGE + "C"),
        SPLIT_BAR_ITEM(PACKAGE + "E$c", PACKAGE + "G$c", PACKAGE + "G$c", PACKAGE + "E$c", PACKAGE + "C$c"),
        TO_FLOATING(PACKAGE + "I", PACKAGE + "K", PACKAGE + "L", PACKAGE + "I", PACKAGE + "G"),
        FLOATING_ANIMATOR("$j", "$j", "$k"),

        ACTIVITY_VIEW("x0", "G0", "H0", "v0"),
        ACTIVITY_MASK_OFFSET("T0", "T0", "U0"),
        ACTIVITY_FOCUS_TASK_ID("E0", "E0", "F0", "u0", "u0"),
        ACTIVITY_FROM_CONTEXT("O1", "X1", "b2", "O1", "u1"),
        ADD_TASK("j0", "p0", "p0", "h0"),
        CREATE_TASK("n0", "s0", "t0", "n0", "h0"),
        TASK_USER("s0", "u0", "u0", "r0", "d0"),
        TASK_ID("l0", "n0", "n0", "k0", "Y"),
        TASK_ID_GETTER("t", "t", "t", "s", "q"),
        TASK_ENLARGED("p0", "r0", "r0", "p0", "J"),
        THREE_LAYOUT("M1", "V1", "Z1", "M1", "s1"),
        LAYOUT("N2", "W2", "W2", "N2", "J2"),
        LAYOUT_TWO("O2", "X2", "X2", "O2", "K2"),
        TASK_CONTENT_USER("w", "w", "w", "v", "s"),
        TASK_BOUNDS("o", "o", "o", "o", "m"),
        CONTROLLER_ADD("d", "d", "d", "d", "b"),
        ADAPTER_FOCUS("I", "I", "I", "H"),
        DECOR_CORNER_RADIUS("k0", "k0", "k0", "j0", "c0"),
        GESTURES(PACKAGE + "A", PACKAGE + "A", PACKAGE + "A", PACKAGE + "y", PACKAGE + "w"),
        GESTURE_CANVAS("h", "h", "h", "g"),
        CLAIM_INPUT("O1", "O1", "O1", "w1", "v1"),
        WINDOW_HEADER(PACKAGE + "v", PACKAGE + "v", PACKAGE + "v", PACKAGE + "t", PACKAGE + "r"),
        WINDOW_MENU_BUILDER("com.coui.appcompat.poplist.L$a", "com.coui.appcompat.poplist.L$a",
                "com.coui.appcompat.poplist.L$a", "com.coui.appcompat.poplist.s$a"),
        LAYOUT_CHILDREN("w1", "D1", "D1", "w1", "v1"),
        THREE_WINDOWS("i1", "o1", "o1", "i1", "h1"),
        EMBEDDED_VIEW("q0", "v0"),
        PREPARE_VIEWS("T2", "V2", "V2", "T2", "P2"),
        FOCUS_SCALE("T", "X"),
        ANIMATE_SCALE("A2", "J2", "J2", "A2", "w2"),
        SCALE_TARGET("a0", "d0"),
        CLOSE_TASK("T1", "a2", "a2", "T1", "Q1"),
        REMOVE_TASK("X1", "e2", "e2", "X1", "U1"),
        TASK_TO_FULLSCREEN("l0", "q0", "q0", "m0"),
        RECT_CHANGED("x1", "N1", "N1", "v1", "u1"),
        IME_HEIGHT("o1", "E1", "E1", "m1", "l1"),
        EXCHANGE_TASKS("k0", "o0"),
        BAR_DRAG("r0", "s0", "s0", "q0"),
        BAR_SCROLL("q0", "r0", "r0", "p0"),
        FLOATING_CAN_ANIMATE("n0", "l0", "r0", "j0", "i0"),
        FLOATING_START_RECTS("i0", "g0", "m0", "i0", "h0"),
        FLOATING_SOURCE_RECT("h0", "f0", "l0", "h0", "g0"),
        FLOATING_ANIMATION("k0", "i0", "o0", "k0", "j0"),
        REFRESH_TASK_DATA("e3", "n3"),
        CALCULATE_RECTS("c1", "j1", "m1", "c1", "P0");

        private final String legacy;
        private final String current;
        private final String updated;
        private final String updatedColorOs16;
        private final String revisedColorOs16;

        Member(String legacy, String current) {
            this(legacy, current, current);
        }

        Member(String legacy, String current, String updated) {
            this(legacy, current, updated, legacy);
        }

        Member(String legacy, String current, String updated, String updatedColorOs16) {
            this(legacy, current, updated, updatedColorOs16, updatedColorOs16);
        }

        Member(String legacy, String current, String updated, String updatedColorOs16, String revisedColorOs16) {
            this.legacy = legacy;
            this.current = current;
            this.updated = updated;
            this.updatedColorOs16 = updatedColorOs16;
            this.revisedColorOs16 = revisedColorOs16;
        }

        String value() {
            return value(profile);
        }

        private String value(Profile candidate) {
            return switch (candidate) {
                case LEGACY -> legacy;
                case UPDATED_COLOR_OS_16 -> updatedColorOs16;
                case REVISED_COLOR_OS_16 -> revisedColorOs16;
                case COLOR_OS_17 -> current;
                case UPDATED_COLOR_OS_17 -> updated;
            };
        }
    }

    private CanvasApi() {}

    static boolean select(ClassLoader loader) {
        for (Profile candidate : new Profile[]{Profile.COLOR_OS_17, Profile.UPDATED_COLOR_OS_17,
                Profile.UPDATED_COLOR_OS_16, Profile.REVISED_COLOR_OS_16, Profile.LEGACY}) {
            boolean colorOs16Update = candidate == Profile.UPDATED_COLOR_OS_16
                    || candidate == Profile.REVISED_COLOR_OS_16;
            if (matches(loader, candidate)
                    && (!colorOs16Update || matchesUpdatedColorOs16(loader, candidate))) {
                profile = candidate;
                updatedColorOs16Overview = colorOs16Update && supportsUpdatedColorOs16Overview(loader);
                ModuleEntry.log("canvas API: " + candidate);
                return true;
            }
        }
        ModuleEntry.log("unsupported canvas API; layout hooks not installed");
        return false;
    }

    private static boolean matches(ClassLoader loader, Profile candidate) {
        try {
            String util = Member.UTIL.value(candidate);
            String layout = Member.THREE_LAYOUT.value(candidate);
            String policy = Member.POLICY_SELECTOR.value(candidate);
            String task = Member.TASK.value(candidate);
            String activityView = Member.ACTIVITY_VIEW.value(candidate);
            Class<?> view = Class.forName(VIEW, false, loader);
            return Class.forName(ACTIVITY, false, loader)
                            .getDeclaredMethod(activityView).getReturnType() == view
                    && Class.forName(task, false, loader)
                            .getDeclaredMethod(Member.TASK_BOUNDS.value(candidate)).getReturnType() == Rect.class
                    && Class.forName(util, false, loader)
                            .getDeclaredMethod(layout, List.class, int.class, float.class)
                            .getReturnType() == List.class
                    && Class.forName(policy, false, loader)
                            .getDeclaredMethod("b", Context.class).getReturnType() != void.class;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    static boolean isColorOs17() {
        return profile == Profile.COLOR_OS_17 || profile == Profile.UPDATED_COLOR_OS_17;
    }

    static boolean hasColorOs16CanvasUpdate() {
        return profile == Profile.UPDATED_COLOR_OS_16 || profile == Profile.REVISED_COLOR_OS_16;
    }

    static boolean isRevisedColorOs16() {
        return profile == Profile.REVISED_COLOR_OS_16;
    }

    static String colorOs16Revision(String original, String revised) {
        return isRevisedColorOs16() ? revised : original;
    }

    static boolean usesScreenSizedOverview() {
        return isColorOs17() || updatedColorOs16Overview;
    }

    private static boolean supportsUpdatedColorOs16Overview(ClassLoader loader) {
        try {
            Class<?> view = Class.forName(VIEW, false, loader);
            Class<?> gestures = Class.forName(Member.GESTURES.value(), false, loader);
            Class<?> header = Class.forName(Member.WINDOW_HEADER.value(), false, loader);
            view.getDeclaredMethod(Member.LAYOUT_TWO.value());
            view.getDeclaredMethod(Member.TASK_TO_FULLSCREEN.value(), int.class);
            gestures.getDeclaredMethod("T", android.view.MotionEvent.class);
            header.getDeclaredMethod(colorOs16Revision("N0", "G0"));
            Class.forName(colorOs16Revision("y1.n", "t1.n"), false, loader)
                    .getDeclaredMethod(colorOs16Revision("e0", "c0"), view);
            return true;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            ModuleEntry.log("updated ColorOS 16 overview unavailable: " + unavailable);
            return false;
        }
    }

    private static boolean matchesUpdatedColorOs16(ClassLoader loader, Profile candidate) {
        try {
            Class<?> view = Class.forName(VIEW, false, loader);
            Class<?> activity = Class.forName(ACTIVITY, false, loader);
            Class<?> util = Class.forName(Member.UTIL.value(candidate), false, loader);
            Class<?> task = Class.forName(Member.TASK.value(candidate), false, loader);
            Class<?> controller = Class.forName(Member.CONTROLLER.value(candidate), false, loader);
            return view.getDeclaredMethod("getContainerController").getReturnType() == controller
                    && activity.getDeclaredMethod(Member.ADD_TASK.value(candidate), Intent.class,
                            int.class, boolean.class).getReturnType() == void.class
                    && util.getDeclaredMethod(Member.CREATE_TASK.value(candidate), Context.class,
                            Intent.class, int.class).getReturnType() == task;
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return false;
        }
    }

    static Object createTask(Class<?> util, Object activity, Intent launch) {
        if (hasColorOs16CanvasUpdate()) {
            return XposedHelpers.callStaticMethod(util, Member.CREATE_TASK.value(), activity, launch, -2);
        }
        return XposedHelpers.callStaticMethod(util, Member.CREATE_TASK.value(), activity, launch, -2, -1);
    }

    static Object launchBounds(Class<?> util, Object activity, Intent launch) {
        if (profile == Profile.LEGACY) {
            return XposedHelpers.callStaticMethod(util, "o", activity, launch, -2, 0);
        }
        return XposedHelpers.callStaticMethod(util, "o", launch, -2, 0);
    }

    static boolean needsDirectedRelayout() {
        return profile == Profile.LEGACY;
    }

    static Object adapter(Object view) {
        if (view == null) return null;
        try {
            return XposedHelpers.callMethod(view, "getAdapter");
        } catch (LinkageError | RuntimeException unavailable) {
            return null;
        }
    }
}
