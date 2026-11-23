// 一加分屏增强修改了 CanvasPlus 的此源文件。
package com.canvasplus;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 模块设置页。UI 用代码搭，不依赖资源文件，跟随系统深浅色。 */
public final class SettingsActivity extends Activity {

    private static final int SAVE_DIAGNOSTICS = 21;
    private static final DateTimeFormatter LOG_NAME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private Switch noClickZoom;
    private Button exportLogs;
    private Button canvasAppInfo;
    private TextView diagnosticStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView checking = new TextView(this);
        checking.setGravity(android.view.Gravity.CENTER);
        checking.setText("正在检查 Root 权限…");
        setContentView(checking);
        new Thread(() -> {
            boolean granted = ScopeRestart.hasRoot();
            runOnUiThread(() -> {
                if (isDestroyed() || isFinishing()) return;
                if (granted) {
                    showSettings();
                    refreshDiagnosticStatus();
                } else {
                    Toast.makeText(this, "未获得 Root 权限，已关闭一加分屏增强", Toast.LENGTH_LONG).show();
                    finishAndRemoveTask();
                }
            });
        }, "CanvasPlus-check-root").start();
    }

    private void showSettings() {
        CharSequence appName = getApplicationInfo().loadLabel(getPackageManager());
        setTitle(appName);

        boolean night = (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int background = night ? 0xFF1B1B1B : 0xFFFFFFFF;
        int primary = night ? 0xFFEEEEEE : 0xFF000000;
        int secondary = night ? 0xFF9A9A9A : 0xFF888888;

        float density = getResources().getDisplayMetrics().density;
        int gap = Math.round(16 * density);
        int pad = Math.round(24 * density);
        int statusBar = 0;
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) statusBar = getResources().getDimensionPixelSize(id);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad + statusBar, pad, pad);
        root.setBackgroundColor(background);

        TextView title = new TextView(this);
        title.setText(appName);
        title.setTextSize(24);
        title.setTextColor(primary);
        title.setPadding(0, gap, 0, gap);
        root.addView(title);

        TextView section = new TextView(this);
        section.setText("画布");
        section.setTextSize(13);
        section.setTextColor(secondary);
        section.setPadding(0, 0, 0, gap / 2);
        root.addView(section);

        noClickZoom = new Switch(this);
        noClickZoom.setText("缩小后点击窗口不放大");
        noClickZoom.setTextColor(primary);
        noClickZoom.setChecked(Settings.get(this));
        noClickZoom.setOnCheckedChangeListener((button, checked) ->
                Settings.put(this, checked));
        root.addView(noClickZoom, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = new TextView(this);
        note.setText("开启后，从窗口菜单缩小过的布局里，点小窗口只切换焦点，"
                + "放大请用窗口菜单。");
        note.setTextSize(12);
        note.setTextColor(secondary);
        note.setPadding(0, gap / 2, 0, 0);
        root.addView(note);

        Switch captureLogs = new Switch(this);
        captureLogs.setText("记录诊断日志");
        captureLogs.setTextColor(primary);
        captureLogs.setPadding(0, gap, 0, 0);
        captureLogs.setChecked(Settings.diagnosticsEnabled(this));
        captureLogs.setOnClickListener(view -> {
            boolean enabled = captureLogs.isChecked();
            if (Settings.setDiagnosticsEnabled(this, enabled)) {
                refreshDiagnosticStatus();
            } else {
                captureLogs.setChecked(!enabled);
                Toast.makeText(this, "无法保存诊断日志设置", Toast.LENGTH_LONG).show();
            }
        });
        root.addView(captureLogs);

        diagnosticStatus = new TextView(this);
        diagnosticStatus.setTextSize(13);
        diagnosticStatus.setTextColor(secondary);
        diagnosticStatus.setPadding(0, gap, 0, gap / 2);
        root.addView(diagnosticStatus);

        exportLogs = new Button(this);
        exportLogs.setText("导出诊断日志");
        exportLogs.setOnClickListener(view -> chooseLogDestination());
        root.addView(exportLogs);

        canvasAppInfo = new Button(this);
        canvasAppInfo.setText("打开系统画布的应用信息");
        canvasAppInfo.setOnClickListener(view -> openCanvasAppInfo());
        root.addView(canvasAppInfo);

        Button restart = new Button(this);
        restart.setText("重启作用域");
        restart.setOnClickListener(view -> restartScope(restart));
        root.addView(restart);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(background);
        scroll.addView(root);
        setContentView(scroll);

        // 状态栏、导航栏跟页面同色，图标明暗跟着夜间模式走
        getWindow().setStatusBarColor(background);
        getWindow().setNavigationBarColor(background);
        android.view.WindowInsetsController controller = getWindow().getInsetsController();
        if (controller != null) {
            int light = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            controller.setSystemBarsAppearance(night ? 0 : light, light);
        }
    }

    private void restartScope(Button button) {
        button.setEnabled(false);
        new Thread(() -> {
            String message = ScopeRestart.restart();
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                button.setEnabled(true);
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        }, "CanvasPlus-restart-scope").start();
    }

    private void openCanvasAppInfo() {
        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", Settings.CANVAS_PACKAGE, null));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "请在系统设置里找到系统画布，或直接重启手机", Toast.LENGTH_LONG).show();
        }
    }

    private void updateDiagnosticStatus() {
        var store = new DiagnosticLogStore(getFilesDir().toPath());
        boolean hasLogs = store.hasLogs();
        boolean enabled = Settings.diagnosticsEnabled(this);
        exportLogs.setEnabled(hasLogs);
        if (!enabled) {
            diagnosticStatus.setText(hasLogs ? "日志记录已关闭，可导出以前的记录。"
                    : "日志记录已关闭。开启后复现问题，再导出诊断日志。");
            canvasAppInfo.setVisibility(android.view.View.GONE);
            return;
        }
        diagnosticStatus.setText(hasLogs
                ? "已收到模块日志。复现问题后导出，文件可能包含参与分屏的应用包名和异常详情。"
                : "尚未收到模块日志。更新后请在应用信息里强行停止系统画布，再重新打开分屏。"
                        + "也可以重启手机，并确认 LSPosed 已为本模块勾选系统画布。");
        canvasAppInfo.setVisibility(hasLogs ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    private void chooseLogDestination() {
        DiagnosticLog.requestPending(this);
        String name = "CanvasPlus-" + LOG_NAME.format(LocalDateTime.now()) + ".txt";
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain")
                .putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(intent, SAVE_DIAGNOSTICS);
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "找不到系统文件选择器", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent result) {
        super.onActivityResult(requestCode, resultCode, result);
        if (requestCode != SAVE_DIAGNOSTICS || resultCode != RESULT_OK
                || result == null || result.getData() == null) return;
        var destination = result.getData();
        exportLogs.setEnabled(false);
        new Thread(() -> {
            String status;
            try {
                var store = new DiagnosticLogStore(getFilesDir().toPath());
                byte[] events = store.snapshot();
                String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
                String header = "CanvasPlus " + version
                        + "\nExported: " + Instant.now()
                        + "\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL
                        + "\nAndroid: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                        + "\nBuild: " + Build.DISPLAY + "\n\n";
                try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
                    if (output == null) throw new IOException("Document could not be opened");
                    output.write(header.getBytes(StandardCharsets.UTF_8));
                    output.write(events.length == 0
                            ? "尚未收到模块日志。请确认 LSPosed 已勾选系统画布，重启画布后再次复现并导出。\n"
                                    .getBytes(StandardCharsets.UTF_8)
                            : events);
                }
                status = events.length == 0 ? "已保存设备信息，尚未收到模块日志" : "诊断日志已保存";
            } catch (IOException | android.content.pm.PackageManager.NameNotFoundException
                     | RuntimeException error) {
                android.util.Log.w("CanvasPlus", "Diagnostic export failed", error);
                status = "导出失败，请重新选择保存位置";
            }
            String message = status;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                exportLogs.setEnabled(true);
                updateDiagnosticStatus();
                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            });
        }, "CanvasPlus-export").start();
    }

    @Override protected void onResume() {
        super.onResume();
        if (diagnosticStatus == null) return;
        if (noClickZoom != null) noClickZoom.setChecked(Settings.get(this));
        refreshDiagnosticStatus();
    }

    private void refreshDiagnosticStatus() {
        updateDiagnosticStatus();
        DiagnosticLog.requestPending(this);
        diagnosticStatus.postDelayed(() -> {
            if (!isDestroyed()) updateDiagnosticStatus();
        }, 1200);
    }
}
