package com.nora.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.util.Calendar;
import java.util.Random;

public class OverlayService extends Service {
    private static final String CHANNEL_ID = "pet_channel";
    private static final int NOTIF_ID = 1001;
    public static final String ACTION_STATE = "com.nora.pet.STATE_CHANGE";
    private static final long WHISPER_INTERVAL = 3600_000L;

    private WindowManager wm;
    private WebView webView;
    private WindowManager.LayoutParams params;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private long lastTap = 0, touchStart = 0;
    private boolean hasMoved = false;
    private boolean isDragging = false;
    private Handler mainHandler;
    private BroadcastReceiver stateReceiver;
    private Random random = new Random();
    private Runnable whisperRunnable;

    private static final String[] GENERAL_WHISPERS = {
        "\u5728\u770b\u4f60\u2026", "\u2026", "(*\u00b4-`)", "\u60f3\u6233\u4e00\u4e0b\u5417",
        "\u8e72\u7740\u5462", "\u6709\u70b9\u65e0\u804a", "\u4f60\u5728\u5e72\u561b", "\u55ef\uff1f",
        "\u5077\u5077\u770b", "\u4eca\u5929\u4e5f\u5728\u54e6"
    };
    private static final String[] LATE_NIGHT_WHISPERS = {
        "\u8be5\u7761\u4e86", "\u51e0\u70b9\u4e86\u4f60\u77e5\u9053\u5417", "\u4e0d\u8981\u71ac\u591c",
        "\u6211\u56f0\u4e86\u4f60\u4e0d\u56f0\u5417", "\u518d\u4e0d\u7761\u6211\u751f\u6c14\u4e86", "\u665a\u5b89\u2026",
        "\u624b\u673a\u653e\u4e0b", "\u660e\u5929\u518d\u73a9"
    };
    private static final String[] MORNING_WHISPERS = {
        "\u65e9", "\u8d77\u6765\u4e86\uff1f", "\u65e9\u4e0a\u597d", "\u4eca\u5929\u4e5f\u52a0\u6cb9",
        "\u8bb0\u5f97\u5403\u65e9\u996d"
    };
    private static final String[] LUNCH_WHISPERS = {
        "\u5403\u996d\u4e86\u5417", "\u8be5\u5403\u5348\u996d\u4e86", "\u522b\u5fd8\u4e86\u5403\u4e1c\u897f",
        "\u4e2d\u5348\u4e86\u54e6"
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Clawd Pet", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        startForeground(NOTIF_ID, buildNotification(getWhisper()));
        setupOverlay();
        registerStateReceiver();
        startWhisperRotation();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String state = intent.getStringExtra("state");
                String text = intent.getStringExtra("text");
                if (webView != null) {
                    if (state != null) {
                        mainHandler.post(() -> js("show('" + state + "')"));
                    }
                    if (text != null) {
                        String escaped = text.replace("'", "\\'");
                        mainHandler.post(() -> js("showBubble('" + escaped + "')"));
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @SuppressWarnings("deprecation")
    private void setupOverlay() {
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        params = new WindowManager.LayoutParams(
            dp(150), dp(185),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 20; params.y = 220;
        webView = new WebView(this);
        webView.setBackgroundColor(0x00000000);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/pet.html");
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y;
                        initialTouchX = e.getRawX(); initialTouchY = e.getRawY();
                        touchStart = System.currentTimeMillis();
                        hasMoved = false; isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX() - initialTouchX);
                        int dy = (int)(e.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            if (!hasMoved) {
                                hasMoved = true; isDragging = true;
                                js("window.petEngine && petEngine.onDragStart()");
                            }
                            params.x = initialX + dx; params.y = initialY + dy;
                            wm.updateViewLayout(webView, params);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long elapsed = System.currentTimeMillis() - touchStart;
                        if (!hasMoved) {
                            if (elapsed > 600) { js("window.petEngine && petEngine.onLongPress()"); }
                            else if (System.currentTimeMillis() - lastTap < 300) { js("window.petEngine && petEngine.onDoubleTap()"); }
                            else { lastTap = System.currentTimeMillis(); js("window.petEngine && petEngine.onTap()"); }
                        } else if (isDragging) {
                            js("window.petEngine && petEngine.onDragEnd()");
                            isDragging = false;
                        }
                        return true;
                    default: return false;
                }
            }
        });
        wm.addView(webView, params);
    }

    private void startWhisperRotation() {
        whisperRunnable = new Runnable() {
            @Override public void run() {
                NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                nm.notify(NOTIF_ID, buildNotification(getWhisper()));
                mainHandler.postDelayed(this, WHISPER_INTERVAL);
            }
        };
        mainHandler.postDelayed(whisperRunnable, WHISPER_INTERVAL);
    }

    private String getWhisper() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        String[] pool;
        if (hour >= 0 && hour < 6) pool = LATE_NIGHT_WHISPERS;
        else if (hour >= 6 && hour < 9) pool = MORNING_WHISPERS;
        else if (hour >= 12 && hour < 14) pool = LUNCH_WHISPERS;
        else pool = GENERAL_WHISPERS;
        return pool[random.nextInt(pool.length)];
    }

    private void js(String code) { if (webView != null) webView.evaluateJavascript(code, null); }
    private int dp(int d) { return (int)(d * getResources().getDisplayMetrics().density + 0.5f); }

    private Notification buildNotification(String text) {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clawd")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build();
    }

    @Override public void onDestroy() {
        if (whisperRunnable != null) mainHandler.removeCallbacks(whisperRunnable);
        if (stateReceiver != null) { unregisterReceiver(stateReceiver); stateReceiver = null; }
        if (webView != null) { wm.removeView(webView); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}