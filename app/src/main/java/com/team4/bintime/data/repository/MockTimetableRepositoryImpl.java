package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 인메모리 시간표 Repository 구현체 (Sprint 1 목업).
 * <p>
 * 앱 프로세스 생존 기간 동안만 데이터를 유지합니다.
 * Sprint 2에서 Firestore 연동 구현체로 교체 예정입니다.
 * <p>
 * Mock 임을 명시하며, 더미 결과를 실제 성공으로 보고하지 않습니다.
 *
 * @see TimetableRepository
 */
public class MockTimetableRepositoryImpl implements TimetableRepository {

    /** 싱글턴 인스턴스. */
    private static MockTimetableRepositoryImpl instance;

    /** userId → Timetable 매핑. */
    private final Map<String, Timetable> storage = new HashMap<>();

    /** 기본 Mock 사용자 ID. 실제 Auth 연동 전까지 사용. */
    public static final String MOCK_USER_ID = "mock_user_001";

    private MockTimetableRepositoryImpl() {
        // 기본 목업 데이터 초기화
        initMockData();
    }

    /**
     * 싱글턴 인스턴스 반환.
     */
    public static synchronized MockTimetableRepositoryImpl getInstance() {
        if (instance == null) {
            instance = new MockTimetableRepositoryImpl();
        }
        return instance;
    }

    /**
     * 피그마 디자인 및 BT-005/006 목업 기준 초기 데이터.
     * 기존 TimetableRegistrationActivity의 하드코딩 데이터와 동일한 수업 구성.
     */
    private void initMockData() {
        Timetable timetable = new Timetable("tt_mock_001", MOCK_USER_ID, "2026년 2학기");

        // 1. 컴퓨터구조론 — 월 10:00~11:00
        Course c1 = Course.createSingle("컴퓨터구조론", "공학관 302호", "월", "10:00", "11:00");
        c1.setColorCode("#FFE0E0");

        // 2. 소프트웨어공학 — 화 10:00~11:00
        Course c2 = Course.createSingle("소프트웨어공학", "인문관 102호", "화", "10:00", "11:00");
        c2.setColorCode("#E0F0FF");

        // 3. 운영체제 — 수 09:00~10:00
        Course c3 = Course.createSingle("운영체제", "공학관 301호", "수", "09:00", "10:00");
        c3.setColorCode("#E0FFE0");

        // 4. 인공지능 — 금 10:00~12:00 (2시간 연속)
        Course c4 = Course.createSingle("인공지능", "사회관 201호", "금", "10:00", "12:00");
        c4.setColorCode("#FFF5CC");

        // 5. 데이터베이스 — 화 13:00~14:30
        Course c5 = Course.createSingle("데이터베이스", "공학관 304호", "화", "13:00", "14:30");
        c5.setColorCode("#F0E0FF");

        // 6. 알고리즘 — 목 13:00~14:30
        Course c6 = Course.createSingle("알고리즘", "공학관 303호", "목", "13:00", "14:30");
        c6.setColorCode("#FFE8CC");

        // 7. 프로그래밍기초 — 수 13:00~14:30
        Course c7 = Course.createSingle("프로그래밍기초", "자연관 101호", "수", "13:00", "14:30");
        c7.setColorCode("#E0FFF0");

        timetable.getCourses().addAll(Arrays.asList(c1, c2, c3, c4, c5, c6, c7));

        // 기본 공강 설정 (수 11:00~12:00 연속 공강, 화 11:00~12:00, 목 11:00~12:00)
        timetable.getFreeSlots().addAll(Arrays.asList(
                new TimeSlot(3, 660, 720),   // 수 11:00~12:00
                new TimeSlot(2, 660, 720),   // 화 11:00~12:00
                new TimeSlot(4, 660, 720),   // 목 11:00~12:00
                new TimeSlot(4, 720, 780)    // 목 12:00~13:00
        ));

        storage.put(MOCK_USER_ID, timetable);
    }

    // ── TimetableRepository 구현 ────────────────────────

    @Override
    public Timetable getTimetable(String userId) {
        return storage.get(userId);
    }

    @Override
    public void saveCourse(String userId, Course course) {
        Timetable timetable = getOrCreateTimetable(userId);
        timetable.addOrUpdateCourse(course);
    }

    @Override
    public boolean deleteCourse(String userId, String courseId) {
        Timetable timetable = storage.get(userId);
        if (timetable == null) return false;
        return timetable.removeCourse(courseId);
    }

    @Override
    public void updateFreeSlots(String userId, List<TimeSlot> freeSlots) {
        Timetable timetable = getOrCreateTimetable(userId);
        timetable.setFreeSlots(freeSlots != null ? new ArrayList<>(freeSlots) : new ArrayList<>());
        timetable.incrementRevision();
    }

    @Override
    public void saveTimetable(String userId, Timetable timetable) {
        if (timetable == null) return;
        timetable.setUserId(userId);
        storage.put(userId, timetable);
    }

    // ── 유틸리티 ─────────────────────────────────────

    private Timetable getOrCreateTimetable(String userId) {
        Timetable timetable = storage.get(userId);
        if (timetable == null) {
            timetable = new Timetable("tt_" + userId, userId, "2026년 2학기");
            storage.put(userId, timetable);
        }
        return timetable;
    }

    /**
     * 테스트/디버그용: 전체 저장소 초기화 후 목업 데이터 재생성.
     */
    public void resetToMockData() {
        storage.clear();
        initMockData();
    }
}
