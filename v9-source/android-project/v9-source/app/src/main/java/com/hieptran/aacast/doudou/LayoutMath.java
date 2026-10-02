package com.carassistant.v9;

/**
 * Toán hình học cho 6 kiểu bố cục.
 *
 * Layout mode:
 *   0 = 2 cột          1 = 2 hàng
 *   2 = 3 cột          3 = 3 hàng
 *   4 = 1 lớn trái + 2 phải (focus left)
 *   5 = 1 lớn trên + 2 dưới (focus top)
 */
public final class LayoutMath {

    private static final int MAX_BUFFER_SIZE = 2560;

    private LayoutMath() {
    }

    /** clamp(v, lo, hi) */
    public static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    /** Tính kích thước buffer cho VirtualDisplay mà không thay đổi state. */
    static int[] bufferSize(int paneIndex, int width, int height) {
        int minWidth = paneIndex == 0 ? 640 : 480;
        int minHeight = 360;
        int viewWidth = Math.max(1, width);
        int viewHeight = Math.max(1, height);
        float minimumScale = Math.max(1f,
                Math.max(minWidth / (float) viewWidth, minHeight / (float) viewHeight));
        float maximumScale = MAX_BUFFER_SIZE / (float) Math.max(viewWidth, viewHeight);
        float scale = Math.min(minimumScale, maximumScale);
        return new int[]{
                Math.max(1, Math.round(viewWidth * scale)),
                Math.max(1, Math.round(viewHeight * scale))
        };
    }

    /** Số "khe" vừa đủ (thực chất là bề dày gap bị kẹp). */
    public static int gapsFit(int length, int gap, int count) {
        return Math.min(Math.max(0, gap), Math.max(0, ((length - count) - 1) / count));
    }

    /**
     * 3 pane chia TUYẾN TÍNH trên cùng một trục? (mode 2 = 3 cột, mode 3 = 3 hàng)
     * Lưu ý: mode 4/5 (focus) cũng có 3 pane nhưng KHÔNG phải linear split —
     * chúng có 2 divider vuông góc, tỉ lệ mặc định 0.5/0.5 và mỗi trục chỉ 1 gap.
     */
    public static boolean isLinearThreeSplit(int mode) {
        return mode == 2 || mode == 3;
    }

    /** Divider có chia theo chiều dọc (trái/phải)? */
    public static boolean isVerticalSplit(int mode, int dividerIndex) {
        if (mode == 0 || mode == 2) {
            return true;
        }
        if (mode != 4) {
            return mode == 5 && dividerIndex == 1;
        }
        return dividerIndex == 0;
    }

    /** Chuẩn hoá tỉ lệ. */
    public static float[] ratios(int mode, float r1, float r2) {
        if (!Float.isFinite(r1)) {
            r1 = isLinearThreeSplit(mode) ? 0.33333334f : 0.5f;
        }
        if (!Float.isFinite(r2)) {
            r2 = isLinearThreeSplit(mode) ? 0.6666667f : 0.5f;
        }
        if (isLinearThreeSplit(mode)) {
            r1 = clamp(r1, 0.20f, 0.60f);
            r2 = clamp(r2, 0.20f + r1, 0.80f);
        } else {
            r1 = clamp(r1, 0.20f, 0.80f);
            r2 = clamp(r2, 0.20f, 0.80f);
        }
        return new float[]{r1, r2};
    }

    private static int[] rect(int a, int b, int cross, boolean vertical) {
        return vertical ? new int[]{a, 0, b, cross} : new int[]{0, a, cross, b};
    }

    /**
     * Tính toàn bộ rect pane + divider.
     */
    public static LayoutRects compute(int mode, int W, int H, int gap, float r1, float r2) {
        if (mode < 0 || mode >= 6) {
            mode = 0;
        }
        int w = Math.max(0, W);
        int h = Math.max(0, H);
        float[] rr = ratios(mode, r1, r2);
        float f1 = rr[0];
        float f2 = rr[1];

        int count = (mode == 0 || mode == 1) ? 2 : 3;
        int[][] panes = new int[count][4];
        int[][] divs = new int[count - 1][4];

        if (mode <= 3) {
            boolean vertical = (mode == 0 || mode == 2);      // chia theo trục ngang (cột)
            int primary = vertical ? w : h;
            int cross = vertical ? h : w;
            int gaps = count - 1;
            int gapCount = gapsFit(primary, gap, gaps);
            int usable = primary - gaps * gapCount;

            float u = usable;
            int seg0 = Math.round(f1 * u);                    // kích thước pane 0
            int segMid = (count == 3) ? Math.round(u * f2) : usable;   // mốc kết thúc pane giữa

            panes[0] = rect(0, seg0, cross, vertical);
            divs[0] = rect(seg0, seg0 + gapCount, cross, vertical);
            panes[1] = rect(seg0 + gapCount, segMid + gapCount, cross, vertical);
            if (count == 3) {
                divs[1] = rect(segMid + gapCount, segMid + 2 * gapCount, cross, vertical);
                panes[2] = rect(segMid + 2 * gapCount, primary, cross, vertical);
            }
            for (int[] r : panes) {                            // khử sai số vượt biên
                r[2] = Math.min(r[2], w);
                r[3] = Math.min(r[3], h);
            }
        } else if (mode == 4) {                               // 1 lớn trái + 2 phải
            int g = gapsFit(w, gap, 1);
            int gv = gapsFit(h, gap, 1);
            int left = Math.round((w - g) * f1);
            int top = Math.round((h - gv) * f2);
            int x1 = g + left;
            panes[0] = new int[]{0, 0, left, h};
            panes[1] = new int[]{x1, 0, w, top};
            panes[2] = new int[]{x1, gv + top, w, h};
            divs[0] = new int[]{left, 0, x1, h};
            divs[1] = new int[]{x1, top, w, gv + top};
        } else {                                              // mode 5: 1 lớn trên + 2 dưới
            int gv = gapsFit(h, gap, 1);
            int g = gapsFit(w, gap, 1);
            int top = Math.round((h - gv) * f1);
            int left = Math.round((w - g) * f2);
            int y1 = gv + top;
            panes[0] = new int[]{0, 0, w, top};
            panes[1] = new int[]{0, y1, left, h};
            panes[2] = new int[]{g + left, y1, w, h};
            divs[0] = new int[]{0, top, w, y1};
            divs[1] = new int[]{left, y1, g + left, h};
        }
        return new LayoutRects(panes, divs);
    }
}
