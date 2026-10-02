package com.carassistant.v9;

import org.junit.Test;
import static org.junit.Assert.*;

public class TileOrderTest {

    @Test
    public void testDefaultOrder() {
        TileOrder order = new TileOrder(null);
        assertEquals("0,1,2", order.serialize());
        assertEquals(0, order.positionOf(0));
        assertEquals(1, order.positionOf(1));
        assertEquals(2, order.positionOf(2));
    }

    @Test
    public void testCustomOrder() {
        TileOrder order = new TileOrder("2,0,1");
        assertEquals("2,0,1", order.serialize());
        assertEquals(1, order.positionOf(0));
        assertEquals(2, order.positionOf(1));
        assertEquals(0, order.positionOf(2));
    }

    @Test
    public void testSwap() {
        TileOrder order = new TileOrder("0,1,2");
        assertTrue(order.swap(0, 2));
        assertEquals("2,1,0", order.serialize());
    }

    @Test
    public void testReset() {
        TileOrder order = new TileOrder("0,1,2");
        order.swap(0, 1);
        assertEquals("1,0,2", order.serialize());
        order.reset("2,1,0");
        assertEquals("2,1,0", order.serialize());
    }

    @Test
    public void testInvalidInputFallsBack() {
        TileOrder order = new TileOrder("0,0,1");
        assertEquals("0,1,2", order.serialize());
    }
}
