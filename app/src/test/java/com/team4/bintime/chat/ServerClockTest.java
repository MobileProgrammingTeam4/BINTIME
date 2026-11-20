package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

/** BT-035 표시용 서버 시각 보정. 취소돼도 마지막 보정 값을 유지한다. */
public class ServerClockTest {

    private long device;
    private ServerClock clock;

    @Before
    public void setUp() {
        device = 1_000_000L;
        clock = new ServerClock(() -> device);
    }

    @Test
    public void beforeFirstOffsetUsesDeviceTime() {
        assertEquals(1_000_000L, clock.nowMillis());
    }

    @Test
    public void appliesPositiveAndNegativeOffsets() {
        clock.onOffset(600_000L); // 단말이 10분 느림
        assertEquals(1_600_000L, clock.nowMillis());
        clock.onOffset(-600_000L); // 단말이 10분 빠름
        assertEquals(400_000L, clock.nowMillis());
        clock.onOffset(1.5e3); // RTDB 가 Double 로 줄 수도 있다
        assertEquals(1_001_500L, clock.nowMillis());
    }

    @Test
    public void ignoresNonNumbersAndKeepsLastOffset() {
        clock.onOffset(5_000L);
        clock.onOffset(null);
        clock.onOffset("12");
        assertEquals(1_005_000L, clock.nowMillis());
    }

    @Test
    public void cancelKeepsLastOffsetInsteadOfResetting() {
        clock.onOffset(-120_000L);
        clock.onCancelled();
        assertEquals(880_000L, clock.nowMillis());
        // 단말 시각이 흐르면 같은 보정으로 따라간다.
        device += 30_000L;
        assertEquals(910_000L, clock.nowMillis());
    }

    @Test
    public void cancelBeforeAnyOffsetStaysOnDeviceTime() {
        clock.onCancelled();
        assertEquals(1_000_000L, clock.nowMillis());
    }
}
