package com.gemjiang.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 已安装应用仓库：后台扫描、图标异步解码、点击启动。 */
public class Apps {

    public static final int ICON_PX = 192;

    public static class AppInfo {
        public final String pkg;
        public final String label;
        public final ComponentName comp;
        public Bitmap icon;

        public AppInfo(String pkg, String label, ComponentName comp) {
            this.pkg = pkg;
            this.label = label;
            this.comp = comp;
        }
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Bitmap> iconCache = new HashMap<String, Bitmap>();
    private final Collator collator = Collator.getInstance(Locale.CHINA);

    public final List<AppInfo> all = new ArrayList<AppInfo>();
    public final Map<String, AppInfo> byPkg = new HashMap<String, AppInfo>();

    private int token = 0;

    public Apps(Context c) {
        ctx = c.getApplicationContext();
    }

    public void refresh(final Runnable onData, final Runnable onIcon) {
        final int t = ++token;
        Thread th = new Thread(new Runnable() {
            @Override
            public void run() {
                final List<AppInfo> found = query();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (t != token) return;
                        all.clear();
                        all.addAll(found);
                        byPkg.clear();
                        for (AppInfo a : all) byPkg.put(a.pkg, a);
                        if (onData != null) onData.run();
                    }
                });
                for (int i = 0; i < found.size(); i++) {
                    if (t != token) return;
                    AppInfo a = found.get(i);
                    a.icon = loadIcon(a);
                    if (a.icon != null) {
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (t != token) return;
                                if (onIcon != null) onIcon.run();
                            }
                        });
                    }
                }
            }
        }, "gem-apps");
        th.setPriority(Thread.MIN_PRIORITY + 1);
        th.start();
    }

    private List<AppInfo> query() {
        ArrayList<AppInfo> out = new ArrayList<AppInfo>();
        try {
            PackageManager pm = ctx.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> rs = pm.queryIntentActivities(main, 0);
            Set<String> seen = new HashSet<String>();
            String self = ctx.getPackageName();
            for (int i = 0; i < rs.size(); i++) {
                ResolveInfo r = rs.get(i);
                if (r == null || r.activityInfo == null) continue;
                String p = r.activityInfo.packageName;
                if (p == null || p.equals(self)) continue;
                if (!seen.add(p)) continue;
                CharSequence cs = null;
                try {
                    cs = r.loadLabel(pm);
                } catch (Throwable ignored) {
                }
                String label = cs == null ? p : cs.toString().trim();
                if (label.length() == 0) label = p;
                out.add(new AppInfo(p, label, new ComponentName(p, r.activityInfo.name)));
            }
        } catch (Throwable ignored) {
        }
        try {
            Collections.sort(out, new Comparator<AppInfo>() {
                @Override
                public int compare(AppInfo a, AppInfo b) {
                    try {
                        return collator.compare(a.label, b.label);
                    } catch (Throwable e) {
                        return a.label.compareTo(b.label);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
        return out;
    }

    private Bitmap loadIcon(AppInfo a) {
        synchronized (iconCache) {
            Bitmap cached = iconCache.get(a.pkg);
            if (cached != null) return cached;
        }
        Bitmap bmp = null;
        try {
            PackageManager pm = ctx.getPackageManager();
            Drawable d = pm.getActivityIcon(a.comp);
            if (d != null) {
                bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                d.setBounds(0, 0, ICON_PX, ICON_PX);
                d.draw(c);
            }
        } catch (Throwable e) {
            bmp = null;
        }
        if (bmp != null) {
            synchronized (iconCache) {
                iconCache.put(a.pkg, bmp);
            }
        }
        return bmp;
    }

    public boolean launch(AppInfo a) {
        if (a == null) return false;
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_LAUNCHER);
            i.setComponent(a.comp);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(i);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }
}
