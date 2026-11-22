package com.gemjiang.launcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * 仿 macOS Big Sur / Sonoma 的流体壁纸。
 * 只在切换主题时渲染一次，缓存成一张 1920x1080 位图，每帧只做一次 drawBitmap。
 */
public final class Wallpaper {

    public static final String[] NAMES = { "海岸", "石墨", "金晖" };

    private static final float W = 1920f;
    private static final float H = 1080f;

    /** 底色三站渐变 */
    private static final int[][] BASE = {
            { 0xFF05060F, 0xFF0C0A22, 0xFF231437 },
            { 0xFF08080A, 0xFF131317, 0xFF232329 },
            { 0xFF110509, 0xFF29101B, 0xFF3A1622 }
    };

    private static Bitmap cache;
    private static int cacheIdx = -1;

    private static final Paint BP = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final Path RP = new Path();
    private static final Paint RIB = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF RF = new RectF();

    private Wallpaper() {
    }

    public static void draw(Canvas c, int idx) {
        if (idx < 0 || idx >= NAMES.length) idx = 0;
        if (cache == null || cache.isRecycled() || cacheIdx != idx) {
            if (cache != null && !cache.isRecycled()) cache.recycle();
            try {
                cache = build(idx);
            } catch (Throwable t) {
                cache = null;
            }
            cacheIdx = idx;
        }
        BP.setFilterBitmap(true);
        RF.set(0f, 0f, W, H);
        if (cache != null && !cache.isRecycled()) {
            c.drawBitmap(cache, null, RF, BP);
        } else {
            c.drawColor(0xFF08080F);
        }
    }

    private static Bitmap build(int idx) {
        // 半分辨率渲染再放大：电视上首帧渲染快 4 倍，放大时天然带柔化
        Bitmap b = Bitmap.createBitmap((int) (W / 2f), (int) (H / 2f), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(0.5f, 0.5f);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0f, 0f, 820f, H, BASE[idx], null, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, W, H, p);
        p.setShader(null);

        if (idx == 0) {
            ribbon(c, -90, 250f, 420f, 40f, 1000f, 500f, 2010, 200f, 0x552BD9E8, 165f);
            ribbon(c, -90, 690f, 540f, 910f, 1080f, 350f, 2010, 640f, 0x4A4A63FF, 235f);
            ribbon(c, -90, 990f, 610f, 760f, 1250f, 1085f, 2010, 850f, 0x44E24A9B, 205f);
            ribbon(c, -90, 70f, 720f, 330f, 1200f, -100f, 2010, 110f, 0x3EFF8A4C, 125f);
            glow(c, 380f, 210f, 640f, 0x332BD9E8);
            glow(c, 1480f, 930f, 700f, 0x33E24A9B);
            glow(c, 1180f, 330f, 560f, 0x2A4A63FF);
        } else if (idx == 1) {
            ribbon(c, -90, 300f, 430f, 120f, 980f, 560f, 2010, 280f, 0x30FFFFFF, 190f);
            ribbon(c, -90, 730f, 560f, 940f, 1100f, 420f, 2010, 700f, 0x2AB9C4FF, 250f);
            ribbon(c, -90, 960f, 640f, 780f, 1260f, 1080f, 2010, 880f, 0x22FFC9E0, 200f);
            glow(c, 420f, 240f, 680f, 0x1FFFFFFF);
            glow(c, 1460f, 900f, 720f, 0x1FB9C4FF);
        } else {
            ribbon(c, -90, 980f, 520f, 720f, 1100f, 1090f, 2010, 820f, 0x55FF9A55, 210f);
            ribbon(c, -90, 700f, 620f, 900f, 1180f, 420f, 2010, 640f, 0x4AE8547A, 250f);
            ribbon(c, -90, 330f, 500f, 60f, 1020f, 560f, 2010, 260f, 0x44C24BFF, 200f);
            ribbon(c, -90, 120f, 760f, 320f, 1240f, -90f, 2010, 180f, 0x40FFD36E, 130f);
            glow(c, 400f, 880f, 680f, 0x3AFF8A4C);
            glow(c, 1500f, 240f, 660f, 0x33E8547A);
        }

        // 暗角
        Paint v = new Paint(Paint.ANTI_ALIAS_FLAG);
        v.setShader(new RadialGradient(W * 0.5f, H * 0.46f, 1220f,
                0x00000000, 0x84000000, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, W, H, v);

        // 底部压暗，让 Dock 上的图标更跳
        Paint btm = new Paint(Paint.ANTI_ALIAS_FLAG);
        btm.setShader(new LinearGradient(0f, H * 0.62f, 0f, H,
                0x00000000, 0x48000000, Shader.TileMode.CLAMP));
        c.drawRect(0f, H * 0.62f, W, H, btm);

        return b;
    }

    /** 一条柔边彩色飘带：核心描边 + 多层加宽淡描边，模拟高斯模糊 */
    private static void ribbon(Canvas c, float x0, float y0, float c1x, float c1y,
                               float c2x, float c2y, float x1, float y1, int color, float w) {
        RP.reset();
        RP.moveTo(x0, y0);
        RP.cubicTo(c1x, c1y, c2x, c2y, x1, y1);
        RIB.setStyle(Paint.Style.STROKE);
        RIB.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 4; i >= 0; i--) {
            float ww = w * (1f + 0.85f * i);
            float a;
            if (i == 0) a = 1f;
            else a = 0.30f / i;
            RIB.setStrokeWidth(ww);
            RIB.setColor(alpha(color, a));
            c.drawPath(RP, RIB);
        }
        RIB.setStrokeCap(Paint.Cap.BUTT);
    }

    private static void glow(Canvas c, float x, float y, float r, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new RadialGradient(x, y, r, color, color & 0x00FFFFFF, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, W, H, p);
    }

    private static int alpha(int color, float a) {
        int out = (int) (((color >>> 24) & 0xFF) * a + 0.5f);
        if (out > 255) out = 255;
        return (out << 24) | (color & 0x00FFFFFF);
    }
}
