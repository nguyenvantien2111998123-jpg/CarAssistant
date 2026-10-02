package com.carassistant.v9;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.google.android.apps.auto.sdk.CarActivity;

/**
 * CarActivity hiển thị trên màn hình xe (do Android Auto host khởi chạy).
 */
public final class AACastCarActivity extends CarActivity {

    public final Handler handler = new Handler(Looper.getMainLooper());

    private RootView root;
    private boolean resumed;
    private final Runnable leaseTicker = new LeaseTicker(this);

    /** Ẩn header/menu của Android Auto + immersive. */
    private void applyChrome() {
        try {
            getCarUiController().getStatusBarController().hideAppHeader();
            getCarUiController().getMenuController().hideMenuButton();
        } catch (RuntimeException ignored) {
            // một số bản Android Auto không có các controller này
        }
        if (root != null) {
            AppCatalog.immersive(root);
        }
    }

    private void stopLease() {
        resumed = false;
        handler.removeCallbacks(leaseTicker);
        DockStateProvider.visibleUntil = 0L;
    }

    /** True khi split screen đang thực sự hiển thị (dùng cho LeaseTicker). */
    public boolean isShowing() {
        return resumed && root != null && !root.destroyed;
    }

    @Override
    public void onCreate(Bundle bundle) {
        setTheme(R.style.Theme_AACast);
        super.onCreate(bundle);
        setIgnoreConfigChanges(-1);
        applyChrome();
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        if (root == null || root.destroyed) {
            root = new RootView(this);
            root.exitToAuto = this::leaveCarActivity;
            setContentView(root);
        }
        applyChrome();
        handler.removeCallbacks(leaseTicker);
        leaseTicker.run();
    }

    /** Nút "Return to Android Auto". */
    private void leaveCarActivity() {
        stopLease();
        super.onBackPressed();
    }

    @Override
    public void onPause() {
        stopLease();
        super.onPause();
    }

    @Override
    public void onStop() {
        stopLease();
        if (root != null) {
            root.destroy();
        }
        super.onStop();
    }

    @Override
    public void onDestroy() {
        stopLease();
        if (root != null) {
            root.destroy();
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (root != null && root.dismissOverlay()) {
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus, boolean touchMode) {
        super.onWindowFocusChanged(hasFocus, touchMode);
        if (hasFocus && resumed) {
            applyChrome();
        }
    }
}
