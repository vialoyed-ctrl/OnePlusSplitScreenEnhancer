package com.canvasplus;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Restarts only the app processes supported by this module. */
final class ScopeRestart {
    private static final String COMMAND = """
            set -e
            [ "$(id -u)" = 0 ]
            processes="$(ps -A -o PID,NAME | awk '
                $2 ~ /^(com[.]oplus[.]pscanvas|com[.]coloros[.]smartsidebar|tv[.]danmaku[.]bili)(:|$)/ { print $1 }
            ')"
            for pid in $processes; do kill -9 "$pid" 2>/dev/null || [ ! -d "/proc/$pid" ]; done
            """;

    private ScopeRestart() {}

    static String restart() {
        Process process = null;
        try {
            process = new ProcessBuilder("su", "-c", COMMAND).redirectErrorStream(true).start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                return "Root 授权超时，请授权后重试";
            }
            return process.exitValue() == 0 ? "作用域进程已停止，重新打开相关应用即可生效"
                    : "重启失败，请检查模块的 Root 授权";
        } catch (IOException error) {
            return "请在 Root 管理器里手动授权一加分屏增强，再重试";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "重启已中断";
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
        }
    }
}
