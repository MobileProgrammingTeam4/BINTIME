package com.team4.bintime.timetable;

import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.MockAsyncTimetableRepository;
import com.team4.bintime.data.repository.MockTimetableRepositoryImpl;
import com.team4.bintime.data.repository.TimetableFailure;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-015/016] TimetableDraftMapper — 저장소 ↔ 화면 draft 변환, courseId 유지, 여러 시간대 묶기, 공강 선택 복원.
 * TimetableLoader — 한 번만 읽고 해제.
 */
public class TimetableDraftMapperTest {

    private static com.team4.bintime.data.model.Course stored(String id, String professor, TimeSlot... slots) {
        return new com.team4.bintime.data.model.Course(id, "수업" + id, professor, "공학관", "#AABBCC",
                new ArrayList<>(Arrays.asList(slots)));
    }

    @Test
    public void toDraft_null_isEmpty() {
        TimetableDraft draft = TimetableDraftMapper.toDraft(null);
        assertTrue(draft.courses.isEmpty());
        assertTrue(TimetableDraftMapper.toFreeSlots(draft).isEmpty());
    }

    @Test
    public void multiSlotCourse_expandsToRows_andRegroupsById() {
        Timetable t = new Timetable("u", "u", null);
        t.getCourses().add(stored("a", "김교수", new TimeSlot(1, 600, 690), new TimeSlot(3, 600, 690)));
        t.getCourses().add(stored("b", null, new TimeSlot(2, 540, 1440)));

        TimetableDraft draft = TimetableDraftMapper.toDraft(t);
        assertEquals(3, draft.courses.size());
        Course row = draft.courses.get(1);
        assertEquals("a", row.getCourseId());
        assertEquals("수", row.getDay());
        assertEquals("10:00", row.getStartTime());
        assertEquals("11:30", row.getEndTime());
        assertEquals("24:00", draft.courses.get(2).getEndTime());

        List<com.team4.bintime.data.model.Course> back = TimetableDraftMapper.toCourses(draft);
        assertEquals(2, back.size());
        com.team4.bintime.data.model.Course a = back.get(0);
        assertEquals("a", a.getCourseId());
        assertEquals("김교수", a.getProfessor());
        assertEquals("#AABBCC", a.getColorCode());
        assertEquals(Arrays.asList(new TimeSlot(1, 600, 690), new TimeSlot(3, 600, 690)), a.getTimeSlots());
        assertEquals(Collections.singletonList(new TimeSlot(2, 540, 1440)), back.get(1).getTimeSlots());
    }

    @Test
    public void editedRow_keepsIdentity_newRowGetsNewId() {
        Timetable t = new Timetable("u", "u", null);
        t.getCourses().add(stored("a", "김교수", new TimeSlot(1, 600, 690)));
        TimetableDraft draft = TimetableDraftMapper.toDraft(t);

        Course edited = new Course("새 이름", "본관", "화", "13:00", "14:00");
        edited.keepIdentityOf(draft.courses.get(0));
        draft.courses.set(0, edited);
        draft.courses.add(new Course("추가", "인문관", "금", "09:00", "10:00"));

        List<com.team4.bintime.data.model.Course> back = TimetableDraftMapper.toCourses(draft);
        assertEquals(2, back.size());
        assertEquals("a", back.get(0).getCourseId());
        assertEquals("새 이름", back.get(0).getName());
        assertEquals("김교수", back.get(0).getProfessor());
        assertEquals(Collections.singletonList(new TimeSlot(2, 780, 840)), back.get(0).getTimeSlots());
        assertNotNull(back.get(1).getCourseId());
        assertTrue(!"a".equals(back.get(1).getCourseId()));
    }

    @Test
    public void legacyRowWithoutId_getsIdOnSave() {
        Course row = new Course("수업", "본관", "월", "09:00", "10:00");
        row.setCourseId(null);
        TimetableDraft draft = new TimetableDraft(Collections.singletonList(row));
        assertNotNull(TimetableDraftMapper.toCourses(draft).get(0).getCourseId());
    }

    @Test
    public void freeSelection_restoredIncluding30MinuteAndWeekend() {
        Timetable t = new Timetable("u", "u", null);
        t.setFreeSlots(Arrays.asList(new TimeSlot(1, 600, 630), new TimeSlot(5, 780, 900),
                new TimeSlot(7, 600, 660)));
        TimetableDraft draft = TimetableDraftMapper.toDraft(t);
        assertTrue(draft.isFreeSelected(0, 20));    // 월 10:00
        assertTrue(draft.isFreeSelected(4, 27));    // 금 13:30
        // 토·일은 화면에 없지만 저장할 때 그대로 돌려준다.
        assertEquals(t.getFreeSlots(), TimetableDraftMapper.toFreeSlots(draft));
    }

    @Test
    public void restoredDraft_doesNotReSuggestGaps() {
        Timetable t = new Timetable("u", "u", null);
        t.getCourses().add(stored("a", null, new TimeSlot(1, 540, 600)));
        t.getCourses().add(stored("b", null, new TimeSlot(1, 720, 780)));
        TimetableDraft draft = TimetableDraftMapper.toDraft(t);
        draft.suggestGaps();
        assertTrue(TimetableDraftMapper.toFreeSlots(draft).isEmpty());
    }

    // ── TimetableLoader ─────────────────────────────

    @Test
    public void loadOnce_deliversFirstValueOnly_andUnsubscribes() {
        MockTimetableRepositoryImpl.getInstance().resetToMockData();
        MockAsyncTimetableRepository repo =
                new MockAsyncTimetableRepository(MockTimetableRepositoryImpl.getInstance(), () -> "loader-uid");
        final List<Timetable> received = new ArrayList<>();
        TimetableLoader.loadOnce(repo, new AsyncTimetableRepository.Callback<Timetable>() {
            @Override
            public void onSuccess(Timetable result) {
                received.add(result);
            }

            @Override
            public void onError(TimetableFailure failure) {
                throw new AssertionError(failure.toString());
            }
        });
        assertEquals(1, received.size());
        assertNull(received.get(0));
        repo.saveFreeSlots(Collections.singletonList(new TimeSlot(1, 600, 660)), null);
        assertEquals(1, received.size());
    }
}
