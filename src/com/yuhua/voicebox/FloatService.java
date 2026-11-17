package com.yuhua.voicebox;

import android.app.Notification;
import android.media.AudioManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Scanner;

/**
 * 语音球 —— 回到「录音 → 传云上 → whisper 转文字」这条路。
 *
 * 2026-11-16 试过手机本机识别，结论是这条路在这台机器上走不通：
 * 系统里能被第三方调用的识别服务不是小米自己的（小米那个要
 * com.xiaomi.mibrain.speech.permission.BIND_SPEECH_RECOGNITION_SERVICE，
 * 只有系统应用拿得到），而是另一个 App 提供的服务，一调就报权限错。
 * 所以先退回能用的那条，本机识别等以后换成自己塞模型（Vosk / whisper.cpp）。
 */
public class FloatService extends Service {

    private static final String HOST = "http://127.0.0.1:8790";
    private static final String CH = "voicebox";

    private WindowManager wm;
    private BallView ball;
    private WindowManager.LayoutParams lp;
    private MediaRecorder rec;
    private File outFile;
    private TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private String pendingSpeech = null;
    private boolean recording = false;
    private boolean dragging = false;
    private boolean downOnCollapsed = false;
    private boolean autoHidden = false;
    private float downX, downY;
    private long downTime;
    private int startX, startY;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private int screenW;

    private int sizeExpand() { return (int) (96 * getResources().getDisplayMetrics().density); }
    private int sizeStripW() { return (int) (8 * getResources().getDisplayMetrics().density); }
    private int sizeStripH() { return (int) (72 * getResources().getDisplayMetrics().density); }
    private int edgeGap() { return (int) (6 * getResources().getDisplayMetrics().density); }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotice();
        initTts();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        screenW = getResources().getDisplayMetrics().widthPixels;

        ball = new BallView(this);
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(sizeExpand(), sizeExpand(), type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = edgeGap();
        lp.y = 700;

        ball.setOnTouchListener((v, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    ball.pressed = true;
                    if (ball.collapsed) {
                        downOnCollapsed = true;
                        return true;
                    }
                    downOnCollapsed = false;
                    downTime = System.currentTimeMillis();
                    // 等一下再开录：短点算“收起/展开”，按住了才算说话
                    ui.postDelayed(() -> {
                        if (ball.pressed && !dragging && !recording) startRecord();
                    }, 260);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX;
                    float dy = e.getRawY() - downY;
                    if (Math.abs(dx) + Math.abs(dy) > 24) {
                        if (!dragging) {
                            dragging = true;
                            ball.pressed = false;
                            if (recording) cancelRecord();
                        }
                        lp.x = startX + (int) dx;
                        lp.y = startY + (int) dy;
                        if (lp.x < 0) lp.x = 0;
                        if (lp.x > screenW - lp.width) lp.x = screenW - lp.width;
                        wm.updateViewLayout(ball, lp);

                        // 靠近侧边就自动藏起来，别占地方
                        int 边 = (int) (26 * getResources().getDisplayMetrics().density);
                        if (lp.x <= 边) { autoCollapse(false); }
                        else if (lp.x >= screenW - lp.width - 边) { autoCollapse(true); }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    ball.pressed = false;
                    // 拖到边上自动收起来之后，这次抬手就不用再走下面的判断了
                    if (autoHidden) { autoHidden = false; dragging = false; return true; }
                    if (downOnCollapsed) {
                        downOnCollapsed = false;
                        if (!dragging) expand();
                        return true;
                    }
                    long held = System.currentTimeMillis() - downTime;
                    if (!dragging && held < 260 && !recording) {
                        if (ball.collapsed) expand();
                        else collapse(lp.x + lp.width / 2 > screenW / 2);
                        return true;
                    }
                    if (recording) {
                        stopRecord();
                        if (!dragging) uploadAndSpeak(); else cancelRecord();
                    } else if (dragging) {
                        snapToEdge();
                    }
                    return true;
            }
            return false;
        });

        wm.addView(ball, lp);

        // 长连接等我的话：云上那头一直挂着，我一写它就立刻回来（不轮询、不空转）
        new Thread(() -> {
            while (true) {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(HOST + "/wait").openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(70000);
                    String r = readAll(c.getInputStream());
                    if (r != null && !r.trim().isEmpty()) speak(r.trim());
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void expand() {
        ball.collapsed = false;
        lp.width = sizeExpand();
        lp.height = sizeExpand();
        int half = screenW / 2;
        lp.x = (lp.x + lp.width / 2) < half ? edgeGap() : screenW - sizeExpand() - edgeGap();
        wm.updateViewLayout(ball, lp);
        ball.invalidate();
    }

    private void collapse(boolean right) {
        ball.collapsed = true;
        lp.width = sizeStripW();
        lp.height = sizeStripH();
        lp.x = right ? screenW - sizeStripW() : 0;
        wm.updateViewLayout(ball, lp);
        ball.invalidate();
    }

    /** 拖到侧边就自动收成条，不再需要松手时恰好落在边上。 */
    private void autoCollapse(boolean right) {
        if (ball == null) return;
        if (recording) cancelRecord();
        dragging = false;
        ball.pressed = false;
        autoHidden = true;
        collapse(right);
    }

    private void snapToEdge() {
        int slop = (int) (40 * getResources().getDisplayMetrics().density);
        if (lp.x <= slop) collapse(false);
        else if (lp.x >= screenW - lp.width - slop) collapse(true);
    }

    private void startForegroundNotice() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "语音球", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(c);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        b.setContentTitle("语音球：按住绿圈说话，松开就发").setSmallIcon(android.R.drawable.ic_btn_speak_now);
        Notification n = b.build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(1, n);
        }
    }

    private void startRecord() {
        try {
            outFile = new File(getCacheDir(), "say.m4a");
            if (outFile.exists()) outFile.delete();
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(16000);
            rec.setOutputFile(outFile.getAbsolutePath());
            rec.prepare();
            rec.start();
            recording = true;
            ball.setRecording(true);
        } catch (Exception e) {
            ball.setRecording(false);
            speak("开不了麦克风：" + e.getClass().getSimpleName());
        }
    }

    private void stopRecord() {
        try {
            if (rec != null) { rec.stop(); rec.release(); }
        } catch (Exception ignored) {}
        rec = null;
        recording = false;
        ball.setRecording(false);
    }

    private void cancelRecord() {
        stopRecord();
        if (outFile != null && outFile.exists()) outFile.delete();
    }

    private void uploadAndSpeak() {
        new Thread(() -> {
            try {
                File f = outFile;
                if (f == null || !f.exists() || f.length() < 1500) {
                    speak("太短了");
                    return;
                }
                HttpURLConnection c = (HttpURLConnection) new URL(HOST + "/voice").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "audio/mp4");
                c.setConnectTimeout(10000);
                c.setReadTimeout(180000);
                OutputStream os = c.getOutputStream();
                FileInputStream in = new FileInputStream(f);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                in.close();
                os.close();
                String heard = readAll(c.getInputStream());
                if (heard == null || heard.trim().isEmpty()) {
                    speak("没听清");
                    return;
                }
                speak("听到你说：" + heard.trim());
            } catch (Exception e) {
                speak("传不上去");
            }
        }).start();
    }

    private String readAll(java.io.InputStream is) {
        Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

    private void initTts() {
        try {
            if (tts != null) { tts.shutdown(); tts = null; }
        } catch (Exception ignored) {}
        ttsReady = false;
        tts = new TextToSpeech(this, st -> {
            if (st == TextToSpeech.SUCCESS) {
                try { tts.setLanguage(Locale.SIMPLIFIED_CHINESE); } catch (Exception ignored) {}
                ttsReady = true;
                String s = pendingSpeech;
                pendingSpeech = null;
                if (s != null && tts != null) tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "vb");
            } else {
                ui.postDelayed(() -> { if (!ttsReady) initTts(); }, 2000);
            }
        });
    }

    private void speak(String text) {
        // 音量用他自己的设置；只有真的一点都没有（0）时才抬到三成
        try {
            AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null) {
                int ttsMax = am.getStreamMaxVolume(9);
                int cur = am.getStreamVolume(9);
                if (cur <= 0 && ttsMax > 0) {
                    am.setStreamVolume(9, Math.max(1, (int) (ttsMax * 0.3)), 0);
                }
                int mCur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                if (mCur <= 0) {
                    int mMax = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.max(1, (int) (mMax * 0.3)), 0);
                }
            }
        } catch (Exception ignored) {}
        if (tts != null && ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "vb");
        } else {
            pendingSpeech = text;
            if (tts == null) initTts();
        }
    }

    @Override
    public void onDestroy() {
        try { if (rec != null) rec.release(); } catch (Exception ignored) {}
        if (ball != null && wm != null) wm.removeView(ball);
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
