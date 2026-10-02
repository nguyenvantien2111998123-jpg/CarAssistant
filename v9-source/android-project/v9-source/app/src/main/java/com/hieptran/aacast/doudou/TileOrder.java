package com.carassistant.v9;

/**
 * Thứ tự tile: chuỗi "a,b,c" với 3 giá trị 0..2 đôi một khác nhau.
 */
public final class TileOrder {

    private final int[] order = {0, 1, 2};

    public TileOrder(String serialized) {
        reset(serialized);
    }

    public void reset(String serialized) {
        if (serialized == null || !serialized.matches("[0-2],[0-2],[0-2]")) {
            order[0] = 0;
            order[1] = 1;
            order[2] = 2;
            return;
        }
        int a = serialized.charAt(0) - '0';
        int b = serialized.charAt(2) - '0';
        int c = serialized.charAt(4) - '0';
        if (a == b || a == c || b == c) {
            order[0] = 0;
            order[1] = 1;
            order[2] = 2;
            return;
        }
        order[0] = a;
        order[1] = b;
        order[2] = c;
    }

    public String serialize() {
        return order[0] + "," + order[1] + "," + order[2];
    }

    /** Vị trí hiển thị của pane. */
    public int positionOf(int pane) {
        for (int i = 0; i < order.length; i++) {
            if (order[i] == pane) {
                return i;
            }
        }
        throw new IllegalStateException("Unknown app");
    }

    /** Hoán vị 2 pane; trả false nếu không hợp lệ. */
    public boolean swap(int paneA, int paneB) {
        if (paneA < 0 || paneA > 2 || paneB < 0 || paneB > 2 || paneA == paneB) {
            return false;
        }
        int ia = positionOf(paneA);
        int ib = positionOf(paneB);
        order[ia] = paneB;
        order[ib] = paneA;
        return true;
    }
}
