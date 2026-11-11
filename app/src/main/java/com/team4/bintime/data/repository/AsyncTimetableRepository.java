package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
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

    /** 현재 로그인 사용자의 시간표. 등록 직후 현재 값을 한 번 전달하고, 바뀔 때마다 다시 전달한다. 미등록이면 onData(null). */
    Registration observeTimetable(Listener<Timetable> listener);

    /** 수업 추가. 같은 요일에 겹치면 OVERLAP, 같은 courseId가 이미 있으면 INVALID_COURSE. 성공 시 저장된 사본을 전달한다. */
    void addCourse(Course course, Callback<Course> callback);

    /** 수업 수정. 없으면 NOT_FOUND. 자기 자신과의 겹침은 검사하지 않는다. */
    void updateCourse(Course course, Callback<Course> callback);

    /** 수업 삭제. 이미 없으면 성공으로 처리한다(멱등). */
    void deleteCourse(String courseId, Callback<Void> callback);

    /** 전체 수업을 한 번에 교체한다(이미지 인식 검수 결과 등). 기존 saveTimetable에 대응. */
    void replaceCourses(List<Course> courses, Callback<Void> callback);
}
