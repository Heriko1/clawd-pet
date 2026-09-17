package com.nora.pet;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
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
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.File;
import java.util.Calendar;
import java.util.Random;

/**
 * v3.1 — 甩飞回弹 + 自动巡逻 + 充电感知
 *
 * 两个 overlay 分工：
 *   视觉窗口 = WebView，永远是完整画布，FLAG_NOT_TOUCHABLE，只负责画。
 *   触摸窗口 = 透明空 View，大小随实测包围盒变化，只负责收手势。
 */
public class OverlayService extends Service {
    private static final String CHANNEL_ID = "pet_channel";
    private static final int NOTIF_ID = 1001;
    public static final String ACTION_STATE = "com.nora.pet.STATE_CHANGE";
    private static final long WHISPER_INTERVAL = 3600_000L;
    private static final String PET_DIR = "/sdcard/Download/clawd-pet/";
    private static final int CANVAS_W_DP = 150;
    private static final int CANVAS_H_DP = 185;

    private WindowManager wm;
    private WebView webView;
    private WindowManager.LayoutParams visualParams;
    private View touchView;
    private WindowManager.LayoutParams touchParams;

    private int canvasX = 20, canvasY = 220;
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

    /* --- Fling --- */
    private VelocityTracker velocityTracker;
    private ValueAnimator flingAnimator;
    private Runnable flingReturnRunnable;
    private boolean flingCancelled = false;
    private int preFlingX, preFlingY;
    private int screenW, screenH;
    private static final float FLING_THRESHOLD_PX = 2000f;

    /* --- Patrol --- */
    private ValueAnimator patrolAnimator;
    private long lastTouchTime = System.currentTimeMillis();
    private Runnable patrolRunnable;
    private boolean isPatrolling = false;
    private static final long PATROL_IDLE_MS = 180_000L;
    private static final long PATROL_INTERVAL_MS = 35_000L;
    private static final long PATROL_MAX_IDLE_MS = 1200_000L;

    /* --- Battery --- */
    private BroadcastReceiver batteryReceiver;

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
        @JavascriptInterface
        public void requestResize(boolean full) { }

        @JavascriptInterface
        public void reportGeo(final float wx, final float wy, final float ww, final float wh,
                              final float bx, final float by, final float bw, final float bh) {
            mainHandler.post(new Runnable() {
                @Override public void run() { applyGeo(bx, by, bw, bh); }
            });
        }
    }

    private void applyGeo(float bx, float by, float bw, float bh) {
        if (bw < 8 || bh < 8) return;
        if (bx == bodyX && by == bodyY && bw == bodyW && bh == bodyH) return;
        bodyX = bx; bodyY = by; bodyW = bw; bodyH = bh;
        if (isDragging) { pendingGeo = true; return; }
        syncTouchWindow();
    }

    private void syncTouchWindow() {
        if (touchView == null || touchParams == null) return;
        touchParams.x = canvasX + dpf(bodyX);
        touchParams.y = canvasY + dpf(bodyY);
        touchParams.width = Math.max(1, dpf(bodyW));
        touchParams.height = Math.max(1, dpf(bodyH));
        try { wm.updateViewLayout(touchView, touchParams); } catch (Exception e) {}
    }

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
        DisplayMetrics dm = getResources().getDisplayMetrics();
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Clawd Pet", NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(ch);
        startForeground(NOTIF_ID, buildNotification(getWhisper()));
        setupOverlay();
        registerStateReceiver();
        registerBatteryReceiver();
        startWhisperRotation();
        startPatrolTimer();
    }

    @Override public int onStartCommand(Intent i, int f, int s) { return START_STICKY; }

    private void registerStateReceiver() {
        stateReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                String state = intent.getStringExtra("state");
                final String text = intent.getStringExtra("text");
                if (webView != null) {
                    if (state != null) {
                        cancelPatrol();
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
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
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
                        cancelFling();
                        cancelPatrol();
                        lastTouchTime = System.currentTimeMillis();
                        if (velocityTracker == null) {
                            velocityTracker = VelocityTracker.obtain();
                        } else {
                            velocityTracker.clear();
                        }
                        velocityTracker.addMovement(e);
                        initialX = canvasX;
                        initialY = canvasY;
                        initialTouchX = e.getRawX();
                        initialTouchY = e.getRawY();
                        touchStart = System.currentTimeMillis();
                        hasMoved = false;
                        isDragging = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (velocityTracker != null) velocityTracker.addMovement(e);
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
                        lastTouchTime = System.currentTimeMillis();
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
                            boolean flung = false;
                            if (velocityTracker != null) {
                                velocityTracker.computeCurrentVelocity(1000);
                                float vx = velocityTracker.getXVelocity();
                                float vy = velocityTracker.getYVelocity();
                                float speed = (float) Math.sqrt(vx * vx + vy * vy);
                                if (speed > FLING_THRESHOLD_PX) {
                                    startFling(vx, vy);
                                    flung = true;
                                }
                            }
                            if (!flung) {
                                js("window.petEngine && petEngine.onDragEnd()");
                            }
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

    /* ---------- Fling ---------- */

    private void startFling(float vx, float vy) {
        flingCancelled = false;
        preFlingX = canvasX;
        preFlingY = canvasY;
        js("window.petEngine && petEngine.onFling()");

        float speed = (float) Math.sqrt(vx * vx + vy * vy);
        float nx = vx / speed, ny = vy / speed;
        int dist = (int)(Math.max(screenW, screenH) * 0.6f);
        int targetX = canvasX + (int)(nx * dist);
        int targetY = canvasY + (int)(ny * dist);
        targetX = Math.max(-dp(CANVAS_W_DP), Math.min(screenW, targetX));
        targetY = Math.max(-dp(CANVAS_H_DP), Math.min(screenH, targetY));

        final int sx = canvasX, sy = canvasY;
        final int ex = targetX, ey = targetY;

        flingAnimator = ValueAnimator.ofFloat(0f, 1f);
        flingAnimator.setDuration(350);
        flingAnimator.setInterpolator(new DecelerateInterpolator(2f));
        flingAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                canvasX = sx + (int)((ex - sx) * t);
                canvasY = sy + (int)((ey - sy) * t);
                moveWindows();
            }
        });
        flingAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                if (!flingCancelled) flingReturn();
            }
        });
        flingAnimator.start();
    }

    private void flingReturn() {
        js("window.petEngine && petEngine.onFlingReturn()");
        final int sx = canvasX, sy = canvasY;
        final int ex = preFlingX, ey = preFlingY;
        flingReturnRunnable = new Runnable() {
            @Override public void run() {
                flingReturnRunnable = null;
                if (flingCancelled) return;
                flingAnimator = ValueAnimator.ofFloat(0f, 1f);
                flingAnimator.setDuration(1500);
                flingAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
                flingAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    @Override public void onAnimationUpdate(ValueAnimator a) {
                        float t = (float) a.getAnimatedValue();
                        canvasX = sx + (int)((ex - sx) * t);
                        canvasY = sy + (int)((ey - sy) * t);
                        moveWindows();
                    }
                });
                flingAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        if (!flingCancelled) {
                            flingAnimator = null;
                            js("window.petEngine && petEngine.onFlingDone()");
                        }
                    }
                });
                flingAnimator.start();
            }
        };
        mainHandler.postDelayed(flingReturnRunnable, 800);
    }

    private void cancelFling() {
        flingCancelled = true;
        if (flingAnimator != null) { flingAnimator.cancel(); flingAnimator = null; }
        if (flingReturnRunnable != null) {
            mainHandler.removeCallbacks(flingReturnRunnable);
            flingReturnRunnable = null;
        }
    }

    /* ---------- Patrol ---------- */

    private void startPatrolTimer() {
        patrolRunnable = new Runnable() {
            @Override public void run() {
                long idle = System.currentTimeMillis() - lastTouchTime;
                if (idle >= PATROL_IDLE_MS && idle < PATROL_MAX_IDLE_MS
                        && !isDragging && flingAnimator == null && !isPatrolling) {
                    doPatrolStep();
                }
                mainHandler.postDelayed(this, PATROL_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(patrolRunnable, PATROL_INTERVAL_MS);
    }

    private void doPatrolStep() {
        int range = dp(60);
        int targetX = canvasX + random.nextInt(range * 2 + 1) - range;
        int targetY = canvasY + random.nextInt(range * 2 + 1) - range;
        targetX = Math.max(0, Math.min(screenW - dp(CANVAS_W_DP / 2), targetX));
        targetY = Math.max(dp(40), Math.min(screenH - dp(CANVAS_H_DP), targetY));
        int ddx = targetX - canvasX, ddy = targetY - canvasY;
        if (Math.sqrt(ddx * ddx + ddy * ddy) < dp(15)) return;

        final int sx = canvasX, sy = canvasY, ex = targetX, ey = targetY;
        isPatrolling = true;
        js("show('crabwalk')");
        js("resetLonely()");

        patrolAnimator = ValueAnimator.ofFloat(0f, 1f);
        patrolAnimator.setDuration(2500 + random.nextInt(2000));
        patrolAnimator.setInterpolator(new LinearInterpolator());
        patrolAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override public void onAnimationUpdate(ValueAnimator a) {
                float t = (float) a.getAnimatedValue();
                canvasX = sx + (int)((ex - sx) * t);
                canvasY = sy + (int)((ey - sy) * t);
                moveWindows();
            }
        });
        patrolAnimator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator a) {
                isPatrolling = false;
                patrolAnimator = null;
                js("show('idle')");
            }
        });
        patrolAnimator.start();
    }

    private void cancelPatrol() {
        isPatrolling = false;
        if (patrolAnimator != null) { patrolAnimator.cancel(); patrolAnimator = null; }
    }

    /* ---------- Battery ---------- */

    private void registerBatteryReceiver() {
        batteryReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, final Intent intent) {
                final String action = intent.getAction();
                mainHandler.post(new Runnable() {
                    @Override public void run() {
                        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                            js("show('happy')");
                            js("showBubble('\u5145\u7535\u4e2d~', 3000, 'happy')");
                        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                            js("showBubble('\u7535\u62d4\u4e86\u2026', 2000)");
                        } else if (Intent.ACTION_BATTERY_LOW.equals(action)) {
                            js("show('low-battery')");
                            js("showBubble('\u5feb\u6ca1\u7535\u4e86\u2026', 5000, 'whisper')");
                        }
                    }
                });
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(Intent.ACTION_BATTERY_LOW);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(batteryReceiver, f, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(batteryReceiver, f);
        }
    }

    /* ---------- Whispers ---------- */

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

    /* ---------- Helpers ---------- */

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
        if (patrolRunnable != null) mainHandler.removeCallbacks(patrolRunnable);
        cancelFling();
        cancelPatrol();
        if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
        if (batteryReceiver != null) {
            try { unregisterReceiver(batteryReceiver); } catch (Exception e) {}
            batteryReceiver = null;
        }
        if (whisperRunnable != null) mainHandler.removeCallbacks(whisperRunnable);
        if (stateReceiver != null) {
            try { unregisterReceiver(stateReceiver); } catch (Exception e) {}
            stateReceiver = null;
        }
        if (touchView != null) { try { wm.removeView(touchView); } catch (Exception e) {} touchView = null; }
        if (webView != null) {
            try { wm.removeView(webView); } catch (Exception e) {}
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
