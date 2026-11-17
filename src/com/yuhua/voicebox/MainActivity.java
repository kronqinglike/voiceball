package com.yuhua.voicebox;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(60, 160, 60, 60);
        ll.setGravity(Gravity.TOP);

        TextView tv = new TextView(this);
        tv.setTextSize(16);
        tv.setText("语音球\n\n点下面的按钮：\n1) 如果弹出「授予悬浮窗」就同意\n2) 再弹「录音」权限也同意\n3) 桌面上会出现一个绿色圆球\n\n按住圆球说话，松开就发出去。");
        ll.addView(tv);

        Button btn = new Button(this);
        btn.setText("开启语音球");
        btn.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())));
                return;
            }
            if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            }
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
            }
            Intent i = new Intent(this, FloatService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            // 隧道也交给 App 自己维持（不再靠 Termux）
            Intent t = new Intent(this, TunnelService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(t); else startService(t);
        });
        ll.addView(btn);

        setContentView(ll);
    }
}
