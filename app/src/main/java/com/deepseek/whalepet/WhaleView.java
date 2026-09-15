package com.deepseek.whalepet;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * 桌宠本体。布局完全对齐 MeteorNOX 原版：
 * <p>
 * - 整个挂件是一个正方形容器（边长 base）；
 * - 立绘只占右下 59.45% x 59.45%（所以气泡天然比人物大一圈）；
 * - 气泡 SVG 铺满容器顶部（viewBox 1026x700），默认隐藏；
 * - 按压 Q 弹：scaleY(0.88) scaleX(1.05)，锚点在人物所在的底角；
 * - 贴左镜像：只镜像立绘，气泡与文字始终保持正向；
 * - 命中检测走 alpha：透明像素不响应触摸，且立绘优先于气泡（尾巴不抢点击）。
 */
public class WhaleView extends View {

    /** 由服务实现的回调。 */
    public interface Controller {
        void onPetTap();

        void onPetLongPress();

        void onPetMenu();

        void onPetDrag(int dx, int dy);

        void onPetDragEnd();

        void onBubbleTap();
    }

    /** 原版几何常量。 */
    private static final float IMG_RATIO = 0.5945f;
    private static final float BASE_MIN_DP = 110f;
    private static final float BASE_UNIT_DP = 150f;
    private static final float BASE_MAX_DP = 520f;

    /** 原版按压变换 SQUISH = scaleY(0.88) scaleX(1.05)。 */
    private static final float SQUISH_Y = 0.88f;
    private static final float SQUISH_X = 1.05f;
    private static final float BREATH_AMP = 0.012f;
    /** 松手回弹的过冲比例（相对当前形变量，方向与按压一致）。 */
    private static final float SPRING_OVERSHOOT = 0.4f;

    private static final long LONG_PRESS_MS = 520L;
    private static final long PRESS_DOWN_MS = 120L;
    private static final long PRESS_UP_MS = 280L;
    private static final long FLIP_MS = 260L;
    private static final long BUBBLE_MS = 5000L;
    private static final int BREATH_TICK_MS = 48;

    /** 连戳生气：3 秒窗口内 5 次 → 生气台词 + 5 秒冷却（借鉴 whale_v151）。 */
    private static final long COMBO_WINDOW_MS = 3000L;
    private static final int COMBO_TAPS = 5;
    private static final long COMBO_COOLDOWN_MS = 5000L;
    /** 连戳生气气泡的停留时长：比普通气泡久，方便看清它闹脾气。 */
    private static final long COMBO_HOLD_MS = 10000L;
    /** 平时自弹对话间隔：空闲 30~45 秒随机。 */
    private static final long IDLE_MIN_MS = 30000L;
    private static final long IDLE_MAX_MS = 45000L;

    private static final int HIT_NONE = 0;
    private static final int HIT_WHALE = 1;
    private static final int HIT_BUBBLE = 2;
    private static final int HIT_MENU = 3;

    private final Bitmap art;
    private final Paint artPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint menuBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint menuLinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final RectF menuRect = new RectF();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final BubblePainter bubble = new BubblePainter();
    private final SoundFx sfx;
    private final int touchSlop;

    private Controller controller;
    private float scale = 1.5f;
    private int basePx;

    private float pressScaleY = 1f;
    private float pressScaleX = 1f;
    private float flip;
    private float breathPhase;
    private float shakeX;
    private ValueAnimator pressAnim;
    private ValueAnimator flipAnim;
    private ValueAnimator shakeAnim;

    private float downRawX;
    private float downRawY;
    private float lastRawX;
    private float lastRawY;
    private boolean dragging;
    private boolean longPressed;
    private int downHit = HIT_NONE;

    // 连戳生气 / 自弹对话
    private final java.util.ArrayList<Long> comboTapTimes = new java.util.ArrayList<>();
    private long comboCooldownUntil;
    private long comboQuoteUntil;

    // ---- 非矩形可触摸区域（隐藏 API：ViewTreeObserver.InternalInsetsInfo） ----
    private static final String TAG_W = "whale-touch";
    private final Region touchRegion = new Region();
    private volatile boolean regionDirty = true;
    private boolean insetsHooked;
    private boolean hideMenuButton;
    /** 点按角色推进泡泡队列（0.3.0）；false 时保持原「点一下刷新/收回」行为。 */
    private boolean tapAdvance;

    private final Runnable breathRunnable = new Runnable() {
        @Override
        public void run() {
            breathPhase += 0.13f;
            if (breathPhase > (float) (Math.PI * 2)) {
                breathPhase -= (float) (Math.PI * 2);
            }
            invalidate();
            handler.postDelayed(this, BREATH_TICK_MS);
        }
    };

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (dragging || controller == null) {
                return;
            }
            longPressed = true;
            controller.onPetLongPress();
        }
    };

    private final Runnable bubbleHideRunnable = new Runnable() {
        @Override
        public void run() {
            bubble.close();
            invalidate();
            notifyGeometry();
        }
    };

    /** 平时自弹：空闲 30~45 秒随机从台词池冒一句。 */
    private final Runnable idleRunnable = new Runnable() {
        @Override
        public void run() {
            if (controller != null && !bubble.isOpen() && !dragging
                    && System.currentTimeMillis() >= comboQuoteUntil) {
                bubble.openWith(bubble.randomIdleLine());
                restartBubbleTimer();
                invalidate();
                notifyGeometry();
            }
            handler.postDelayed(this, nextIdleDelay());
        }
    };

    public WhaleView(Context context, float scale) {
        super(context);
        this.scale = Prefs.clampScale(scale);
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        this.sfx = SoundFx.get(context);
        menuBgPaint.setColor(0xD9203170);
        menuLinePaint.setColor(0xFFFFFFFF);
        menuLinePaint.setStyle(Paint.Style.STROKE);
        menuLinePaint.setStrokeWidth(dp(2f));
        menuLinePaint.setStrokeCap(Paint.Cap.ROUND);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        // 立绘放在 drawable-nodpi，且显式禁止密度缩放，保证拿到 610x610 原始像素
        opts.inScaled = false;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        art = BitmapFactory.decodeResource(getResources(), R.drawable.whale_ds, opts);
    }

    public void setController(Controller value) {
        this.controller = value;
    }

    private float dp(float value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    // ---- 尺寸 ----

    public float getScale() {
        return scale;
    }

    public void setScale(float value) {
        float next = Prefs.clampScale(value);
        if (Math.abs(next - scale) < 0.001f) {
            return;
        }
        scale = next;
        requestLayout();
        invalidate();
        notifyGeometry();
    }

    /** 可见范围可能变了：标记可触摸区域需要重算，并触发一次遍历让系统重新读取。 */
    private void notifyGeometry() {
        regionDirty = true;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        notifyGeometry();
    }

    /**
     * 让窗口的「可触摸区域」收窄到真正可见的部分（立绘轮廓 + 气泡 + 按钮），
     * 这样容器里的透明区域就能点到下面的应用。
     * <p>
     * 走的是隐藏 API {@code ViewTreeObserver.InternalInsetsInfo}，· 先用
     * {@code VMRuntime.setHiddenApiExemptions} 自豁免；拿不到就安静降级 ——
     * 窗口退回整块矩形可点，不影响其它功能。
     */
    private void installTouchableRegion() {
        if (insetsHooked) {
            return;
        }
        insetsHooked = true;
        try {
            exemptHiddenApi();
            Class<?> infoCls = Class.forName("android.view.ViewTreeObserver$InternalInsetsInfo");
            final Field regionField = infoCls.getField("touchableRegion");
            final Method setTouchableInsets = infoCls.getMethod("setTouchableInsets", int.class);
            final int insetsRegion = infoCls.getField("TOUCHABLE_INSETS_REGION").getInt(null);
            Class<?> listenerCls = Class.forName("android.view.ViewTreeObserver$OnComputeInternalInsetsListener");
            Object listener = Proxy.newProxyInstance(listenerCls.getClassLoader(),
                    new Class<?>[]{listenerCls},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onComputeInternalInsets".equals(method.getName())
                                    && args != null && args.length == 1) {
                                try {
                                    Region r = (Region) regionField.get(args[0]);
                                    if (r != null) {
                                        if (regionDirty) {
                                            buildTouchRegion(touchRegion);
                                            regionDirty = false;
                                        }
                                        r.set(touchRegion);
                                        setTouchableInsets.invoke(args[0], insetsRegion);
                                    }
                                } catch (Throwable ignored) {
                                }
                            }
                            return null;
                        }
                    });
            Method add = ViewTreeObserver.class.getMethod(
                    "addOnComputeInternalInsetsListener", listenerCls);
            add.invoke(getViewTreeObserver(), listener);
            Log.i(TAG_W, "非矩形触摸区域已挂载");
        } catch (Throwable t) {
            Log.w(TAG_W, "非矩形触摸区域不可用，退回整块矩形: " + t);
        }
    }

    /** 尝试关掉本进程的隐藏 API 拦截（失败就让调用方照常降级）。 */
    private static void exemptHiddenApi() {
        try {
            Class<?> vmRuntime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = vmRuntime.getDeclaredMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);
            Method setExemptions = vmRuntime.getDeclaredMethod("setHiddenApiExemptions", String[].class);
            setExemptions.invoke(runtime, (Object) new String[]{"L"});
            Log.i(TAG_W, "hiddenapi 自豁免成功");
        } catch (Throwable t) {
            Log.w(TAG_W, "hiddenapi 自豁免失败: " + t);
        }
    }

    /**
     * 把当前真正可见的区域（立绘的 alpha 轮廓 + 气泡 + 按钮）算成 Region。
     */
    public void buildTouchRegion(Region out) {
        out.setEmpty();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        layoutMenu(w, h);
        float left = imgLeft(w);
        float top = imgTop(h);
        float size = imgSize(w);
        final int cells = 30;
        float cw = w / (float) cells;
        if (art != null && !art.isRecycled()) {
            for (int gy = 0; gy < cells; gy++) {
                for (int gx = 0; gx < cells; gx++) {
                    float sx = (gx + 0.5f) * cw;
                    float sy = (gy + 0.5f) * cw;
                    float ax = isMirrored() ? (w - sx) : sx;
                    if (ax < left || ax > left + size || sy < top || sy > top + size) {
                        continue;
                    }
                    float fu = (ax - left) / size;
                    float fv = (sy - top) / size;
                    int ix = Math.min(art.getWidth() - 1, Math.max(0, (int) (fu * art.getWidth())));
                    int iy = Math.min(art.getHeight() - 1, Math.max(0, (int) (fv * art.getHeight())));
                    if ((art.getPixel(ix, iy) >>> 24) > 10) {
                        out.op(Math.round(gx * cw), Math.round(gy * cw),
                                Math.round((gx + 1) * cw), Math.round((gy + 1) * cw), Region.Op.UNION);
                    }
                }
            }
        } else {
            out.op(Math.round(left), Math.round(top), Math.round(left + size), Math.round(top + size), Region.Op.UNION);
        }
        if (bubble.isOpen()) {
            bubble.addHitRegion(out, w, isMirrored());
        }
        if (!hideMenuButton && menuRect.width() > 0f) {
            out.op(Math.round(menuRect.left) - 2, Math.round(menuRect.top) - 2,
                    Math.round(menuRect.right) + 2, Math.round(menuRect.bottom) + 2, Region.Op.UNION);
        }
    }

    /** 当前容器边长。 */
    public int basePx() {
        return Math.max(1, Math.round(clamp(dp(BASE_MIN_DP), dp(BASE_UNIT_DP) * scale, dp(BASE_MAX_DP))));
    }

    private static float clamp(float lo, float v, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        installTouchableRegion();
        handler.removeCallbacks(breathRunnable);
        handler.postDelayed(breathRunnable, BREATH_TICK_MS);
        handler.removeCallbacks(idleRunnable);
        handler.postDelayed(idleRunnable, nextIdleDelay());
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(breathRunnable);
        handler.removeCallbacks(idleRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        basePx = basePx();
        setMeasuredDimension(basePx, basePx);
    }

    // ---- 外部状态 ----

    public BubblePainter bubble() {
        return bubble;
    }

    public void updateBalance(double amount, String currency, double todayUsage, boolean hasUsage,
                              boolean peak, String peakMode) {
        bubble.updateState(amount, currency, todayUsage, hasUsage, peak, peakMode);
        if (!bubble.isOpen()) {
            bubble.showDefaultLines();
        }
        invalidate();
    }

    /** 强制把气泡内容切回「余额」视图（结果回来时用，避免停留在随机台词/加载中）。 */
    public void showBalanceContent() {
        if (System.currentTimeMillis() < comboQuoteUntil) {
            return;   // 生气台词停留期内不被余额覆盖
        }
        bubble.showDefaultLines();
        invalidate();
    }

    /** 弹气泡（默认内容），5 秒后自动收起。 */
    public void openBubble() {
        if (System.currentTimeMillis() < comboQuoteUntil) {
            return;   // 生气台词停留期内不被余额气泡打断
        }
        bubble.open();
        restartBubbleTimer();
        invalidate();
        notifyGeometry();
    }

    public void closeBubble() {
        handler.removeCallbacks(bubbleHideRunnable);
        bubble.close();
        invalidate();
        notifyGeometry();
    }

    private void restartBubbleTimer() {
        restartBubbleTimer(BUBBLE_MS);
    }

    /** 指定停留时长地重置气泡自动收起（生气气泡用更长的 COMBO_HOLD_MS）。 */
    private void restartBubbleTimer(long ms) {
        handler.removeCallbacks(bubbleHideRunnable);
        handler.postDelayed(bubbleHideRunnable, ms);
    }

    /** 余额变化联动：滚数字。 */
    public void animateAmount(double from, double to, String currency) {
        if (System.currentTimeMillis() < comboQuoteUntil) {
            return;   // 生气台词停留期内不切回余额
        }
        bubble.animateAmount(from, to, currency, 700L);
        invalidate();
    }

    public boolean isMirrored() {
        return flip > 0.5f;
    }

    /** 是否隐藏「三条杠」菜单按钮（隐藏后长按鲸鱼依旧能打开菜单）。 */
    public boolean isMenuButtonHidden() {
        return hideMenuButton;
    }

    public void setHideMenuButton(boolean hidden) {
        if (hideMenuButton == hidden) {
            return;
        }
        hideMenuButton = hidden;
        invalidate();
        notifyGeometry();
    }

    /** 点按角色推进泡泡队列开关（0.3.0）。 */
    public void setTapAdvance(boolean on) {
        tapAdvance = on;
    }

    public void setMirrored(boolean mirrored) {
        float target = mirrored ? 1f : 0f;
        if (Math.abs(flip - target) < 0.01f) {
            return;
        }
        if (flipAnim != null) {
            flipAnim.cancel();
        }
        flipAnim = ValueAnimator.ofFloat(flip, target);
        flipAnim.setDuration(FLIP_MS);
        flipAnim.setInterpolator(new DecelerateInterpolator());
        flipAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                flip = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        flipAnim.start();
        // 动画结束后按新的镜像状态重算可触摸区域
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                notifyGeometry();
            }
        }, FLIP_MS + 40L);
    }

    /**
     * 查询成功时的小弹跳。只在没有任何形变动画时生效，
     * 避免和松手回弹叠在一起变成「往回缩两次」。
     */
    public void bounce() {
        if (pressAnim != null && pressAnim.isRunning()) {
            return;
        }
        if (pressAnim != null) {
            pressAnim.cancel();
        }
        pressAnim = ValueAnimator.ofFloat(0f, 1f);
        pressAnim.setDuration(300L);
        pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                // 纵向单程弹一下，横向不动
                float v = t < 0.4f
                        ? lerp(1f, 0.955f, t / 0.4f)
                        : lerp(0.955f, 1f, (t - 0.4f) / 0.6f);
                pressScaleY = v;
                pressScaleX = 1f;
                invalidate();
            }
        });
        pressAnim.start();
    }

    /** 出错左右抖两下。 */
    public void shake() {
        if (shakeAnim != null) {
            shakeAnim.cancel();
        }
        shakeAnim = ValueAnimator.ofFloat(0f, 1f);
        shakeAnim.setDuration(420L);
        shakeAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                shakeX = (float) Math.sin(t * Math.PI * 6.0) * dp(4f) * (1f - t);
                invalidate();
            }
        });
        shakeAnim.start();
    }

    private void pressDown() {
        animatePressScale(SQUISH_X, SQUISH_Y, PRESS_DOWN_MS);
    }

    /**
     * 松手：单程弹簧，X 从被压宽继续收窄一点再回正，Y 从被压扁继续拉长一点再回正。
     * 两个方向都朝着按压的反方向过冲一次，不会出现「先回 1.0、再整体缩一圈」的二次反向。
     */
    private void pressUp() {
        if (pressAnim != null) {
            pressAnim.cancel();
        }
        final float fromX = pressScaleX;
        final float fromY = pressScaleY;
        final float overX = 1f + (fromX - 1f) * -SPRING_OVERSHOOT;
        final float overY = 1f + (fromY - 1f) * -SPRING_OVERSHOOT;
        pressAnim = ValueAnimator.ofFloat(0f, 1f);
        pressAnim.setDuration(PRESS_UP_MS);
        pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                float k = 0.42f;
                pressScaleX = t < k ? lerp(fromX, overX, t / k) : lerp(overX, 1f, (t - k) / (1f - k));
                pressScaleY = t < k ? lerp(fromY, overY, t / k) : lerp(overY, 1f, (t - k) / (1f - k));
                invalidate();
            }
        });
        pressAnim.start();
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private void animatePressScale(float targetX, float targetY, long duration) {
        if (pressAnim != null) {
            pressAnim.cancel();
        }
        final float fromX = pressScaleX;
        final float fromY = pressScaleY;
        pressAnim = ValueAnimator.ofFloat(0f, 1f);
        pressAnim.setDuration(duration);
        pressAnim.setInterpolator(new DecelerateInterpolator());
        pressAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                float t = (Float) animation.getAnimatedValue();
                pressScaleX = lerp(fromX, targetX, t);
                pressScaleY = lerp(fromY, targetY, t);
                invalidate();
            }
        });
        pressAnim.start();
    }

    // ---- 几何 ----

    private float imgLeft(float w) {
        return w * (1f - IMG_RATIO);
    }

    private float imgTop(float h) {
        return h * (1f - IMG_RATIO);
    }

    private float imgSize(float w) {
        return w * IMG_RATIO;
    }

    /** 汉堡按钮（不参与镜像，锚在人物头部右上）。 */
    private void layoutMenu(float w, float h) {
        if (hideMenuButton) {
            menuRect.setEmpty();
            return;
        }
        float size = Math.max(dp(20f), w * 0.07f);
        float y = imgTop(h) - size - dp(2f);
        if (y < dp(2f)) {
            y = dp(2f);
        }
        float x = isMirrored() ? dp(2f) : w - size - dp(2f);
        menuRect.set(x, y, x + size, y + size);
    }

    // ---- 绘制 ----

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        layoutMenu(w, h);

        // ---- 立绘层：镜像 + 呼吸 + 按压 ----
        canvas.save();
        float mirror = 1f - 2f * flip;
        if (Math.abs(mirror) < 0.02f) {
            mirror = mirror < 0f ? -0.02f : 0.02f;
        }
        // 先镜像（贴左时整体翻到左侧），再在「本地坐标」里缩放
        canvas.scale(mirror, 1f, w / 2f, h / 2f);
        float breath = 1f + BREATH_AMP * (float) Math.sin(breathPhase);
        // 锚点固定在人物所在的那个底角（本地坐标恒为右下角）：
        // 缩放时外侧边和底边坐标不变，所以永远不会顶出容器。
        canvas.scale(breath * pressScaleX, breath * pressScaleY, w, h);
        if (shakeX != 0f) {
            canvas.translate(shakeX, 0f);
        }

        if (art != null && !art.isRecycled()) {
            float left = imgLeft(w);
            float top = imgTop(h);
            float size = imgSize(w);
            dst.set(left, top, left + size, top + size);
            canvas.drawBitmap(art, null, dst, artPaint);
        }
        canvas.restore();

        // ---- 气泡层：不跟随镜像、不跟随按压，文字永远正向且不会出框 ----
        bubble.draw(canvas, w, mirror);

        // 汉堡按钮不跟随镜像，固定在人物头部右上侧（可在设置里隐藏）
        if (!hideMenuButton) {
            float size = menuRect.width();
            canvas.drawRoundRect(menuRect, size * 0.23f, size * 0.23f, menuBgPaint);
            float left = menuRect.left + size * 0.26f;
            float right = menuRect.right - size * 0.26f;
            for (int i = 0; i < 3; i++) {
                float y = menuRect.top + size * 0.31f + i * size * 0.19f;
                canvas.drawLine(left, y, right, y, menuLinePaint);
            }
        }

        if (bubble.tick()) {
            postInvalidateOnAnimation();
        }
    }

    // ---- 命中检测 ----

    private int hitTest(float x, float y) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return HIT_NONE;
        }
        // 按钮不参与镜像
        if (!hideMenuButton && menuRect.contains(x, y)) {
            return HIT_MENU;
        }

        // 立绘优先：先把屏幕坐标换算回「未镜像」坐标再做 alpha 采样。
        // 这样气泡的两个小尾巴压到人物身上时也不会抢走点击。
        float ax = isMirrored() ? (w - x) : x;
        float left = imgLeft(w);
        float top = imgTop(h);
        float size = imgSize(w);
        if (ax >= left && ax <= left + size && y >= top && y <= top + size) {
            if (art == null || art.isRecycled()) {
                return HIT_WHALE;
            }
            float fu = (ax - left) / size;
            float fv = (y - top) / size;
            int px = Math.min(art.getWidth() - 1, Math.max(0, (int) (fu * art.getWidth())));
            int py = Math.min(art.getHeight() - 1, Math.max(0, (int) (fv * art.getHeight())));
            if ((art.getPixel(px, py) >>> 24) > 10) {
                return HIT_WHALE;
            }
        }

        // 气泡（只在打开时可点；形状镜像所以尾巴方向跟着人物，文字仍正向）
        if (bubble.isOpen() && bubble.hit(x, y, w, isMirrored())) {
            return HIT_BUBBLE;
        }
        return HIT_NONE;
    }

    public boolean isOnPet(float x, float y) {
        return hitTest(x, y) == HIT_WHALE;
    }

    /** 服务销毁时释放定时器与位图。 */
    public void release() {
        handler.removeCallbacksAndMessages(null);
        if (pressAnim != null) {
            pressAnim.cancel();
        }
        if (flipAnim != null) {
            flipAnim.cancel();
        }
        if (shakeAnim != null) {
            shakeAnim.cancel();
        }
        controller = null;
    }

    /** 3 秒窗口内点满 5 次 → 返回一句生气台词；否则 null。 */
    private String registerComboTap() {
        long now = System.currentTimeMillis();
        comboTapTimes.add(now);
        while (!comboTapTimes.isEmpty() && now - comboTapTimes.get(0) > COMBO_WINDOW_MS) {
            comboTapTimes.remove(0);
        }
        if (comboTapTimes.size() >= COMBO_TAPS && now >= comboCooldownUntil) {
            comboCooldownUntil = now + COMBO_COOLDOWN_MS;
            comboTapTimes.clear();
            return bubble.randomComboLine();
        }
        return null;
    }

    /** 自弹间隔：30~45 秒随机。 */
    private long nextIdleDelay() {
        return IDLE_MIN_MS + (long) (Math.random() * (IDLE_MAX_MS - IDLE_MIN_MS + 1));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                downHit = hitTest(event.getX(), event.getY());
                if (downHit == HIT_NONE) {
                    return false;   // 透明区不响应
                }
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                lastRawX = downRawX;
                lastRawY = downRawY;
                dragging = false;
                longPressed = false;
                if (downHit == HIT_WHALE) {
                    pressDown();
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float rawX = event.getRawX();
                float rawY = event.getRawY();
                if (downHit == HIT_WHALE) {
                    if (!dragging
                            && (Math.abs(rawX - downRawX) > touchSlop || Math.abs(rawY - downRawY) > touchSlop)) {
                        dragging = true;
                        handler.removeCallbacks(longPressRunnable);
                        pressUp();
                        closeBubble();
                    }
                    if (dragging) {
                        int dx = Math.round(rawX - lastRawX);
                        int dy = Math.round(rawY - lastRawY);
                        lastRawX = rawX;
                        lastRawY = rawY;
                        if (controller != null) {
                            controller.onPetDrag(dx, dy);
                        }
                    }
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                handler.removeCallbacks(longPressRunnable);
                int hit = downHit;
                downHit = HIT_NONE;
                if (hit == HIT_WHALE) {
                    pressUp();
                }
                if (dragging) {
                    dragging = false;
                    if (controller != null) {
                        controller.onPetDragEnd();
                    }
                    return true;
                }
                if (longPressed) {
                    longPressed = false;
                    return true;
                }
                if (controller == null) {
                    return true;
                }
                if (hit == HIT_MENU) {
                    controller.onPetMenu();
                } else if (hit == HIT_BUBBLE) {
                    // 点气泡：换下一段随机台词，并重置自动关闭
                    if (System.currentTimeMillis() < comboQuoteUntil) {
                        // 生气台词停留期内：点气泡不换词，只延长停留
                        comboQuoteUntil = System.currentTimeMillis() + COMBO_HOLD_MS;
                        restartBubbleTimer(COMBO_HOLD_MS);
                    } else {
                        sfx.pet();
                        bubble.showRandomLines();
                        restartBubbleTimer();
                        invalidate();
                        controller.onBubbleTap();
                    }
                } else if (hit == HIT_WHALE) {
                    String combo = registerComboTap();
                    if (combo != null) {
                        comboQuoteUntil = System.currentTimeMillis() + COMBO_HOLD_MS;
                        sfx.combo();
                        bubble.openWith(combo);
                        restartBubbleTimer(COMBO_HOLD_MS);
                        invalidate();
                        notifyGeometry();
                    } else if (System.currentTimeMillis() < comboQuoteUntil) {
                        // 生气台词停留期内：继续点击不收回，并把停留时间往后顺延
                        comboQuoteUntil = System.currentTimeMillis() + COMBO_HOLD_MS;
                        restartBubbleTimer(COMBO_HOLD_MS);
                    } else if (tapAdvance) {
                        // 0.3.0：点按角色推进泡泡队列（不再走「刷新/收回」）
                        sfx.pet();
                        boolean closed = bubble.advanceQueue();
                        if (closed) {
                            handler.removeCallbacks(bubbleHideRunnable);
                        } else {
                            restartBubbleTimer();
                        }
                        invalidate();
                        notifyGeometry();
                    } else {
                        sfx.pet();
                        controller.onPetTap();
                    }
                }
                return true;
            }
            default:
                return super.onTouchEvent(event);
        }
    }
}
