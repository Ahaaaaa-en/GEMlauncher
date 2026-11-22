package com.gemjiang.launcher;

import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * v1.2：虚拟光标 + 窗口。
 * 方向键推动一支自由移动的虚拟光标（有加速、有惯性、有磁吸收敛），光标指到哪儿，
 * 焦点就“汇合”到哪儿；OK 当鼠标左键用；窗口能用光标按住标题栏拖动、红点关闭、点击置顶。
 */
public class DesktopView extends View {

    // ---------- 设计坐标 ----------
    static final float DW = 1920f;
    static final float DH = 1080f;
    static final float MB_H = 44f;

    static final int D_COLS = 6, D_ROWS = 4, D_PER_PAGE = D_COLS * D_ROWS;
    static final float D_X0 = 138f, D_Y0 = 146f, D_CW = 190f, D_CH = 176f;
    static final float ICON_D = 88f;

    static final int L_COLS = 7, L_ROWS = 5, L_PER_PAGE = L_COLS * L_ROWS;
    static final float L_X0 = 120f, L_Y0 = 140f, L_CW = 240f, L_CH = 176f, L_ICON = 104f;

    static final float DOCK_ITEM = 92f, DOCK_GAP = 20f, DOCK_H = 140f, DOCK_BOTTOM = 14f, DIV_W = 26f;

    static final int W_SET = 1, W_FINDER = 2, W_ABOUT = 3, W_HELP = 4, W_CLOCK = 5, W_CAL = 6;

    static final float CURSOR_SPEED = 900f;    // 中速基准，设计像素/秒

    // ---------- 灵动岛 ----------
    static final float ISL_TOP = 52f;
    static final float ISL_W0 = 208f, ISL_W1 = 660f, ISL_H0 = 40f, ISL_H1 = 196f;

    // ---------- 窗口 ----------
    static final class Win {
        final int id;
        final int type;
        float x, y, w, h;
        int page = 0;

        Win(int id, int type, float x, float y, float w, float h) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }
    }

    private static final class Hit {
        final String id;
        final RectF r;

        Hit(String id, RectF r) {
            this.id = id;
            this.r = r;
        }
    }

    private final List<Win> wins = new ArrayList<Win>();
    private int nextWinId = 0;

    // ---------- 光标 ----------
    private float curX = DW * 0.5f, curY = DH * 0.46f;
    private float velX = 0f, velY = 0f;
    private boolean kL, kR, kU, kD;
    private boolean cursorMode = true;
    private String hoverId = "";
    private float hoverBurst = 0f;
    private float ringR = 15f;
    // 光标速度档：0 慢 / 1 中 / 2 快
    private int speedMode = 1;

    private boolean pressing = false;
    private long pressAt = 0L;
    private boolean pressConsumed = false;
    private float pressX = 0f, pressY = 0f;

    private Win dragWin = null;
    private float dragOffX = 0f, dragOffY = 0f;
    private boolean dragMoved = false;

    // ---------- 状态 ----------
    private boolean lpOpen = false;
    private int lpPage = 0;
    private int page = 0;

    private final List<Hit> hits = new ArrayList<Hit>();
    private final Path clip = new Path();
    private final Paint cp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();

    private final Apps apps;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SharedPreferences sp;

    private float scale = 1f, offX = 0f, offY = 0f;
    private int vw = 1920, vh = 1080;

    private int wpIndex = 0;
    private boolean animOn = true;
    private int iconScaleMode = 1;

    // 灵动岛运行时状态
    private float islT = 0f;            // 0 收起 / 1 展开
    private boolean islOpen = false;    // 是否展开
    private boolean islAuto = false;    // 自动收起
    private long islAutoUntil = 0L;
    private String islEv = "";          // 临时事件文案
    private Bitmap islEvIcon = null;
    private long islEvUntil = 0L;
    private long islTimerEnd = 0L;      // 计时结束时刻
    private final AudioManager audio;   // 音量与音频状态
    private long audChkAt = 0L;         // isMusicActive 每秒最多查一次
    private boolean audChk = false;

    private final List<String> dockPkgs = new ArrayList<String>();
    private final List<Apps.AppInfo> dockApps = new ArrayList<Apps.AppInfo>();
    private final List<Float> dockX = new ArrayList<Float>();
    private float dockW = 0f;
    private float dockCursor = DW / 2f, dockCursorTo = DW / 2f, dockMag = 0f, dockMagTo = 0f;

    private final String[] MENU_TXT = { "访达", "文件", "编辑", "显示", "前往", "窗口", "系统设置", "帮助" };
    private final String[] MENU_ID = { "mb:0", "mb:1", "mb:2", "mb:3", "mb:4", "mb:5", "mb:6", "mb:7" };

    // 下拉菜单：{文字, 动作 id, 快捷键}；文字为 "-" 是分隔线；id 为空表示灰掉不可点
    private static final String[][] M0 = {
            { "关于 Gem桌面", "m:about", "" },
            { "系统设置…", "m:settings", "⌘," },
            { "-", "-", "" },
            { "新建访达窗口", "m:finder", "⌘N" },
            { "关闭全部窗口", "m:closeall", "⌥W" },
            { "-", "-", "" },
            { "清空 Dock", "m:dockreset", "" },
            { "重新载入应用列表", "m:reload", "⌘R" },
            { "-", "-", "" },
            { "睡眠", "", "" },
            { "重新启动…", "", "" },
            { "关机…", "", "" }
    };
    private static final String[][] M1 = {
            { "新建访达窗口", "m:finder", "⌘N" },
            { "打开系统设置", "m:settings", "" },
            { "-", "-", "" },
            { "层叠所有窗口", "m:cascade", "" },
            { "关闭全部窗口", "m:closeall", "⌘W" },
            { "-", "-", "" },
            { "回到桌面", "m:home", "⇧⌘D" },
            { "打开启动台", "m:launchpad", "⇧⌘L" },
            { "-", "-", "" },
            { "清空废纸篓", "", "" }
    };
    private static final String[][] M2 = {
            { "撤销", "", "⌘Z" },
            { "重做", "", "⇧⌘Z" },
            { "-", "-", "" },
            { "剪切", "", "⌘X" },
            { "拷贝", "", "⌘C" },
            { "粘贴", "", "⌘V" },
            { "-", "-", "" },
            { "全选", "", "⌘A" }
    };
    private static final String[][] M3 = {
            { "图标：小", "m:icons0", "" },
            { "图标：中", "m:icons1", "" },
            { "图标：大", "m:icons2", "" },
            { "-", "-", "" },
            { "更换壁纸", "m:wallpaper", "" },
            { "焦点动画", "m:anim", "" },
            { "-", "-", "" },
            { "显示网格", "", "" },
            { "进入全屏", "", "" }
    };
    private static final String[][] M4 = {
            { "应用程序", "m:finder", "⇧⌘A" },
            { "启动台", "m:launchpad", "⇧⌘L" },
            { "系统设置", "m:settings", "" },
            { "关于 Gem桌面", "m:about", "" },
            { "-", "-", "" },
            { "回到桌面", "m:home", "⇧⌘D" }
    };
    private static final String[][] M5 = {
            { "访达 — 应用程序", "m:finder", "" },
            { "系统设置", "m:settings", "" },
            { "关于本机", "m:about", "" },
            { "-", "-", "" },
            { "把窗口置顶", "m:front", "" },
            { "层叠所有窗口", "m:cascade", "" },
            { "关闭全部窗口", "m:closeall", "⌥W" }
    };
    private static final String[][] M7 = {
            { "操作说明", "m:help", "" },
            { "关于 Gem桌面", "m:about", "" },
            { "-", "-", "" },
            { "版本 1.3.3", "", "" }
    };

    private int menuOpen = -1;
    private float menuPanelX = 0f, menuPanelW = 0f, menuPanelH = 0f;
    private final List<Hit> menuItems = new ArrayList<Hit>();
    private final float[] menuX = new float[8];
    private final float[] menuW = new float[8];

    private String toastText = null;
    private long toastEnd = 0L;

    private String clockStr = "--:--", dateStr = "", monthStr = "", dayStr = "", weekStr = "";
    private int firstDow = 0, daysInMonth = 30, todayNum = 1;
    private String[] WEEK = { "周日", "周一", "周二", "周三", "周四", "周五", "周六" };

    private boolean animating = false;
    private long lastRefreshAt = 0L;

    // 命中表按需重建：布局/状态变化才标脏，不在每帧分配
    private boolean hitsDirty = true;
    // 16ms 独立帧循环：位移由定时器按真实时间推进，不再依赖按键重复事件
    private final Handler frameHandler = new Handler(Looper.getMainLooper());
    private final Runnable frameTick = new Runnable() {
        @Override
        public void run() {
            long now = SystemClock.uptimeMillis();
            float dt = lastTick == 0L ? 16f : (float) (now - lastTick);
            if (dt > 64f) dt = 64f;
            lastTick = now;
            try {
                if (hitsDirty) rebuildHits();
                step(dt);
            } catch (Throwable t) {
                animating = false;
            }
            if (animating || keysHeld()) {
                invalidate();
                frameHandler.postDelayed(frameTick, 16L);
            } else {
                frameRunning = false;
            }
        }
    };
    private boolean frameRunning = false;
    private long lastTick = 0L;

    /** 四个方向键是否还有按住的 */
    private boolean keysHeld() {
        return kL || kR || kU || kD;
    }

    /** 看门狗：每秒检查一次，循环断了就重新接上，不让光标永久卡死 */
    private void frameWatchdog() {
        if (!keysHeld()) return;
        long now = SystemClock.uptimeMillis();
        if (!frameRunning || lastTick == 0L || now - lastTick > 200L) {
            lastTick = 0L;
            animating = true;
            if (!frameRunning) {
                frameRunning = true;
                frameHandler.removeCallbacks(frameTick);
                frameHandler.postDelayed(frameTick, 16L);
            }
            invalidate();
        }
    }
    // 输入诊断：帮助窗口里显示最近按键与光标坐标，便于判断是“没收到键”还是“收到没动”
    private String secStr = "00";
    private int monNum = 1;
    private int yearNum = 0;
    private String lastKeyDesc = "—";
    private long keySeq = 0L;

    // ---------- 构造 ----------
    public DesktopView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        setBackgroundColor(0xFF000000);
        apps = new Apps(c);
        sp = c.getSharedPreferences("gem_desktop", Context.MODE_PRIVATE);
        audio = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        loadPrefs();
        refreshClock();
        setWillNotDraw(false);
        slotDock();
        layoutMenu();
    }

    private void loadPrefs() {
        wpIndex = sp.getInt("wp", 0);
        if (wpIndex < 0 || wpIndex >= Wallpaper.NAMES.length) wpIndex = 0;
        animOn = sp.getBoolean("anim", true);
        iconScaleMode = sp.getInt("iconscale", 1);
        if (iconScaleMode < 0 || iconScaleMode > 2) iconScaleMode = 1;
        cursorMode = sp.getBoolean("cursor", true);
        speedMode = sp.getInt("cspeed", 1);
        if (speedMode < 0 || speedMode > 2) speedMode = 1;
        String dock = sp.getString("dock", "");
        dockPkgs.clear();
        if (dock != null && dock.length() > 0) {
            String[] parts = dock.split(",");
            for (int i = 0; i < parts.length; i++) {
                String s = parts[i].trim();
                if (s.length() > 0 && !dockPkgs.contains(s)) dockPkgs.add(s);
            }
        }
    }

    private void saveDock() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dockPkgs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(dockPkgs.get(i));
        }
        sp.edit().putString("dock", sb.toString()).apply();
    }

    public void start() {
        refreshClock();
        resolveDock();
        slotDock();
        refreshApps();
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 1000L);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacks(ticker);
        frameHandler.removeCallbacks(frameTick);
        frameRunning = false;
        super.onDetachedFromWindow();
    }

    public void onResumeRefresh() {
        long now = SystemClock.uptimeMillis();
        if (now - lastRefreshAt < 800L) {
            if (refreshClock()) invalidate();
            return;
        }
        lastRefreshAt = now;
        refreshApps();
    }

    private void refreshApps() {
        apps.refresh(new Runnable() {
            @Override
            public void run() {
                resolveDock();
                slotDock();
                clampPages();
                hitsDirty = true;
                invalidate();
            }
        }, new Runnable() {
            @Override
            public void run() {
                invalidate();
            }
        });
    }

    // ---------- 尺寸 ----------
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w <= 0 || h <= 0) return;
        vw = w;
        vh = h;
        scale = Math.min(w / DW, h / DH);
        if (scale <= 0f) scale = 1f;
        offX = (w - DW * scale) / 2f;
        offY = (h - DH * scale) / 2f;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        requestFocus();
    }

    // ---------- 几何 ----------
    private RectF deskCellAt(int slot) {
        int col = slot % D_COLS;
        int row = slot / D_COLS;
        return new RectF(D_X0 + col * D_CW, D_Y0 + row * D_CH,
                D_X0 + (col + 1) * D_CW, D_Y0 + (row + 1) * D_CH);
    }

    private RectF lpCellAt(int slot) {
        int col = slot % L_COLS;
        int row = slot / L_COLS;
        return new RectF(L_X0 + col * L_CW, L_Y0 + row * L_CH,
                L_X0 + (col + 1) * L_CW, L_Y0 + (row + 1) * L_CH);
    }

    private int pageCount() {
        int n = apps.all.size();
        return n <= 0 ? 1 : (n + D_PER_PAGE - 1) / D_PER_PAGE;
    }

    private int lpPageCount() {
        int n = apps.all.size();
        return n <= 0 ? 1 : (n + L_PER_PAGE - 1) / L_PER_PAGE;
    }

    private void clampPages() {
        if (page >= pageCount()) page = pageCount() - 1;
        if (page < 0) page = 0;
        if (lpPage >= lpPageCount()) lpPage = lpPageCount() - 1;
        if (lpPage < 0) lpPage = 0;
    }

    private float iconFactor() {
        if (iconScaleMode == 0) return 0.90f;
        if (iconScaleMode == 2) return 1.14f;
        return 1f;
    }

    // ---------- Dock 布局 ----------
    private void slotDock() {
        dockX.clear();
        float total = DOCK_ITEM;
        for (int i = 0; i < dockApps.size(); i++) total += DOCK_GAP + DOCK_ITEM;
        total += DIV_W + DOCK_ITEM;
        dockW = total;
        float x = (DW - total) / 2f;
        dockX.add(x + DOCK_ITEM / 2f);
        x += DOCK_ITEM;
        for (int i = 0; i < dockApps.size(); i++) {
            x += DOCK_GAP;
            dockX.add(x + DOCK_ITEM / 2f);
            x += DOCK_ITEM;
        }
        x += DIV_W;
        dockX.add(x + DOCK_ITEM / 2f);
        hitsDirty = true;
    }

    private float dockTop() {
        return DH - DOCK_BOTTOM - DOCK_H;
    }

    private int dockIndexUnder(float x) {
        for (int k = 0; k < dockX.size(); k++) {
            if (Math.abs(x - dockX.get(k)) < (DOCK_ITEM + DOCK_GAP) / 2f) return k;
        }
        return -1;
    }

    private void resolveDock() {
        dockApps.clear();
        for (int i = 0; i < dockPkgs.size(); i++) {
            Apps.AppInfo a = apps.byPkg.get(dockPkgs.get(i));
            if (a != null) dockApps.add(a);
        }
        if (dockApps.isEmpty() && dockPkgs.isEmpty() && apps.all.size() > 0) {
            int n = Math.min(7, apps.all.size());
            for (int i = 0; i < n; i++) dockPkgs.add(apps.all.get(i).pkg);
            saveDock();
            for (int i = 0; i < dockPkgs.size(); i++) {
                Apps.AppInfo a = apps.byPkg.get(dockPkgs.get(i));
                if (a != null) dockApps.add(a);
            }
        }
    }

    private String[][] menuDef(int i) {
        switch (i) {
            case 0: return M0;
            case 1: return M1;
            case 2: return M2;
            case 3: return M3;
            case 4: return M4;
            case 5: return M5;
            case 7: return M7;
            default: return null;
        }
    }

    private String menuLabel(String[] row) {
        String id = row[1];
        String lab = row[0];
        if (id.equals("m:icons0") && iconScaleMode == 0) return "✓ " + lab;
        if (id.equals("m:icons1") && iconScaleMode == 1) return "✓ " + lab;
        if (id.equals("m:icons2") && iconScaleMode == 2) return "✓ " + lab;
        return lab;
    }

    private String menuShortcut(String[] row) {
        if (row[1].equals("m:anim")) return animOn ? "开" : "关";
        return row[2];
    }

    private void layoutMenuPanel() {
        menuItems.clear();
        String[][] rows = menuDef(menuOpen);
        if (rows == null) {
            menuOpen = -1;
            return;
        }
        float maxL = 0f, maxS = 0f;
        for (int i = 0; i < rows.length; i++) {
            if (rows[i][0].equals("-")) continue;
            float l = Painter.measure(menuLabel(rows[i]), 14f, Painter.TFM);
            if (l > maxL) maxL = l;
            String sc = menuShortcut(rows[i]);
            if (sc.length() > 0) {
                float sw = Painter.measure(sc, 13f, Painter.TF);
                if (sw > maxS) maxS = sw;
            }
        }
        float w = 18f + maxL + (maxS > 0f ? 26f + maxS : 0f) + 18f;
        if (w < 190f) w = 190f;
        float h = 8f;
        for (int i = 0; i < rows.length; i++) h += rows[i][0].equals("-") ? 9f : 28f;
        h += 8f;
        menuPanelX = clampF(menuX[menuOpen] - 16f, 8f, DW - w - 8f);
        menuPanelW = w;
        menuPanelH = h;

        float cy = MB_H + 10f;
        for (int i = 0; i < rows.length; i++) {
            if (rows[i][0].equals("-")) {
                cy += 9f;
                continue;
            }
            menuItems.add(new Hit("mm:" + i, new RectF(menuPanelX + 6f, cy, menuPanelX + w - 6f, cy + 28f)));
            cy += 28f;
        }
        hitsDirty = true;
    }

    private void openMenu(int i) {
        menuOpen = i;
        layoutMenuPanel();
        hoverId = "";
        hitsDirty = true;
        invalidate();
    }

    private void closeMenu() {
        menuOpen = -1;
        menuItems.clear();
        hitsDirty = true;
        invalidate();
    }

    private void layoutMenu() {
        float x = 56f;
        for (int i = 0; i < MENU_TXT.length; i++) {
            menuW[i] = Painter.measure(MENU_TXT[i], 16f, Painter.TFM);
            menuX[i] = x;
            x += menuW[i] + 18f;
        }
    }

    // ---------- 窗口 ----------
    private Win findWin(int type) {
        for (int i = 0; i < wins.size(); i++) {
            if (wins.get(i).type == type) return wins.get(i);
        }
        return null;
    }

    private void openWindow(int type) {
        Win w = findWin(type);
        if (w != null) {
            raise(w);
            toast("窗口已置顶");
            return;
        }
        float ww, wh;
        if (type == W_SET) {
            ww = 1000f;
            wh = 560f;
        } else if (type == W_FINDER) {
            ww = 1040f;
            wh = 600f;
        } else if (type == W_HELP) {
            ww = 680f;
            wh = 560f;
        } else if (type == W_CLOCK) {
            ww = 780f;
            wh = 460f;
        } else if (type == W_CAL) {
            ww = 900f;
            wh = 600f;
        } else {
            ww = 640f;
            wh = 460f;
        }
        int n = wins.size();
        float x = 380f + n * 34f;
        float y = 268f + n * 28f;
        if (x + ww > DW - 60f) x = DW - 60f - ww;
        if (y + wh > DH - 190f) y = DH - 190f - wh;
        w = new Win(nextWinId++, type, x, y, ww, wh);
        wins.add(w);
        hitsDirty = true;
        invalidate();
    }

    private void raise(Win w) {
        hitsDirty = true;
        if (wins.isEmpty() || wins.get(wins.size() - 1) == w) return;
        wins.remove(w);
        wins.add(w);
        invalidate();
    }

    private void closeWin(Win w) {
        wins.remove(w);
        hitsDirty = true;
        if (dragWin == w) dragWin = null;
        invalidate();
    }

    private Win winById(int id) {
        for (int i = 0; i < wins.size(); i++) if (wins.get(i).id == id) return wins.get(i);
        return null;
    }

    private RectF winTitleRect(Win w) {
        return new RectF(w.x + 44f, w.y, w.x + w.w - 44f, w.y + 40f);
    }

    private RectF winCloseRect(Win w) {
        return new RectF(w.x + 6f, w.y + 2f, w.x + 48f, w.y + 38f);
    }

    private RectF setRowRect(Win w, int i) {
        float left = w.x + 226f, right = w.x + w.w - 24f;
        float top = w.y + 44f + 20f + i * 54f;
        return new RectF(left, top, right, top + 54f);
    }

    private RectF finderCell(Win w, int i) {
        int col = i % 6, row = i / 6;
        float x0 = w.x + 40f, y0 = w.y + 44f + 64f;
        float cw = (w.w - 80f) / 6f, ch = 150f;
        return new RectF(x0 + col * cw, y0 + row * ch, x0 + (col + 1) * cw, y0 + (row + 1) * ch);
    }

    // ---------- 命中列表 ----------
    private void rebuildHits() {
        if (!hitsDirty) return;
        hitsDirty = false;
        hits.clear();
        if (lpOpen) {
            int total = apps.all.size();
            int start = lpPage * L_PER_PAGE;
            for (int i = 0; i < L_PER_PAGE; i++) {
                int gi = start + i;
                if (gi >= total) break;
                hits.add(new Hit("lp:" + gi, lpCellAt(i)));
            }
            int pc = lpPageCount();
            if (pc > 1) {
                float x0 = DW / 2f - (pc - 1) * 20f / 2f;
                for (int i = 0; i < pc; i++) {
                    hits.add(new Hit("lpg:" + i, new RectF(x0 + i * 20f - 10f, 1030f, x0 + i * 20f + 10f, 1058f)));
                }
            }
            return;
        }
        for (int i = wins.size() - 1; i >= 0; i--) {
            Win w = wins.get(i);
            String p = "w:" + w.id + ":";
            hits.add(new Hit(p + "title", winTitleRect(w)));
            hits.add(new Hit(p + "close", winCloseRect(w)));
            if (w.type == W_SET) {
                for (int r = 0; r < 9; r++) hits.add(new Hit(p + "row:" + r, setRowRect(w, r)));
            } else if (w.type == W_FINDER) {
                int total = apps.all.size();
                int start = w.page * 18;
                for (int k = 0; k < 18; k++) {
                    int gi = start + k;
                    if (gi >= total) break;
                    hits.add(new Hit(p + "app:" + gi, finderCell(w, k)));
                }
                hits.add(new Hit(p + "prev", new RectF(w.x + 18f, w.y + w.h - 48f, w.x + 72f, w.y + w.h - 8f)));
                hits.add(new Hit(p + "next", new RectF(w.x + w.w - 72f, w.y + w.h - 48f, w.x + w.w - 18f, w.y + w.h - 8f)));
            } else if (w.type == W_CAL) {
                hits.add(new Hit(p + "prev", new RectF(w.x + 24f, w.y + 52f, w.x + 78f, w.y + 96f)));
                hits.add(new Hit(p + "next", new RectF(w.x + w.w - 78f, w.y + 52f, w.x + w.w - 24f, w.y + 96f)));
            }
            // 窗口整块命中：空白处点击只置顶，不再穿透到底下的图标
            hits.add(new Hit(p + "body", new RectF(w.x, w.y, w.x + w.w, w.y + w.h)));
        }
        for (int i = 0; i < MENU_ID.length; i++) {
            hits.add(new Hit(MENU_ID[i], new RectF(menuX[i] - 12f, 4f, menuX[i] + menuW[i] + 12f, MB_H - 4f)));
        }
        if (menuOpen >= 0) {
            // 下拉菜单盖在所有东西上面，所以放最前
            hits.addAll(0, menuItems);
        }
        if (dockX.size() >= 2) {
            float half = (DOCK_ITEM + DOCK_GAP) / 2f;
            hits.add(new Hit("dock:lp", new RectF(dockX.get(0) - half, dockTop(), dockX.get(0) + half, DH - DOCK_BOTTOM)));
            for (int i = 0; i < dockApps.size(); i++) {
                float cx = dockX.get(i + 1);
                hits.add(new Hit("dock:" + i, new RectF(cx - half, dockTop(), cx + half, DH - DOCK_BOTTOM)));
            }
            float gx = dockX.get(dockX.size() - 1);
            hits.add(new Hit("dock:g", new RectF(gx - half, dockTop(), gx + half, DH - DOCK_BOTTOM)));
        }
        int total = apps.all.size();
        int start = page * D_PER_PAGE;
        for (int i = 0; i < D_PER_PAGE; i++) {
            int gi = start + i;
            if (gi >= total) break;
            hits.add(new Hit("d:" + gi, deskCellAt(i)));
        }
        hits.add(new Hit("z:clock", clockWidgetRect()));
        hits.add(new Hit("z:cal", calWidgetRect()));
        int pc = pageCount();
        if (pc > 1) {
            float cx = D_X0 + D_COLS * D_CW / 2f;
            float x0 = cx - (pc - 1) * 20f / 2f;
            for (int i = 0; i < pc; i++) {
                hits.add(new Hit("pg:" + i, new RectF(x0 + i * 20f - 9f, 872f, x0 + i * 20f + 9f, 898f)));
            }
        }

        // 灵动岛在最上层
        List<Hit> isl = new ArrayList<Hit>();
        if (islOpen) {
            RectF ir = islandHitRect();
            float rx = ir.left + 352f;
            float mc = rx + 62f, bp = ir.right - 68f;
            isl.add(new Hit("is:vol-", new RectF(mc - 20f, ir.top + 53f, mc + 20f, ir.top + 93f)));
            isl.add(new Hit("is:vol+", new RectF(bp - 20f, ir.top + 53f, bp + 20f, ir.top + 93f)));
            isl.add(new Hit("is:timer", new RectF(rx + 58f, ir.top + 116f, rx + 180f, ir.top + 164f)));
        }
        isl.add(new Hit("is:body", islandHitRect()));
        hits.addAll(0, isl);
    }

    private String hitAt(float x, float y) {
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            if (h.r.contains(x, y)) return h.id;
        }
        return null;
    }

    private Hit nearestHit(float x, float y, float radius) {
        Hit best = null;
        float bd = radius * radius;
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            float dx = h.r.centerX() - x, dy = h.r.centerY() - y;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = h;
            }
        }
        return best;
    }

    // ---------- 动作 ----------
    private void clickAt(float x, float y) {
        String id = hitAt(x, y);
        // 岛展开时，点外面只收起岛（跟系统弹层一样）
        if (islOpen && id != null && !id.startsWith("is:")
                && !id.startsWith("mm:") && !id.startsWith("mb:")) {
            islandCloseNow();
            return;
        }
        if (id != null) {
            if (id.equals("is:body")) {
                if (islOpen) islandCloseNow(); else islandOpenNow(false, 0L);
                return;
            }
            if (id.equals("is:vol-")) { volStep(-1); return; }
            if (id.equals("is:vol+")) { volStep(1); return; }
            if (id.equals("is:timer")) { timerTap(); return; }
        }
        if (menuOpen >= 0 && (id == null || (!id.startsWith("mm:") && !id.startsWith("mb:")))) {
            // 点菜单外面：macOS 只收起菜单，不触发下面的东西
            closeMenu();
            return;
        }
        if (id == null) {
            if (lpOpen) closeLaunchpad();
            return;
        }
        if (id.startsWith("mm:")) {
            int mi = toInt(id.substring(3), -1);
            String[][] rows = menuDef(menuOpen);
            if (rows != null && mi >= 0 && mi < rows.length) {
                String act = rows[mi][1];
                closeMenu();
                if (act.length() > 0) menuAction(act);
            }
            return;
        }
        if (id.startsWith("mb:")) {
            int mi = toInt(id.substring(3), -1);
            if (mi == 6) {
                closeMenu();
                openWindow(W_SET);
            } else if (menuOpen == mi) {
                closeMenu();
            } else if (menuDef(mi) != null) {
                openMenu(mi);
            }
        } else if (id.startsWith("pg:")) {
            page = toInt(id.substring(3), 0);
            clampPages();
            hitsDirty = true;
            invalidate();
        } else if (id.startsWith("d:")) {
            int gi = toInt(id.substring(2), -1);
            if (gi >= 0 && gi < apps.all.size()) launchApp(apps.all.get(gi));
        } else if (id.equals("dock:lp")) {
            openLaunchpad();
        } else if (id.equals("dock:g")) {
            openWindow(W_SET);
        } else if (id.startsWith("dock:")) {
            int i = toInt(id.substring(5), -1);
            if (i >= 0 && i < dockApps.size()) launchApp(dockApps.get(i));
        } else if (id.startsWith("lpg:")) {
            lpPage = toInt(id.substring(4), 0);
            clampPages();
            hoverId = "";
            hitsDirty = true;
            invalidate();
        } else if (id.startsWith("lp:")) {
            int gi = toInt(id.substring(3), -1);
            if (gi >= 0 && gi < apps.all.size()) {
                closeLaunchpad();
                launchApp(apps.all.get(gi));
            }
        } else if (id.equals("z:clock")) {
            openWindow(W_CLOCK);
        } else if (id.equals("z:cal")) {
            openWindow(W_CAL);
        } else if (id.startsWith("w:")) {
            winClick(id);
        }
    }

    private void winClick(String id) {
        String rest = id.substring(2);
        int c = rest.indexOf(':');
        if (c < 0) return;
        int wid = toInt(rest.substring(0, c), -1);
        String part = rest.substring(c + 1);
        Win w = winById(wid);
        if (w == null) return;
        if (part.equals("close")) {
            closeWin(w);
            return;
        }
        raise(w);
        if (part.equals("body")) return;
        if (w.type == W_CAL) {
            if (part.equals("prev")) { w.page--; invalidate(); return; }
            if (part.equals("next")) { w.page++; invalidate(); return; }
        }
        if (part.startsWith("row:")) {
            settingsAction(toInt(part.substring(4), 0));
        } else if (part.startsWith("app:")) {
            int gi = toInt(part.substring(4), -1);
            if (gi >= 0 && gi < apps.all.size()) launchApp(apps.all.get(gi));
        } else if (part.equals("prev")) {
            if (w.page > 0) { w.page--; hitsDirty = true; }
            invalidate();
        } else if (part.equals("next")) {
            int pages = (apps.all.size() + 17) / 18;
            if (w.page + 1 < pages) { w.page++; hitsDirty = true; }
            invalidate();
        }
    }

    private void longPressAt(float x, float y) {
        String id = hitAt(x, y);
        if (id == null) return;
        if (id.startsWith("d:")) {
            int gi = toInt(id.substring(2), -1);
            if (gi < 0 || gi >= apps.all.size()) return;
            Apps.AppInfo a = apps.all.get(gi);
            if (dockPkgs.contains(a.pkg)) {
                dockPkgs.remove(a.pkg);
                toast("已从 Dock 移除：" + a.label);
            } else if (dockPkgs.size() >= 8) {
                toast("Dock 已满（最多 8 个）");
                return;
            } else {
                dockPkgs.add(a.pkg);
                toast("已加入 Dock：" + a.label);
            }
            saveDock();
            resolveDock();
            slotDock();
            invalidate();
        } else if (id.startsWith("dock:") && !id.equals("dock:lp") && !id.equals("dock:g")) {
            int i = toInt(id.substring(5), -1);
            if (i < 0 || i >= dockApps.size()) return;
            Apps.AppInfo a = dockApps.get(i);
            dockPkgs.remove(a.pkg);
            saveDock();
            toast("已从 Dock 移除：" + a.label);
            resolveDock();
            slotDock();
            invalidate();
        }
    }

    private void openLaunchpad() {
        menuOpen = -1;
        menuItems.clear();
        lpOpen = true;
        lpPage = 0;
        hoverId = "";
        hoverBurst = 1f;
        invalidate();
    }

    private void closeLaunchpad() {
        lpOpen = false;
        invalidate();
    }

    private void menuAction(String act) {
        if (act.equals("about")) {
            openWindow(W_ABOUT);
        } else if (act.equals("help")) {
            openWindow(W_HELP);
        } else if (act.equals("finder")) {
            openWindow(W_FINDER);
        } else if (act.equals("settings")) {
            openWindow(W_SET);
        } else if (act.equals("closeall")) {
            wins.clear();
            dragWin = null;
            toast("已关闭全部窗口");
        } else if (act.equals("home")) {
            wins.clear();
            dragWin = null;
            closeLaunchpad();
            toast("已回到桌面");
        } else if (act.equals("front")) {
            if (wins.isEmpty()) {
                toast("桌面上没有窗口");
            } else if (wins.size() == 1) {
                raise(wins.get(0));
                toast("只有一个窗口，已置顶");
            } else {
                Win bottom = wins.get(0);
                raise(bottom);
                toast("已把最下层的窗口提到最前");
            }
        } else if (act.equals("cascade")) {
            if (wins.isEmpty()) {
                toast("桌面上没有窗口");
            } else {
                for (int i = 0; i < wins.size(); i++) {
                    Win w = wins.get(i);
                    w.x = clampF(360f + i * 40f, 16f, Math.max(16f, DW - w.w - 16f));
                    w.y = clampF(MB_H + 40f + i * 34f, MB_H + 6f, Math.max(MB_H + 6f, DH - w.h - 8f));
                }
                toast("已层叠 " + wins.size() + " 个窗口");
            }
        } else if (act.equals("reload")) {
            refreshApps();
            toast("正在重新载入应用列表…");
        } else if (act.equals("dockreset")) {
            dockPkgs.clear();
            saveDock();
            resolveDock();
            slotDock();
            toast("Dock 已清空并重新填充");
        } else if (act.equals("launchpad")) {
            openLaunchpad();
        } else if (act.equals("wallpaper")) {
            wpIndex = (wpIndex + 1) % Wallpaper.NAMES.length;
            sp.edit().putInt("wp", wpIndex).apply();
            toast("壁纸：" + Wallpaper.NAMES[wpIndex]);
        } else if (act.equals("anim")) {
            animOn = !animOn;
            sp.edit().putBoolean("anim", animOn).apply();
            toast("焦点动画：" + (animOn ? "打开" : "关闭"));
        } else if (act.startsWith("icons")) {
            int m = toInt(act.substring(5), 1);
            if (m < 0 || m > 2) m = 1;
            iconScaleMode = m;
            sp.edit().putInt("iconscale", m).apply();
            toast("图标大小：" + new String[] { "小", "中", "大" }[m]);
        }
        invalidate();
    }

    private void settingsAction(int row) {
        switch (row) {
            case 0:
                wpIndex = (wpIndex + 1) % Wallpaper.NAMES.length;
                sp.edit().putInt("wp", wpIndex).apply();
                toast("壁纸：" + Wallpaper.NAMES[wpIndex]);
                break;
            case 1:
                iconScaleMode = (iconScaleMode + 1) % 3;
                sp.edit().putInt("iconscale", iconScaleMode).apply();
                toast("图标大小：" + new String[] { "小", "中", "大" }[iconScaleMode]);
                break;
            case 2:
                animOn = !animOn;
                sp.edit().putBoolean("anim", animOn).apply();
                toast("焦点动画：" + (animOn ? "打开" : "关闭"));
                break;
            case 3:
                cursorMode = !cursorMode;
                sp.edit().putBoolean("cursor", cursorMode).apply();
                toast(cursorMode ? "方向键：虚拟光标" : "方向键：逐格跳转");
                break;
            case 4:
                speedMode = (speedMode + 1) % 3;
                sp.edit().putInt("cspeed", speedMode).apply();
                toast("光标速度：" + new String[] { "慢", "中", "快" }[speedMode]);
                break;
            case 5:
                refreshApps();
                toast("正在刷新应用列表…");
                break;
            case 6:
                dockPkgs.clear();
                saveDock();
                resolveDock();
                slotDock();
                toast("Dock 已重置");
                break;
            case 7:
                openSystemSettings();
                break;
            default:
                openWindow(W_ABOUT);
                break;
        }
        invalidate();
    }

    private void openSystemSettings() {
        try {
            Intent it = new Intent(Settings.ACTION_SETTINGS);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(it);
        } catch (Throwable e) {
            toast("无法打开系统设置");
        }
    }

    private boolean canFreeform() {
        try {
            if (Build.VERSION.SDK_INT < 24) return false;
            int v = Settings.Global.getInt(getContext().getContentResolver(), "enable_freeform_support", 0);
            return v == 1;
        } catch (Throwable t) {
            return false;
        }
    }

    private void launchApp(Apps.AppInfo a) {
        if (a == null) return;
        islandEvent("正在打开 " + a.label, a.icon, 2600L);
        Intent it = new Intent(Intent.ACTION_MAIN);
        it.addCategory(Intent.CATEGORY_LAUNCHER);
        it.setComponent(a.comp);
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            if (Build.VERSION.SDK_INT >= 24 && canFreeform()) {
                startWindowed(it);
            } else {
                getContext().startActivity(it);
            }
        } catch (Throwable t) {
            toast("无法启动 " + a.label);
        }
    }

    /** Android 7.0+ 才有 setLaunchBounds；单独放一个方法，避免老机器校验类时找不到 API。 */
    private void startWindowed(Intent it) {
        Rect b = new Rect(
                (int) (60f * scale + offX),
                (int) (104f * scale + offY),
                (int) ((DW - 80f) * scale + offX),
                (int) ((DH - 120f) * scale + offY));
        try {
            ActivityOptions o = ActivityOptions.makeBasic();
            o.setLaunchBounds(b);
            // 再尝试显式要求自由窗口（部分 ROM 支持但默认没开）
            try {
                ActivityOptions.class.getMethod("setLaunchWindowingMode", int.class)
                        .invoke(o, 5 /* WINDOWING_MODE_FREEFORM */);
            } catch (Throwable ignored) {
            }
            getContext().startActivity(it, o.toBundle());
            return;
        } catch (Throwable t) {
            // 走下面的全屏兜底
        }
        try {
            getContext().startActivity(it);
            toast("系统不支持自由窗口，已全屏打开");
        } catch (Throwable ignored) {
        }
    }

    private void toast(String s) {
        toastText = s;
        toastEnd = SystemClock.uptimeMillis() + 2000L;
        wake();
        invalidate();
    }

    private void noteKey(int keyCode) {
        keySeq++;
        lastKeyDesc = keyName(keyCode);
    }

    private static String keyName(int k) {
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) return "方向 ←";
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) return "方向 →";
        if (k == KeyEvent.KEYCODE_DPAD_UP) return "方向 ↑";
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) return "方向 ↓";
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A) return "确认";
        if (k == KeyEvent.KEYCODE_BACK) return "返回";
        if (k == KeyEvent.KEYCODE_MENU || k == KeyEvent.KEYCODE_M) return "菜单";
        return "键 " + k;
    }

    /** 帧循环没跑起来时用按键重复兜底，至少保证光标能移动 */
    private void dpadDown(int keyCode, KeyEvent e) {
        if (e == null || e.getRepeatCount() <= 0) return;
        long now = SystemClock.uptimeMillis();
        boolean loopLive = frameRunning && lastTick != 0L && now - lastTick < 220L;
        if (loopLive) return;
        float stepPx = 30f;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) curX -= stepPx;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) curX += stepPx;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) curY -= stepPx;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) curY += stepPx;
        curX = clampF(curX, 0f, DW);
        curY = clampF(curY, 0f, DH);
    }

    private void wake() {
        animating = true;
        long now = SystemClock.uptimeMillis();
        boolean stalled = frameRunning && lastTick != 0L && now - lastTick > 300L;
        if (!frameRunning || stalled) {
            frameRunning = true;
            lastTick = 0L;
            frameHandler.removeCallbacks(frameTick);
            frameHandler.postDelayed(frameTick, 16L);
        }
    }

    private static int toInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Throwable e) {
            return def;
        }
    }

    // ---------- 时钟 ----------
    private boolean refreshClock() {
        Calendar cal = Calendar.getInstance();
        int h = cal.get(Calendar.HOUR_OF_DAY);
        int m = cal.get(Calendar.MINUTE);
        String c = (h < 10 ? "0" + h : String.valueOf(h)) + ":" + (m < 10 ? "0" + m : String.valueOf(m));
        String d = (cal.get(Calendar.MONTH) + 1) + "月" + cal.get(Calendar.DAY_OF_MONTH) + "日 "
                + WEEK[cal.get(Calendar.DAY_OF_WEEK) - 1];
        monthStr = cal.get(Calendar.YEAR) + "年" + (cal.get(Calendar.MONTH) + 1) + "月";
        dayStr = String.valueOf(cal.get(Calendar.DAY_OF_MONTH));
        weekStr = WEEK[cal.get(Calendar.DAY_OF_WEEK) - 1];
        todayNum = cal.get(Calendar.DAY_OF_MONTH);
        daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar first = (Calendar) cal.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        firstDow = first.get(Calendar.DAY_OF_WEEK) - 1;
        String ss = cal.get(Calendar.SECOND) < 10
                ? "0" + cal.get(Calendar.SECOND) : String.valueOf(cal.get(Calendar.SECOND));
        int y = cal.get(Calendar.YEAR);
        boolean changed = !c.equals(clockStr) || !d.equals(dateStr) || !ss.equals(secStr) || y != yearNum;
        clockStr = c;
        dateStr = d;
        secStr = ss;
        yearNum = y;
        monNum = cal.get(Calendar.MONTH) + 1;
        return changed;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (refreshClock()) invalidate();
            islandTick();
            frameWatchdog();
            ui.postDelayed(this, 1000);
        }
    };

    // ---------- 逐帧 ----------
    @Override
    protected void onDraw(Canvas canvas) {
        int sc = canvas.save();
        try {
            drawAll(canvas);
        } catch (Throwable t) {
            animating = false;
        }
        canvas.restoreToCount(sc);
    }

    private void drawAll(Canvas canvas) {
        canvas.save();
        canvas.translate(offX, offY);
        canvas.scale(scale, scale);

        Wallpaper.draw(canvas, wpIndex);
        drawWidgets(canvas);
        drawDesktop(canvas);
        drawWindows(canvas);
        drawDock(canvas);
        drawMenuBar(canvas);
        drawMenuPanel(canvas);
        if (lpOpen) drawLaunchpad(canvas);
        drawIsland(canvas);
        drawToast(canvas);
        drawCursor(canvas);

        canvas.restore();
    }

    private void step(float dt) {
        animating = false;
        if (toastText != null) wake();

        float tx = (kR ? 1f : 0f) - (kL ? 1f : 0f);
        float ty = (kD ? 1f : 0f) - (kU ? 1f : 0f);
        boolean moving = (tx != 0f || ty != 0f);
        if (tx != 0f && ty != 0f) {
            tx *= 0.72f;
            ty *= 0.72f;
        }
        float k = 1f - (float) Math.exp(-dt / (moving ? 72f : 30f));
        float speed = CURSOR_SPEED * (speedMode == 0 ? 0.60f : (speedMode == 2 ? 1.15f : 0.80f));
        velX += (tx * speed - velX) * k;
        velY += (ty * speed - velY) * k;

        if (cursorMode && Math.abs(velX) + Math.abs(velY) > 0.6f) {
            curX += velX * dt / 1000f;
            curY += velY * dt / 1000f;
            wake();
        }

        if (curX < 0f) { curX = 0f; velX = 0f; }
        if (curX > DW) { curX = DW; velX = 0f; }
        if (curY < 0f) { curY = 0f; velY = 0f; }
        if (curY > DH) { curY = DH; velY = 0f; }

        // 松手后磁吸：光标自己收敛到最近目标（焦点汇合）
        // 磁吸只在空白处生效：光标已经在某个目标上时绝不往回拉，
        // 否则每点一下方向键就被吸回格子中心，看起来就是“卡住不动”。
        if (cursorMode && !moving && !keysHeld() && dragWin == null
                && Math.abs(velX) + Math.abs(velY) < 20f
                && hitAt(curX, curY) == null) {
            Hit near = nearestHit(curX, curY, 24f);
            if (near != null) {
                // 吸到目标的最近边界，不吸到格子中心，避免“一格跳一格”的大跳
                float px = clampF(curX, near.r.left + 2f, near.r.right - 2f);
                float py = clampF(curY, near.r.top + 2f, near.r.bottom - 2f);
                float gx = px - curX;
                float gy = py - curY;
                if (Math.abs(gx) > 0.6f || Math.abs(gy) > 0.6f) {
                    curX += gx * 0.12f;
                    curY += gy * 0.12f;
                    wake();
                }
            }
        }

        if (dragWin != null) {
            if (Math.abs(curX - pressX) + Math.abs(curY - pressY) > 8f) dragMoved = true;
            dragWin.x = clampF(curX - dragOffX, 16f, Math.max(16f, DW - dragWin.w - 16f));
            dragWin.y = clampF(curY - dragOffY, MB_H + 6f, Math.max(MB_H + 6f, DH - dragWin.h - 8f));
            wake();
        }

        String h = hitAt(curX, curY);
        if (h == null) h = "";
        if (!h.equals(hoverId)) {
            hoverId = h;
            hoverBurst = 1f;
            wake();
        }
        if (hoverBurst > 0.004f) {
            hoverBurst *= (float) Math.exp(-dt / 130f);
            if (hoverBurst < 0.004f) hoverBurst = 0f;
            wake();
        }

        boolean inDock = (curY >= dockTop() - 6f);
        dockCursorTo = curX;
        dockMagTo = inDock ? 1f : 0f;
        if (Math.abs(dockCursor - dockCursorTo) > 0.5f) {
            float kk = 1f - (float) Math.exp(-dt / 90f);
            dockCursor += (dockCursorTo - dockCursor) * kk;
            if (Math.abs(dockCursor - dockCursorTo) < 1f) dockCursor = dockCursorTo;
            wake();
        }
        float ringTarget = (hoverId != null && hoverId.length() > 0) ? 12.5f : 15f;
        if (Math.abs(ringR - ringTarget) > 0.1f) {
            float kr = 1f - (float) Math.exp(-dt / 70f);
            ringR += (ringTarget - ringR) * kr;
            wake();
        }

        if (Math.abs(dockMag - dockMagTo) > 0.004f) {
            float kk = 1f - (float) Math.exp(-dt / 100f);
            dockMag += (dockMagTo - dockMag) * kk;
            if (Math.abs(dockMag - dockMagTo) < 0.006f) dockMag = dockMagTo;
            wake();
        }

        // 灵动岛：弹簧式收展
        long nowMs = SystemClock.uptimeMillis();
        if (islOpen && islAuto && nowMs > islAutoUntil) {
            islandCloseNow();
        }
        float islTarget = islOpen ? 1f : 0f;
        if (Math.abs(islTarget - islT) > 0.002f) {
            islT += (islTarget - islT) * (1f - (float) Math.exp(-dt / 55f));
            if (Math.abs(islTarget - islT) < 0.005f) islT = islTarget;
            wake();
        }
        // 展开着且有声音时，波形要动
        if (islT > 0.5f && audioActive()) wake();
    }

    private static float clampF(float v, float a, float b) {
        return v < a ? a : (v > b ? b : v);
    }

    // ---------- 绘制 ----------
    private void drawDesktop(Canvas c) {
        int total = apps.all.size();
        float iconSize = ICON_D * iconFactor();
        int start = page * D_PER_PAGE;

        if (total == 0) {
            Painter.text(c, "正在读取应用列表…", DW / 2f, 470f, 20f, 0x8AFFFFFF,
                    Painter.TFM, Paint.Align.CENTER, false);
        }

        for (int i = 0; i < D_PER_PAGE; i++) {
            int gi = start + i;
            if (gi >= total) break;
            Apps.AppInfo a = apps.all.get(gi);
            RectF cell = deskCellAt(i);
            String id = "d:" + gi;
            boolean hov = id.equals(hoverId);
            float size = iconSize * (hov && animOn ? 1.045f : 1f);
            float cx = cell.centerX();
            float cy = cell.centerY() - 14f;
            RectF tile = new RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            Painter.iconTile(c, tile, a.icon, size * 0.225f);

            String lab = Painter.fit(a.label, 15f, Painter.TFM, D_CW - 26f);
            Painter.text(c, lab, cx, cy + size / 2f + 26f, 15f, 0xF5FFFFFF,
                    Painter.TFM, Paint.Align.CENTER, true);

            if (hov) {
                RectF plate = new RectF(tile);
                plate.inset(-12f, -10f);
                Painter.round(c, plate, 26f, 0x22FFFFFF);
                drawConverge(c, plate, 26f);
            }
        }

        drawPageDots(c, page, pageCount(), D_X0 + D_COLS * D_CW / 2f, 884f);
    }

    /** 焦点汇合：光环 + 四角从远处收拢 */
    private void drawConverge(Canvas c, RectF r, float rad) {
        RectF halo = new RectF(r);
        halo.inset(-2f, -2f);
        Painter.roundStroke(c, halo, rad, 1.4f, 0x7AFFFFFF);
        for (int i = 3; i >= 1; i--) {
            RectF g = new RectF(halo);
            float sp = 8f * i;
            g.inset(-sp, -sp);
            Painter.roundStroke(c, g, rad + sp, 2.5f, Painter.alpha(0x2AFFFFFF, 0.09f / i));
        }
        float off = 4f + 16f * hoverBurst;
        RectF br = new RectF(halo);
        br.inset(-off, -off);
        float len = 12f;
        int col = 0x8CFFFFFF;
        drawBracket(c, br.left, br.top, len, 1f, 1f, col);
        drawBracket(c, br.right, br.top, len, -1f, 1f, col);
        drawBracket(c, br.left, br.bottom, len, 1f, -1f, col);
        drawBracket(c, br.right, br.bottom, len, -1f, -1f, col);
    }

    private void drawBracket(Canvas c, float x, float y, float len, float sx, float sy, int col) {
        cp.setShader(null);
        cp.setStyle(Paint.Style.STROKE);
        cp.setStrokeWidth(1.6f);
        cp.setStrokeCap(Paint.Cap.ROUND);
        cp.setColor(col);
        c.drawLine(x, y, x + len * sx, y, cp);
        c.drawLine(x, y, x, y + len * sy, cp);
        cp.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawPageDots(Canvas c, int cur, int total, float cx, float y) {
        if (total <= 1) return;
        float gap = 20f;
        float x0 = cx - (total - 1) * gap / 2f;
        cp.setShader(null);
        cp.setStyle(Paint.Style.FILL);
        for (int i = 0; i < total; i++) {
            cp.setColor(i == cur ? 0x9EFFFFFF : 0x3CFFFFFF);
            c.drawCircle(x0 + i * gap, y, i == cur ? 5f : 4.2f, cp);
        }
    }

    private RectF clockWidgetRect() {
        return new RectF(1462f, 142f, 1782f, 352f);
    }

    private RectF calWidgetRect() {
        return new RectF(1462f, 368f, 1782f, 704f);
    }

    /** 桌面右侧两个小部件：深色液态玻璃，点击打开对应窗口 */
    private void drawWidgets(Canvas c) {
        RectF w1 = clockWidgetRect();
        boolean h1 = "z:clock".equals(hoverId);
        Painter.glassDark(c, w1, 24f, h1 ? 22f : 14f);
        if (h1) Painter.roundStroke(c, w1, 24f, 2f, 0x5CFFFFFF);

        Painter.text(c, "时钟", w1.left + 28f, w1.top + 34f, 13f, 0x8CFFFFFF, Painter.TFM, Paint.Align.LEFT, false);
        Painter.text(c, "本地", w1.right - 28f, w1.top + 34f, 12f, 0x5CFFFFFF, Painter.TF, Paint.Align.RIGHT, false);
        Painter.text(c, clockStr, w1.left + 26f, w1.top + 108f, 58f, 0xFFF7F7FA, Painter.TFM, Paint.Align.LEFT, false);
        Painter.text(c, dateStr, w1.left + 28f, w1.top + 146f, 15f, 0xB4FFFFFF, Painter.TF, Paint.Align.LEFT, false);
        Painter.text(c, h1 ? "点击打开时钟  ›" : "点击打开", w1.left + 28f, w1.bottom - 26f, 12f,
                h1 ? 0xE8FFFFFF : 0x62FFFFFF, Painter.TF, Paint.Align.LEFT, false);

        RectF w2 = calWidgetRect();
        boolean h2 = "z:cal".equals(hoverId);
        Painter.glassDark(c, w2, 24f, h2 ? 22f : 14f);
        if (h2) Painter.roundStroke(c, w2, 24f, 2f, 0x5CFFFFFF);

        Painter.text(c, monthStr, w2.left + 28f, w2.top + 36f, 13f, 0x9CFFFFFF, Painter.TFM, Paint.Align.LEFT, false);
        Painter.text(c, h2 ? "点击打开日历  ›" : "点击打开", w2.right - 28f, w2.top + 36f, 12f,
                h2 ? 0xE8FFFFFF : 0x62FFFFFF, Painter.TF, Paint.Align.RIGHT, false);
        Painter.text(c, dayStr, w2.left + 26f, w2.top + 108f, 64f, 0xFFF7F7FA, Painter.TFB, Paint.Align.LEFT, false);
        float dx = w2.left + 28f + Painter.measure(dayStr, 64f, Painter.TFB) + 14f;
        Painter.text(c, weekStr, dx, w2.top + 94f, 14f, 0xD0FFFFFF, Painter.TFM, Paint.Align.LEFT, false);

        String[] wk = { "日", "一", "二", "三", "四", "五", "六" };
        float gx = w2.left + 28f, gy = w2.top + 170f, cw = 40f, rh = 22f;
        for (int i = 0; i < 7; i++) {
            Painter.text(c, wk[i], gx + cw * i + cw / 2f, gy, 11f, 0x5EFFFFFF, Painter.TF, Paint.Align.CENTER, false);
        }
        for (int d = 1; d <= daysInMonth; d++) {
            int idx = firstDow + d - 1;
            int col = idx % 7, row = idx / 7;
            if (row > 5) break;
            float cxx = gx + cw * col + cw / 2f;
            float cyy = gy + 15f + rh * row + rh / 2f;
            if (d == todayNum) Painter.dot(c, cxx, cyy - 1f, 10f, 0xFF0A84FF);
            Painter.text(c, String.valueOf(d), cxx, cyy + 4f, 11.5f,
                    d == todayNum ? 0xFFFFFFFF : 0x8AFFFFFF, Painter.TF, Paint.Align.CENTER, false);
        }
    }

    private void drawDock(Canvas c) {
        int n = dockX.size();
        if (n < 2) return;
        float left = (DW - dockW) / 2f;
        float top = dockTop();
        float bottom = DH - DOCK_BOTTOM;
        RectF pill = new RectF(left - 25f, top, left + dockW + 25f, bottom);
        Painter.glass(c, pill, DOCK_H / 2f, 0x1EFFFFFF, 18f);

        if (n >= 3) {
            float vx = (dockX.get(n - 2) + dockX.get(n - 1)) / 2f;
            Painter.solid(c, vx - 0.5f, top + 30f, vx + 0.5f, bottom - 30f, 0x24FFFFFF);
        }

        int hovIdx = (curY >= top - 6f) ? dockIndexUnder(curX) : -1;
        float baseY = bottom - 11f;
        float sigma = 84f;
        for (int k = 0; k < n; k++) {
            float cx = dockX.get(k);
            float dist = Math.abs(cx - dockCursor);
            float wgt = (float) Math.exp(-(dist * dist) / (2f * sigma * sigma));
            float m = (curY >= top - 6f) ? dockMag * wgt : 0f;
            float size = DOCK_ITEM * (1f + 0.38f * m);
            float cy = baseY - size / 2f;
            RectF tile = new RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);

            if (k == 0) {
                Painter.tile(c, tile, size * 0.225f, 0x1FFFFFFF);
                Painter.launchpadIcon(c, tile);
            } else if (k == n - 1) {
                Painter.tile(c, tile, size * 0.225f, 0x18FFFFFF);
                Painter.gear(c, cx, cy, size * 0.25f, 0xE0FFFFFF);
            } else {
                Apps.AppInfo a = dockApps.get(k - 1);
                Painter.iconTile(c, tile, a.icon, size * 0.225f);
            }

            if (k == hovIdx && dockMag > 0.05f) {
                String name = (k == 0) ? "启动台" : (k == n - 1 ? "系统设置" : dockApps.get(k - 1).label);
                String lab = Painter.fit(name, 14f, Painter.TFM, 300f);
                float tw = Painter.measure(lab, 14f, Painter.TFM);
                RectF lr = new RectF(cx - tw / 2f - 12f, cy - size / 2f - 38f,
                        cx + tw / 2f + 12f, cy - size / 2f - 10f);
                Painter.round(c, lr, 8f, Painter.alpha(0xE01A1A1C, Math.min(1f, dockMag)));
                Painter.text(c, lab, cx, lr.centerY() + 5f, 14f,
                        Painter.alpha(0xFFFFFFFF, Math.min(1f, dockMag)), Painter.TFM, Paint.Align.CENTER, false);
            }
        }
    }

    private void drawMenuPanel(Canvas c) {
        if (menuOpen < 0) return;
        String[][] rows = menuDef(menuOpen);
        if (rows == null) return;
        RectF panel = new RectF(menuPanelX, MB_H + 2f, menuPanelX + menuPanelW, MB_H + 2f + menuPanelH);
        Painter.shadow(c, panel, 10f, 26f, 0x8C000000);
        Painter.round(c, panel, 10f, 0xF01C1C20);
        Painter.roundStroke(c, panel, 10f, 1f, 0x38FFFFFF);

        float cy = panel.top + 8f;
        for (int i = 0; i < rows.length; i++) {
            if (rows[i][0].equals("-")) {
                Painter.solid(c, panel.left + 14f, cy + 4f, panel.right - 14f, cy + 4.6f, 0x24FFFFFF);
                cy += 9f;
                continue;
            }
            String id = "mm:" + i;
            boolean hov = id.equals(hoverId);
            boolean enabled = rows[i][1].length() > 0;
            if (hov) Painter.round(c, new RectF(panel.left + 6f, cy, panel.right - 6f, cy + 28f), 6f, 0xFF0A84FF);
            Painter.text(c, menuLabel(rows[i]), panel.left + 16f, cy + 19f, 14f,
                    hov ? 0xFFFFFFFF : (enabled ? 0xE6F2F2F7 : 0x59FFFFFF),
                    Painter.TFM, Paint.Align.LEFT, false);
            String sc = menuShortcut(rows[i]);
            if (sc.length() > 0) {
                Painter.text(c, sc, panel.right - 16f, cy + 19f, 13f,
                        hov ? 0xCCFFFFFF : (enabled ? 0x8CFFFFFF : 0x44FFFFFF),
                        Painter.TF, Paint.Align.RIGHT, false);
            }
            cy += 28f;
        }
    }

    private void drawHelp(Canvas c, Win w) {
        Painter.text(c, "怎么操作", w.x + 30f, w.y + 96f, 20f, 0xFFF5F5F7, Painter.TFB, Paint.Align.LEFT, false);
        Painter.text(c, "全部用遥控器方向键 + 确认键，不需要鼠标", w.x + 30f, w.y + 124f, 13f,
                0x8AFFFFFF, Painter.TF, Paint.Align.LEFT, false);

        String[][] rows = {
                { "方向键", "推动白色圆环光标，按住连续移动，松手轻微吸附" },
                { "OK / 回车", "点击光标下的图标、按钮、菜单" },
                { "长按 OK", "把图标加入 / 移出 Dock" },
                { "标题栏 + OK", "按住 OK 再按方向键，拖动窗口" },
                { "红点", "关闭这个窗口" },
                { "MENU", "打开启动台" },
                { "BACK", "先收菜单，再关窗口，最后回桌面" },
                { "菜单栏", "访达 / 文件 / 显示 / 前往 / 窗口 / 帮助 都能点" },
                { "顶部灵动岛", "点一下展开：时间、音量、计时；点空白收起" }
        };
        float y = w.y + 172f;
        for (int i = 0; i < rows.length; i++) {
            float kw = Painter.measure(rows[i][0], 13f, Painter.TFM) + 22f;
            RectF k = new RectF(w.x + 30f, y - 16f, w.x + 30f + kw, y + 10f);
            Painter.round(c, k, 6f, 0x1FFFFFFF);
            Painter.roundStroke(c, k, 6f, 1f, 0x2EFFFFFF);
            Painter.text(c, rows[i][0], k.centerX(), y + 4f, 13f, 0xE0FFFFFF,
                    Painter.TFM, Paint.Align.CENTER, false);
            Painter.text(c, rows[i][1], w.x + 30f + kw + 18f, y + 4f, 13f, 0xC6FFFFFF,
                    Painter.TF, Paint.Align.LEFT, false);
            y += 34f;
        }

        // 输入诊断，用来区分“遥控器没送到”和“送到了但没动”
        float dy = w.y + w.h - 30f;
        Painter.solid(c, w.x + 30f, dy - 34f, w.x + w.w - 30f, dy - 33f, 0x1AFFFFFF);
        Painter.text(c, "最近按键 " + lastKeyDesc + "   光标 " + (int) curX + ", " + (int) curY
                        + "   帧 " + (frameRunning ? "运行" : "停") + "   " + keySeq,
                w.x + 30f, dy, 12f, 0x8AFFFFFF, Painter.TF, Paint.Align.LEFT, false);
    }

    private void drawMenuBar(Canvas c) {
        Painter.solid(c, 0f, 0f, DW, MB_H, 0xBA141417);
        Painter.solid(c, 0f, 0f, DW, 1f, 0x1EFFFFFF);
        Painter.solid(c, 0f, MB_H - 0.8f, DW, MB_H, 0x14000000);

        Painter.apple(c, 30f, MB_H / 2f + 1f, 16f, 0xFFEDEDF2);

        for (int i = 0; i < MENU_TXT.length; i++) {
            RectF r = new RectF(menuX[i] - 12f, 5f, menuX[i] + menuW[i] + 12f, MB_H - 5f);
            if (menuOpen == i) {
                Painter.round(c, r, 8f, 0xFF0A84FF);
            } else if (MENU_ID[i].equals(hoverId)) {
                Painter.round(c, r, 8f, 0x30FFFFFF);
            }
            Painter.text(c, MENU_TXT[i], menuX[i], MB_H / 2f + 6.5f, 16f,
                    (i == 0 || menuOpen == i) ? 0xFFF5F5F7 : 0xE8F2F2F7,
                    (i == 0 || menuOpen == i) ? Painter.TFB : Painter.TFM, Paint.Align.LEFT, false);
        }

        Painter.controlCenter(c, 1600f, MB_H / 2f, 22f, 0xD2FFFFFF);
        Painter.magnifier(c, 1637f, MB_H / 2f - 1f, 6.5f, 0xD2FFFFFF);
        Painter.wifi(c, 1676f, MB_H / 2f - 4f, 9.5f, 0xD2FFFFFF);
        Painter.battery(c, 1700f, MB_H / 2f - 6.5f, 30f, 13f, 0.72f, 0xD2FFFFFF);
        Painter.text(c, dateStr, 1842f, MB_H / 2f + 6f, 14f, 0xB2F2F2F7, Painter.TF, Paint.Align.RIGHT, false);
        Painter.text(c, clockStr, 1892f, MB_H / 2f + 6f, 16f, 0xFFF2F2F7, Painter.TFM, Paint.Align.RIGHT, false);
    }

    // ---------- 窗口 ----------
    private void drawWindows(Canvas c) {
        for (int i = 0; i < wins.size(); i++) {
            Win w = wins.get(i);
            String base = "w:" + w.id + ":";
            Painter.shadow(c, new RectF(w.x, w.y, w.x + w.w, w.y + w.h), 14f, 34f, 0x8C000000);
            Painter.round(c, new RectF(w.x, w.y, w.x + w.w, w.y + w.h), 14f, 0xF21A1A1D);

            clip.reset();
            clip.addRoundRect(new RectF(w.x, w.y, w.x + w.w, w.y + w.h), 14f, 14f, Path.Direction.CW);
            int sc = c.save();
            try {
                c.clipPath(clip);
            } catch (Throwable ignored) {
            }

            boolean titleHover = (base + "title").equals(hoverId);
            Painter.solid(c, w.x, w.y, w.x + w.w, w.y + 40f, titleHover ? 0x24FFFFFF : 0x0CFFFFFF);
            Painter.solid(c, w.x, w.y + 40f, w.x + w.w, w.y + 40.8f, 0x1FFFFFFF);

            int[] dots = { 0xFFFF5F57, 0xFFFFBD2E, 0xFF28C840 };
            for (int k = 0; k < 3; k++) {
                float dx = w.x + 26f + k * 27f;
                boolean ch = k == 0 && (base + "close").equals(hoverId);
                Painter.dot(c, dx, w.y + 19f, ch ? 11.5f : 9.5f, dots[k]);
                Painter.dot(c, dx - 2f, w.y + 15.5f, ch ? 3.2f : 2.6f, 0x66FFFFFF);
            }

            drawWindowContent(c, w, base);
            c.restoreToCount(sc);

            Painter.text(c, winTitle(w), w.x + w.w / 2f, w.y + 27f, 15f, 0xFFF2F2F7,
                    Painter.TFM, Paint.Align.CENTER, false);
            Painter.roundStroke(c, new RectF(w.x, w.y, w.x + w.w, w.y + w.h), 14f, 1f, 0x33FFFFFF);

            if (w == dragWin) {
                Painter.roundStroke(c, new RectF(w.x - 1f, w.y - 1f, w.x + w.w + 1f, w.y + w.h + 1f), 15f, 2f, 0x66FFFFFF);
            }
        }
    }

    private String winTitle(Win w) {
        if (w.type == W_SET) return "系统设置";
        if (w.type == W_FINDER) return "访达 — 应用程序";
        if (w.type == W_HELP) return "操作说明";
        if (w.type == W_CLOCK) return "时钟";
        if (w.type == W_CAL) return "日历";
        return "关于本机";
    }

    private void drawWindowContent(Canvas c, Win w, String base) {
        if (w.type == W_SET) drawSettings(c, w, base);
        else if (w.type == W_FINDER) drawFinder(c, w, base);
        else if (w.type == W_HELP) drawHelp(c, w);
        else if (w.type == W_CLOCK) drawClockWin(c, w);
        else if (w.type == W_CAL) drawCalWin(c, w);
        else drawAbout(c, w);
    }

    private void drawSettings(Canvas c, Win w, String base) {
        RectF sb = new RectF(w.x, w.y + 41f, w.x + 200f, w.y + w.h);
        Painter.solid(c, sb.left, sb.top, sb.right, sb.bottom, 0x0CFFFFFF);
        Painter.solid(c, sb.right, sb.top, sb.right + 0.8f, sb.bottom, 0x1AFFFFFF);

        String[] cats = { "通用", "外观", "桌面与程序坞", "声音", "关于本机" };
        int[] kinds = { 5, 0, 1, 3, 6 };
        for (int i = 0; i < cats.length; i++) {
            float ry = sb.top + 14f + i * 44f;
            boolean sel = i == 1;
            if (sel) Painter.round(c, new RectF(sb.left + 8f, ry, sb.right - 12f, ry + 36f), 8f, 0xFF0A84FF);
            RectF gt = new RectF(sb.left + 16f, ry + 7f, sb.left + 40f, ry + 31f);
            Painter.round(c, gt, 6f, sel ? 0x33FFFFFF : 0x1FFFFFFF);
            Painter.glyph(c, kinds[i], gt, sel ? 0xFFFFFFFF : 0xC9FFFFFF);
            Painter.text(c, cats[i], sb.left + 50f, ry + 25f, 14f,
                    sel ? 0xFFFFFFFF : 0xC2FFFFFF, Painter.TFM, Paint.Align.LEFT, false);
        }

        String ver = "1.5.0";
        try {
            ver = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        String[] keys = { "壁纸", "图标大小", "焦点动画", "方向键", "光标速度", "刷新应用列表", "重置 Dock", "打开系统设置", "关于 Gem桌面" };
        String[] vals = {
                Wallpaper.NAMES[wpIndex],
                (new String[] { "小", "中", "大" })[iconScaleMode],
                animOn ? "打开" : "关闭",
                cursorMode ? "虚拟光标" : "逐格跳转",
                new String[] { "慢", "中", "快" }[speedMode],
                "", "", "", ver
        };
        int[] cols = { 0xFF4FA3FF, 0xFFB06BFF, 0xFFFF9F43, 0xFF34C759, 0xFFFFB340, 0xFF32C57E, 0xFFFF5F57, 0xFF8E8E93, 0xFF3ED6C8 };
        int[] gk = { 0, 1, 2, 3, 4, 5, 4, 5, 6 };

        for (int i = 0; i < keys.length; i++) {
            RectF r = setRowRect(w, i);
            boolean hov = (base + "row:" + i).equals(hoverId);
            if (hov) {
                Painter.round(c, new RectF(r.left + 3f, r.top + 3f, r.right - 3f, r.bottom - 3f), 9f, 0xFF0A84FF);
            } else if (i > 0) {
                Painter.solid(c, r.left + 14f, r.top, r.right - 14f, r.top + 0.7f, 0x16FFFFFF);
            }
            RectF tile = new RectF(r.left + 12f, r.centerY() - 15f, r.left + 42f, r.centerY() + 15f);
            Painter.round(c, tile, 7f, cols[i]);
            Painter.glyph(c, gk[i], tile, 0xFFFFFFFF);

            Painter.text(c, keys[i], r.left + 56f, r.centerY() + 5f, 15f,
                    hov ? 0xFFFFFFFF : 0xE6F2F2F7, Painter.TFM, Paint.Align.LEFT, false);
            if (vals[i].length() > 0) {
                Painter.text(c, vals[i], r.right - 38f, r.centerY() + 5f, 14f,
                        hov ? 0xCFFFFFFF : 0x96FFFFFF, Painter.TF, Paint.Align.RIGHT, false);
            }
            Painter.chevron(c, r.right - 20f, r.centerY(), 9f, hov ? 0xCFFFFFFF : 0x60FFFFFF);
        }
    }

    private void drawFinder(Canvas c, Win w, String base) {
        int total = apps.all.size();
        int pages = (total + 17) / 18;
        Painter.solid(c, w.x, w.y + 41f, w.x + w.w, w.y + 44f + 62f, 0x08FFFFFF);
        Painter.text(c, "应用程序", w.x + 24f, w.y + 84f, 17f, 0xFFF2F2F7, Painter.TFB, Paint.Align.LEFT, false);
        Painter.text(c, "共 " + total + " 个", w.x + 112f, w.y + 84f, 13f, 0x7AFFFFFF, Painter.TF, Paint.Align.LEFT, false);
        Painter.text(c, "用光标点图标即可打开", w.x + w.w - 24f, w.y + 84f, 12f, 0x5CFFFFFF,
                Painter.TF, Paint.Align.RIGHT, false);

        int start = w.page * 18;
        for (int k = 0; k < 18; k++) {
            int gi = start + k;
            if (gi >= total) break;
            Apps.AppInfo a = apps.all.get(gi);
            RectF cell = finderCell(w, k);
            String id = base + "app:" + gi;
            boolean hov = id.equals(hoverId);
            float cx = cell.centerX();
            float cy = cell.centerY() - 8f;
            float size = 78f;
            RectF tile = new RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            Painter.iconTile(c, tile, a.icon, size * 0.225f);
            String lab = Painter.fit(a.label, 13f, Painter.TFM, cell.width() - 12f);
            Painter.text(c, lab, cx, cy + size / 2f + 22f, 13f,
                    hov ? 0xFFFFFFFF : 0xDDFFFFFF, Painter.TFM, Paint.Align.CENTER, true);
            if (hov) {
                RectF plate = new RectF(tile);
                plate.inset(-10f, -10f);
                Painter.round(c, plate, 24f, 0x16FFFFFF);
                drawConverge(c, plate, 24f);
            }
        }

        boolean prevH = (base + "prev").equals(hoverId);
        boolean nextH = (base + "next").equals(hoverId);
        Painter.chevron(c, w.x + 42f, w.y + w.h - 30f, 12f, prevH ? 0xFFFFFFFF : 0x66FFFFFF);
        Painter.text(c, (w.page + 1) + " / " + (pages <= 0 ? 1 : pages), w.x + w.w / 2f, w.y + w.h - 24f,
                13f, 0x88FFFFFF, Painter.TF, Paint.Align.CENTER, false);
        Painter.chevron(c, w.x + w.w - 42f, w.y + w.h - 30f, 12f, nextH ? 0xFFFFFFFF : 0x66FFFFFF);
    }

    private void drawClockWin(Canvas c, Win w) {
        float cx = w.x + w.w / 2f;
        Painter.text(c, "本地时间", cx, w.y + 92f, 14f, 0x8CFFFFFF, Painter.TFM, Paint.Align.CENTER, false);

        Painter.text(c, clockStr, cx + 34f, w.y + 238f, 106f, 0xFFF7F7FA, Painter.TFM, Paint.Align.RIGHT, false);
        Painter.text(c, secStr, cx + 52f, w.y + 226f, 30f, 0xA8FFFFFF, Painter.TFM, Paint.Align.LEFT, false);

        Painter.text(c, dateStr, cx, w.y + 292f, 19f, 0xE0FFFFFF, Painter.TFM, Paint.Align.CENTER, false);

        // 秒进度条
        float bx = w.x + 72f, bw = w.w - 144f, by = w.y + 314f;
        Painter.round(c, new RectF(bx, by, bx + bw, by + 3f), 2f, 0x1AFFFFFF);
        int sec = toInt(secStr, 0);
        Painter.round(c, new RectF(bx, by, bx + bw * (sec / 60f), by + 3f), 2f, 0xFF0A84FF);

        String tz = "本地时区";
        try {
            tz = java.util.TimeZone.getDefault().getID();
        } catch (Throwable ignored) {
        }
        String[][] rows = {
                { "时区", tz },
                { "今日", yearNum + "年" + (monNum < 10 ? "0" + monNum : String.valueOf(monNum)) + "月" + todayNum + "日 " + weekStr },
                { "计时", "24 小时制" }
        };
        float y = w.y + 356f;
        for (int i = 0; i < rows.length; i++) {
            Painter.text(c, rows[i][0], w.x + 72f, y, 13f, 0x7AFFFFFF, Painter.TF, Paint.Align.LEFT, false);
            Painter.text(c, rows[i][1], w.x + w.w - 72f, y, 13f, 0xCCFFFFFF, Painter.TFM, Paint.Align.RIGHT, false);
            y += 26f;
        }
    }

    private void drawCalWin(Canvas c, Win w) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.MONTH, w.page);
        int yy = cal.get(Calendar.YEAR);
        int mm = cal.get(Calendar.MONTH) + 1;
        int days = cal.getActualMaximum(Calendar.DAY_OF_MONTH);
        Calendar first = (Calendar) cal.clone();
        first.set(Calendar.DAY_OF_MONTH, 1);
        int fdow = first.get(Calendar.DAY_OF_WEEK) - 1;

        float cx = w.x + w.w / 2f;
        Painter.text(c, yy + "年 " + mm + "月", cx, w.y + 84f, 22f, 0xFFF7F7FA, Painter.TFB, Paint.Align.CENTER, false);
        if (w.page == 0) {
            Painter.text(c, "今天", cx, w.y + 112f, 12f, 0x88FFFFFF, Painter.TF, Paint.Align.CENTER, false);
        }

        boolean ph = (("w:" + w.id + ":prev")).equals(hoverId);
        boolean nh = (("w:" + w.id + ":next")).equals(hoverId);
        Painter.chevron2(c, w.x + 52f, w.y + 74f, 16f, ph ? 0xFFFFFFFF : 0x66FFFFFF, true);
        Painter.chevron2(c, w.x + w.w - 52f, w.y + 74f, 16f, nh ? 0xFFFFFFFF : 0x66FFFFFF, false);

        String[] wk = { "日", "一", "二", "三", "四", "五", "六" };
        float gx = w.x + 60f, gy = w.y + 146f;
        float cw = (w.w - 120f) / 7f, rh = 56f;
        for (int i = 0; i < 7; i++) {
            Painter.text(c, wk[i], gx + cw * i + cw / 2f, gy, 13f, 0x70FFFFFF, Painter.TFM, Paint.Align.CENTER, false);
        }
        for (int i = 0; i < 42; i++) {
            int d = i - fdow + 1;
            if (d < 1 || d > days) continue;
            int col = i % 7, row = i / 7;
            float cxx = gx + cw * col + cw / 2f;
            float cyy = gy + 24f + rh * row + rh / 2f;
            boolean today = w.page == 0 && d == todayNum;
            if (today) {
                Painter.round(c, new RectF(cxx - 24f, cyy - 22f, cxx + 24f, cyy + 22f), 22f, 0xFF0A84FF);
            }
            Painter.text(c, String.valueOf(d), cxx, cyy + 7f, 19f,
                    today ? 0xFFFFFFFF : (col == 0 || col == 6 ? 0x88FFFFFF : 0xE4FFFFFF),
                    today ? Painter.TFB : Painter.TFM, Paint.Align.CENTER, false);
        }
        Painter.text(c, "方向键移动光标，确认键切换月份", w.x + 32f, w.y + w.h - 22f, 12f, 0x5CFFFFFF,
                Painter.TF, Paint.Align.LEFT, false);
    }

    private void drawAbout(Canvas c, Win w) {
        String ver = "1.2.0";
        try {
            ver = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0).versionName;
        } catch (Throwable ignored) {
        }
        Painter.text(c, "Gem桌面", w.x + 34f, w.y + 96f, 24f, 0xFFF5F5F7, Painter.TFB, Paint.Align.LEFT, false);
        Painter.text(c, "版本 " + ver, w.x + 34f, w.y + 126f, 14f, 0x9CFFFFFF, Painter.TF, Paint.Align.LEFT, false);
        Painter.solid(c, w.x + 30f, w.y + 148f, w.x + w.w - 30f, w.y + 149f, 0x1AFFFFFF);

        String[][] rows = {
                { "机型", safeModel() },
                { "系统", "Android " + Build.VERSION.RELEASE },
                { "屏幕", vw + " × " + vh },
                { "已安装应用", apps.all.size() + " 个" },
                { "窗口模式", canFreeform() ? "可用（自由窗口）" : "系统未开启" }
        };
        float y = w.y + 190f;
        for (int i = 0; i < rows.length; i++) {
            Painter.text(c, rows[i][0], w.x + 34f, y, 14f, 0x8FFFFFFF, Painter.TF, Paint.Align.LEFT, false);
            Painter.text(c, rows[i][1], w.x + w.w - 34f, y, 14f, 0xE0FFFFFF, Painter.TFM, Paint.Align.RIGHT, false);
            y += 44f;
        }
        Painter.text(c, canFreeform() ? "App 会以浮动窗口打开" : "提示：开启自由窗口后，App 可开成窗口",
                w.x + 34f, w.y + w.h - 26f, 12f, 0x5CFFFFFF, Painter.TF, Paint.Align.LEFT, false);
    }

    private String safeModel() {
        try {
            String m = Build.MODEL;
            return m == null || m.length() == 0 ? "Android TV" : m;
        } catch (Throwable t) {
            return "Android TV";
        }
    }

    // ---------- 启动台 ----------
    private void drawLaunchpad(Canvas c) {
        Painter.solid(c, 0f, 0f, DW, DH, 0x8C000000);

        int total = apps.all.size();
        int start = lpPage * L_PER_PAGE;
        for (int i = 0; i < L_PER_PAGE; i++) {
            int gi = start + i;
            if (gi >= total) break;
            Apps.AppInfo a = apps.all.get(gi);
            RectF cell = lpCellAt(i);
            String id = "lp:" + gi;
            boolean hov = id.equals(hoverId);
            float size = L_ICON * (hov && animOn ? 1.04f : 1f);
            float cx = cell.centerX();
            float cy = cell.centerY() - 14f;
            RectF tile = new RectF(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy + size / 2f);
            Painter.iconTile(c, tile, a.icon, size * 0.225f);

            String lab = Painter.fit(a.label, 15f, Painter.TFM, L_CW - 18f);
            Painter.text(c, lab, cx, cy + size / 2f + 26f, 15f, 0xEFFFFFFF,
                    Painter.TFM, Paint.Align.CENTER, true);
            if (hov) drawConverge(c, tile, 22f);
        }

        drawPageDots(c, lpPage, lpPageCount(), DW / 2f, 1044f);
        Painter.text(c, "MENU / BACK 返回桌面", 120f, DH - 22f, 14f, 0x5CFFFFFF,
                Painter.TF, Paint.Align.LEFT, false);
        Painter.text(c, "共 " + total + " 个应用", 1800f, DH - 22f, 14f, 0x5CFFFFFF,
                Painter.TF, Paint.Align.RIGHT, false);
    }

    // ---------- 光标 ----------
    private void drawCursor(Canvas c) {
        if (!cursorMode) return;
        float x = curX, y = curY;
        cp.setShader(null);

        // 投影
        cp.setStyle(Paint.Style.STROKE);
        cp.setStrokeWidth(2.6f);
        cp.setColor(0x59000000);
        c.drawCircle(x + 1.5f, y + 2.5f, ringR, cp);

        // 白环
        cp.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, ringR, cp);

        // 内缘细线，让环在浅色背景上也看得清
        cp.setStrokeWidth(1f);
        cp.setColor(0x66000000);
        c.drawCircle(x, y, ringR, cp);

        // 中心点：黑点 + 白点
        cp.setStyle(Paint.Style.FILL);
        cp.setColor(0xFF101014);
        c.drawCircle(x, y, 3.8f, cp);
        cp.setColor(0xFFFFFFFF);
        c.drawCircle(x, y, 2.2f, cp);
    }


    // ---------- 灵动岛 ----------
    private float islW() { return ISL_W0 + (ISL_W1 - ISL_W0) * islT; }

    private float islH() { return ISL_H0 + (ISL_H1 - ISL_H0) * islT; }

    private RectF islandRect() {
        float w = islW(), h = islH();
        return new RectF(DW / 2f - w / 2f, ISL_TOP, DW / 2f + w / 2f, ISL_TOP + h);
    }

    /** 命中用终态尺寸，动画途中不抖 */
    private RectF islandHitRect() {
        float w = islOpen ? ISL_W1 : ISL_W0;
        float h = islOpen ? ISL_H1 : ISL_H0;
        return new RectF(DW / 2f - w / 2f, ISL_TOP, DW / 2f + w / 2f, ISL_TOP + h);
    }

    private static int withA(int color, float a) {
        float k = a < 0f ? 0f : (a > 1f ? 1f : a);
        int al = (int) (((color >>> 24) & 0xFF) * k);
        return (al << 24) | (color & 0x00FFFFFF);
    }

    private void islandOpenNow(boolean auto, long autoCloseMs) {
        long now = SystemClock.uptimeMillis();
        islOpen = true;
        islAuto = auto && autoCloseMs > 0L;
        islAutoUntil = islAuto ? now + autoCloseMs : 0L;
        hitsDirty = true;
        wake();
        invalidate();
    }

    private void islandCloseNow() {
        islOpen = false;
        islAuto = false;
        islAutoUntil = 0L;
        islEv = "";
        islEvIcon = null;
        islEvUntil = 0L;
        hitsDirty = true;
        wake();
        invalidate();
    }

    /** 临时事件：展开岛、显示一行字，到点自动收回 */
    private void islandEvent(String text, Bitmap icon, long ms) {
        long now = SystemClock.uptimeMillis();
        islEv = text == null ? "" : text;
        islEvIcon = icon;
        islEvUntil = now + ms;
        islandOpenNow(true, ms);
    }

    private boolean audioActive() {
        long now = SystemClock.uptimeMillis();
        if (now - audChkAt < 800L) return audChk;
        audChkAt = now;
        try {
            audChk = audio != null && audio.isMusicActive();
        } catch (Throwable t) {
            audChk = false;
        }
        return audChk;
    }

    private int volNow() {
        try {
            return audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        } catch (Throwable t) {
            return 0;
        }
    }

    private int volMax() {
        try {
            return audio == null ? 15 : Math.max(1, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } catch (Throwable t) {
            return 15;
        }
    }

    private void volStep(int d) {
        try {
            if (audio != null) {
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                        d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            }
        } catch (Throwable ignored) {
        }
        islandOpenNow(true, 2200L);
    }

    private String timerText() {
        long left = islTimerEnd - SystemClock.uptimeMillis();
        if (left < 0L) left = 0L;
        int sec = (int) (left / 1000L);
        return (sec / 60 < 10 ? "0" : "") + (sec / 60) + ":" + (sec % 60 < 10 ? "0" : "") + (sec % 60);
    }

    private void timerTap() {
        long now = SystemClock.uptimeMillis();
        if (islTimerEnd > now) {
            islTimerEnd = 0L;
            islandEvent("计时已取消", null, 1800L);
        } else {
            islTimerEnd = now + 5L * 60L * 1000L;
            islandEvent("已开始 5 分钟计时", null, 2000L);
        }
        invalidate();
    }

    /** 计时到点在 ticker 里检查 */
    private void islandTick() {
        long now = SystemClock.uptimeMillis();
        if (islTimerEnd > 0L && now >= islTimerEnd) {
            islTimerEnd = 0L;
            islandEvent("计时结束 · 5 分钟到", null, 3200L);
        }
        if (islOpen && islAuto) {
            if (now > islAutoUntil) {
                islandCloseNow();
            }
        }
    }

    private void drawIsland(Canvas c) {
        long now = SystemClock.uptimeMillis();
        RectF r = islandRect();
        float h = r.height();
        float rad = Math.min(h / 2f, 20f + 15f * islT);

        // 投影：让岛浮在壁纸上
        Painter.round(c, new RectF(r.left - 2f, r.top + 8f, r.right + 2f, r.bottom + 14f), rad + 6f, 0x33000000);
        // 岛体
        Painter.round(c, r, rad, 0xFF07070A);
        RectF hi = new RectF(r.left + 3f, r.top + 1.4f, r.right - 3f, r.top + 7f);
        Painter.round(c, hi, rad, 0x12FFFFFF);
        Painter.roundStroke(c, new RectF(r.left + 0.6f, r.top + 0.6f, r.right - 0.6f, r.bottom - 0.6f), rad, 1f, 0x24FFFFFF);

        boolean ev = islEv.length() > 0 && now < islEvUntil;
        boolean aud = audioActive();
        boolean tim = now < islTimerEnd;
        float ia = clampF((islT - 0.20f) / 0.70f, 0f, 1f);

        if (ia > 0.02f) {
            int sc = c.save();
            clip.reset();
            clip.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(clip);
            drawIslandExpanded(c, r, ia, ev, aud, tim, now);
            c.restoreToCount(sc);
        }
        if (ia < 0.98f) {
            drawIslandCompact(c, r, 1f - ia, ev, aud, tim, now);
        }
        // 摄像头：整块岛右侧那枚小黑点
        float camX = r.right - 22f, camY = r.top + ISL_H0 / 2f + (h - ISL_H0) * 0.5f;
        Painter.dot(c, camX, camY, 5.4f, withA(0xFF15151A, 1f - islT * 0.9f));
        Painter.roundStroke(c, new RectF(camX - 5.4f, camY - 5.4f, camX + 5.4f, camY + 5.4f), 5.4f, 1f,
                withA(0x2AFFFFFF, 1f - islT * 0.9f));
    }

    private void drawIslandCompact(Canvas c, RectF r, float a, boolean ev, boolean aud, boolean tim, long now) {
        if (a <= 0.02f) return;
        float cy = r.top + ISL_H0 / 2f + (r.height() - ISL_H0) * 0.5f;
        if (tim) {
            Painter.text(c, timerText(), r.left + 30f, cy + 6f, 14f, withA(0xFF9BE7B0, a), Painter.TFM, Paint.Align.LEFT, false);
        } else if (aud) {
            drawIslandBars(c, r.left + 34f, cy, 40f, 16f, withA(0xFFFFFFFF, a * 0.55f), 0L);
        }
    }

    private void drawIslandBars(Canvas c, float x, float cy, float w, float h, int color, long now) {
        int n = 6;
        float bw = (w / n) * 0.58f;
        float gap = (w / n);
        for (int i = 0; i < n; i++) {
            float bh = h * (0.32f + 0.62f * (float) Math.abs(Math.sin(now / 260.0 + i * 0.9)));
            float bx = x + i * gap;
            Painter.round(c, new RectF(bx, cy - bh / 2f, bx + bw, cy + bh / 2f), bw / 2f, color);
        }
    }

    private void drawIslandIcon(Canvas c, Bitmap bmp, RectF dst, float a) {
        if (bmp == null) return;
        int sc = c.save();
        clip.reset();
        float rad = 8f;
        clip.addRoundRect(dst, rad, rad, Path.Direction.CW);
        c.clipPath(clip);
        cp.setAlpha((int) (255 * clampF(a, 0f, 1f)));
        c.drawBitmap(bmp, null, dst, cp);
        c.restoreToCount(sc);
    }

    private void drawIslandBtn(Canvas c, float cx, float cy, float rad, String glyph, float a, boolean hot) {
        Painter.round(c, new RectF(cx - rad, cy - rad, cx + rad, cy + rad), rad,
                withA(hot ? 0x3DFFFFFF : 0x1FFFFFFF, a));
        Painter.text(c, glyph, cx, cy + 7f, 17f, withA(0xF0FFFFFF, a), Painter.TFM, Paint.Align.CENTER, false);
    }

    private void drawIslandExpanded(Canvas c, RectF r, float ia, boolean ev, boolean aud, boolean tim, long now) {
        float bx = r.left + 34f, by = r.top;

        // 左：时间 + 日期
        Painter.text(c, clockStr, bx, by + 76f, 54f, withA(0xFFF5F5F7, ia), Painter.TFM, Paint.Align.LEFT, false);
        float tw = Painter.measure(clockStr, 54f, Painter.TFM);
        Painter.text(c, secStr, bx + tw + 10f, by + 54f, 19f, withA(0x9AFFFFFF, ia), Painter.TFM, Paint.Align.LEFT, false);
        Painter.text(c, dateStr, bx, by + 102f, 15f, withA(0xBEFFFFFF, ia), Painter.TFM, Paint.Align.LEFT, false);

        // 音频 / 事件胶囊
        RectF chip = new RectF(bx, by + 114f, bx + 296f, by + 156f);
        Painter.round(c, chip, 21f, withA(0x16FFFFFF, ia));
        if (ev) {
            float left = islEvUntil - SystemClock.uptimeMillis();
            Painter.round(c, new RectF(chip.left + 2f, chip.bottom - 4f,
                    chip.left + 2f + (chip.width() - 4f) * clampF(left / 3200f, 0f, 1f), chip.bottom - 2f), 1f,
                    withA(0xFF0A84FF, ia * 0.7f));
            if (islEvIcon != null) {
                drawIslandIcon(c, islEvIcon, new RectF(chip.left + 10f, chip.top + 9f, chip.left + 42f, chip.top + 41f), ia);
            }
            Painter.text(c, islEv, chip.left + (islEvIcon != null ? 52f : 16f), chip.top + 27f, 14f,
                    withA(0xE6FFFFFF, ia), Painter.TFM, Paint.Align.LEFT, false);
        } else if (aud) {
            drawIslandBars(c, chip.left + 16f, chip.top + 21f, 38f, 22f, withA(0xFF3DDC84, ia), now);
            Painter.text(c, "正在播放", chip.left + 66f, chip.top + 27f, 14f, withA(0xE0FFFFFF, ia), Painter.TFM, Paint.Align.LEFT, false);
            Painter.text(c, "电视音频", chip.left + 148f, chip.top + 27f, 12f, withA(0x66FFFFFF, ia), Painter.TF, Paint.Align.LEFT, false);
        } else {
            Painter.text(c, "无音频播放", chip.left + 16f, chip.top + 27f, 14f, withA(0x8AFFFFFF, ia), Painter.TFM, Paint.Align.LEFT, false);
            Painter.text(c, "开着电视就会显示", chip.left + 118f, chip.top + 27f, 12f, withA(0x55FFFFFF, ia), Painter.TF, Paint.Align.LEFT, false);
        }

        // 分隔线
        Painter.line(c, r.left + 320f, by + 24f, r.left + 320f, by + 170f, 1f, withA(0x1AFFFFFF, ia));

        // 右：音量
        float rx = r.left + 352f;
        Painter.text(c, "音量", rx, by + 78f, 13f, withA(0x8CFFFFFF, ia), Painter.TFM, Paint.Align.LEFT, false);
        float mc = rx + 62f, pc = r.right - 68f;
        float trackL = mc + 26f, trackR = pc - 26f;
        int v = volNow(), vmax = volMax();
        Painter.round(c, new RectF(trackL, by + 69f, trackR, by + 77f), 4f, withA(0x1FFFFFFF, ia));
        float frac = vmax <= 0 ? 0f : clampF(v / (float) vmax, 0f, 1f);
        if (frac > 0.01f) {
            Painter.round(c, new RectF(trackL, by + 69f, trackL + (trackR - trackL) * frac, by + 77f), 4f, withA(0xFF61D0FF, ia));
        }
        drawIslandBtn(c, mc, by + 73f, 16f, "−", ia, "is:vol-".equals(hoverId));
        drawIslandBtn(c, pc, by + 73f, 16f, "+", ia, "is:vol+".equals(hoverId));

        // 右：计时
        Painter.text(c, "计时", rx, by + 142f, 13f, withA(0x8CFFFFFF, ia), Painter.TFM, Paint.Align.LEFT, false);
        RectF tb = new RectF(rx + 62f, by + 120f, rx + 176f, by + 160f);
        Painter.round(c, tb, 12f, withA("is:timer".equals(hoverId) ? 0x3DFFFFFF : 0x1FFFFFFF, ia));
        Painter.text(c, tim ? "取消" : "5 分钟", tb.centerX(), tb.centerY() + 6f, 13f,
                withA(0xE6FFFFFF, ia), Painter.TFM, Paint.Align.CENTER, false);
        if (tim) {
            Painter.text(c, timerText(), r.right - 56f, by + 146f, 22f, withA(0xFF9BE7B0, ia), Painter.TFM, Paint.Align.RIGHT, false);
        }

        // 底部提示
        Painter.text(c, "点空白处收起 · 方向键移动光标", bx, by + 180f, 11f, withA(0x50FFFFFF, ia), Painter.TF, Paint.Align.LEFT, false);
    }

    private void drawToast(Canvas c) {
        if (toastText == null) return;
        long left = toastEnd - SystemClock.uptimeMillis();
        if (left <= 0L) {
            toastText = null;
            return;
        }
        float a = left > 480L ? 1f : (left / 480f);
        float size = 15f;
        float tw = Painter.measure(toastText, size, Painter.TFM);
        float hh = 42f;
        float cx = DW / 2f;
        float cy = 104f;
        RectF r = new RectF(cx - tw / 2f - 26f, cy - hh / 2f, cx + tw / 2f + 26f, cy + hh / 2f);
        Painter.round(c, r, hh / 2f, Painter.alpha(0xE61A1A1C, a));
        Painter.roundStroke(c, r, hh / 2f, 1f, Painter.alpha(0x2AFFFFFF, a));
        Painter.text(c, toastText, cx, cy + 5f, size, Painter.alpha(0xFFFFFFFF, a),
                Painter.TFM, Paint.Align.CENTER, false);
    }

    // ---------- 按键 ----------
    private boolean isOkKey(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        return handleKeyDown(keyCode, e);
    }

    /** 按键入口：View 焦点事件和 Activity 的 dispatchKeyEvent 都走这里 */
    public boolean handleKeyDown(int keyCode, KeyEvent e) {
        noteKey(keyCode);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { if (jumpMode(keyCode)) return true; dpadDown(keyCode, e); kL = true; wake(); invalidate(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { if (jumpMode(keyCode)) return true; dpadDown(keyCode, e); kR = true; wake(); invalidate(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { if (jumpMode(keyCode)) return true; dpadDown(keyCode, e); kU = true; wake(); invalidate(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { if (jumpMode(keyCode)) return true; dpadDown(keyCode, e); kD = true; wake(); invalidate(); return true; }

        if (isOkKey(keyCode)) {
            if (e.getRepeatCount() == 0) {
                pressing = true;
                pressAt = SystemClock.uptimeMillis();
                pressConsumed = false;
                dragMoved = false;
                pressX = curX;
                pressY = curY;
                startDragIfTitle();
            } else if (!pressConsumed && dragWin == null
                    && SystemClock.uptimeMillis() - pressAt > 620L) {
                pressConsumed = true;
                longPressAt(curX, curY);
            }
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (menuOpen >= 0) { closeMenu(); return true; }
            if (lpOpen) closeLaunchpad();
            else if (!wins.isEmpty()) closeWin(wins.get(wins.size() - 1));
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_M) {
            if (lpOpen) closeLaunchpad();
            else openLaunchpad();
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    private boolean jumpMode(int keyCode) {
        if (cursorMode) return false;
        float dx = 0f, dy = 0f;
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dx = -1f;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dx = 1f;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dy = -1f;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dy = 1f;
        if (dx == 0f && dy == 0f) return false;
        rebuildHits();
        Hit best = null;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < hits.size(); i++) {
            Hit h = hits.get(i);
            String cur = hitAt(curX, curY);
            if (cur != null && h.id.equals(cur)) continue;
            float vx = h.r.centerX() - curX;
            float vy = h.r.centerY() - curY;
            float proj = vx * dx + vy * dy;
            if (proj <= 10f) continue;
            float perp = Math.abs(vx * dy - vy * dx);
            if (perp > proj * 1.25f + 72f) continue;
            float score = proj + perp * 0.9f;
            if (score < bestScore) {
                bestScore = score;
                best = h;
            }
        }
        if (best != null) {
            curX = best.r.centerX();
            curY = best.r.centerY();
            hoverId = best.id;
            hoverBurst = 1f;
            wake();
            invalidate();
        }
        return true;
    }

    private void startDragIfTitle() {
        if (!cursorMode) return;
        String id = hitAt(curX, curY);
        if (id != null && id.startsWith("w:") && id.endsWith(":title")) {
            String rest = id.substring(2);
            int c = rest.indexOf(':');
            Win w = winById(toInt(rest.substring(0, c), -1));
            if (w != null) {
                dragWin = w;
                dragOffX = curX - w.x;
                dragOffY = curY - w.y;
                raise(w);
            }
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return handleKeyUp(keyCode, e);
    }

    public boolean handleKeyUp(int keyCode, KeyEvent e) {
        noteKey(keyCode);
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { kL = false; wake(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { kR = false; wake(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) { kU = false; wake(); return true; }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) { kD = false; wake(); return true; }

        if (isOkKey(keyCode)) {
            long held = SystemClock.uptimeMillis() - pressAt;
            boolean wasDrag = dragWin != null && dragMoved;
            if (dragWin != null) dragWin = null;
            if (pressing) {
                if (!pressConsumed) {
                    if (held > 620L) {
                        pressConsumed = true;
                        longPressAt(curX, curY);
                    } else if (!wasDrag) {
                        clickAt(curX, curY);
                    }
                }
            }
            pressing = false;
            pressConsumed = false;
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK) return true;
        return super.onKeyUp(keyCode, e);
    }

    /**
     * 空鼠 / 手感遥控器：摇杆轴和鼠标 HOVER 也能直接带动光标，
     * 不再只认方向键（有些电视遥控器根本不发 DPAD，只发摇杆轴）。
     */
    @Override
    public boolean onGenericMotionEvent(MotionEvent e) {
        int src = e.getSource();
        if ((src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) {
            float ax = e.getAxisValue(MotionEvent.AXIS_X);
            float ay = e.getAxisValue(MotionEvent.AXIS_Y);
            float hat = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            if (Math.abs(hat) > Math.abs(ax)) ax = hat;
            if (Math.abs(hatY) > Math.abs(ay)) ay = hatY;
            if (Math.abs(ax) > 0.15f || Math.abs(ay) > 0.15f) {
                curX = clampF(curX + ax * 46f, 0f, DW);
                curY = clampF(curY + ay * 46f, 0f, DH);
                velX = 0f;
                velY = 0f;
                wake();
                return true;
            }
        }
        if ((src & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE && scale > 0f) {
            float x = (e.getX() - offX) / scale;
            float y = (e.getY() - offY) / scale;
            if (x >= 0f && x <= DW && y >= 0f && y <= DH) {
                curX = x;
                curY = y;
                velX = 0f;
                velY = 0f;
                wake();
                return true;
            }
        }
        return super.onGenericMotionEvent(e);
    }

    // ---------- 触摸 ----------
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = (e.getX() - offX) / scale;
        float y = (e.getY() - offY) / scale;
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_DOWN) {
            curX = x;
            curY = y;
            velX = 0f;
            velY = 0f;
            rebuildHits();
            startDragIfTitle();
            wake();
            return true;
        }
        if (act == MotionEvent.ACTION_HOVER_MOVE) {
            curX = clampF(x, 0f, DW);
            curY = clampF(y, 0f, DH);
            velX = 0f;
            velY = 0f;
            wake();
            return true;
        }
        if (act == MotionEvent.ACTION_MOVE) {
            curX = clampF(x, 0f, DW);
            curY = clampF(y, 0f, DH);
            if (dragWin != null) dragMoved = true;
            wake();
            return true;
        }
        if (act == MotionEvent.ACTION_UP) {
            boolean wasDrag = dragWin != null && dragMoved;
            if (dragWin != null) dragWin = null;
            if (!wasDrag) {
                curX = clampF(x, 0f, DW);
                curY = clampF(y, 0f, DH);
                rebuildHits();
                clickAt(curX, curY);
            }
            wake();
            return true;
        }
        return true;
    }
}
