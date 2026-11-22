package com.gemjiang.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;

import java.util.HashMap;

/**
 * 统一的绘制工具，模仿 macOS 的视觉语言。
 * 所有坐标都在 1920x1080 的设计坐标系里。
 */
public final class Painter {

    public static final Typeface TF = Typeface.create("sans-serif", Typeface.NORMAL);
    public static final Typeface TFM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    public static final Typeface TFB = Typeface.create("sans-serif", Typeface.BOLD);

    private static final Paint P = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint GP = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint TP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private static final Path PATH = new Path();
    private static final Path PATH2 = new Path();
    private static final RectF TMP = new RectF();

    private Painter() {
    }

    /** 颜色乘透明度（0..1） */
    public static int alpha(int color, float a) {
        if (a <= 0f) return 0;
        if (a > 1f) a = 1f;
        int base = (color >>> 24) & 0xFF;
        int out = (int) (base * a + 0.5f);
        if (out > 255) out = 255;
        return (out << 24) | (color & 0x00FFFFFF);
    }

    public static void solid(Canvas c, float l, float t, float r, float b, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.drawRect(l, t, r, b, P);
    }

    public static void round(Canvas c, RectF r, float rad, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.drawRoundRect(r, rad, rad, P);
    }

    public static void roundStroke(Canvas c, RectF r, float rad, float width, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(width);
        P.setColor(color);
        c.drawRoundRect(r, rad, rad, P);
    }

    public static void dot(Canvas c, float x, float y, float r, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.drawCircle(x, y, r, P);
    }

    public static void line(Canvas c, float x1, float y1, float x2, float y2, float w, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(w);
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setColor(color);
        c.drawLine(x1, y1, x2, y2, P);
        P.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 柔和阴影：多层圆角矩形叠加（无 BlurMaskFilter，硬件加速下也稳定） */
    public static void shadow(Canvas c, RectF r, float rad, float spread, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        int steps = 5;
        for (int i = steps; i >= 1; i--) {
            float s = spread * i / (float) steps;
            TMP.set(r.left - s, r.top - s + s * 0.28f, r.right + s, r.bottom + s * 1.35f);
            int a = ((color >>> 24) * i) / (steps * 2);
            P.setColor((a << 24) | (color & 0x00FFFFFF));
            c.drawRoundRect(TMP, rad + s, rad + s, P);
        }
    }

    /** macOS 玻璃面板：投影 + 半透明填充 + 顶部高光 + 1px 描边 */
    public static void glass(Canvas c, RectF r, float rad, int fill, float spread) {
        if (spread > 0f) shadow(c, r, rad, spread, 0x5C000000);
        round(c, r, rad, fill);
        float hh = Math.min(r.height() * 0.55f, 52f);
        GP.setShader(new LinearGradient(0f, r.top, 0f, r.top + hh,
                0x1CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        GP.setStyle(Paint.Style.FILL);
        c.drawRoundRect(r, rad, rad, GP);
        GP.setShader(null);
        roundStroke(c, r, rad, 1f, 0x2BFFFFFF);
    }

    public static void text(Canvas c, CharSequence s, float x, float y, float size,
                            int color, Typeface tf, Paint.Align align, boolean shadow) {
        if (s == null || s.length() == 0) return;
        String str = s.toString();
        TP.setShader(null);
        TP.setStyle(Paint.Style.FILL);
        TP.setTypeface(tf);
        TP.setTextSize(size);
        TP.setTextAlign(align);
        TP.setSubpixelText(true);
        if (shadow) {
            TP.setColor(alpha(0xFF000000, 0.42f));
            c.drawText(str, x, y + Math.max(1f, size * 0.045f), TP);
        }
        TP.setColor(color);
        c.drawText(str, x, y, TP);
    }

    public static float measure(CharSequence s, float size, Typeface tf) {
        if (s == null) return 0f;
        TP.setTypeface(tf);
        TP.setTextSize(size);
        TP.setTextAlign(Paint.Align.LEFT);
        return TP.measureText(s, 0, s.length());
    }

    /** 超宽文字省略号截断 */
    public static String fit(CharSequence s, float size, Typeface tf, float maxW) {
        if (s == null) return "";
        String str = s.toString();
        if (measure(str, size, tf) <= maxW) return str;
        while (str.length() > 1 && measure(str + "…", size, tf) > maxW) {
            str = str.substring(0, str.length() - 1);
        }
        return str + "…";
    }

    private static final HashMap<String, Bitmap> ICONS = new HashMap<String, Bitmap>();

    /**
     * macOS 风格图标：超椭圆（squircle）蒙版 + 顶部极淡玻璃光 + 内描边 + 落地柔和投影。
     * 每个图标第一次绘制时烘成一张 2 倍分辨率的位图缓存，之后只做缩放贴图，低端电视也不掉帧。
     */
    public static void iconTile(Canvas c, RectF r, Bitmap bmp, float rad) {
        float w = r.width();
        // 落地投影：只在图标下缘，不能是整块黑板
        float sp = w * 0.085f;
        TMP.set(r.left + w * 0.10f, r.top + w * 0.90f, r.right - w * 0.10f, r.bottom + w * 0.13f);
        shadow(c, TMP, w * 0.18f, sp, 0x36000000);

        Bitmap out = iconBitmap(bmp, (int) (w * 2f));
        if (out == null) {
            // 缓存失败时退化为直接画，至少不空
            P.setShader(null);
            P.setStyle(Paint.Style.FILL);
            P.setColor(0xFF5B6675);
            c.drawRoundRect(r, rad, rad, P);
            return;
        }
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(0xFFFFFFFF);
        P.setFilterBitmap(true);
        c.drawBitmap(out, null, r, P);
        P.setFilterBitmap(false);
    }

    /** 超椭圆路径：macOS 那种“连续曲率”的圆角，不是普通圆角矩形 */
    private static Path squircle(RectF r) {
        return squircle(r, PATH);
    }

    private static Path squircle(RectF r, Path target) {
        float cx = r.centerX(), cy = r.centerY();
        float a = r.width() / 2f, b = r.height() / 2f;
        final float exp = 2f / 5f;                 // n = 5
        final int steps = 96;
        target.reset();
        for (int i = 0; i <= steps; i++) {
            double t = Math.PI * 2.0 * i / steps;
            float ct = (float) Math.cos(t), st = (float) Math.sin(t);
            float x = cx + a * Math.signum(ct) * (float) Math.pow(Math.abs(ct), exp);
            float y = cy + b * Math.signum(st) * (float) Math.pow(Math.abs(st), exp);
            if (i == 0) target.moveTo(x, y);
            else target.lineTo(x, y);
        }
        target.close();
        return target;
    }

    private static Bitmap iconBitmap(Bitmap src, int size) {
        if (size < 32) size = 32;
        if (size > 320) size = 320;
        String key = System.identityHashCode(src) + ":" + size;
        Bitmap cached = ICONS.get(key);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }
        Bitmap out;
        try {
            out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return null;
        }
        Canvas cv = new Canvas(out);
        float n = size;
        float inset = n * 0.018f;
        RectF rr = new RectF(inset, inset, n - inset, n - inset);
        Path mask = squircle(rr);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

        int sc = cv.save();
        try {
            cv.clipPath(mask);
            // 浅色背板：只在图标透明处透出，方形旧图标满幅覆盖后基本不可见
            GP.setShader(new LinearGradient(0f, rr.top, 0f, rr.bottom,
                    0xFFEEF1F6, 0xFFD7DEE8, Shader.TileMode.CLAMP));
            GP.setStyle(Paint.Style.FILL);
            cv.drawRect(rr, GP);

            if (src != null && !src.isRecycled()) {
                cv.drawBitmap(src, null, rr, p);
            } else {
                GP.setShader(new LinearGradient(0f, rr.top, 0f, rr.bottom,
                        0xFF9AA6B6, 0xFF5E6A7A, Shader.TileMode.CLAMP));
                cv.drawRect(rr, GP);
                GP.setShader(null);
                GP.setColor(0x66FFFFFF);
                cv.drawRoundRect(new RectF(rr.left + n * 0.28f, rr.top + n * 0.28f,
                        rr.right - n * 0.28f, rr.bottom - n * 0.28f), n * 0.08f, n * 0.08f, GP);
            }

            // 顶部玻璃光：只到 42%，很浅，避免那层“发白横条”
            GP.setShader(new LinearGradient(0f, rr.top, 0f, rr.top + rr.height() * 0.42f,
                    0x16FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            cv.drawRect(rr, GP);
            // 下缘轻微压暗，给点体积
            GP.setShader(new LinearGradient(0f, rr.top + rr.height() * 0.62f, 0f, rr.bottom,
                    0x00000000, 0x10000000, Shader.TileMode.CLAMP));
            cv.drawRect(rr, GP);
            // 内描边：上亮下暗
            GP.setShader(new LinearGradient(0f, rr.top, 0f, rr.bottom,
                    0x47FFFFFF, 0x0FFFFFFF, Shader.TileMode.CLAMP));
            GP.setStyle(Paint.Style.STROKE);
            GP.setStrokeWidth(Math.max(1f, n / 150f));
            cv.drawPath(mask, GP);
            GP.setShader(null);
            GP.setStyle(Paint.Style.FILL);
        } catch (Throwable t) {
            // 忽略，返回已画好的部分
        } finally {
            cv.restoreToCount(sc);
        }

        if (ICONS.size() > 160) {
            for (Bitmap b : ICONS.values()) {
                if (b != null && !b.isRecycled()) b.recycle();
            }
            ICONS.clear();
        }
        ICONS.put(key, out);
        return out;
    }

    /** 深色液态玻璃：深底 + 折射高光 + 双层内描边，桌面小部件用 */
    public static void glassDark(Canvas c, RectF r, float rad, float spread) {
        if (spread > 0f) shadow(c, r, rad, spread, 0x66000000);
        round(c, r, rad, 0x54101319);
        // 顶部折射高光
        float hh = Math.max(24f, r.height() * 0.46f);
        GP.setShader(new LinearGradient(0f, r.top, 0f, r.top + hh,
                0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        GP.setStyle(Paint.Style.FILL);
        c.drawRoundRect(r, rad, rad, GP);
        // 底部内侧反光，像一层薄玻璃
        GP.setShader(new LinearGradient(0f, r.bottom - hh * 0.55f, 0f, r.bottom,
                0x00FFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, GP);
        GP.setShader(null);
        GP.setShader(null);
        // 双层描边：外圈亮、内圈暗
        roundStroke(c, r, rad, 1.6f, 0x3DFFFFFF);
        float inset = 1.6f;
        RectF inner = new RectF(r.left + inset, r.top + inset, r.right - inset, r.bottom - inset);
        roundStroke(c, inner, rad - inset, 1f, 0x22000000);
    }

    /** 圆角玻璃小块（Dock 里的启动台/齿轮底板） */
    public static void tile(Canvas c, RectF r, float rad, int fill) {
        round(c, r, rad, fill);
        float hh = Math.min(r.height() * 0.44f, 30f);
        GP.setShader(new LinearGradient(0f, r.top, 0f, r.top + hh,
                0x0FFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
        GP.setStyle(Paint.Style.FILL);
        c.drawRoundRect(r, rad, rad, GP);
        GP.setShader(null);
        roundStroke(c, r, rad, 1f, 0x1CFFFFFF);
    }

    /** 启动台图标：3x3 小方块 */
    public static void launchpadIcon(Canvas c, RectF r) {
        float w = r.width();
        float s = w * 0.155f;
        float gap = (w - s * 3f) / 4f;
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                float l = r.left + gap + x * (s + gap);
                float t = r.top + gap + y * (s + gap);
                TMP.set(l, t, l + s, t + s);
                P.setColor(y == 1 && x == 1 ? 0xFFFFFFFF : 0xDFFFFFFF);
                c.drawRoundRect(TMP, s * 0.24f, s * 0.24f, P);
            }
        }
    }

    /** 苹果标 */
    public static void apple(Canvas c, float cx, float cy, float h, int color) {
        float s = h;
        PATH.reset();
        PATH.moveTo(cx, cy - 0.12f * s);
        PATH.cubicTo(cx - 0.44f * s, cy - 0.56f * s, cx - 0.96f * s, cy + 0.08f * s, cx - 0.54f * s, cy + 0.60f * s);
        PATH.cubicTo(cx - 0.32f * s, cy + 0.92f * s, cx + 0.32f * s, cy + 0.92f * s, cx + 0.54f * s, cy + 0.60f * s);
        PATH.cubicTo(cx + 0.96f * s, cy + 0.08f * s, cx + 0.44f * s, cy - 0.56f * s, cx, cy - 0.12f * s);
        PATH.close();
        PATH2.reset();
        PATH2.addCircle(cx + 0.82f * s, cy + 0.12f * s, 0.26f * s, Path.Direction.CW);
        PATH.op(PATH2, Path.Op.DIFFERENCE);

        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.drawPath(PATH, P);

        // 叶子
        c.save();
        c.translate(cx + 0.02f * s, cy - 0.40f * s);
        c.rotate(-32f);
        TMP.set(-0.10f * s, -0.20f * s, 0.11f * s, 0.04f * s);
        c.drawOval(TMP, P);
        c.restore();
    }

    /** Wi-Fi 弧形图标 */
    public static void wifi(Canvas c, float cx, float cy, float r, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1f, r * 0.13f));
        P.setColor(color);
        float rr = r;
        for (int i = 0; i < 3; i++) {
            float rad = rr - i * (r * 0.40f);
            if (rad < 1f) break;
            TMP.set(cx - rad, cy - rad + r * 0.55f, cx + rad, cy + rad + r * 0.55f);
            c.drawArc(TMP, 215f, 110f, false, P);
        }
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.drawCircle(cx, cy + r * 0.42f, r * 0.11f, P);
    }

    /** 电池图标 */
    public static void battery(Canvas c, float x, float y, float w, float h, float level, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.4f);
        P.setColor(color);
        TMP.set(x, y, x + w, y + h);
        c.drawRoundRect(TMP, h * 0.32f, h * 0.32f, P);
        P.setStyle(Paint.Style.FILL);
        TMP.set(x + w + 1.5f, y + h * 0.32f, x + w + 3.2f, y + h * 0.68f);
        c.drawRoundRect(TMP, 1f, 1f, P);
        TMP.set(x + 2.4f, y + 2.4f, x + 2.4f + (w - 4.8f) * level, y + h - 2.4f);
        c.drawRoundRect(TMP, h * 0.22f, h * 0.22f, P);
    }

    /** 放大镜 */
    public static void magnifier(Canvas c, float cx, float cy, float r, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.4f);
        P.setColor(color);
        c.drawCircle(cx, cy, r, P);
        c.drawLine(cx + r * 0.68f, cy + r * 0.68f, cx + r * 1.45f, cy + r * 1.45f, P);
    }

    /** 控制中心图标：两条拨杆 */
    public static void controlCenter(Canvas c, float cx, float cy, float w, int color) {
        float h = w * 0.52f;
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(1.4f);
        P.setColor(color);
        TMP.set(cx - w / 2f, cy - h * 0.62f, cx + w / 2f, cy - h * 0.62f + h);
        c.drawRoundRect(TMP, h / 2f, h / 2f, P);
        TMP.set(cx - w / 2f, cy + h * 0.62f - h, cx + w / 2f, cy + h * 0.62f);
        c.drawRoundRect(TMP, h / 2f, h / 2f, P);

        P.setStyle(Paint.Style.FILL);
        float rr = h * 0.62f;
        c.drawCircle(cx - w * 0.18f, cy - h * 0.12f, rr, P);
        c.drawCircle(cx + w * 0.18f, cy + h * 0.12f, rr, P);
    }

    public static void chevron(Canvas c, float x, float y, float s, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1.2f, s * 0.16f));
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setColor(color);
        c.drawLine(x - s * 0.25f, y - s * 0.5f, x + s * 0.25f, y, P);
        c.drawLine(x + s * 0.25f, y, x - s * 0.25f, y + s * 0.5f, P);
        P.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 左/右箭头（日历翻月） */
    public static void chevron2(Canvas c, float x, float y, float s, int color, boolean left) {
        P.setShader(null);
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(Math.max(1.4f, s * 0.14f));
        P.setStrokeCap(Paint.Cap.ROUND);
        P.setColor(color);
        float d = left ? -1f : 1f;
        c.drawLine(x - d * s * 0.28f, y - s * 0.5f, x + d * s * 0.28f, y, P);
        c.drawLine(x + d * s * 0.28f, y, x - d * s * 0.28f, y + s * 0.5f, P);
        P.setStrokeCap(Paint.Cap.BUTT);
    }

    /** 齿轮 */
    public static void gear(Canvas c, float cx, float cy, float r, int color) {
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        c.save();
        c.translate(cx, cy);
        for (int i = 0; i < 8; i++) {
            c.drawRect(-r * 0.13f, -r * 1.02f, r * 0.13f, -r * 0.72f, P);
            c.rotate(45f);
        }
        c.restore();
        P.setStyle(Paint.Style.STROKE);
        P.setStrokeWidth(r * 0.26f);
        c.drawCircle(cx, cy, r * 0.58f, P);
        P.setStyle(Paint.Style.FILL);
    }

    /** 设置行里的小图标（画在彩色圆角块上） */
    public static void glyph(Canvas c, int kind, RectF tile, int color) {
        float cx = tile.centerX(), cy = tile.centerY(), r = tile.width() * 0.30f;
        P.setShader(null);
        P.setStyle(Paint.Style.FILL);
        P.setColor(color);
        switch (kind) {
            case 0: { // 图片
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(tile.width() * 0.05f);
                TMP.set(tile.left + tile.width() * 0.24f, tile.top + tile.width() * 0.24f,
                        tile.right - tile.width() * 0.24f, tile.bottom - tile.width() * 0.24f);
                c.drawRoundRect(TMP, tile.width() * 0.10f, tile.width() * 0.10f, P);
                P.setStyle(Paint.Style.FILL);
                c.drawCircle(tile.left + tile.width() * 0.38f, tile.top + tile.width() * 0.38f,
                        tile.width() * 0.055f, P);
                PATH.reset();
                PATH.moveTo(tile.left + tile.width() * 0.28f, tile.bottom - tile.width() * 0.30f);
                PATH.lineTo(tile.left + tile.width() * 0.46f, tile.top + tile.width() * 0.46f);
                PATH.lineTo(tile.right - tile.width() * 0.26f, tile.bottom - tile.width() * 0.30f);
                PATH.close();
                c.drawPath(PATH, P);
                break;
            }
            case 1: { // 网格
                float s = r * 0.86f;
                float gap = r * 0.30f;
                for (int i = 0; i < 2; i++) {
                    for (int j = 0; j < 2; j++) {
                        TMP.set(cx - s - gap / 2f + i * (s + gap), cy - s - gap / 2f + j * (s + gap),
                                cx - gap / 2f + i * (s + gap), cy - gap / 2f + j * (s + gap));
                        c.drawRoundRect(TMP, s * 0.20f, s * 0.20f, P);
                    }
                }
                break;
            }
            case 2: { // 星光
                PATH.reset();
                float o = r * 1.15f, in = r * 0.22f;
                PATH.moveTo(cx, cy - o);
                PATH.lineTo(cx + in, cy - in);
                PATH.lineTo(cx + o, cy);
                PATH.lineTo(cx + in, cy + in);
                PATH.lineTo(cx, cy + o);
                PATH.lineTo(cx - in, cy + in);
                PATH.lineTo(cx - o, cy);
                PATH.lineTo(cx - in, cy - in);
                PATH.close();
                c.drawPath(PATH, P);
                break;
            }
            case 3: { // 循环箭头
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(tile.width() * 0.055f);
                TMP.set(cx - r, cy - r, cx + r, cy + r);
                c.drawArc(TMP, -160f, 300f, false, P);
                P.setStyle(Paint.Style.FILL);
                PATH.reset();
                PATH.moveTo(cx + r * 0.30f, cy - r * 1.10f);
                PATH.lineTo(cx + r * 1.10f, cy - r * 0.60f);
                PATH.lineTo(cx + r * 0.20f, cy - r * 0.30f);
                PATH.close();
                c.drawPath(PATH, P);
                break;
            }
            case 4: { // 垃圾桶
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(tile.width() * 0.05f);
                TMP.set(cx - r * 0.72f, cy - r * 0.50f, cx + r * 0.72f, cy + r);
                c.drawRoundRect(TMP, r * 0.16f, r * 0.16f, P);
                P.setStyle(Paint.Style.FILL);
                c.drawRect(cx - r * 0.95f, cy - r * 0.78f, cx + r * 0.95f, cy - r * 0.62f, P);
                c.drawRect(cx - r * 0.28f, cy - r * 1.06f, cx + r * 0.28f, cy - r * 0.86f, P);
                break;
            }
            case 5:
                gear(c, cx, cy, r, color);
                break;
            default: { // 信息
                P.setStyle(Paint.Style.STROKE);
                P.setStrokeWidth(tile.width() * 0.05f);
                c.drawCircle(cx, cy, r, P);
                P.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - r * 0.52f, tile.width() * 0.045f, P);
                c.drawRect(cx - tile.width() * 0.045f, cy - r * 0.16f,
                        cx + tile.width() * 0.045f, cy + r * 0.62f, P);
                break;
            }
        }
    }
}
