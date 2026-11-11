package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * [BT-012] TimetableRepository 계약 (Mock 구현) 단위 테스트.
 * 직접 입력/분석 결과 화면이 같은 목록을 다시 저장해도 courseId 기준으로 중복되지 않아야 합니다.
 */
public class MockTimetableRepositoryImplTest {

    private static final String UID = MockTimetableRepositoryImpl.MOCK_USER_ID;
    private MockTimetableRepositoryImpl repo;

    @Before
    public void setUp() {
        repo = MockTimetableRepositoryImpl.getInstance();
        repo.resetToMockData();
    }

    @Test
    public void saveCourse_sameIdTwice_replacesInsteadOfDuplicating() {
        Timetable tt = repo.getTimetable(UID);
        int before = tt.getCourseCount();
        Course course = Course.createSingle("네트워크", "공학관 305호", "금", "13:00", "14:30");

        repo.saveCourse(UID, course);
        repo.saveCourse(UID, course);

        assertEquals(before + 1, tt.getCourseCount());
    }

    @Test
    public void saveCourse_sameNameDifferentId_keepsBoth() {
        Timetable tt = repo.getTimetable(UID);
        int before = tt.getCourseCount();

        repo.saveCourse(UID, Course.createSingle("세미나", "A", "월", "15:00", "16:00"));
        repo.saveCourse(UID, Course.createSingle("세미나", "A", "수", "15:00", "16:00"));

        assertEquals(before + 2, tt.getCourseCount());
    }

    @Test
    public void saveCourse_incrementsRevisionAndCreatesTimetableForNewUser() {
        assertNull(repo.getTimetable("new_user"));
        repo.saveCourse("new_user", Course.createSingle("A", "R", "월", "10:00", "11:00"));

        Timetable tt = repo.getTimetable("new_user");
        assertNotNull(tt);
        assertEquals(2, tt.getRevision());
    }

    @Test
    public void deleteCourse_byIdOnly() {
        Course course = Course.createSingle("A", "R", "월", "16:00", "17:00");
        repo.saveCourse(UID, course);

        assertTrue(repo.deleteCourse(UID, course.getCourseId()));
        assertFalse(repo.deleteCourse(UID, course.getCourseId()));
        assertNull(repo.getTimetable(UID).findCourseById(course.getCourseId()));
    }

    @Test
    public void updateFreeSlots_replacesAllAndCopiesInput() {
        java.util.List<TimeSlot> input = new java.util.ArrayList<>(
                Collections.singletonList(new TimeSlot(5, 840, 900)));
        repo.updateFreeSlots(UID, input);
        input.add(new TimeSlot(1, 540, 570)); // 호출 후 원본 변경이 저장소에 새지 않아야 함

        assertEquals(Arrays.asList(new TimeSlot(5, 840, 900)), repo.getTimetable(UID).getFreeSlots());
    }
}
