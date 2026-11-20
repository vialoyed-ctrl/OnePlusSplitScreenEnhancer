// 一加分屏增强修改了 CanvasPlus 的此源文件。
package com.canvasplus;

import android.app.Activity;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

/** 模块设置页。UI 用代码搭，不依赖资源文件，跟随系统深浅色。 */
public final class SettingsActivity extends Activity {

    private Switch noClickZoom;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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

        setContentView(root);

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

    @Override protected void onResume() {
        super.onResume();
        if (noClickZoom != null) noClickZoom.setChecked(Settings.get(this));
    }
}
