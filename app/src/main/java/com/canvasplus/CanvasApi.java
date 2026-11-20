package com.canvasplus;

import android.content.Context;
import android.graphics.Rect;

import java.util.List;

import de.robv.android.xposed.XposedHelpers;

/** 私有接口随画布 APK 变化，用实际签名识别接口版本。 */
final class CanvasApi {
    static final String PACKAGE = "com.oplus.pscanvas.canvasmode.canvas.";
    static final String ACTIVITY = PACKAGE + "ContainerActivity";
    static final String VIEW = PACKAGE + "view.ContainerView";

    private static boolean colorOs17;

    enum Member {
        ADAPTER("u1.c", "c2.c"),
        TASK("u1.d", "c2.d"),
        CONTROLLER(PACKAGE + "s0", PACKAGE + "v0"),
        POLICY_SELECTOR("B1.n", "i2.m"),
        TABLET_POLICY("B1.r", "i2.r"),
        UTIL("B1.l", PACKAGE + "util.FlexibleWindowUtil"),
        UI_MANAGER("A1.b", "h2.b"),
        SPLIT_BAR(PACKAGE + "E", PACKAGE + "G"),
        SPLIT_BAR_ITEM(PACKAGE + "E$c", PACKAGE + "G$c"),
        TO_FLOATING(PACKAGE + "I", PACKAGE + "K"),

        ACTIVITY_VIEW("x0", "G0"),
        ACTIVITY_FROM_CONTEXT("O1", "X1"),
        ADD_TASK("j0", "p0"),
        CREATE_TASK("n0", "s0"),
        TASK_USER("s0", "u0"),
        TASK_ID("l0", "n0"),
        TASK_ENLARGED("p0", "r0"),
        THREE_LAYOUT("M1", "V1"),
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
        RECT_CHANGED("x1", "N1"),
        IME_HEIGHT("o1", "E1"),
        EXCHANGE_TASKS("k0", "o0"),
        BAR_DRAG("r0", "s0"),
        BAR_SCROLL("q0", "r0"),
        FLOATING_CAN_ANIMATE("n0", "l0"),
        FLOATING_START_RECTS("i0", "g0"),
        FLOATING_SOURCE_RECT("h0", "f0"),
        FLOATING_ANIMATION("k0", "i0"),
        REFRESH_TASK_DATA("e3", "n3"),
        CALCULATE_RECTS("c1", "j1");

        private final String legacy;
        private final String current;

        Member(String legacy, String current) {
            this.legacy = legacy;
            this.current = current;
        }

        String value() {
            return colorOs17 ? current : legacy;
        }
    }

    private CanvasApi() {}

    static boolean select(ClassLoader loader) {
        if (matches(loader, Member.UTIL.current, Member.THREE_LAYOUT.current,
                Member.POLICY_SELECTOR.current, Member.TASK.current,
                Member.ACTIVITY_VIEW.current)) {
            colorOs17 = true;
            ModuleEntry.log("canvas API: ColorOS 17");
            return true;
        }
        colorOs17 = false;
        if (matches(loader, Member.UTIL.legacy, Member.THREE_LAYOUT.legacy,
                Member.POLICY_SELECTOR.legacy, Member.TASK.legacy,
                Member.ACTIVITY_VIEW.legacy)) {
            ModuleEntry.log("canvas API: ColorOS 16");
            return true;
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
        return colorOs17;
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
