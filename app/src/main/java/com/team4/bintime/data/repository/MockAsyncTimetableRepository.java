package com.team4.bintime.data.repository;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * [BT-015] 동기 Mock 저장소({@link TimetableRepository})를 비동기 계약으로 감싼 어댑터.
 * <p>
 * google-services.json이 없는 환경(CI·설정 전 팀원)용이며 데이터는 메모리에만 있다(앱 재실행 시 사라짐).
 * 콜백은 호출한 스레드에서 즉시 부르며, 메인 스레드에서만 호출한다고 가정한다(스레드 안전하지 않음).
 * 검증은 Firestore 구현과 같은 {@link CourseValidator}를 쓴다.
 * <p>
 * UID는 생성자로 받은 {@code uidSupplier}에서 매 호출마다 얻는다. 앱에서는 PR ②의 선택 클래스가
 * {@code AuthRepositories.get(context)::getCurrentUid}를 넘긴다.
 */
public final class MockAsyncTimetableRepository implements AsyncTimetableRepository {

    private final TimetableRepository delegate;
    private final Supplier<String> uidSupplier;
    private final List<Subscription> subscriptions = new ArrayList<>();

    public MockAsyncTimetableRepository(TimetableRepository delegate, Supplier<String> uidSupplier) {
        if (delegate == null || uidSupplier == null) throw new IllegalArgumentException("null argument");
        this.delegate = delegate;
        this.uidSupplier = uidSupplier;
    }

    @Override
    public Registration observeTimetable(Listener<Timetable> listener) {
        if (listener == null) throw new IllegalArgumentException("listener is null");
        String uid = uidSupplier.get();
        if (uid == null) {
            listener.onError(notSignedIn());
            return () -> { };
        }
        Subscription subscription = new Subscription(uid, listener);
        subscriptions.add(subscription);
        listener.onData(snapshot(uid));
        return () -> subscriptions.remove(subscription);
    }

    @Override
    public void addCourse(Course course, Callback<Course> callback) {
        String uid = uidSupplier.get();
        if (uid == null) {
            fail(callback, notSignedIn());
            return;
        }
        Course candidate = CourseValidator.sanitize(course);
        List<Course> existing = currentCourses(uid);
        TimetableFailure failure = CourseValidator.validateFields(candidate);
        if (failure == null && find(existing, candidate.getCourseId()) != null) {
            failure = TimetableFailure.of(TimetableFailure.Code.INVALID_COURSE,
                    "courseId already exists: " + candidate.getCourseId());
        }
        if (failure == null) failure = CourseValidator.validate(candidate, existing);
        if (failure != null) {
            fail(callback, failure);
            return;
        }
        delegate.saveCourse(uid, candidate);
        notifyListeners(uid);
        if (callback != null) callback.onSuccess(copy(candidate));
    }

    @Override
    public void updateCourse(Course course, Callback<Course> callback) {
        String uid = uidSupplier.get();
        if (uid == null) {
            fail(callback, notSignedIn());
            return;
        }
        Course candidate = CourseValidator.sanitize(course);
        List<Course> existing = currentCourses(uid);
        TimetableFailure failure = CourseValidator.validateFields(candidate);
        if (failure == null && find(existing, candidate.getCourseId()) == null) {
            failure = TimetableFailure.of(TimetableFailure.Code.NOT_FOUND,
                    "no course " + candidate.getCourseId());
        }
        if (failure == null) failure = CourseValidator.validate(candidate, existing);
        if (failure != null) {
            fail(callback, failure);
            return;
        }
        delegate.saveCourse(uid, candidate);
        notifyListeners(uid);
        if (callback != null) callback.onSuccess(copy(candidate));
    }

    @Override
    public void deleteCourse(String courseId, Callback<Void> callback) {
        String uid = uidSupplier.get();
        if (uid == null) {
            fail(callback, notSignedIn());
            return;
        }
        if (courseId == null || courseId.trim().isEmpty()) {
            fail(callback, TimetableFailure.of(TimetableFailure.Code.INVALID_COURSE, "courseId is empty"));
            return;
        }
        if (delegate.deleteCourse(uid, courseId)) notifyListeners(uid);
        if (callback != null) callback.onSuccess(null);
    }

    @Override
    public void replaceCourses(List<Course> courses, Callback<Void> callback) {
        String uid = uidSupplier.get();
        if (uid == null) {
            fail(callback, notSignedIn());
            return;
        }
        List<Course> candidates = new ArrayList<>();
        if (courses != null) {
            for (Course c : courses) candidates.add(CourseValidator.sanitize(c));
        }
        TimetableFailure failure = CourseValidator.validateAll(courses == null ? null : candidates);
        if (failure != null) {
            fail(callback, failure);
            return;
        }
        Timetable current = delegate.getTimetable(uid);
        Timetable next;
        if (current == null) {
            next = new Timetable(uid, uid, null);
        } else {
            next = copy(current);
            next.incrementRevision();
        }
        next.setCourses(candidates);
        delegate.saveTimetable(uid, next);
        notifyListeners(uid);
        if (callback != null) callback.onSuccess(null);
    }

    // ── 내부 ───────────────────────────────────────────

    private List<Course> currentCourses(String uid) {
        Timetable t = delegate.getTimetable(uid);
        return t == null || t.getCourses() == null ? new ArrayList<>() : t.getCourses();
    }

    private static Course find(List<Course> courses, String courseId) {
        for (Course c : courses) {
            if (c != null && courseId != null && courseId.equals(c.getCourseId())) return c;
        }
        return null;
    }

    private void notifyListeners(String uid) {
        for (Subscription s : new ArrayList<>(subscriptions)) {
            if (s.uid.equals(uid) && subscriptions.contains(s)) s.listener.onData(snapshot(uid));
        }
    }

    /** 리스너가 저장소 내부 객체를 바꾸지 못하도록 깊은 사본을 전달한다. */
    private Timetable snapshot(String uid) {
        Timetable t = delegate.getTimetable(uid);
        return t == null ? null : copy(t);
    }

    private static Timetable copy(Timetable t) {
        Timetable c = new Timetable(t.getTimetableId(), t.getUserId(), t.getSemester());
        c.setRevision(t.getRevision());
        List<Course> courses = new ArrayList<>();
        if (t.getCourses() != null) {
            for (Course course : t.getCourses()) courses.add(copy(course));
        }
        c.setCourses(courses);
        c.setFreeSlots(copySlots(t.getFreeSlots()));
        return c;
    }

    private static Course copy(Course course) {
        return new Course(course.getCourseId(), course.getName(), course.getProfessor(),
                course.getClassroom(), course.getColorCode(), copySlots(course.getTimeSlots()));
    }

    private static List<TimeSlot> copySlots(List<TimeSlot> slots) {
        List<TimeSlot> result = new ArrayList<>();
        if (slots == null) return result;
        for (TimeSlot s : slots) {
            if (s != null) result.add(new TimeSlot(s.getDayOfWeek(), s.getStartMinute(), s.getEndMinute()));
        }
        return result;
    }

    private static TimetableFailure notSignedIn() {
        return TimetableFailure.of(TimetableFailure.Code.NOT_SIGNED_IN, "no current uid");
    }

    private static <T> void fail(Callback<T> callback, TimetableFailure failure) {
        if (callback != null) callback.onError(failure);
    }

    private static final class Subscription {
        final String uid;
        final Listener<Timetable> listener;

        Subscription(String uid, Listener<Timetable> listener) {
            this.uid = uid;
            this.listener = listener;
        }
    }
}
