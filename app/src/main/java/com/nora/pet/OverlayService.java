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
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Calendar;
import java.util.Random;

/**
 * 单个 overlay，尺寸恒为完整画布，永不改变。
 *
 * 穿透靠「触摸区域」而不是「整窗不可触摸」：
 * 窗口仍然是可触摸的（所以不会被系统当成 pass-through 窗口压暗），
 * 但只把螃蟹包围盒声明为可接收触摸的区域，区域外的触摸由系统直接
 * 投递给下层应用。
 *
 * 演进历史见 git log：先后试过 JS transform 平移内容、负 margin 平移
 * WebView、视觉/触摸双窗口。前两者都因为「窗口几何」和「内容偏移」
 * 是两条无法同步的管线而闪现；第三种解决了闪现，但 FLAG_NOT_TOUCHABLE
 * 导致螃蟹半透明。区域方案让三个约束（不闪现、不半透明、真穿透）
 * 同时成立。
 */
public class OverlayService extends Service {
    private static final String CHANNEL_ID = "pet_channel";
    private static final int NOTIF_ID = 1001;
    public static final String ACTION_STATE = "com.nora.pet.STATE_CHANGE";
    private static final long WHISPER_INTERVAL = 3600_000L;
    private static final String PET_DIR = "/sdcard/Download/clawd-pet/";

    /* 画布逻辑尺寸（dp），必须与 pet.html 里的 CANVAS_W / CANVAS_H 一致。
       窗口恒为这个尺寸，只有拖拽会改 x/y。 */
    private static final int CANVAS_W_DP = 150;
    private static final int CANVAS_H_DP = 185;

    /* ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION，@hide 常量 */
    private static final int TOUCHABLE_INSETS_REGION = 3;

    private WindowManager wm;
    private WebView webView;
    private WindowManager.LayoutParams params;

    private int canvasX = 20, canvasY = 220;
    /* 螃蟹实体范围，画布坐标系，dp。由 pet.html 用 getBBox 实测上报。 */
    private float bodyX = 0, bodyY = 0, bodyW = CANVAS_W_DP, bodyH = CANVAS_H_DP;
    /* 上面那个矩形换算成 px，直接作为窗口的可触摸区域 */
    private final Rect touchRect = new Rect(0, 0, 9999, 9999);
    /* 区域方案是否装上了。装不上就退回命中判定（不穿透但不半透明）。 */
    private boolean regionMode = false;

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private long lastTap = 0, touchStart = 0;
    private boolean hasMoved = false;
    private boolean isDragging = false;
    private boolean isValidTouch = false;
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
           win* 是早期裁剪窗口方案的参数，窗口现在不再裁剪，忽略；
           只取 body*（螃蟹实体范围）。签名保持 8 参不变，
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
        updateTouchRect();
    }

    /* 只改 Rect + 请求重算内部 insets。不动窗口几何，不动 WebView 布局，
       所以不会 relayout、不会重新光栅化、不会闪。 */
    private void updateTouchRect() {
        touchRect.set(dpf(bodyX), dpf(bodyY), dpf(bodyX + bodyW), dpf(bodyY + bodyH));
        if (regionMode && webView != null) webView.requestLayout();
    }

    /* 拖拽：只改窗口 x/y。触摸区域是窗口内的局部坐标，跟着窗口走，不用更新。 */
    private void moveWindow() {
        if (webView == null || params == null) return;
        params.x = canvasX;
        params.y = canvasY;
        try { wm.updateViewLayout(webView, params); } catch (Exception e) {}
    }

    /* 用 Proxy 接入 @hide 的 OnComputeInternalInsetsListener，
       把窗口的可触摸区域声明为 touchRect。
       这是能同时拿到「穿透」和「不被压暗」的唯一途径：
       窗口没有声明自己 pass-through，只是缩小了接收触摸的范围。 */
    private boolean installTouchRegion(final View v) {
        try {
            Class<?> listenerCls = Class.forName(
                "android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Class<?> infoCls = Class.forName(
                "android.view.ViewTreeObserver$InternalInsetsInfo");
            final Method setTouchableInsets = infoCls.getMethod("setTouchableInsets", int.class);
            final Field regionField = infoCls.getField("touchableRegion");

            Object listener = Proxy.newProxyInstance(
                listenerCls.getClassLoader(),
                new Class<?>[]{ listenerCls },
                new InvocationHandler() {
                    @Override public Object invoke(Object proxy, Method m, Object[] args) throws Throwable {
                        String name = m.getName();
                        if ("onComputeInternalInsets".equals(name) && args != null && args.length == 1) {
                            Object info = args[0];
                            setTouchableInsets.invoke(info, TOUCHABLE_INSETS_REGION);
                            Region r = (Region) regionField.get(info);
                            if (r != null) r.set(touchRect);
                            return null;
                        }
                        if ("equals".equals(name)) return proxy == args[0];
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("toString".equals(name)) return "PetTouchRegionListener";
                        return null;
                    }
                });

            Method add = ViewTreeObserver.class.getMethod(
                "addOnComputeInternalInsetsListener", listenerCls);
            add.invoke(v.getViewTreeObserver(), listener);
            return true;
        } catch (Throwable t) {
            return false;
        }
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

        // 注意：不加 FLAG_NOT_TOUCHABLE。那会让系统把窗口当成 pass-through
        // 并压暗不透明度（螃蟹变半透明）。穿透交给 touchable region 处理。
        params = new WindowManager.LayoutParams(
            dp(CANVAS_W_DP), dp(CANVAS_H_DP),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = canvasX;
        params.y = canvasY;
        params.alpha = 1f;

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

        final float density = getResources().getDisplayMetrics().density;
        webView.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        /* regionMode 下窗口只在螃蟹范围内收触摸，能进到这里
                           就一定在螃蟹上。降级模式下才需要自己判一次。 */
                        if (!regionMode) {
                            float cx = e.getX() / density;
                            float cy = e.getY() / density;
                            if (cx < bodyX || cx > bodyX + bodyW || cy < bodyY || cy > bodyY + bodyH) {
                                isValidTouch = false;
                                return false;
                            }
                        }
                        isValidTouch = true;
                        initialX = canvasX;
                        initialY = canvasY;
                        initialTouchX = e.getRawX();
                        initialTouchY = e.getRawY();
                        touchStart = System.currentTimeMillis();
                        hasMoved = false;
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (!isValidTouch) return false;
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
                            moveWindow();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isValidTouch) return false;
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
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        isDragging = false;
                        return false;
                    default:
                        return false;
                }
            }
        });

        wm.addView(webView, params);
        regionMode = installTouchRegion(webView);
        updateTouchRect();
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
        if (webView != null) {
            try { wm.removeView(webView); } catch (Exception e) {}
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
