package com.deepseek.whalepet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/**
 * 悬浮菜单（长按小鲸鱼打开），v1.4.7 重排：
 * <ul>
 * <li>标题 + 版本号 + 右上角关闭按钮</li>
 * <li>大小：滑杆（右侧刻度 + 倍率）</li>
 * <li>峰谷文案：三段胶囊</li>
 * <li>气泡 / 音效 / 贴左镜像 / 点按推进：双列开关胶囊</li>
 * <li>吸附离边距离：- 步进 + （0~200dp，步进 10）</li>
 * </ul>
 * 触控目标全部 ≥ 44dp 高，卡片 300dp 宽。
 */
public class WhaleMenuView extends View {

    public interface Listener {
        void onScale(float scale);

        void onPeakMode(String mode);

        void onBubbleToggle(boolean on);

        void onSoundToggle(boolean on);

        void onMirrorToggle(boolean on);

        void onTapAdvanceToggle(boolean on);

        void onSnapEdgeChange(int edgeDp);

        void onDismiss();
    }

    private static final int COLOR_CARD = 0xF7FFFFFF;
    private static final int COLOR_BORDER = 0x1F203170;
    private static final int COLOR_TITLE = 0xFF203170;
    private static final int COLOR_LABEL = 0xFF536BA9;
    private static final int COLOR_HINT = 0xFF9FB0D9;
    private static final int COLOR_DIVIDER = 0x12203170;
    private static final int COLOR_TRACK_BG = 0x1A203170;
    private static final int COLOR_ACCENT = 0xFF536BA9;
    private static final int COLOR_CHIP = 0x10203170;
    private static final int COLOR_CHIP_ON = 0xFF203170;
    private static final int COLOR_CHIP_ON_TEXT = 0xFFFFFFFF;
    private static final int COLOR_CLOSE_BG = 0x14203170;

    private static final String[] PEAK_LABELS = {"默认", "梁文峰谷", "!?强强?!"};
    private static final String[] PEAK_VALUES = {"default", "liangwen", "qiangqiang"};

    private static final int ROW_COUNT = 5;
    private static final int SNAP_STEP = 10;
    private static final int SNAP_MIN = 0;
    private static final int SNAP_MAX = 200;

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF card = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF closeRect = new RectF();
    private final RectF[] peakChips = {new RectF(), new RectF(), new RectF()};
    private final RectF bubbleChip = new RectF();
    private final RectF soundChip = new RectF();
    private final RectF mirrorChip = new RectF();
    private final RectF tapAdvanceChip = new RectF();
    private final RectF snapMinus = new RectF();
    private final RectF snapPlus = new RectF();

    private final float pad;
    private final float headerH;
    private final float rowH;
    private final float valueW;
    private final int widthPx;
    private final int heightPx;

    private Listener listener;
    private float scale = 1.5f;
    private String peakMode = "default";
    private boolean bubbleOn = true;
    private boolean soundOn = true;
    private boolean mirrorLeft = true;
    private boolean tapAdvance;
    private int snapEdgeDp;
    private boolean draggingTrack;
    /** 刚弹出来的 250ms 内忽略触摸，避免上一下手势的 UP 误触控件。 */
    private long ignoreTouchUntil;

    public WhaleMenuView(Context context) {
        super(context);
        pad = dp(16f);
        headerH = dp(32f);
        rowH = dp(46f);
        valueW = dp(50f);
        widthPx = Math.round(dp(300f));
        heightPx = Math.round(pad * 2f + headerH + rowH * ROW_COUNT);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
        shadowPaint.setColor(COLOR_CARD);
        shadowPaint.setShadowLayer(dp(8f), 0f, dp(2.5f), 0x40000000);
        borderPaint.setColor(COLOR_BORDER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        textPaint.setColor(COLOR_TITLE);
        knobPaint.setColor(0xFFFFFFFF);
        knobPaint.setStyle(Paint.Style.FILL);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void sync(float scale, String peakMode, boolean bubbleOn, boolean soundOn,
                     boolean mirrorLeft, boolean tapAdvance, int snapEdgeDp) {
        this.scale = Prefs.clampScale(scale);
        this.peakMode = Prefs.normalizePeakMode(peakMode);
        this.bubbleOn = bubbleOn;
        this.soundOn = soundOn;
        this.mirrorLeft = mirrorLeft;
        this.tapAdvance = tapAdvance;
        this.snapEdgeDp = Math.max(SNAP_MIN, Math.min(SNAP_MAX, snapEdgeDp));
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ignoreTouchUntil = SystemClock.uptimeMillis() + 250L;
    }

    private static float centerBaseline(float y, Paint paint) {
        Paint.FontMetrics fm = paint.getFontMetrics();
        return y - (fm.ascent + fm.descent) / 2f;
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(widthPx, heightPx);
    }

    private float rowCenter(int index) {
        return pad + headerH + rowH * (index + 0.5f);
    }

    private void layoutRows(int w) {
        float contentLeft = pad + dp(46f);
        float contentRight = w - pad;

        // 关闭按钮（右上角）
        float cs = dp(24f);
        closeRect.set(w - pad - cs, pad + (headerH - cs) / 2f, w - pad, pad + (headerH - cs) / 2f + cs);

        // 行 0：大小滑杆
        float c0 = rowCenter(0);
        trackRect.set(contentLeft, c0 - dp(3f), contentRight - valueW - dp(8f), c0 + dp(3f));

        // 行 1：峰谷三段
        float c1 = rowCenter(1);
        float gap = dp(6f);
        float segW = (contentRight - contentLeft - gap * 2f) / 3f;
        for (int i = 0; i < 3; i++) {
            float left = contentLeft + i * (segW + gap);
            peakChips[i].set(left, c1 - dp(15f), left + segW, c1 + dp(15f));
        }

        // 行 2 / 行 3：双列开关
        layoutToggleRow(2, contentLeft, contentRight, bubbleChip, soundChip);
        layoutToggleRow(3, contentLeft, contentRight, mirrorChip, tapAdvanceChip);

        // 行 4：吸附离边步进器
        float c4 = rowCenter(4);
        float btn = dp(30f);
        snapMinus.set(contentLeft, c4 - btn / 2f, contentLeft + btn, c4 + btn / 2f);
        snapPlus.set(contentRight - btn, c4 - btn / 2f, contentRight, c4 + btn / 2f);
    }

    private void layoutToggleRow(int row, float left, float right, RectF outLeft, RectF outRight) {
        float cy = rowCenter(row);
        float gap = dp(10f);
        float half = (right - left - gap) / 2f;
        outLeft.set(left, cy - dp(17f), left + half, cy + dp(17f));
        outRight.set(left + half + gap, cy - dp(17f), right, cy + dp(17f));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        layoutRows(w);

        float inset = dp(4f);
        card.set(inset, inset, w - inset, h - inset - dp(3f));
        canvas.drawRoundRect(card, dp(16f), dp(16f), shadowPaint);
        canvas.drawRoundRect(card, dp(16f), dp(16f), borderPaint);

        drawHeader(canvas, w);

        // 行标签
        textPaint.setColor(COLOR_LABEL);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(12.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("大小", pad, centerBaseline(rowCenter(0), textPaint), textPaint);
        canvas.drawText("峰谷", pad, centerBaseline(rowCenter(1), textPaint), textPaint);
        canvas.drawText("开关", pad, centerBaseline(rowCenter(2), textPaint), textPaint);
        canvas.drawText("交互", pad, centerBaseline(rowCenter(3), textPaint), textPaint);
        canvas.drawText("吸附", pad, centerBaseline(rowCenter(4), textPaint), textPaint);
        divider(canvas, rowCenter(0) + rowH * 0.5f - dp(6f), w);
        divider(canvas, rowCenter(1) + rowH * 0.5f - dp(6f), w);

        drawTrack(canvas, w);
        drawPeakChips(canvas);
        drawToggle(canvas, bubbleChip, "气泡", bubbleOn);
        drawToggle(canvas, soundChip, "音效", soundOn);
        drawToggle(canvas, mirrorChip, "贴左镜像", mirrorLeft);
        drawToggle(canvas, tapAdvanceChip, "点按推进", tapAdvance);
        drawStepper(canvas);
    }

    private void drawHeader(Canvas canvas, int w) {
        textPaint.setColor(COLOR_TITLE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(14f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("小鲸鱼挂件", pad, centerBaseline(pad + headerH * 0.5f, textPaint), textPaint);

        // 版本号
        textPaint.setColor(COLOR_HINT);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(dp(10f));
        canvas.drawText(BuildConfigLabel.get(), pad + dp(74f),
                centerBaseline(pad + headerH * 0.5f, textPaint), textPaint);

        // 关闭按钮
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(COLOR_CLOSE_BG);
        canvas.drawCircle(closeRect.centerX(), closeRect.centerY(), closeRect.width() / 2f, fillPaint);
        textPaint.setColor(COLOR_LABEL);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(13f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("×", closeRect.centerX(),
                centerBaseline(closeRect.centerY(), textPaint) + dp(0.5f), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);

        divider(canvas, pad + headerH - dp(7f), w);
    }

    private void divider(Canvas canvas, float y, int w) {
        fillPaint.setColor(COLOR_DIVIDER);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(pad, y, w - pad, y + dp(1f), fillPaint);
    }

    private void drawTrack(Canvas canvas, int w) {
        float cy = trackRect.centerY();
        fillPaint.setStyle(Paint.Style.FILL);

        fillPaint.setColor(COLOR_TRACK_BG);
        tmp.set(trackRect.left, cy - dp(3.5f), trackRect.right, cy + dp(3.5f));
        canvas.drawRoundRect(tmp, dp(3.5f), dp(3.5f), fillPaint);

        float ratio = (scale - Prefs.MIN_SCALE) / (Prefs.MAX_SCALE - Prefs.MIN_SCALE);
        ratio = Math.max(0f, Math.min(1f, ratio));
        float kx = trackRect.left + ratio * trackRect.width();
        fillPaint.setColor(COLOR_ACCENT);
        tmp.set(trackRect.left, cy - dp(3.5f), Math.max(trackRect.left + dp(3.5f), kx), cy + dp(3.5f));
        canvas.drawRoundRect(tmp, dp(3.5f), dp(3.5f), fillPaint);

        knobPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(kx, cy, dp(9f), knobPaint);
        knobPaint.setStyle(Paint.Style.STROKE);
        knobPaint.setStrokeWidth(dp(2.5f));
        knobPaint.setColor(COLOR_CHIP_ON);
        canvas.drawCircle(kx, cy, dp(9f), knobPaint);
        knobPaint.setStyle(Paint.Style.FILL);

        float right = w - pad;
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(COLOR_TITLE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(14.5f));
        canvas.drawText(String.valueOf(Prefs.scaleToDisplay(scale)), right,
                centerBaseline(cy - dp(8f), textPaint), textPaint);
        textPaint.setColor(COLOR_HINT);
        textPaint.setTypeface(Typeface.DEFAULT);
        textPaint.setTextSize(dp(10f));
        canvas.drawText(String.format(Locale.US, "%.1f×", scale), right,
                centerBaseline(cy + dp(9f), textPaint), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawPeakChips(Canvas canvas) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 3; i++) {
            boolean on = PEAK_VALUES[i].equals(peakMode);
            paintChip(canvas, peakChips[i], on);
            textPaint.setColor(on ? COLOR_CHIP_ON_TEXT : COLOR_LABEL);
            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(dp(12f));
            canvas.drawText(PEAK_LABELS[i], peakChips[i].centerX(),
                    centerBaseline(peakChips[i].centerY(), textPaint), textPaint);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawToggle(Canvas canvas, RectF r, String label, boolean on) {
        paintChip(canvas, r, on);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(on ? COLOR_CHIP_ON_TEXT : COLOR_LABEL);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(12f));
        canvas.drawText(label + (on ? " · 开" : " · 关"), r.centerX(),
                centerBaseline(r.centerY(), textPaint), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawStepper(Canvas canvas) {
        // 两个圆形按钮
        drawRoundBtn(canvas, snapMinus, "−");
        drawRoundBtn(canvas, snapPlus, "+");

        // 中间数值
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_TITLE);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(14f));
        canvas.drawText(snapEdgeDp + " dp", (snapMinus.right + snapPlus.left) / 2f,
                centerBaseline(rowCenter(4), textPaint), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawRoundBtn(Canvas canvas, RectF r, String glyph) {
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(COLOR_CHIP);
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, fillPaint);
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeWidth(dp(1f));
        fillPaint.setColor(0x33203170);
        canvas.drawCircle(r.centerX(), r.centerY(), r.width() / 2f, fillPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(COLOR_LABEL);
        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(15f));
        canvas.drawText(glyph, r.centerX(), centerBaseline(r.centerY(), textPaint) + dp(0.5f), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void paintChip(Canvas canvas, RectF r, boolean on) {
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(on ? COLOR_CHIP_ON : COLOR_CHIP);
        canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fillPaint);
        if (on) {
            fillPaint.setStyle(Paint.Style.STROKE);
            fillPaint.setStrokeWidth(dp(1f));
            fillPaint.setColor(0x33203170);
            canvas.drawRoundRect(r, r.height() / 2f, r.height() / 2f, fillPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (SystemClock.uptimeMillis() < ignoreTouchUntil && event.getActionMasked() != MotionEvent.ACTION_OUTSIDE) {
            return true;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_OUTSIDE:
                if (listener != null) {
                    listener.onDismiss();
                }
                return true;
            case MotionEvent.ACTION_DOWN: {
                // 关闭按钮热区放大
                RectF close = new RectF(closeRect);
                close.inset(-dp(6f), -dp(6f));
                if (close.contains(x, y)) {
                    if (listener != null) {
                        listener.onDismiss();
                    }
                    return true;
                }
                RectF track = new RectF(trackRect);
                track.inset(-dp(8f), -dp(18f));
                if (track.contains(x, y)) {
                    draggingTrack = true;
                    applyTrack(x);
                    return true;
                }
                for (int i = 0; i < 3; i++) {
                    if (peakChips[i].contains(x, y)) {
                        if (listener != null) {
                            listener.onPeakMode(PEAK_VALUES[i]);
                        }
                        return true;
                    }
                }
                if (hitToggle(bubbleChip, x, y)) {
                    if (listener != null) {
                        listener.onBubbleToggle(!bubbleOn);
                    }
                    return true;
                }
                if (hitToggle(soundChip, x, y)) {
                    if (listener != null) {
                        listener.onSoundToggle(!soundOn);
                    }
                    return true;
                }
                if (hitToggle(mirrorChip, x, y)) {
                    if (listener != null) {
                        listener.onMirrorToggle(!mirrorLeft);
                    }
                    return true;
                }
                if (hitToggle(tapAdvanceChip, x, y)) {
                    if (listener != null) {
                        listener.onTapAdvanceToggle(!tapAdvance);
                    }
                    return true;
                }
                RectF minus = new RectF(snapMinus);
                RectF plus = new RectF(snapPlus);
                minus.inset(-dp(8f), -dp(8f));
                plus.inset(-dp(8f), -dp(8f));
                if (minus.contains(x, y)) {
                    applySnap(-SNAP_STEP);
                    return true;
                }
                if (plus.contains(x, y)) {
                    applySnap(SNAP_STEP);
                    return true;
                }
                return true;
            }
            case MotionEvent.ACTION_MOVE:
                if (draggingTrack) {
                    applyTrack(x);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingTrack = false;
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** 开关热区：垂直方向放大约 1.3 倍，保证好点。 */
    private boolean hitToggle(RectF r, float x, float y) {
        float grow = (r.height() * 0.3f) / 2f;
        return x >= r.left - dp(2f) && x <= r.right + dp(2f)
                && y >= r.top - grow && y <= r.bottom + grow;
    }

    private void applyTrack(float x) {
        float ratio = (x - trackRect.left) / Math.max(1f, trackRect.width());
        ratio = Math.max(0f, Math.min(1f, ratio));
        float next = Prefs.clampScale(Prefs.MIN_SCALE + ratio * (Prefs.MAX_SCALE - Prefs.MIN_SCALE));
        if (Math.abs(next - scale) < 0.001f) {
            return;
        }
        scale = next;
        invalidate();
        if (listener != null) {
            listener.onScale(next);
        }
    }

    private void applySnap(int delta) {
        int next = Math.max(SNAP_MIN, Math.min(SNAP_MAX, snapEdgeDp + delta));
        if (next == snapEdgeDp) {
            return;
        }
        snapEdgeDp = next;
        invalidate();
        if (listener != null) {
            listener.onSnapEdgeChange(next);
        }
    }

    /** 版本号标签（避免直接依赖 BuildConfig，手动注入一次即可）。 */
    public static final class BuildConfigLabel {
        private static String label = "v1.4.7";

        public static String get() {
            return label;
        }

        public static void set(String v) {
            label = v == null ? "" : v;
        }
    }
}
