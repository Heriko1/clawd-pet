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
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.util.Calendar;
import java.util.Random;

/**
 * 两个 overlay 分工：
 *
 *   视觉窗口 = WebView，永远是完整画布，FLAG_NOT_TOUCHABLE，只负责画。
 *   触摸窗口 = 透明空 View，大小随实测包围盒变化，只负责收手势。
 *
 * 为什么是这个结构，见 git log。简述四次尝试：
 *
 *   v1 裁剪窗口 + JS transform 平移内容 —— 闪现。evaluateJavascript
 *      异步，内容偏移落后窗口一两帧。
 *   v2 裁剪窗口 + 负 margin 平移 WebView —— 闪现更明显。setLayoutParams
 *      触发 WebView 重新布局与光栅化，比 CSS 合成层 transform 更慢。
 *      结论：窗口几何与内容偏移是两条管线，无法同步。
 *   v3 本方案 —— 让会变几何的窗口没有像素。不闪现、判定准、真穿透，
 *      代价是 FLAG_NOT_TOUCHABLE 触发系统压暗，alpha 被限到 0.8。
 *   v4 单窗口 + touchable region（反射 @hide 接口）—— 理论上四项全中，
 *      但 Android 16 已拦截该接口，实机降级为不穿透，不可用。
 *
 * 半透明是系统策略：dumpsys 显示 touchOcclusionMode=USE_OPACITY，
 * maximum_obscuring_opacity_for_touch 默认 0.8。vivo 的实现是压暗
 * surface 而非拦截触摸。应用侧无法绕过；只能由使用者在系统层把该阈值
 * 调到 1.0（全局设置，会削弱对恶意悬浮窗的防护）。
 */
public class OverlayService extends Service {
    private static final String CHANNEL_ID = "pet_channel";
    private static final int NOTIF_ID = 1001;
    public static final String ACTION_STATE = "com.nora.pet.STATE_CHANGE";
    private static final long WHISPER_INTERVAL = 3600_000L;
    private static final String PET_DIR = "/sdcard/Download/clawd-pet/";

    /* 画布逻辑尺寸（dp），必须与 pet.html 里的 CANVAS_W / CANVAS_H 一致。
       视觉窗口恒为这个尺寸，永不改变。 */
    private static final int CANVAS_W_DP = 150;
    private static final int CANVAS_H_DP = 185;

    private WindowManager wm;
    private WebView webView;
    private WindowManager.LayoutParams visualParams;
    private View touchView;
    private WindowManager.LayoutParams touchParams;

    /* 画布原点在屏幕上的位置，拖拽改的是这个 */
    private int canvasX = 20, canvasY = 220;
    /* 螃蟹实体范围，画布坐标系，dp。由 pet.html 用 getBBox 实测上报，
       直接决定触摸窗口的位置和大小。 */
    private float bodyX = 0, bodyY = 0, bodyW = CANVAS_W_DP, bodyH = CANVAS_H_DP;
    private boolean pendingGeo = false;

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

    // --- JS Bridge ---
    private class PetBridge {
        /* 旧接口，保留以兼容老 pet.html */
        @JavascriptInterface
        public void requestResize(boolean full) { }

        /* pet.html 每次换 SVG / 气泡开合后上报几何。
           win* 是早期裁剪窗口方案的参数，视觉窗口现在不裁剪，忽略；
           只用 body*（螃蟹实体范围）来摆触摸窗口。签名保持 8 参不变，
           同一份 pet.html 在新旧 APK 上都能跑。 */
        @JavascriptInterface
        public void reportGeo(final float wx, final float wy, final float ww, final float wh,
                              final float bx, final float by, final float bw, final float bh) {
            mainHandler.post(new Runnable() {
                @Override public void run() { applyGeo(bx, by, bw, bh); }
            });
        }
    }

    private void applyGeo(float bx, float by, float bw, float bh) {
        if (bw < 8 || bh < 8) return;   // 测量异常，保持现状
        if (bx == bodyX && by == bodyY && bw == bodyW && bh == bodyH) return;
        bodyX = bx; bodyY = by; bodyW = bw; bodyH = bh;
        // 拖拽途中改触摸窗口尺寸有打断手势的风险，推迟到手势结束
        if (isDragging) { pendingGeo = true; return; }
        syncTouchWindow();
    }

    /* 触摸窗口贴着螃蟹本体。它是透明的，所以尺寸怎么变都不会被看见，
       也就不可能出现视觉上的错位。 */
    private void syncTouchWindow() {
        if (touchView == null || touchParams == null) return;
        touchParams.x = canvasX + dpf(bodyX);
        touchParams.y = canvasY + dpf(bodyY);
        touchParams.width = Math.max(1, dpf(bodyW));
        touchParams.height = Math.max(1, dpf(bodyH));
        try { wm.updateViewLayout(touchView, touchParams); } catch (Exception e) {}
    }

    /* 拖拽：两个窗口一起挪，只改 x/y。视觉窗口尺寸不变 → 不重排不重绘内容。 */
    private void moveWindows() {
        if (webView != null && visualParams != null) {
            visualParams.x = canvasX;
            visualParams.y = canvasY;
            try { wm.updateViewLayout(webView, visualParams); } catch (Exception e) {}
        }
        syncTouchWindow();
    }

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
                final String text = intent.getStringExtra("text");
                if (webView != null) {
                    if (state != null) {
                        final String s = state;
                        mainHandler.post(new Runnable() {
                            @Override public void run() { js("show('" + s + "')"); }
                        });
                    }
                    if (text != null) {
                        final String escaped = text.replace("\\", "\\\\").replace("'", "\\'");
                        mainHandler.post(new Runnable() {
                            @Override public void run() { js("showBubble('" + escaped + "')"); }
                        });
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
        setupVisualWindow();
        setupTouchWindow();
    }

    /* 视觉窗口：完整画布，不可触摸。尺寸是常量，只有拖拽会改 x/y。
       FLAG_NOT_TOUCHABLE 是穿透的来源，也是 alpha 被压到 0.8 的来源，
       两者绑在一起，无法只要一个。 */
    private void setupVisualWindow() {
        visualParams = new WindowManager.LayoutParams(
            dp(CANVAS_W_DP), dp(CANVAS_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        visualParams.gravity = Gravity.TOP | Gravity.START;
        visualParams.x = canvasX;
        visualParams.y = canvasY;

        webView = new WebView(this);
        webView.setBackgroundColor(0x00000000);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        // Disable cache to always load fresh pet.html
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                // 通知 pet.html 开始实测上报包围盒
                js("window.petGeo && petGeo.enable()");
            }
        });
        webView.addJavascriptInterface(new PetBridge(), "Android");

        File f = new File(PET_DIR + "pet.html");
        if (f.exists()) {
            webView.loadUrl("file://" + PET_DIR + "pet.html");
        } else {
            webView.loadUrl("file:///android_asset/pet.html");
        }
        wm.addView(webView, visualParams);
    }

    /* 触摸窗口：透明空 View，边界就是螃蟹边界。
       它收到的任何事件都必然落在螃蟹上，所以不需要命中判定。 */
    private void setupTouchWindow() {
        touchParams = new WindowManager.LayoutParams(
            dp(CANVAS_W_DP), dp(CANVAS_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        touchParams.gravity = Gravity.TOP | Gravity.START;
        touchParams.x = canvasX;
        touchParams.y = canvasY;

        touchView = new View(this);
        touchView.setBackgroundColor(0x00000000);
        touchView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = canvasX;
                        initialY = canvasY;
                        initialTouchX = e.getRawX();
                        initialTouchY = e.getRawY();
                        touchStart = System.currentTimeMillis();
                        hasMoved = false;
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int)(e.getRawX() - initialTouchX);
                        int dy = (int)(e.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            if (!hasMoved) {
                                hasMoved = true;
                                isDragging = true;
                                js("window.petEngine && petEngine.onDragStart()");
                            }
                            canvasX = initialX + dx;
                            canvasY = initialY + dy;
                            moveWindows();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        long elapsed = System.currentTimeMillis() - touchStart;
                        if (!hasMoved) {
                            if (elapsed > 600) {
                                js("window.petEngine && petEngine.onLongPress()");
                            } else if (System.currentTimeMillis() - lastTap < 300) {
                                js("window.petEngine && petEngine.onDoubleTap()");
                            } else {
                                lastTap = System.currentTimeMillis();
                                js("window.petEngine && petEngine.onTap()");
                            }
                        } else if (isDragging) {
                            js("window.petEngine && petEngine.onDragEnd()");
                        }
                        isDragging = false;
                        if (pendingGeo) { pendingGeo = false; syncTouchWindow(); }
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        isDragging = false;
                        if (pendingGeo) { pendingGeo = false; syncTouchWindow(); }
                        return true;
                    default:
                        return false;
                }
            }
        });
        wm.addView(touchView, touchParams);
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
    private int dpf(float d) { return Math.round(d * getResources().getDisplayMetrics().density); }

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
        if (touchView != null) { try { wm.removeView(touchView); } catch (Exception e) {} touchView = null; }
        if (webView != null) {
            try { wm.removeView(webView); } catch (Exception e) {}
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
