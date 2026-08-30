package com.nora.pet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class OverlayService extends Service {
    private static final String CHANNEL_ID = "pet_channel";
    private static final int NOTIF_ID = 1001;
    private WindowManager wm;
    private WebView webView;
    private WindowManager.LayoutParams params;
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private long lastTap = 0, touchStart = 0;
    private boolean hasMoved = false;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Clawd Pet", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        startForeground(NOTIF_ID, buildNotification());
        setupOverlay();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    private void setupOverlay() {
        // 没有悬浮窗权限时直接退出，避免 addView 抛 SecurityException
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
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
        webView.setWebViewClient(new WebViewClient());
        webView.loadUrl("file:///android_asset/pet.html");
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x; initialY = params.y;
                        initialTouchX = e.getRawX(); initialTouchY = e.getRawY();
                        touchStart = System.currentTimeMillis(); hasMoved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX() - initialTouchX);
                        int dy = (int)(e.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            hasMoved = true;
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
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(webView, params);
    }

    private void js(String code) { if (webView != null) webView.evaluateJavascript(code, null); }
    private int dp(int d) { return (int)(d * getResources().getDisplayMetrics().density + 0.5f); }

    private Notification buildNotification() {
        return new Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Clawd")
            .setContentText("\u5728\u770b\u4f60\u2026")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build();
    }

    @Override public void onDestroy() {
        if (webView != null) { wm.removeView(webView); webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
