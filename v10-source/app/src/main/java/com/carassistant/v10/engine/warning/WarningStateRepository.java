package com.carassistant.v10.engine.warning;

import android.graphics.Bitmap;

/**
 * Atomic, immutable snapshots of WYN's persistent speed sign and temporary alert.
 * Published bitmaps are owned by this repository and are never recycled while a
 * consumer may still hold a snapshot.
 */
public final class WarningStateRepository {
    private volatile Snapshot current = new Snapshot(null, null, 0L);

    public Snapshot snapshot() {
        return current;
    }

    public synchronized boolean setSpeedSign(Bitmap source) {
        Bitmap copy = copyForRepository(source);
        if (copy == null) return false;
        Snapshot old = current;
        if (sameBitmap(old.speedSign, copy)) {
            recycleUnpublished(copy, source);
            return false;
        }
        current = new Snapshot(copy, old.temporaryAlert, old.generation + 1L);
        return true;
    }

    public synchronized boolean setTemporaryAlert(Bitmap source) {
        Bitmap copy = copyForRepository(source);
        if (copy == null) return false;
        Snapshot old = current;
        if (sameBitmap(old.temporaryAlert, copy)) {
            recycleUnpublished(copy, source);
            return false;
        }
        current = new Snapshot(old.speedSign, copy, old.generation + 1L);
        return true;
    }

    public synchronized boolean clearTemporaryAlert() {
        Snapshot old = current;
        if (old.temporaryAlert == null) return false;
        current = new Snapshot(old.speedSign, null, old.generation + 1L);
        return true;
    }

    public synchronized boolean clearAll() {
        Snapshot old = current;
        if (old.speedSign == null && old.temporaryAlert == null) return false;
        current = new Snapshot(null, null, old.generation + 1L);
        return true;
    }

    private static Bitmap copyForRepository(Bitmap source) {
        if (!isUsable(source)) return null;
        try {
            int width = source.getWidth();
            int height = source.getHeight();
            float scale = Math.min(1f, 512f / Math.max(width, height));
            if (scale < 1f) {
                return Bitmap.createScaledBitmap(source,
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)), true);
            }
            return source.copy(Bitmap.Config.ARGB_8888, false);
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static boolean sameBitmap(Bitmap left, Bitmap right) {
        if (!isUsable(left) || !isUsable(right)) return false;
        try {
            return left.getWidth() == right.getWidth()
                    && left.getHeight() == right.getHeight()
                    && left.sameAs(right);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static boolean isUsable(Bitmap bitmap) {
        return bitmap != null && !bitmap.isRecycled()
                && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
    }

    private static void recycleUnpublished(Bitmap candidate, Bitmap source) {
        if (candidate != null && candidate != source && !candidate.isRecycled()) {
            candidate.recycle();
        }
    }

    public static final class Snapshot {
        public final Bitmap speedSign;
        public final Bitmap temporaryAlert;
        public final long generation;

        private Snapshot(Bitmap speedSign, Bitmap temporaryAlert, long generation) {
            this.speedSign = speedSign;
            this.temporaryAlert = temporaryAlert;
            this.generation = generation;
        }

        /** A temporary warning replaces the persistent speed sign until it clears. */
        public Bitmap activeWarning() {
            if (isUsable(temporaryAlert)) return temporaryAlert;
            return isUsable(speedSign) ? speedSign : null;
        }

        public boolean hasWarnings() {
            return activeWarning() != null;
        }
    }
}
