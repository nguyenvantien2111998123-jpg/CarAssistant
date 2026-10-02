package com.carassistant.v9;

import org.junit.Test;
import static org.junit.Assert.*;

public class LayoutMathTest {

    @Test
    public void testTwoColumnsLayoutRects() {
        LayoutRects rects = LayoutMath.compute(0, 1000, 500, 10, 0.5f, 0.5f);
        assertEquals(2, rects.panes.length);
        assertEquals(1, rects.dividers.length);

        // Check left pane
        int[] left = rects.panes[0];
        assertEquals(0, left[0]);
        assertEquals(0, left[1]);
        assertTrue(left[2] > 0);
        assertEquals(500, left[3]);

        // Check right pane
        int[] right = rects.panes[1];
        assertTrue(right[0] > left[2]);
        assertEquals(1000, right[2]);
        assertEquals(500, right[3]);
    }

    @Test
    public void testTwoRowsLayoutRects() {
        LayoutRects rects = LayoutMath.compute(1, 800, 600, 10, 0.5f, 0.5f);
        assertEquals(2, rects.panes.length);
        assertEquals(1, rects.dividers.length);

        int[] top = rects.panes[0];
        assertEquals(0, top[0]);
        assertEquals(0, top[1]);
        assertEquals(800, top[2]);

        int[] bottom = rects.panes[1];
        assertEquals(0, bottom[0]);
        assertTrue(bottom[1] > 0);
        assertEquals(800, bottom[2]);
        assertEquals(600, bottom[3]);
    }

    @Test
    public void testThreeColumnsLayoutRects() {
        LayoutRects rects = LayoutMath.compute(2, 900, 400, 6, 0.33f, 0.66f);
        assertEquals(3, rects.panes.length);
        assertEquals(2, rects.dividers.length);
    }

    @Test
    public void testFocusLeftLayoutRects() {
        LayoutRects rects = LayoutMath.compute(4, 1000, 600, 8, 0.6f, 0.5f);
        assertEquals(3, rects.panes.length);
        assertEquals(2, rects.dividers.length);
    }

    @Test
    public void testFocusTopLayoutRects() {
        LayoutRects rects = LayoutMath.compute(5, 1000, 600, 8, 0.6f, 0.5f);
        assertEquals(3, rects.panes.length);
        assertEquals(2, rects.dividers.length);
    }

    @Test
    public void testRatiosClamping() {
        float[] r = LayoutMath.ratios(0, 0.05f, 0.95f);
        assertTrue(r[0] >= 0.20f);
        assertTrue(r[1] <= 0.80f);
    }

    @Test
    public void bufferSizeKeepsLargePaneAtNativeSize() {
        assertArrayEquals(new int[]{900, 460}, LayoutMath.bufferSize(0, 900, 460));
    }

    @Test
    public void bufferSizeUpscalesSmallPanesToMinimum() {
        assertArrayEquals(new int[]{640, 480}, LayoutMath.bufferSize(0, 320, 240));
        assertArrayEquals(new int[]{480, 360}, LayoutMath.bufferSize(1, 320, 240));
    }

    @Test
    public void bufferSizePreservesAspectRatioWhenCapped() {
        int[] size = LayoutMath.bufferSize(0, 5120, 1440);
        assertArrayEquals(new int[]{2560, 720}, size);
        assertEquals(5120f / 1440f, size[0] / (float) size[1], 0.001f);
    }

    @Test
    public void bufferSizeIsStableForTheSameInput() {
        assertArrayEquals(LayoutMath.bufferSize(2, 605, 460),
                LayoutMath.bufferSize(2, 605, 460));
    }
}
