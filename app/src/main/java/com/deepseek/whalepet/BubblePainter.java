package com.deepseek.whalepet;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * 「思考气泡」绘制器。形状与排版照搬原版 SVG（viewBox 1026x700）：
 * <p>
 * - 主泡泡：椭圆 cx454 cy248 rx373 ry232；尾巴两个小椭圆 (352,561,37.5,26) 与 (442,646,24.5,18)；
 * - 白底 #FFFFFF + 描边 #203170 宽 18（都按 u = 宽度/1026 缩放）；
 * - 文字堆叠居中在 (454,266)，样式：label 66u / amount 128u / period 104u / hint 56u。
 * <p>
 * 贴左镜像时：形状（含尾巴）跟着镜像，这样尾巴才会指向人物；但文字单独在正向坐标里画，
 * 不会变成反字。
 */
public class BubblePainter {

    public static final int STYLE_LABEL = 0;
    public static final int STYLE_AMOUNT = 1;
    public static final int STYLE_PERIOD = 2;
    public static final int STYLE_HINT = 3;

    private static final float VB_W = 1026f;
    private static final float[] SIZE_REL = {66f, 128f, 104f, 56f};
    private static final int COLOR_MAIN = 0xFF536BA9;
    private static final int COLOR_HINT = 0xFF9FB0D9;
    private static final int COLOR_FILL = 0xFFFFFFFF;
    private static final int COLOR_STROKE = 0xFF203170;
    private static final float STROKE_REL = 18f;

    private static final float CX = 454f, CY = 248f, RX = 373f, RY = 232f;
    private static final float[] TAIL1 = {352f, 561f, 37.5f, 26f};
    private static final float[] TAIL2 = {442f, 646f, 24.5f, 18f};
    private static final float TEXT_CY = 266f;
    private static final float WRAP_W = 560f;
    private static final float LINE_SPACING = 1.15f;

    private static final long REVEAL_MS = 600L;
    private static final long HIDE_MS = 200L;
    private static final long ROLL_MS = 700L;

    /** 台词池：一轮内每条只出现一次（峰谷页出现 3 次），轮与轮之间不会间隔重复。 */
    private static final String[] POOL = {
            "PEAK", "PEAK", "PEAK",
            "M1", "M2",
            "T0", "T1", "T2", "T3", "T4", "T5",
            "G0", "G1", "G2",
            "D0", "D1", "D2",
            "W"
    };
    private static final String[] SASS = {
            "不知道用户有什么用，先赶走吧~",
            "我...我...我也要挣钱吗？",
            "我去吃饭啦，测完叫我",
            "压力一只蓝色大肥鱼？！",
            "DeepSleep...",
            "坏了...用户彻底怒了！"
    };
    private static final String[] GIF_FAIL = {
            "gif 加载失败了...",
            "今天没有动图给你看~",
            "呜呜 动图不见了..."
    };
    private static final String[] WEIRD = {
            "你目录里的dsh是什么...大烧货吗...?",
            "恭喜你实现token自由！token全跑了！",
            "真当我是便宜货啊..."
    };

    /** 连戳生气台词（借鉴 whale_v151 的 comboLines）。 */
    private static final String[] COMBO = {
            "别戳了别戳了，再戳要吐泡泡了！ 🫧",
            "戳戳戳，你今天是啄木鸟吗？ 🔨",
            "再戳，余额就要被你戳没了！（威胁） 😤",
            "呜…本鲸鱼要罢工了！ 😭",
            "别摸啦，发型要乱了！ 💢",
            "头可摸，发型不能乱！ 😠",
            "尾巴不能乱摸！ 🐟",
            "甩你一脸水花 💦"
    };

    /** 平时自弹台词（借鉴 whale_v151 待机台词，扩展 + 带 emoji）。 */
    private static final String[] IDLE = {
            "摸鱼陪伴，开口说梗～ 🐟",
            "摸鱼使我快乐～ 🐳",
            "打工是不可能打工的 💼",
            "早安打工人！ ☀️",
            "下班撒欢！ 🎉",
            "午休吃鱼啦 🐟",
            "大肥鱼在线摸鱼 🐟",
            "深潜中，请稍后～ 🌊",
            "今天也要元气满满！ ✨",
            "鲸鱼娘摸鱼中～ 🐋",
            "好舒服～再摸摸～ 🥰",
            "呼噜呼噜…差点睡着了 😴",
            "咕噜咕噜…冒泡泡 🫧",
            "我是小鱼腩，软软的～ 🐠",
            "快夸我可爱，不然不干活 😤",
            "这网速…是鱼在游吗 🐌",
            "别看我，我在认真摸鱼 👀",
            "今日份可爱已送达 💌",
            "要不要一起去潜水？ 🤿",
            "哼，才不是想理你呢 😼"
    };

    /** 一行文字。style 决定字号/默认颜色；wrap=true 时长文本换行。 */
    public static final class Line {
        public final String text;
        public final int style;
        public final int color;
        public final boolean wrap;

        public Line(String text, int style, int color, boolean wrap) {
            this.text = text;
            this.style = style;
            this.color = color;
            this.wrap = wrap;
        }

        public static Line of(String text, int style, boolean wrap) {
            return new Line(text, style, 0, wrap);
        }

        public static Line of(String text, int style, int color, boolean wrap) {
            return new Line(text, style, color, wrap);
        }
    }

    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint wrapPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final Random random = new Random();

    private final List<Line> lines = new ArrayList<>();
    private final List<String> plan = new ArrayList<>();
    private String lastKey = "";

    private boolean open;
    private long openAt;
    private long closeAt;

    // 余额数字滚动
    private double rollFrom;
    private double rollTo;
    private long rollAt;
    private boolean rolling;
    private String rollCurrency = "CNY";
    private long rollDuration = ROLL_MS;

    // 供随机台词使用的快照
    private double balance = Double.NaN;
    private String currency = "CNY";
    private double todayUsage = Double.NaN;
    private boolean hasUsage;
    private boolean peak;
    private String peakMode = "default";

    public BubblePainter() {
        shapePaint.setStyle(Paint.Style.FILL);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    // ---- 状态更新 ----

    public void updateState(double balance, String currency, double todayUsage, boolean hasUsage,
                            boolean peak, String peakMode) {
        this.balance = balance;
        this.currency = currency == null ? "CNY" : currency;
        this.todayUsage = todayUsage;
        this.hasUsage = hasUsage;
        this.peak = peak;
        this.peakMode = Prefs.normalizePeakMode(peakMode);
        this.rollCurrency = this.currency;
        if (!rolling && !Double.isNaN(balance)) {
            rollFrom = balance;
            rollTo = balance;
        }
    }

    public boolean isOpen() {
        return open;
    }

    public boolean isVisible() {
        return open || SystemClock.uptimeMillis() - closeAt < HIDE_MS;
    }

    public void open() {
        if (open) {
            return;
        }
        open = true;
        openAt = SystemClock.uptimeMillis();
        showDefaultLines();
    }

    /** 带台词弹出（连戳 / 自弹用）。直接设内容，避开 open() 的余额默认内容覆盖。 */
    public void openWith(String text) {
        boolean wasOpen = open;
        open = true;
        if (!wasOpen) {
            openAt = SystemClock.uptimeMillis();
        }
        showText(text);
    }

    public void close() {
        if (!open) {
            return;
        }
        open = false;
        closeAt = SystemClock.uptimeMillis();
    }

    /** 默认内容：余额 + 今日已用。 */
    public void showDefaultLines() {
        lines.clear();
        lines.add(Line.of("DeepSeek 余额", STYLE_LABEL, false));
        lines.add(Line.of(fmt(shownAmount()), STYLE_AMOUNT, false));
        lines.add(Line.of(hasUsage ? ("今日已用 " + fmt(todayUsage)) : "今日已用 --", STYLE_HINT, false));
    }

    /** 加载中：对齐原版 render() 的 status==='loading' 分支。 */
    public void showLoading() {
        lines.clear();
        lines.add(Line.of("DeepSeek 余额", STYLE_LABEL, false));
        lines.add(Line.of("…", STYLE_AMOUNT, false));
        lines.add(Line.of("加载中…", STYLE_HINT, false));
    }

    /** 出错：对齐原版 render() 的 status==='error' 分支。 */
    public void showError(String message) {
        lines.clear();
        lines.add(Line.of("DeepSeek 余额", STYLE_LABEL, false));
        lines.add(Line.of(fmt(shownAmount()), STYLE_AMOUNT, false));
        lines.add(Line.of(message == null ? "获取失败 · 点击重试" : message, STYLE_HINT, false));
    }

    /**
     * 点击气泡的台词。改成「一轮洗牌后顺序播放」：
     * 同一轮里每条台词只出现一次，且新一轮开头不会接上一轮结尾。
     */
    public void showRandomLines() {
        if (plan.isEmpty()) {
            refillPlan();
        }
        String key = plan.remove(plan.size() - 1);
        lastKey = key;
        lines.clear();
        applyKey(key);
    }

    /** 连戳生气：随机取一句。 */
    public String randomComboLine() {
        return COMBO[random.nextInt(COMBO.length)];
    }

    /** 平时自弹：随机取一句。 */
    public String randomIdleLine() {
        return IDLE[random.nextInt(IDLE.length)];
    }

    /** 直接把气泡内容换成一句台词（连戳 / 自弹用）。 */
    public void showText(String text) {
        lines.clear();
        lines.add(Line.of(text, STYLE_LABEL, true));
    }

    private void refillPlan() {
        List<String> next = new ArrayList<>();
        Collections.addAll(next, POOL);
        Collections.shuffle(next, random);
        // 下一张牌（末尾弹出）不能跟上一张一样
        int last = next.size() - 1;
        if (next.get(last).equals(lastKey) && next.size() > 1) {
            for (int i = 0; i < last; i++) {
                if (!next.get(i).equals(lastKey)) {
                    Collections.swap(next, i, last);
                    break;
                }
            }
        }
        plan.addAll(next);
    }

    private void applyKey(String key) {
        if ("PEAK".equals(key)) {
            lines.add(Line.of("当前时间段为:", STYLE_LABEL, false));
            lines.add(Line.of(PeakPricing.peakText(peakMode, peak), STYLE_PERIOD,
                    PeakPricing.peakColor(peak), false));
            lines.add(Line.of("今日已用 " + (hasUsage ? fmt(todayUsage) : "--"), STYLE_HINT, false));
            return;
        }
        if ("M1".equals(key)) {
            lines.add(Line.of("好模型... ↓", STYLE_AMOUNT, false));
            return;
        }
        if ("M2".equals(key)) {
            lines.add(Line.of("好女孩...↓", STYLE_AMOUNT, false));
            return;
        }
        if ("W".equals(key)) {
            lines.add(Line.of("哦鲸鲸... ", STYLE_AMOUNT, false));
            return;
        }
        if (key.startsWith("T")) {
            lines.add(Line.of(SASS[Integer.parseInt(key.substring(1))], STYLE_LABEL, true));
            return;
        }
        if (key.startsWith("G")) {
            // 原版是 rua.gif，安卓侧没有动图资源，用原版「gif 加载失败」的降级文案
            lines.add(Line.of(GIF_FAIL[Integer.parseInt(key.substring(1))], STYLE_LABEL, true));
            return;
        }
        lines.add(Line.of(WEIRD[Integer.parseInt(key.substring(1))], STYLE_LABEL, true));
    }

    /** 余额变化时启动滚动动画。 */
    public void animateAmount(double from, double to, String currency, long durationMs) {
        this.currency = currency == null ? "CNY" : currency;
        this.rollCurrency = this.currency;
        this.rollFrom = from;
        this.rollTo = to;
        this.rollAt = SystemClock.uptimeMillis();
        this.rolling = true;
        this.rollDuration = Math.max(1L, durationMs);
        if (!hasAmountLine()) {
            showDefaultLines();
        }
    }

    private boolean hasAmountLine() {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).style == STYLE_AMOUNT) {
                return true;
            }
        }
        return false;
    }

    private double shownAmount() {
        if (rolling) {
            float t = Math.min(1f, (SystemClock.uptimeMillis() - rollAt) / (float) rollDuration);
            if (t >= 1f) {
                rolling = false;
                return rollTo;
            }
            double eased = 1d - Math.pow(1d - t, 3d);
            return rollFrom + (rollTo - rollFrom) * eased;
        }
        return rollTo;
    }

    /** 需要继续重绘就返回 true。 */
    public boolean tick() {
        long now = SystemClock.uptimeMillis();
        if (rolling) {
            return true;
        }
        if (open) {
            return now - openAt < REVEAL_MS;
        }
        return now - closeAt < HIDE_MS;
    }

    /** 把显示值钉在某个数（用于滚动前的「先显示旧余额」）。 */
    public void holdAmount(double value, String currency) {
        this.currency = currency == null ? "CNY" : currency;
        this.rollCurrency = this.currency;
        this.rolling = false;
        this.rollFrom = value;
        this.rollTo = value;
        replaceAmountLine(fmtWith(value, this.currency));
    }

    public void refreshAmountLine() {
        replaceAmountLine(fmtWith(shownAmount(), rollCurrency));
    }

    private void replaceAmountLine(String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).style == STYLE_AMOUNT) {
                lines.set(i, Line.of(text, STYLE_AMOUNT, false));
                return;
            }
        }
    }

    public String fmt(double v) {
        return fmtWith(v, currency);
    }

    private String fmtWith(double v, String cur) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return "--";
        }
        String fixed = String.format(Locale.US, "%.2f", v);
        if (cur == null || "CNY".equalsIgnoreCase(cur)) {
            return "¥ " + fixed;
        }
        return fixed + " " + cur;
    }

    // ---- 几何 / 命中 ----

    /** 气泡在容器里的中心 x（镜像后折算回屏幕坐标）；mirrorX = ±1。 */
    public float centerX(float w, float mirrorX) {
        return w / 2f + mirrorX * (CX * (w / VB_W) - w / 2f);
    }

    /** 点是否落在气泡主体（含尾巴）上；w = 容器边长。 */
    public boolean hit(float x, float y, float w, boolean mirrored) {
        float u = w / VB_W;
        if (u <= 0f) {
            return false;
        }
        float px = (mirrored ? (w - x) : x) / u;
        float py = y / u;
        return insideEllipse(px, py, CX, CY, RX, RY)
                || insideEllipse(px, py, TAIL1[0], TAIL1[1], TAIL1[2], TAIL1[3])
                || insideEllipse(px, py, TAIL2[0], TAIL2[1], TAIL2[2], TAIL2[3]);
    }

    /** 把气泡的可见范围并入触摸区域（透明区不抢触摸）。 */
    public void addHitRegion(Region out, float w, boolean mirrored) {
        float u = w / VB_W;
        float mx = mirrored ? -1f : 1f;
        addRegionRect(out, w, mx, CX, CY, RX, RY, u);
        addRegionRect(out, w, mx, TAIL1[0], TAIL1[1], TAIL1[2], TAIL1[3], u);
        addRegionRect(out, w, mx, TAIL2[0], TAIL2[1], TAIL2[2], TAIL2[3], u);
    }

    private static void addRegionRect(Region out, float w, float mx, float cx, float cy,
                                      float rx, float ry, float u) {
        float sx = w / 2f + mx * (cx * u - w / 2f);
        float left = sx - rx * u - 4f;
        float right = sx + rx * u + 4f;
        float top = cy * u - ry * u - 4f;
        float bottom = cy * u + ry * u + 4f;
        out.op(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom), Region.Op.UNION);
    }

    private static boolean insideEllipse(float x, float y, float cx, float cy, float rx, float ry) {
        float dx = (x - cx) / rx;
        float dy = (y - cy) / ry;
        return dx * dx + dy * dy <= 1f;
    }

    // ---- 绘制 ----
    /**
     * @param w       容器边长（px），也即气泡宽
     * @param mirrorX 1 = 正常，-1 = 贴左镜像（形状镜像、文字保持正向）
     */
    public void draw(Canvas canvas, float w, float mirrorX) {
        if (!isVisible()) {
            return;
        }
        if (rolling) {
            refreshAmountLine();
        }
        long now = SystemClock.uptimeMillis();
        float reveal;
        if (open) {
            reveal = Math.min(1f, (now - openAt) / (float) REVEAL_MS);
        } else {
            reveal = 1f - Math.min(1f, (now - closeAt) / (float) HIDE_MS);
        }
        float u = w / VB_W;
        float stroke = STROKE_REL * u;
        shapePaint.setStrokeWidth(stroke);

        float mx = mirrorX;
        if (Math.abs(mx) < 0.02f) {
            mx = mx < 0f ? -0.02f : 0.02f;
        }

        canvas.save();
        canvas.scale(mx, 1f, w / 2f, 0f);
        drawEllipse(canvas, TAIL2, u, progress(reveal, 0.0f, 0.2f));
        drawEllipse(canvas, TAIL1, u, progress(reveal, 0.13f, 0.33f));
        drawMain(canvas, u, progress(reveal, 0.26f, 0.46f));
        canvas.restore();

        float textAlpha = progress(reveal, 0.36f, 0.59f);
        if (textAlpha > 0.01f) {
            drawLines(canvas, u, w, mx, textAlpha);
        }
    }

    private static float progress(float t, float start, float end) {
        if (t <= start) {
            return 0f;
        }
        if (t >= end) {
            return 1f;
        }
        return (t - start) / (end - start);
    }

    private void applyAlpha(float a) {
        int alpha = Math.round(255f * a);
        shapePaint.setAlpha(alpha);
        textPaint.setAlpha(alpha);
        wrapPaint.setAlpha(alpha);
    }

    private void drawEllipse(Canvas canvas, float[] e, float u, float p) {
        if (p <= 0.01f) {
            return;
        }
        applyAlpha(p);
        float scale = 0.7f + 0.3f * p;
        canvas.save();
        canvas.scale(scale, scale, e[0] * u, e[1] * u);
        oval.set((e[0] - e[2]) * u, (e[1] - e[3]) * u, (e[0] + e[2]) * u, (e[1] + e[3]) * u);
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(COLOR_FILL);
        canvas.drawOval(oval, shapePaint);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setColor(COLOR_STROKE);
        canvas.drawOval(oval, shapePaint);
        canvas.restore();
    }

    private void drawMain(Canvas canvas, float u, float p) {
        if (p <= 0.01f) {
            return;
        }
        applyAlpha(p);
        float scale = 0.7f + 0.3f * p;
        canvas.save();
        canvas.scale(scale, scale, CX * u, CY * u);
        oval.set((CX - RX) * u, (CY - RY) * u, (CX + RX) * u, (CY + RY) * u);
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(COLOR_FILL);
        canvas.drawOval(oval, shapePaint);
        shapePaint.setStyle(Paint.Style.STROKE);
        shapePaint.setColor(COLOR_STROKE);
        canvas.drawOval(oval, shapePaint);
        canvas.restore();
    }

    /** 文字永远正向：中心 x 用镜像后的位置，但不做水平翻转。 */
    private void drawLines(Canvas canvas, float u, float w, float mx, float alpha) {
        applyAlpha(alpha);
        List<Object> layout = new ArrayList<>();
        float total = 0f;
        for (Line ln : lines) {
            float size = SIZE_REL[ln.style] * u;
            if (ln.wrap) {
                wrapPaint.setFakeBoldText(false);
                wrapPaint.setTextSize(size);
                wrapPaint.setColor(colorOf(ln));
                StaticLayout sl = StaticLayout.Builder
                        .obtain(ln.text, 0, ln.text.length(), wrapPaint, Math.round(WRAP_W * u))
                        .setAlignment(Layout.Alignment.ALIGN_CENTER)
                        .setLineSpacing(0f, 1.15f)
                        .setIncludePad(false)
                        .build();
                layout.add(sl);
                total += sl.getHeight();
            } else {
                layout.add(null);
                total += size * LINE_SPACING;
            }
        }
        float y = TEXT_CY * u - total / 2f;
        float cx = w / 2f + mx * (CX * u - w / 2f);
        for (int i = 0; i < lines.size(); i++) {
            Line ln = lines.get(i);
            float size = SIZE_REL[ln.style] * u;
            Object item = layout.get(i);
            if (item instanceof StaticLayout) {
                StaticLayout sl = (StaticLayout) item;
                canvas.save();
                canvas.translate(cx - sl.getWidth() / 2f, y);
                sl.draw(canvas);
                canvas.restore();
                y += sl.getHeight();
            } else {
                textPaint.setTextSize(size);
                textPaint.setFakeBoldText(ln.style != STYLE_LABEL);
                textPaint.setColor(colorOf(ln));
                textPaint.setTypeface(ln.style == STYLE_LABEL ? Typeface.DEFAULT : Typeface.DEFAULT_BOLD);
                canvas.drawText(ln.text, cx, y + size, textPaint);
                y += size * LINE_SPACING;
            }
        }
    }

    private int colorOf(Line ln) {
        if (ln.color != 0) {
            return ln.color;
        }
        return ln.style == STYLE_HINT ? COLOR_HINT : COLOR_MAIN;
    }
}
