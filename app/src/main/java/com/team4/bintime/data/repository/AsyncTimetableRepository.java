package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import java.util.List;

/**
 * [BT-015] 시간표 저장소 비동기 계약 (콜백 + Registration).
 * <p>
 * 기존 동기 계약 {@link TimetableRepository}는 그대로 두고 옆에 둔다. Firestore 구현은 동기 조회를
 * 메인 스레드에서 할 수 없으므로 상속하지 않는다.
 * <ul>
 *   <li>UID는 인자로 받지 않는다. 구현체가 저장소 안에서 현재 로그인 UID를 얻는다
 *       (Firestore 구현은 {@code AuthRepositories.get(context).getCurrentUid()}, PR ②).</li>
 *   <li>Firestore 구현의 콜백은 메인 스레드, {@link MockAsyncTimetableRepository}는 호출한 스레드에서
 *       즉시 부른다.</li>
 *   <li>리스너는 등록 시점의 UID에 묶인다. 로그아웃·계정 전환 시 화면이 {@link Registration#remove()} 후
 *       다시 구독한다.</li>
 * </ul>
 * 구현체: {@link MockAsyncTimetableRepository} (PR ①), FirestoreTimetableRepository (PR ②).
 */
public interface AsyncTimetableRepository {

    interface Registration {
        /** 구독 해제. 여러 번 불러도 안전하다. */
        void remove();
    }

    interface Listener<T> {
        void onData(T data);

        void onError(TimetableFailure failure);
    }

    interface Callback<T> {
        void onSuccess(T result);

        void onError(TimetableFailure failure);
    }

    /**
     * 현재 로그인 사용자의 시간표. 등록 직후 현재 값을 한 번 전달하고, 바뀔 때마다 다시 전달한다. 미등록이면 onData(null).
     * [BT-016] {@link Timetable#getFreeSlots()}는 사용자가 고른 공강 선택 원본(30분 칸을 이어 붙인 구간,
     * 60분 미만 포함)이다. 화면 재진입 때 이 값으로 선택을 복원한다.
     */
    Registration observeTimetable(Listener<Timetable> listener);

    /** 수업 추가. 같은 요일에 겹치면 OVERLAP, 같은 courseId가 이미 있으면 INVALID_COURSE. 성공 시 저장된 사본을 전달한다. */
    void addCourse(Course course, Callback<Course> callback);

    /** 수업 수정. 없으면 NOT_FOUND. 자기 자신과의 겹침은 검사하지 않는다. */
    void updateCourse(Course course, Callback<Course> callback);

    /** 수업 삭제. 이미 없으면 성공으로 처리한다(멱등). */
    void deleteCourse(String courseId, Callback<Void> callback);

    /**
     * 전체 수업을 한 번에 교체한다(이미지 인식 검수 결과 등). 기존 saveTimetable에 대응.
     * [BT-016] 같은 쓰기에서 새 수업과 겹치는 공강 선택 칸을 지우고, 남은 선택으로 매칭 대상 공강
     * ({@code availability/{uid}}, sourceRevision = 새 revision)을 다시 저장한다.
     * 남은 선택의 매칭 대상 구간이 상한을 넘으면 TOO_MANY_FREE_SLOTS.
     */
    void replaceCourses(List<Course> courses, Callback<Void> callback);

    /**
     * [BT-016] 공강 선택 원본을 저장하고 매칭 대상 공강({@code availability/{uid}})을 함께 갱신한다.
     * <ul>
     *   <li>selected는 30분 칸 경계 구간이며 수업과 겹치면 INVALID_FREE_SLOT.</li>
     *   <li>availability.slots = matchableFreeSlots(selected) (병합 + 60분 이상), 40개 초과면
     *       TOO_MANY_FREE_SLOTS로 저장하지 않는다.</li>
     *   <li>시간표 revision은 바꾸지 않는다. sourceRevision = 현재 revision.</li>
     * </ul>
     */
    void saveFreeSlots(List<TimeSlot> selected, Callback<Void> callback);

    /**
     * [BT-026] 저장된 수업 수를 **서버에서만** 읽는다(기기 캐시 사용 안 함). 새 시간표로 교체하기 전에
     * "기존 수업 N개가 지워진다"를 확인받는 데 쓴다. 캐시의 0개를 믿고 확인 없이 교체하지 않도록,
     * 오프라인·권한 거부 등으로 서버 값을 얻지 못하면 onError 로 끝낸다(호출한 화면이 수를 뺀 확인을 띄운다).
     */
    void countCoursesFromServer(Callback<Integer> callback);
}
