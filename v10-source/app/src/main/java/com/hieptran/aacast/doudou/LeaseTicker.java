package com.carassistant.v10;

import android.os.SystemClock;

/**
 * Vòng lặp lease dock: khi split screen đang hiển thị, ghi `visible_until = now + 3000`
 * mỗi 1 giây. Gearhead đọc giá trị này qua DockStateProvider để quyết định ẩn/khôi phục dock.
 */
public final class LeaseTicker implements Runnable {

    private static final long LEASE_MS = 3000L;
    private static final long PERIOD_MS = 1000L;

    private final AACastCarActivity activity;

    public LeaseTicker(AACastCarActivity activity) {
        this.activity = activity;
    }

    @Override
    public void run() {
        boolean visible = activity.isShowing();
        DockStateProvider.visibleUntil = visible ? SystemClock.uptimeMillis() + LEASE_MS : 0L;
        if (visible) {
            activity.handler.postDelayed(this, PERIOD_MS);
        }
    }
}
