package com.team4.bintime.timetable;

import com.team4.bintime.data.availability.FreeCells;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.repository.FreeSlotValidator;

import org.junit.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-036] TimetableDraft — 주간 탭 공강 칸 선택·해제·재선택, 수업과 겹친 칸 정리, 다음 공강 카드 경계.
 * 화면({@code TimetableWeekFragment.applyLatest})과 같은 순서(toDraft → discardOccupiedFreeSlots → 저장 검증)를 따른다.
 */
public class TimetableDraftTest {

    private static final int MON = 0;
    private static final int FRI = 4;

    private static com.team4.bintime.data.model.Course stored(String id, TimeSlot... slots) {
        return new com.team4.bintime.data.model.Course(id, "수업" + id, null, null, null,
                new ArrayList<>(Arrays.asList(slots)));
    }

    /** 저장소 값 → 화면 draft (TimetableWeekFragment.applyLatest와 같은 정리). */
    private static TimetableDraft applyLatest(Timetable t) {
        TimetableDraft draft = TimetableDraftMapper.toDraft(t);
        draft.discardOccupiedFreeSlots();
        return draft;
    }

    // ── 선택 → 해제 → 재선택 ─────────────────────────

    @Test
    public void toggle_onOffOn_restoresSameMask() {
        TimetableDraft draft = new TimetableDraft(Collections.emptyList());
        draft.toggleFree(MON, 20);
        assertEquals(1L << 20, draft.freeCells()[MON]);
        draft.toggleFree(MON, 20);
        assertEquals(0L, draft.freeCells()[MON]);
        draft.toggleFree(MON, 20);
        assertEquals(1L << 20, draft.freeCells()[MON]);
        assertTrue(draft.isFreeSelected(MON, 20));
    }

    @Test
    public void toggle_cellTouchedByCourse_ignored_nextCellAllowed() {
        // 10:00~10:15 수업 → 10:00 칸은 1분이라도 겹쳐 선택 불가, 10:30 칸은 가능
        TimetableDraft draft = new TimetableDraft(Collections.singletonList(
                new Course("수학", "101호", "월", "10:00", "10:15")));
        draft.toggleFree(MON, 20);
        draft.toggleFree(MON, 21);
        assertEquals(1L << 21, draft.freeCells()[MON]);
        assertFalse(draft.isFreeSelected(MON, 20));
    }

    @Test
    public void toggle_firstAndLastCell_mapToMidnightBoundaries() {
        TimetableDraft draft = new TimetableDraft(Collections.emptyList());
        draft.toggleFree(MON, 0);
        draft.toggleFree(FRI, 47);
        assertEquals(Arrays.asList(new TimeSlot(1, 0, 30), new TimeSlot(5, 1410, 1440)),
                TimetableDraftMapper.toFreeSlots(draft));
    }

    // ── 수업 변경 후 겹친 칸 정리 ─────────────────────

    @Test
    public void discard_unalignedCourseEnd_clearsTouchedCell() {
        Timetable t = new Timetable("u", "u", null);
        t.getCourses().add(stored("a", new TimeSlot(1, 600, 615)));          // 월 10:00~10:15
        t.setFreeSlots(Collections.singletonList(new TimeSlot(1, 570, 660))); // 09:30~11:00 (수업 추가 전 선택)
        TimetableDraft draft = applyLatest(t);
        assertEquals(Arrays.asList(new TimeSlot(1, 570, 600), new TimeSlot(1, 630, 660)),
                TimetableDraftMapper.toFreeSlots(draft));
        assertNull(FreeSlotValidator.validate(draft.freeCells(), t.getCourses()));
    }

    @Test
    public void discard_courseEndingAtMidnight_clearsLastCell() {
        Timetable t = new Timetable("u", "u", null);
        t.getCourses().add(stored("a", new TimeSlot(5, 1380, 1440)));          // 금 23:00~24:00
        t.setFreeSlots(Collections.singletonList(new TimeSlot(5, 1320, 1440)));
        TimetableDraft draft = applyLatest(t);
        assertEquals(Collections.singletonList(new TimeSlot(5, 1320, 1380)),
                TimetableDraftMapper.toFreeSlots(draft));
        assertNull(FreeSlotValidator.validate(draft.freeCells(), t.getCourses()));
    }

    @Test
    public void discard_weekendCourse_clearsWeekendCell_soNextSaveIsValid() {
        // 주말 공강 칸이 저장된 뒤 addCourse로 겹치는 주말 수업이 들어온 상태(addCourse는 freeCells를 정리하지 않는다).
        // 화면은 토·일을 그리지 않지만 저장할 때 7일을 모두 보내므로, 주말의 겹친 칸도 지워야 저장이 거부되지 않는다.
        Timetable t = new Timetable("u", "u", null);
        t.getCourses().add(stored("a", new TimeSlot(7, 630, 690)));            // 일 10:30~11:30
        t.setFreeSlots(Arrays.asList(new TimeSlot(1, 600, 660), new TimeSlot(7, 600, 720)));
        TimetableDraft draft = applyLatest(t);
        draft.toggleFree(MON, 30); // 사용자가 주중 칸 하나를 누른다 → 7일 치 저장
        assertEquals(Arrays.asList(new TimeSlot(1, 600, 660), new TimeSlot(1, 900, 930),
                        new TimeSlot(7, 600, 630), new TimeSlot(7, 690, 720)),
                TimetableDraftMapper.toFreeSlots(draft));
        assertNull(FreeSlotValidator.validate(draft.freeCells(), t.getCourses()));
    }

    @Test
    public void discard_weekdayCourseAddedLater_clearsCell_andKeepsWeekendSelection() {
        Timetable t = new Timetable("u", "u", null);
        t.getCourses().add(stored("a", new TimeSlot(2, 660, 720)));            // 화 11:00~12:00
        t.setFreeSlots(Arrays.asList(new TimeSlot(2, 600, 780), new TimeSlot(6, 600, 660)));
        TimetableDraft draft = applyLatest(t);
        assertEquals(Arrays.asList(new TimeSlot(2, 600, 660), new TimeSlot(2, 720, 780),
                new TimeSlot(6, 600, 660)), TimetableDraftMapper.toFreeSlots(draft));
        assertEquals(3, FreeCells.matchable(draft.freeCells()).size());
    }

    // ── 다음 공강 카드 경계 ───────────────────────────

    @Test
    public void nextFreeWindow_lastHourOfDay_shownUntilMidnight() {
        TimetableDraft draft = new TimetableDraft(Collections.emptyList());
        draft.toggleFree(FRI, 46);
        draft.toggleFree(FRI, 47);
        LocalDate friday = LocalDate.of(2026, 11, 13);
        TimetableDraft.FreeWindow w = draft.nextFreeWindow(LocalDateTime.of(2026, 11, 13, 23, 59));
        assertEquals(friday, w.date);
        assertEquals(46, w.start);
        assertEquals(48, w.end);
        // 자정이 지나면(토) 주말을 건너뛰고 다음 금요일
        TimetableDraft.FreeWindow next = draft.nextFreeWindow(LocalDateTime.of(2026, 11, 14, 0, 0));
        assertEquals(friday.plusDays(7), next.date);
    }

    @Test
    public void nextFreeWindow_weekendOnlyOr30MinOnly_isNull() {
        TimetableDraft weekend = new TimetableDraft(Collections.emptyList());
        long[] cells = new long[7];
        cells[5] = 3L << 20; // 토 10:00~11:00
        cells[6] = 3L << 20; // 일 10:00~11:00
        weekend.setFreeCells(cells);
        assertNull(weekend.nextFreeWindow(LocalDateTime.of(2026, 11, 13, 9, 0)));

        TimetableDraft single = new TimetableDraft(Collections.emptyList());
        single.toggleFree(MON, 20);
        single.toggleFree(MON, 22); // 30분 칸 두 개가 떨어져 있음
        assertNull(single.nextFreeWindow(LocalDateTime.of(2026, 11, 9, 9, 0)));
    }
}
