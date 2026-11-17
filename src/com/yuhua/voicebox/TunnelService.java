package com.yuhua.voicebox;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * 把到云上的 SSH 隧道搬进 App 自己身上。
 *
 * 以前隧道跑在 Termux 里，MIUI 一冻结 Termux，隧道就断，球就传不上去。
 * 现在由这个前台服务自己维持连接（通知栏有一条，系统不容易冻它），
 * 球还是打 127.0.0.1:8790，背后换成了 App 自己的本地转发。
 *
 * 私钥放在 /sdcard/Download/voicebox_key（第一次跑时从 assets 拷过去也行）。
 */
public class TunnelService extends Service {
    static final String TAG = "VBTunnel";
    static final int NOTI = 4242;
    static final String CHANNEL = "vbtunnel";

    // 云上那台
    static final String HOST = "203.0.113.10";   // ← 改成你自己的服务器
    static final int PORT = 22;
    static final String USER = "root";           // 一般不用改
    // 本地监听 → 云上的语音口
    static final int LOCAL_PORT = 8790;
    static final String REMOTE_HOST = "127.0.0.1";
    static final int REMOTE_PORT = 8788;

    private volatile boolean running = true;
    private volatile Session session;
    private Thread worker;

    @Override
    public void onCreate() {
        super.onCreate();
        ensureChannel();
        startForeground(NOTI, build("连接中…"));
        worker = new Thread(new Runnable() {
            @Override
            public void run() {
                循环();
            }
        }, "vbtunnel");
        worker.start();
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager m = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel ch = new NotificationChannel(CHANNEL, "语音球隧道",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            m.createNotificationChannel(ch);
        }
    }

    private Notification build(String text) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("语音球隧道").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_upload_done)
                .setOngoing(true);
        return b.build();
    }

    private void 提示(String text) {
        try {
            NotificationManager m = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            m.notify(NOTI, build(text));
        } catch (Throwable t) {
            Log.w(TAG, "通知失败", t);
        }
    }

    /**
     * 私钥：优先用打包带进来的那份（assets），拷到应用私有目录再用。
     * 安卓 10 以后普通应用读不了 /sdcard 任意文件，所以 sdcard 只作最后兜底。
     */
    private File 找私钥() {
        File 私有 = new File(getFilesDir(), "voicebox_key");
        if (私有.exists() && 私有.length() > 0) return 私有;

        // 1) 包里带的那份
        try {
            java.io.InputStream in = getAssets().open("voicebox_key");
            FileOutputStream out = new FileOutputStream(私有);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            out.close();
            if (私有.length() > 0) return 私有;
        } catch (Throwable ignored) {
        }

        // 2) 兜底：外部目录（能读就用，读不了就算了）
        String[] 候选 = {
                "/sdcard/Download/voicebox_key",
                "/sdcard/Download/QQ/voicebox_key",
                "/sdcard/tencent/QQfile_recv/voicebox_key"
        };
        for (String s : 候选) {
            File c = new File(s);
            if (c.exists() && c.length() > 0) {
                try {
                    FileInputStream in = new FileInputStream(c);
                    FileOutputStream out = new FileOutputStream(私有);
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    in.close();
                    out.close();
                    if (私有.length() > 0) return 私有;
                } catch (Throwable ignored) {
                }
                return 私有.exists() ? 私有 : null;
            }
        }
        return null;
    }
    private void 循环() {
        while (running) {
            try {
                连一次();
            } catch (Throwable t) {
                Log.w(TAG, "连接失败：" + t);
                提示("断开：" + t.getClass().getSimpleName() + " " + t.getMessage());
            }
            // 连上了就等它断；没连上就歇几秒再来
            while (running && session != null && session.isConnected()) {
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (session != null) {
                try { session.disconnect(); } catch (Throwable ignored) {}
                session = null;
            }
            if (!running) return;
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void 连一次() throws Exception {
        File key = 找私钥();
        if (key == null) {
            提示("找不到私钥（/sdcard/Download/voicebox_key）");
            return;
        }
        JSch jsch = new JSch();
        jsch.addIdentity(key.getAbsolutePath());
        Session s = jsch.getSession(USER, HOST, PORT);
        java.util.Properties cfg = new java.util.Properties();
        cfg.put("StrictHostKeyChecking", "no");
        cfg.put("ServerAliveInterval", "20");
        cfg.put("ServerAliveCountMax", "3");
        s.setConfig(cfg);
        s.setTimeout(15000);
        s.connect(15000);
        s.setPortForwardingL(LOCAL_PORT, REMOTE_HOST, REMOTE_PORT);
        session = s;
        Log.i(TAG, "隧道通了：127.0.0.1:" + LOCAL_PORT + " -> " + HOST + ":" + REMOTE_PORT);
        提示("通着 · 127.0.0.1:" + LOCAL_PORT);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (session != null) {
            try { session.disconnect(); } catch (Throwable ignored) {}
            session = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
