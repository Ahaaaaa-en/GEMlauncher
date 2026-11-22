package com.gemjiang.launcher;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public class LauncherActivity extends Activity {

    private DesktopView desktop;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 常亮，桌面不会因为空闲变暗
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        desktop = new DesktopView(this);
        setContentView(desktop);
        hideSystemUi();
        desktop.start();
        desktop.post(new Runnable() {
            @Override
            public void run() {
                desktop.requestFocus();
                desktop.requestFocusFromTouch();
            }
        });
    }

    /**
     * 关键改动：遥控器按键在 Activity 这一层就截下来直接喂给光标，
     * 不再依赖 View 有没有拿到焦点（电视 ROM 上焦点容易丢，一丢方向键就完全没反应）。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (desktop != null) {
            int code = event.getKeyCode();
            if (isCursorKey(code)) {
                boolean handled = event.getAction() == KeyEvent.ACTION_DOWN
                        ? desktop.handleKeyDown(code, event)
                        : desktop.handleKeyUp(code, event);
                if (handled) return true;
            }
            if (event.getAction() == KeyEvent.ACTION_DOWN && !desktop.hasFocus()) {
                desktop.requestFocus();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean isCursorKey(int code) {
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_BUTTON_A:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_M:
            case KeyEvent.KEYCODE_BACK:
                return true;
            default:
                return false;
        }
    }

    private void hideSystemUi() {
        try {
            View d = getWindow().getDecorView();
            d.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
        if (desktop != null) {
            desktop.requestFocus();
            desktop.onResumeRefresh();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
            if (desktop != null) desktop.requestFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (desktop != null) {
            desktop.requestFocus();
            desktop.onResumeRefresh();
        }
    }
}
