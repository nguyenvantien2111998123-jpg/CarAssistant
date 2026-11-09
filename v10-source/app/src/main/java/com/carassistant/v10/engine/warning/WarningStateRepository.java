package com.carassistant.v10.engine.warning;

import android.graphics.Bitmap;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Multi-source warning state. Each source owns an independent persistent speed
 * sign and temporary alert. The aggregate snapshot selects the newest temporary
 * alert first, then the newest persistent speed sign.
 *
 * Published bitmaps are copied into repository ownership and are not recycled
 * while a consumer may still hold an immutable snapshot.
 */
public final class WarningStateRepository {
    private final EnumMap<TrafficWarningSource, SourceState> states =
            new EnumMap<>(TrafficWarningSource.class);
    private final EnumSet<TrafficWarningSource> enabled =
            EnumSet.of(TrafficWarningSource.WYN);
    private volatile Snapshot current = Snapshot.empty();

    public synchronized Snapshot snapshot() {
        return current;
    }

    public synchronized Set<TrafficWarningSource> enabledSources() {
        return enabled.isEmpty()
                ? EnumSet.noneOf(TrafficWarningSource.class)
                : EnumSet.copyOf(enabled);
    }

    public synchronized boolean isSourceEnabled(TrafficWarningSource source) {
        return source != null && enabled.contains(source);
    }

    public synchronized boolean setEnabledSources(Set<TrafficWarningSource> sources) {
        EnumSet<TrafficWarningSource> next = EnumSet.noneOf(TrafficWarningSource.class);
        if (sources != null) next.addAll(sources);
        if (enabled.equals(next)) return false;

        enabled.clear();
        enabled.addAll(next);
        states.keySet().removeIf(source -> !enabled.contains(source));
        publishNextSnapshot();
        return true;
    }

    public synchronized boolean setSourceEnabled(TrafficWarningSource source, boolean value) {
        if (source == null) return false;
        boolean changed = value ? enabled.add(source) : enabled.remove(source);
        if (!changed) return false;
        if (!value) states.remove(source);
        publishNextSnapshot();
        return true;
    }

    /** Backward-compatible WYN entry point. */
    public boolean setSpeedSign(Bitmap bitmap) {
        return setSpeedSign(TrafficWarningSource.WYN, "speed_limit", bitmap);
    }

    public synchronized boolean setSpeedSign(
            TrafficWarningSource source, String code, Bitmap bitmap) {
        if (source == null || !enabled.contains(source)) return false;
        Bitmap copy = copyForRepository(bitmap);
        if (copy == null) return false;

        SourceState state = stateFor(source);
        if (sameBitmap(state.speedSign, copy) && equals(state.speedCode, code)) {
            recycleUnpublished(copy, bitmap);
            return false;
        }
        state.speedSign = copy;
        state.speedCode = code;
        state.speedUpdatedAt = System.currentTimeMillis();
        publishNextSnapshot();
        return true;
    }

    /** Backward-compatible WYN entry point. */
    public boolean setTemporaryAlert(Bitmap bitmap) {
        return setTemporaryAlert(TrafficWarningSource.WYN, null, bitmap);
    }

    public synchronized boolean setTemporaryAlert(
            TrafficWarningSource source, String code, Bitmap bitmap) {
        if (source == null || !enabled.contains(source)) return false;
        Bitmap copy = copyForRepository(bitmap);
        if (copy == null) return false;

        SourceState state = stateFor(source);
        if (sameBitmap(state.temporaryAlert, copy)
                && equals(state.temporaryCode, code)) {
            recycleUnpublished(copy, bitmap);
            return false;
        }
        state.temporaryAlert = copy;
        state.temporaryCode = code;
        state.temporaryUpdatedAt = System.currentTimeMillis();
        publishNextSnapshot();
        return true;
    }

    public synchronized boolean clearTemporaryAlert() {
        return clearTemporaryAlert(TrafficWarningSource.WYN);
    }

    public synchronized boolean clearTemporaryAlert(TrafficWarningSource source) {
        SourceState state = states.get(source);
        if (state == null || state.temporaryAlert == null) return false;
        state.temporaryAlert = null;
        state.temporaryCode = null;
        state.temporaryUpdatedAt = 0L;
        removeIfEmpty(source, state);
        publishNextSnapshot();
        return true;
    }

    public synchronized boolean clearSpeedSign(TrafficWarningSource source) {
        SourceState state = states.get(source);
        if (state == null || state.speedSign == null) return false;
        state.speedSign = null;
        state.speedCode = null;
        state.speedUpdatedAt = 0L;
        removeIfEmpty(source, state);
        publishNextSnapshot();
        return true;
    }

    public synchronized boolean clearSource(TrafficWarningSource source) {
        if (source == null || states.remove(source) == null) return false;
        publishNextSnapshot();
        return true;
    }

    public synchronized boolean clearAll() {
        if (states.isEmpty()) return false;
        states.clear();
        publishNextSnapshot();
        return true;
    }

    private SourceState stateFor(TrafficWarningSource source) {
        SourceState state = states.get(source);
        if (state == null) {
            state = new SourceState();
            states.put(source, state);
        }
        return state;
    }

    private void removeIfEmpty(TrafficWarningSource source, SourceState state) {
        if (state.speedSign == null && state.temporaryAlert == null) {
            states.remove(source);
        }
    }

    private void publishNextSnapshot() {
        Bitmap selectedSpeed = null;
        Bitmap selectedAlert = null;
        String selectedSpeedCode = null;
        String selectedAlertCode = null;
        TrafficWarningSource selectedSpeedSource = null;
        TrafficWarningSource selectedAlertSource = null;
        long newestSpeedAt = Long.MIN_VALUE;
        long newestAlertAt = Long.MIN_VALUE;

        for (Map.Entry<TrafficWarningSource, SourceState> entry : states.entrySet()) {
            TrafficWarningSource source = entry.getKey();
            if (!enabled.contains(source)) continue;
            SourceState state = entry.getValue();

            if (isUsable(state.speedSign) && state.speedUpdatedAt >= newestSpeedAt) {
                newestSpeedAt = state.speedUpdatedAt;
                selectedSpeed = state.speedSign;
                selectedSpeedCode = state.speedCode;
                selectedSpeedSource = source;
            }
            if (isUsable(state.temporaryAlert)
                    && state.temporaryUpdatedAt >= newestAlertAt) {
                newestAlertAt = state.temporaryUpdatedAt;
                selectedAlert = state.temporaryAlert;
                selectedAlertCode = state.temporaryCode;
                selectedAlertSource = source;
            }
        }

        current = new Snapshot(selectedSpeed, selectedAlert,
                selectedSpeedSource, selectedAlertSource,
                selectedSpeedCode, selectedAlertCode,
                current.generation + 1L);
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

    private static boolean equals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static final class SourceState {
        Bitmap speedSign;
        Bitmap temporaryAlert;
        String speedCode;
        String temporaryCode;
        long speedUpdatedAt;
        long temporaryUpdatedAt;
    }

    public static final class Snapshot {
        public final Bitmap speedSign;
        public final Bitmap temporaryAlert;
        public final TrafficWarningSource speedSource;
        public final TrafficWarningSource temporarySource;
        public final String speedCode;
        public final String temporaryCode;
        public final long generation;

        private Snapshot(Bitmap speedSign, Bitmap temporaryAlert,
                         TrafficWarningSource speedSource,
                         TrafficWarningSource temporarySource,
                         String speedCode, String temporaryCode, long generation) {
            this.speedSign = speedSign;
            this.temporaryAlert = temporaryAlert;
            this.speedSource = speedSource;
            this.temporarySource = temporarySource;
            this.speedCode = speedCode;
            this.temporaryCode = temporaryCode;
            this.generation = generation;
        }

        private static Snapshot empty() {
            return new Snapshot(null, null, null, null, null, null, 0L);
        }

        /** A temporary warning from any enabled source replaces the persistent sign. */
        public Bitmap activeWarning() {
            if (isUsable(temporaryAlert)) return temporaryAlert;
            return isUsable(speedSign) ? speedSign : null;
        }

        public TrafficWarningSource activeSource() {
            return isUsable(temporaryAlert) ? temporarySource : speedSource;
        }

        public String activeCode() {
            return isUsable(temporaryAlert) ? temporaryCode : speedCode;
        }

        public boolean hasWarnings() {
            return activeWarning() != null;
        }

        private static boolean isUsable(Bitmap bitmap) {
            return bitmap != null && !bitmap.isRecycled()
                    && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
        }
    }
}
