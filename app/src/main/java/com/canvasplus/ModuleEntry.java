// 一加分屏增强修改了 CanvasPlus 的此源文件。
package com.canvasplus;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * 多窗口工作台。
 *
 * ColorOS 的画布（Pocket Studio）本身支持多窗，手机上被策略限制成最多两个：
 * pscanvas 的 PhonePolicy 对三个及以上返回 0，第三个窗口被 layout 成 0x0。
 *
 * 模块做的事：
 *   1. 放开窗口数量判断，四窗接进官方的 w1 → N2 → M1 布局链路
 *   2. 布局、调节条、焦点缩放、关闭、浮窗全部走原实现，只补四窗缺的分支
 *   3. 智能侧边栏点应用时问画布能不能接，满了（4 个）就还是开小窗
 *   4. 广播入口：START 一次拉多个应用进画布，ADD 加一个
 *
 * 安全阀：/data/system/canvasplus-halt 存在时完全不加载。
 */
public final class ModuleEntry implements IXposedHookLoadPackage {

    private static final String HALT_MARKER = "/data/system/canvasplus-halt";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam param) {
        if (param == null || param.packageName == null) return;
        if (halted()) return;

        switch (param.packageName) {
            case "com.oplus.pscanvas":
                // 画布容器进程，布局问题的真正位置。
                if (!CanvasApi.select(param.classLoader)) break;
                CanvasLayout.install(param.classLoader);
                Trigger.arm(param.classLoader);
                break;

            case "com.coloros.smartsidebar":
                // 智能侧边栏：点应用时问画布能不能接，接不了才开小窗。
                SidebarEntry.install(param.classLoader);
                break;

            default:
                break;
        }
    }

    private static boolean halted() {
        try {
            return new java.io.File(HALT_MARKER).exists();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static void log(String message) {
        XposedBridge.log("CanvasPlus: " + message);
    }
}
