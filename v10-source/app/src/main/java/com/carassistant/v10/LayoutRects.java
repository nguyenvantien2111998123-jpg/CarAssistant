package com.carassistant.v10;

/**
 * Kết quả tính hình học: rect của các pane và các divider.
 * Mỗi rect là {left, top, right, bottom}.
 */
public final class LayoutRects {

    public final int[][] panes;
    public final int[][] dividers;

    public LayoutRects(int[][] panes, int[][] dividers) {
        this.panes = panes;
        this.dividers = dividers;
    }
}
