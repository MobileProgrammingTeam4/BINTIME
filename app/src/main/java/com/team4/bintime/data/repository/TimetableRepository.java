package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import java.util.List;

/**
 * 시간표 데이터 접근 계약 (Repository 인터페이스).
 * <p>
 * BT-012: Activity → ViewModel/Controller → Repository → DataSource 계층 분리를 위한
 * 단일 진실 출처 인터페이스.
 * <p>
 * 현재 구현체:
 * <ul>
 *   <li>{@link MockTimetableRepositoryImpl} — 인메모리 목업 (Sprint 1)</li>
 *   <li>(Sprint 2+) FirestoreTimetableRepository — Firestore 연동</li>
 * </ul>
 *
 * @see Course
 * @see TimeSlot
 * @see Timetable
 */
public interface TimetableRepository {

    /**
     * 사용자의 시간표를 조회합니다.
     *
     * @param userId 사용자 UID
     * @return Timetable 또는 null (시간표 미등록 시)
     */
    Timetable getTimetable(String userId);

    /**
     * 과목을 저장(추가/수정)합니다.
     * 동일 courseId가 존재하면 교체합니다.
     * revision이 자동으로 증가합니다.
     *
     * @param userId 사용자 UID
     * @param course 저장할 과목
     */
    void saveCourse(String userId, Course course);

    /**
     * 과목을 삭제합니다.
     * revision이 자동으로 증가합니다.
     *
     * @param userId   사용자 UID
     * @param courseId 삭제할 과목 ID
     * @return 삭제 성공 여부
     */
    boolean deleteCourse(String userId, String courseId);

    /**
     * 사용자가 선택한 공강 슬롯을 저장/갱신합니다.
     * 기존 공강 슬롯을 완전히 교체합니다.
     *
     * @param userId    사용자 UID
     * @param freeSlots 사용자가 선택한 공강 슬롯 목록
     */
    void updateFreeSlots(String userId, List<TimeSlot> freeSlots);

    /**
     * 전체 시간표(과목 포함)를 저장합니다.
     * 이미지 인식 결과 일괄 저장 등에 사용합니다.
     *
     * @param userId    사용자 UID
     * @param timetable 저장할 시간표
     */
    void saveTimetable(String userId, Timetable timetable);
}
