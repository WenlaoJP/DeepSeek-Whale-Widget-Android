package com.deepseek.whalepet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

/**
 * 悬浮菜单（长按小鲸鱼打开）：
 * <ul>
 * <li>大小：0.6~2.5 连续轨道（对齐原版 1~20 数字框，右侧同时显示倍率）</li>
 * <li>峰谷文案：默认 / 梁文峰谷 / !?强强?!</li>
 * <li>气泡开关</li>
 * </ul>
 * 样式：白色圆角卡片 + 投影 + 分组分隔线 + 选中态胶囊按钮。
 */
public class WhaleMenuView extends View {

    public interface Listener {
        void onScale(float scale);

        void onPeakMode(String mode);

        void onBubbleToggle(boolean on);

        void onSoundToggle(boolean on);

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

    private static final String[] PEAK_LABELS = {"默认", "梁文峰谷", "!?强强?!"};
    private static final String[] PEAK_VALUES = {"default", "liangwen", "qiangqiang"};

    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tmp = new RectF();
    private final RectF card = new RectF();
    private final RectF trackRect = new RectF();
    private final RectF[] chips = {new RectF(), new RectF(), new RectF()};
    private final RectF bubbleChip = new RectF();
    private final RectF soundChip = new RectF();

    private final float pad;
    private final float headerH;
    private final float rowH;
    private final float labelW;
    private final float valueW;
    private final int widthPx;
    private final int heightPx;

    private Listener listener;
    private float scale = 1.5f;
    private String peakMode = "default";
    private boolean bubbleOn = true;
    private boolean soundOn = true;
    private boolean draggingTrack;
    /** 刚弹出来的 250ms 内忽略触摸，避免上一下手势的 UP 误触控件。 */
    private long ignoreTouchUntil;

    public WhaleMenuView(Context context) {
        super(context);
        pad = dp(14f);
        headerH = dp(26f);
        rowH = dp(44f);
        labelW = dp(42f);
        valueW = dp(54f);
        widthPx = Math.round(dp(262f));
        heightPx = Math.round(pad * 2f + headerH + rowH * 4f);

        setLayerType(LAYER_TYPE_SOFTWARE, null);
        shadowPaint.setColor(COLOR_CARD);
        shadowPaint.setShadowLayer(dp(7f), 0f, dp(2f), 0x40000000);
        borderPaint.setColor(COLOR_BORDER);
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(1f));
        textPaint.setColor(COLOR_TITLE);
        textPaint.setTextSize(dp(12f));
        knobPaint.setColor(0xFFFFFFFF);
        knobPaint.setStyle(Paint.Style.FILL);
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void sync(float scale, String peakMode, boolean bubbleOn, boolean soundOn) {
        this.scale = Prefs.clampScale(scale);
        this.peakMode = Prefs.normalizePeakMode(peakMode);
        this.bubbleOn = bubbleOn;
        this.soundOn = soundOn;
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
        float contentLeft = pad + labelW;
        float contentRight = w - pad;

        float c0 = rowCenter(0);
        trackRect.set(contentLeft, c0 - dp(3f), contentRight - valueW - dp(6f), c0 + dp(3f));

        float c1 = rowCenter(1);
        float gap = dp(5f);
        float segW = (contentRight - contentLeft - gap * 2f) / 3f;
        for (int i = 0; i < 3; i++) {
            float left = contentLeft + i * (segW + gap);
            chips[i].set(left, c1 - dp(14f), left + segW, c1 + dp(14f));
        }

        float c2 = rowCenter(2);
        bubbleChip.set(contentLeft, c2 - dp(14f), contentLeft + dp(68f), c2 + dp(14f));

        float c3 = rowCenter(3);
        soundChip.set(contentLeft, c3 - dp(14f), contentLeft + dp(68f), c3 + dp(14f));
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

        // 标题
        textPaint.setColor(COLOR_TITLE);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(13.5f));
        textPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("小鲸鱼挂件", pad, centerBaseline(pad + headerH * 0.5f, textPaint), textPaint);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        divider(canvas, pad + headerH - dp(6f), w);

        // 行标签
        textPaint.setColor(COLOR_LABEL);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(12f));
        canvas.drawText("大小", pad, centerBaseline(rowCenter(0), textPaint), textPaint);
        canvas.drawText("峰谷", pad, centerBaseline(rowCenter(1), textPaint), textPaint);
        canvas.drawText("气泡", pad, centerBaseline(rowCenter(2), textPaint), textPaint);
        canvas.drawText("音效", pad, centerBaseline(rowCenter(3), textPaint), textPaint);
        divider(canvas, rowCenter(0) + rowH * 0.5f - dp(4f), w);

        drawTrack(canvas, w);
        drawChips(canvas);
        drawBubbleChip(canvas);
        drawSoundChip(canvas);
    }

    private void divider(Canvas canvas, float y, int w) {
        fillPaint.setColor(COLOR_DIVIDER);
        fillPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(pad, y, w - pad, y + dp(1f), fillPaint);
    }

    private void drawTrack(Canvas canvas, int w) {
        float cy = trackRect.centerY();
        fillPaint.setStyle(Paint.Style.FILL);

        // 轨道底
        fillPaint.setColor(COLOR_TRACK_BG);
        tmp.set(trackRect.left, cy - dp(3f), trackRect.right, cy + dp(3f));
        canvas.drawRoundRect(tmp, dp(3f), dp(3f), fillPaint);

        // 已选段
        float ratio = (scale - Prefs.MIN_SCALE) / (Prefs.MAX_SCALE - Prefs.MIN_SCALE);
        ratio = Math.max(0f, Math.min(1f, ratio));
        float kx = trackRect.left + ratio * trackRect.width();
        fillPaint.setColor(COLOR_ACCENT);
        tmp.set(trackRect.left, cy - dp(3f), Math.max(trackRect.left + dp(3f), kx), cy + dp(3f));
        canvas.drawRoundRect(tmp, dp(3f), dp(3f), fillPaint);

        // 旋钮：白底 + 描边
        knobPaint.setColor(0xFFFFFFFF);
        canvas.drawCircle(kx, cy, dp(8f), knobPaint);
        knobPaint.setStyle(Paint.Style.STROKE);
        knobPaint.setStrokeWidth(dp(2.5f));
        knobPaint.setColor(COLOR_CHIP_ON);
        canvas.drawCircle(kx, cy, dp(8f), knobPaint);
        knobPaint.setStyle(Paint.Style.FILL);

        // 右侧：刻度 + 倍率
        float right = w - pad;
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(COLOR_TITLE);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(14f));
        canvas.drawText(String.valueOf(Prefs.scaleToDisplay(scale)), right,
                centerBaseline(cy - dp(8f), textPaint), textPaint);
        textPaint.setColor(COLOR_HINT);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT);
        textPaint.setTextSize(dp(9.5f));
        String factor = String.format(java.util.Locale.US, "%.1f×", scale);
        canvas.drawText(factor, right, centerBaseline(cy + dp(9f), textPaint), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawChips(Canvas canvas) {
        textPaint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < 3; i++) {
            boolean on = PEAK_VALUES[i].equals(peakMode);
            paintChip(canvas, chips[i], on);
            textPaint.setColor(on ? COLOR_CHIP_ON_TEXT : COLOR_LABEL);
            textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(dp(11.5f));
            canvas.drawText(PEAK_LABELS[i], chips[i].centerX(),
                    centerBaseline(chips[i].centerY(), textPaint), textPaint);
        }
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBubbleChip(Canvas canvas) {
        paintChip(canvas, bubbleChip, bubbleOn);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(bubbleOn ? COLOR_CHIP_ON_TEXT : COLOR_LABEL);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(11.5f));
        canvas.drawText(bubbleOn ? "自动弹出 · 开" : "自动弹出 · 关", bubbleChip.centerX(),
                centerBaseline(bubbleChip.centerY(), textPaint), textPaint);
        textPaint.setTextAlign(Paint.Align.LEFT);
    }

    private void drawSoundChip(Canvas canvas) {
        paintChip(canvas, soundChip, soundOn);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setColor(soundOn ? COLOR_CHIP_ON_TEXT : COLOR_LABEL);
        textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(dp(11.5f));
        canvas.drawText(soundOn ? "音效 · 开" : "音效 · 关", soundChip.centerX(),
                centerBaseline(soundChip.centerY(), textPaint), textPaint);
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
                RectF track = new RectF(trackRect);
                track.inset(-dp(8f), -dp(16f));
                if (track.contains(x, y)) {
                    draggingTrack = true;
                    applyTrack(x);
                    return true;
                }
                for (int i = 0; i < 3; i++) {
                    if (chips[i].contains(x, y)) {
                        if (listener != null) {
                            listener.onPeakMode(PEAK_VALUES[i]);
                        }
                        return true;
                    }
                }
                if (bubbleChip.contains(x, y)) {
                    if (listener != null) {
                        listener.onBubbleToggle(!bubbleOn);
                    }
                    return true;
                }
                if (soundChip.contains(x, y)) {
                    if (listener != null) {
                        listener.onSoundToggle(!soundOn);
                    }
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
}
