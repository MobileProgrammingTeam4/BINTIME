package com.team4.bintime.data.availability;

import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.util.TimeUtils;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

/**
 * [BT-016] SlotGrid — 자정 기준 30분 칸 변환.
 */
public class SlotGridTest {

    @Test
    public void slotOf_midnightBased() {
        assertEquals(0, SlotGrid.slotOf(0));
        assertEquals(0, SlotGrid.slotOf(29));
        assertEquals(1, SlotGrid.slotOf(30));
        assertEquals(18, SlotGrid.slotOf(540));
        assertEquals(47, SlotGrid.slotOf(1439));
    }

    @Test(expected = IllegalArgumentException.class)
    public void slotOf_negative_throws() {
        SlotGrid.slotOf(-1);
    }

    @Test(expected = IllegalArgumentException.class)
    public void slotOf_1440_throws() {
        SlotGrid.slotOf(1440);
    }

    @Test
    public void startAndEndSlot_roundDownAndUp() {
        assertEquals(19, SlotGrid.startSlotOf(575));
        assertEquals(19, SlotGrid.endSlotOf(570));
        assertEquals(20, SlotGrid.endSlotOf(575));
        assertEquals(48, SlotGrid.endSlotOf(1440));
        assertEquals(1, SlotGrid.endSlotOf(1));
    }

    @Test(expected = IllegalArgumentException.class)
    public void endSlotOf_zero_throws() {
        SlotGrid.endSlotOf(0);
    }

    @Test
    public void slotMinutes() {
        assertEquals(0, SlotGrid.slotStartMinute(0));
        assertEquals(30, SlotGrid.slotEndMinute(0));
        assertEquals(1410, SlotGrid.slotStartMinute(47));
        assertEquals(1440, SlotGrid.slotEndMinute(47));
    }

    @Test(expected = IllegalArgumentException.class)
    public void slotStartMinute_48_throws() {
        SlotGrid.slotStartMinute(48);
    }

    /** 09:00 기준 행 r ↔ 자정 기준 칸 r + 18 (09:00~18:00 범위에서만). */
    @Test
    public void legacyNineOClockRow_equalsSlotMinus18() {
        for (int row = 0; row < TimeUtils.TOTAL_SLOTS; row++) {
            assertEquals(row + TimeUtils.BASE_HOUR * 2,
                    SlotGrid.slotOf(TimeUtils.slotIndexToStartMinute(row)));
        }
    }

    @Test
    public void cellsToSlots_emptyMask() {
        assertEquals(Collections.emptyList(), SlotGrid.cellsToSlots(1, 0L));
    }

    @Test
    public void cellsToSlots_lastCellEndsAt1440() {
        assertEquals(Collections.singletonList(new TimeSlot(1, 1410, 1440)),
                SlotGrid.cellsToSlots(1, 1L << 47));
    }

    @Test
    public void cellsToSlots_firstAndLastCell_notJoinedAcrossMidnight() {
        assertEquals(Arrays.asList(new TimeSlot(2, 0, 30), new TimeSlot(2, 1410, 1440)),
                SlotGrid.cellsToSlots(2, 1L | (1L << 47)));
    }

    @Test
    public void cellsToSlots_adjacentMerged_separatedSplit() {
        long mask = (1L << 20) | (1L << 21) | (1L << 24);
        assertEquals(Arrays.asList(new TimeSlot(3, 600, 660), new TimeSlot(3, 720, 750)),
                SlotGrid.cellsToSlots(3, mask));
    }

    @Test(expected = IllegalArgumentException.class)
    public void cellsToSlots_bitBeyond47_throws() {
        SlotGrid.cellsToSlots(1, 1L << 48);
    }

    @Test(expected = IllegalArgumentException.class)
    public void cellsToSlots_invalidDay_throws() {
        SlotGrid.cellsToSlots(8, 1L);
    }
}
