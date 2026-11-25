package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** [BT-048] 원본 보기 확대·넘기기 계산. */
public class ZoomMathTest {

    @Test
    public void scale_isClampedBetweenOneAndMax() {
        assertEquals(1f, ZoomMath.clampScale(0.3f), 0f);
        assertEquals(2f, ZoomMath.clampScale(2f), 0f);
        assertEquals(ZoomMath.MAX_SCALE, ZoomMath.clampScale(40f), 0f);
        assertEquals(1f, ZoomMath.clampScale(Float.NaN), 0f);
    }

    @Test
    public void boundShift_centersSmallImage_andKeepsLargeImageEdgesOutside() {
        assertEquals(25f, ZoomMath.boundShift(0f, 50f, 100f), 0f);
        assertEquals(-10f, ZoomMath.boundShift(10f, 210f, 100f), 0f);
        assertEquals(20f, ZoomMath.boundShift(-120f, 80f, 100f), 0f);
        assertEquals(0f, ZoomMath.boundShift(-50f, 150f, 100f), 0f);
    }

    @Test
    public void swipe_needsMostlyHorizontalFastLongMove() {
        assertEquals(1, ZoomMath.swipeDirection(-200f, 10f, -800f));
        assertEquals(-1, ZoomMath.swipeDirection(200f, 10f, 800f));
        assertEquals(0, ZoomMath.swipeDirection(-50f, 0f, -800f));
        assertEquals(0, ZoomMath.swipeDirection(-200f, 300f, -800f));
        assertEquals(0, ZoomMath.swipeDirection(-200f, 0f, -100f));
    }

    @Test
    public void page_staysWithinRange() {
        assertEquals(1, ZoomMath.page(0, 1, 3));
        assertEquals(2, ZoomMath.page(2, 1, 3));
        assertEquals(0, ZoomMath.page(0, -1, 3));
        assertEquals(0, ZoomMath.page(4, 0, 0));
        assertEquals(1, ZoomMath.page(5, 0, 2));
    }
}
