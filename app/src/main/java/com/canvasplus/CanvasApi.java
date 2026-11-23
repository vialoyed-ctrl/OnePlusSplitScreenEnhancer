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

    private enum Profile { LEGACY, UPDATED_COLOR_OS_16, COLOR_OS_17, UPDATED_COLOR_OS_17 }

    private static Profile profile = Profile.LEGACY;

    enum Member {
        ADAPTER("u1.c", "c2.c"),
        TASK("u1.d", "c2.d"),
        CONTROLLER(PACKAGE + "s0", PACKAGE + "v0", PACKAGE + "w0", PACKAGE + "r0"),
        POLICY_SELECTOR("B1.n", "i2.m"),
        TABLET_POLICY("B1.r", "i2.r"),
        UTIL("B1.l", PACKAGE + "util.FlexibleWindowUtil"),
        UI_MANAGER("A1.b", "h2.b"),
        SPLIT_BAR(PACKAGE + "E", PACKAGE + "G"),
        SPLIT_BAR_ITEM(PACKAGE + "E$c", PACKAGE + "G$c"),
        TO_FLOATING(PACKAGE + "I", PACKAGE + "K", PACKAGE + "L"),
        FLOATING_ANIMATOR("$j", "$j", "$k"),

        ACTIVITY_VIEW("x0", "G0", "H0", "v0"),
        ACTIVITY_MASK_OFFSET("T0", "T0", "U0"),
        ACTIVITY_FOCUS_TASK_ID("E0", "E0", "F0", "u0"),
        ACTIVITY_FROM_CONTEXT("O1", "X1", "b2"),
        ADD_TASK("j0", "p0", "p0", "h0"),
        CREATE_TASK("n0", "s0", "t0"),
        TASK_USER("s0", "u0", "u0", "r0"),
        TASK_ID("l0", "n0", "n0", "k0"),
        TASK_ID_GETTER("t", "t", "t", "s"),
        TASK_ENLARGED("p0", "r0"),
        THREE_LAYOUT("M1", "V1", "Z1"),
        LAYOUT("N2", "W2"),
        LAYOUT_CHILDREN("w1", "D1"),
        THREE_WINDOWS("i1", "o1"),
        EMBEDDED_VIEW("q0", "v0"),
        PREPARE_VIEWS("T2", "V2"),
        FOCUS_SCALE("T", "X"),
        ANIMATE_SCALE("A2", "J2"),
        SCALE_TARGET("a0", "d0"),
        CLOSE_TASK("T1", "a2"),
        REMOVE_TASK("X1", "e2"),
        TASK_TO_FULLSCREEN("l0", "q0"),
        RECT_CHANGED("x1", "N1", "N1", "v1"),
        IME_HEIGHT("o1", "E1", "E1", "m1"),
        EXCHANGE_TASKS("k0", "o0"),
        BAR_DRAG("r0", "s0", "s0", "q0"),
        BAR_SCROLL("q0", "r0", "r0", "p0"),
        FLOATING_CAN_ANIMATE("n0", "l0", "r0", "j0"),
        FLOATING_START_RECTS("i0", "g0", "m0"),
        FLOATING_SOURCE_RECT("h0", "f0", "l0"),
        FLOATING_ANIMATION("k0", "i0", "o0"),
        REFRESH_TASK_DATA("e3", "n3"),
        CALCULATE_RECTS("c1", "j1", "m1");

        private final String legacy;
        private final String current;
        private final String updated;
        private final String updatedColorOs16;

        Member(String legacy, String current) {
            this(legacy, current, current);
        }

        Member(String legacy, String current, String updated) {
            this(legacy, current, updated, legacy);
        }

        Member(String legacy, String current, String updated, String updatedColorOs16) {
            this.legacy = legacy;
            this.current = current;
            this.updated = updated;
            this.updatedColorOs16 = updatedColorOs16;
        }

        String value() {
            return value(profile);
        }

        private String value(Profile candidate) {
            return switch (candidate) {
                case LEGACY -> legacy;
                case UPDATED_COLOR_OS_16 -> updatedColorOs16;
                case COLOR_OS_17 -> current;
                case UPDATED_COLOR_OS_17 -> updated;
            };
        }
    }

    private CanvasApi() {}

    static boolean select(ClassLoader loader) {
        for (Profile candidate : new Profile[]{Profile.COLOR_OS_17, Profile.UPDATED_COLOR_OS_17,
                Profile.UPDATED_COLOR_OS_16, Profile.LEGACY}) {
            if (matches(loader, Member.UTIL.value(candidate), Member.THREE_LAYOUT.value(candidate),
                    Member.POLICY_SELECTOR.value(candidate), Member.TASK.value(candidate),
                    Member.ACTIVITY_VIEW.value(candidate))
                    && (candidate != Profile.UPDATED_COLOR_OS_16 || matchesUpdatedColorOs16(loader))) {
                profile = candidate;
                ModuleEntry.log("canvas API: " + candidate);
                return true;
            }
        }
        ModuleEntry.log("unsupported canvas API; layout hooks not installed");
        return false;
    }

    private static boolean matches(ClassLoader loader, String util, String layout,
                                   String policy, String task, String activityView) {
        try {
            Class<?> view = Class.forName(VIEW, false, loader);
            return Class.forName(ACTIVITY, false, loader)
                            .getDeclaredMethod(activityView).getReturnType() == view
                    && Class.forName(task, false, loader)
                            .getDeclaredMethod("o").getReturnType() == Rect.class
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

    private static boolean matchesUpdatedColorOs16(ClassLoader loader) {
        Profile candidate = Profile.UPDATED_COLOR_OS_16;
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
        if (profile == Profile.UPDATED_COLOR_OS_16) {
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
