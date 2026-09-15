package com.deepseek.whalepet;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;

/**
 * 悬浮小鲸鱼：前台服务 + 两个 overlay 窗口（挂件本体 / 悬浮菜单）。
 * 行为：
 * - 尺寸 0.9~2.5（步进 0.1，共 17 档），缩放时以「鲸鱼所在的角」为固定点；
 * - 余额变化 → 弹气泡 + 数字滚动（延迟 0.3s）；
 * - 点小鲸鱼：气泡收起时刷新并弹出，气泡打开时再点则收起；
 * - 点气泡 → 顺序轮播台词（含峰谷与今日已用）；
 * - 拖拽吸附 + 左吸附镜像（只镜像立绘，文字保持正向）；
 * - 窗口可触摸区域收窄到可见部分，透明区不拦触摸。
 */
public class PetService extends Service implements WhaleView.Controller, WhaleMenuView.Listener {

    public static final String ACTION_START = "com.deepseek.whalepet.action.START";
    public static final String ACTION_STOP = "com.deepseek.whalepet.action.STOP";
    public static final String ACTION_REFRESH = "com.deepseek.whalepet.action.REFRESH";
    /** 设置页「保存并应用」：让运行中的挂件立刻重读配置。 */
    public static final String ACTION_CONFIG = "com.deepseek.whalepet.action.CONFIG";

    /** 供 UI 读取的运行状态。 */
    public static volatile boolean running = false;
    public static volatile String lastBalance = "";
    public static volatile String lastMessage = "未运行";

    private static final String CH_STATUS = "whale_pet_status";
    private static final String CH_ALERT = "whale_pet_alert";
    private static final int ID_FOREGROUND = 1001;
    private static final int ID_ALERT = 1002;
    private static final long ROLL_DELAY_MS = 300L;

    private WindowManager windowManager;
    private WhaleView petView;
    private WindowManager.LayoutParams petParams;

    private WhaleMenuView menuView;
    private WindowManager.LayoutParams menuParams;
    private boolean menuShown;

    private double shownBalance = Double.NaN;
    private String shownCurrency = "CNY";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            refresh(false);
            handler.postDelayed(this, Prefs.refreshMinutes(PetService.this) * 60000L);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createChannels();
        running = true;
        lastMessage = "待命中";
        startForegroundCompat(buildNotification(getString(R.string.notif_title)));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        showOverlay();
        handler.removeCallbacks(refreshRunnable);
        handler.postDelayed(refreshRunnable, Prefs.refreshMinutes(this) * 60000L);
        if (ACTION_CONFIG.equals(action)) {
            // 设置页点了「保存并应用」：重读配置，不重新查询余额
            applyConfig();
            return START_STICKY;
        }
        if (intent == null || ACTION_REFRESH.equals(action) || ACTION_START.equals(action)) {
            refresh(true);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        hideMenu();
        if (petView != null) {
            petView.release();
            removeView(petView);
        }
        petView = null;
        running = false;
        lastMessage = "未运行";
        SoundFx.release();
        super.onDestroy();
    }

    /**
     * 设置页点了「保存并应用」后调用：把新配置立刻套到正在运行的挂件上。
     * 以前这里只写 SharedPreferences、服务从不重读，所以设置看起来「不起作用」。
     */
    private void applyConfig() {
        if (petView == null) {
            return;
        }
        float want = Prefs.scale(this);
        if (Math.abs(want - petView.getScale()) > 0.001f) {
            onScale(want);
        }
        petView.setHideMenuButton(Prefs.hideMenu(this));
        petView.setTapAdvance(Prefs.tapAdvance(this));
        if (!Prefs.showBubble(this)) {
            petView.closeBubble();
        } else {
            pushState();
            openBubble();
        }
        if (menuView != null) {
            menuView.sync(petView.getScale(), Prefs.peakMode(this), Prefs.showBubble(this), Prefs.soundOn(this));
        }
        petView.invalidate();
    }

    private int overlayType() {
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
    }

    private void createChannels() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel status = new NotificationChannel(CH_STATUS,
                getString(R.string.notif_channel_status), NotificationManager.IMPORTANCE_LOW);
        status.setShowBadge(false);
        NotificationChannel alert = new NotificationChannel(CH_ALERT,
                getString(R.string.notif_channel_alert), NotificationManager.IMPORTANCE_HIGH);
        manager.createNotificationChannel(status);
        manager.createNotificationChannel(alert);
    }

    private PendingIntent pendingFlags(Intent intent) {
        return PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private Notification buildNotification(String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        return new Notification.Builder(this, CH_STATUS)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingFlags(intent))
                .build();
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID_FOREGROUND, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID_FOREGROUND, notification);
        }
    }

    private void updateForeground(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.notify(ID_FOREGROUND, buildNotification(text));
        } catch (Throwable ignored) {
        }
    }

    // ---- 挂件窗口 ----

    private void showOverlay() {
        if (petView != null) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        petView = new WhaleView(this, Prefs.scale(this));
        petView.setHideMenuButton(Prefs.hideMenu(this));
        petView.setTapAdvance(Prefs.tapAdvance(this));
        petView.setController(this);
        petParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        petParams.gravity = Gravity.TOP | Gravity.START;

        try {
            windowManager.addView(petView, petParams);
        } catch (Throwable t) {
            petView = null;
            stopSelf();
            return;
        }

        petView.post(new Runnable() {
            @Override
            public void run() {
                if (petView == null) {
                    return;
                }
                int w = Math.max(petView.getWidth(), 1);
                int h = Math.max(petView.getHeight(), 1);
                int screenW = screenWidth();
                int screenH = screenHeight();
                int x = Prefs.posX(PetService.this);
                int y = Prefs.posY(PetService.this);
                if (x < 0 || y < 0) {
                    // 原版默认锚点是右下角
                    x = Math.max(0, screenW - w);
                    y = Math.max(0, screenH - h);
                }
                petParams.x = clamp(x, 0, Math.max(0, screenW - w));
                petParams.y = clamp(y, 0, Math.max(0, screenH - h));
                updateView(petView, petParams);
                applyMirrorForPosition();
                petView.bounce();
                openBubble();
            }
        });
    }

    private int screenWidth() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private int screenHeight() {
        return getResources().getDisplayMetrics().heightPixels;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private void applyMirrorForPosition() {
        if (petView == null || petParams == null) {
            return;
        }
        int w = Math.max(petView.getWidth(), 1);
        boolean onLeft = petParams.x + w / 2 < screenWidth() / 2;
        petView.setMirrored(Prefs.mirrorLeft(this) && onLeft);
    }

    private void updateView(android.view.View view, WindowManager.LayoutParams params) {
        if (view == null || windowManager == null) {
            return;
        }
        try {
            windowManager.updateViewLayout(view, params);
        } catch (Throwable ignored) {
        }
    }

    private void removeView(android.view.View view) {
        if (view == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Throwable ignored) {
        }
    }

    // ---- 悬浮菜单 ----

    private void toggleMenu() {
        if (menuShown) {
            hideMenu();
        } else {
            showMenu();
        }
    }

    private void showMenu() {
        if (petView == null || windowManager == null) {
            return;
        }
        if (menuView == null) {
            menuView = new WhaleMenuView(this);
            menuView.setListener(this);
            menuParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType(),
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            menuParams.gravity = Gravity.TOP | Gravity.START;
        }
        menuView.sync(petView.getScale(), Prefs.peakMode(this), Prefs.showBubble(this), Prefs.soundOn(this));
        positionMenu();
        if (!menuShown) {
            try {
                windowManager.addView(menuView, menuParams);
                menuShown = true;
            } catch (Throwable ignored) {
                return;
            }
        } else {
            updateView(menuView, menuParams);
        }
    }

    private void hideMenu() {
        if (!menuShown || menuView == null) {
            return;
        }
        removeView(menuView);
        menuShown = false;
    }

    private void positionMenu() {
        if (menuView == null || petView == null) {
            return;
        }
        int menuW = Math.max(menuView.getMeasuredWidth(), dp(262f));
        int menuH = Math.max(menuView.getMeasuredHeight(), dp(184f));
        int screenW = screenWidth();
        int screenH = screenHeight();
        int margin = dp(6);
        int x = petParams.x + petView.getWidth() / 2 - menuW / 2;
        x = clamp(x, margin, Math.max(margin, screenW - menuW - margin));
        int y = petParams.y + Math.round(petView.getHeight() * 0.35f);
        if (y + menuH > screenH - margin) {
            y = Math.max(margin, screenH - menuH - margin);
        }
        menuParams.x = x;
        menuParams.y = y;
    }

    // ---- WhaleMenuView.Listener ----

    @Override
    public void onScale(float scale) {
        if (petView == null || petParams == null) {
            return;
        }
        int oldW = Math.max(petView.getWidth(), 1);
        int oldH = Math.max(petView.getHeight(), 1);
        boolean mirrored = petView.isMirrored();
        petView.setScale(scale);
        Prefs.setScale(this, scale);
        int newSize = petView.basePx();

        // 缩放时以鲸鱼所在的角为固定点（原版 setScale 的 fixed point 逻辑）
        if (mirrored) {
            petParams.x = petParams.x;
        } else {
            petParams.x = petParams.x + oldW - newSize;
        }
        petParams.y = petParams.y + oldH - newSize;
        petParams.x = clamp(petParams.x, 0, Math.max(0, screenWidth() - newSize));
        petParams.y = clamp(petParams.y, 0, Math.max(0, screenHeight() - newSize));
        petView.requestLayout();
        updateView(petView, petParams);
        petView.post(new Runnable() {
            @Override
            public void run() {
                positionMenu();
                if (menuShown && menuView != null) {
                    updateView(menuView, menuParams);
                }
            }
        });
        Prefs.setPos(this, petParams.x, petParams.y);
    }

    @Override
    public void onPeakMode(String mode) {
        Prefs.setPeakMode(this, mode);
        if (menuView != null) {
            menuView.sync(petView == null ? 1.5f : petView.getScale(), Prefs.peakMode(this), Prefs.showBubble(this), Prefs.soundOn(this));
        }
        pushState();
    }

    @Override
    public void onBubbleToggle(boolean on) {
        Prefs.setShowBubble(this, on);
        if (menuView != null) {
            menuView.sync(petView == null ? 1.5f : petView.getScale(), Prefs.peakMode(this), on, Prefs.soundOn(this));
        }
        if (!on && petView != null) {
            petView.closeBubble();
        } else {
            openBubble();
        }
    }

    @Override
    public void onSoundToggle(boolean on) {
        Prefs.setSoundOn(this, on);
        if (menuView != null) {
            menuView.sync(petView == null ? 1.5f : petView.getScale(),
                    Prefs.peakMode(this), Prefs.showBubble(this), on);
        }
    }

    @Override
    public void onDismiss() {
        hideMenu();
    }

    // ---- 余额 ----

    private void openBubble() {
        if (petView == null || !Prefs.showBubble(this)) {
            return;
        }
        pushState();
        petView.openBubble();
    }

    /** 把当前余额 / 今日已用 / 峰谷状态推给挂件。 */
    private void pushState() {
        if (petView == null) {
            return;
        }
        double usage = UsageLedger.todayUsage(this);
        boolean hasUsage = !Double.isNaN(shownBalance);
        petView.updateBalance(shownBalance, shownCurrency, usage, hasUsage,
                PeakPricing.isPeakNow(), Prefs.peakMode(this));
    }

    private void refresh(boolean userTriggered) {
        final String key = Prefs.apiKey(this);
        if (key.length() == 0) {
            lastMessage = "未配置 API Key";
            if (petView != null) {
                openBubble();
                petView.bubble().showError("未配置 API Key");
                petView.invalidate();
            }
            return;
        }
        if (userTriggered && petView != null) {
            openBubble();
            petView.bubble().showLoading();
            petView.invalidate();
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final BalanceClient.Result result = BalanceClient.fetch(key);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        applyResult(result, userTriggered);
                    }
                });
            }
        }, "whale-balance").start();
    }

    private void applyResult(BalanceClient.Result result, boolean userTriggered) {
        if (petView == null) {
            return;
        }
        if (!result.ok) {
            lastMessage = "查询失败";
            petView.shake();
            openBubble();
            petView.bubble().showError(result.message == null ? "获取失败" : result.message);
            petView.invalidate();
            updateForeground("查询失败：" + result.message);
            return;
        }

        double previous = shownBalance;
        String prevCurrency = shownCurrency;
        boolean changed = !Double.isNaN(previous)
                && (Math.abs(previous - result.amount) > 1e-9 || !prevCurrency.equalsIgnoreCase(result.currency));
        boolean currencyChanged = !Double.isNaN(previous) && !prevCurrency.equalsIgnoreCase(result.currency);

        shownBalance = result.amount;
        shownCurrency = result.currency;
        lastBalance = result.display();
        lastMessage = "余额 " + result.display();

        // 不管哪种模式，都先把余额观测记入「小鲸鱼记账」账本
        UsageLedger.record(this, result.amount, result.currency);

        final boolean low = result.amount < Prefs.threshold(this);
        final double rollFrom = Double.isNaN(previous) ? result.amount : previous;

        if (changed && !currencyChanged) {
            // 余额变化：先弹气泡并停在旧数字，0.3s 后再滚（原版时序）
            openBubble();
            petView.showBalanceContent();
            petView.bubble().holdAmount(rollFrom, shownCurrency);
            petView.invalidate();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (petView != null) {
                        petView.animateAmount(rollFrom, result.amount, shownCurrency);
                    }
                }
            }, ROLL_DELAY_MS);
        } else {
            // 数值没变 / 只是换了币种：只弹一下，内容强制回到余额视图
            petView.showBalanceContent();
            petView.bounce();
            openBubble();
            petView.showBalanceContent();
        }

        if (low) {
            notifyLowBalance(result.display());
        }
        updateForeground("余额 " + result.display());
    }

    private void notifyLowBalance(String balance) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification notification = new Notification.Builder(this, CH_ALERT)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.notif_alert_title))
                .setContentText(getString(R.string.notif_alert_body, balance))
                .setAutoCancel(true)
                .setContentIntent(pendingFlags(intent))
                .build();
        try {
            manager.notify(ID_ALERT, notification);
        } catch (Throwable ignored) {
        }
    }

    // ---- WhaleView.Controller ----

    @Override
    public void onPetTap() {
        // 气泡开着 -> 再点一次收回；收起状态 -> 刷新并弹出来
        if (petView != null && petView.bubble().isOpen()) {
            petView.closeBubble();
            return;
        }
        refresh(true);
    }

    @Override
    public void onPetLongPress() {
        toggleMenu();
    }

    @Override
    public void onPetMenu() {
        toggleMenu();
    }
    @Override
    public void onBubbleTap() {
        // 台词由挂件自己切换，这里只需保证菜单不遮挡
    }


    @Override
    public void onPetDrag(int dx, int dy) {
        if (petView == null || petParams == null) {
            return;
        }
        hideMenu();
        int w = Math.max(petView.getWidth(), 1);
        int h = Math.max(petView.getHeight(), 1);
        int screenW = screenWidth();
        int screenH = screenHeight();
        petParams.x = clamp(petParams.x + dx, 0, Math.max(0, screenW - w));
        petParams.y = clamp(petParams.y + dy, 0, Math.max(0, screenH - h));
        updateView(petView, petParams);
    }

    @Override
    public void onPetDragEnd() {
        if (petView == null || petParams == null) {
            return;
        }
        int w = Math.max(petView.getWidth(), 1);
        int h = Math.max(petView.getHeight(), 1);
        int screenW = screenWidth();
        int screenH = screenHeight();
        // 离边距离可自定义（0.3.0）
        int edge = dp(Prefs.snapEdgeDp(this));

        // 四边 1/4、3/4 吸附
        int cx = petParams.x + w / 2;
        if (cx < screenW / 4) {
            petParams.x = edge;
        } else if (cx > screenW * 3 / 4) {
            petParams.x = screenW - w - edge;
        }
        int cy = petParams.y + h / 2;
        if (cy < screenH / 4) {
            petParams.y = edge;
        } else if (cy > screenH * 3 / 4) {
            petParams.y = screenH - h - edge;
        }
        petParams.x = clamp(petParams.x, 0, Math.max(0, screenW - w));
        petParams.y = clamp(petParams.y, 0, Math.max(0, screenH - h));

        updateView(petView, petParams);
        applyMirrorForPosition();
        Prefs.setPos(this, petParams.x, petParams.y);
    }
}
