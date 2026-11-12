package com.team4.bintime.data.repository;

import android.content.Context;
import android.util.Log;

import com.google.firebase.firestore.CollectionReference;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.Transaction;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.Timetable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [BT-015] Firestore 시간표 저장소.
 * <pre>
 * timetables/{uid}                    uid, semester?, revision, updatedAt
 * timetables/{uid}/courses/{courseId} TimetableDocuments 참고
 * </pre>
 * <ul>
 *   <li>UID는 매 호출마다 {@code AuthRepositories.get(context).getCurrentUid()}로 얻는다.</li>
 *   <li>동시성: 쓰기 전에 부모 revision과 수업 목록을 읽어 {@link CourseValidator}로 검증하고, 트랜잭션에서
 *       부모 revision이 그대로일 때만 수업과 revision+1을 함께 쓴다. 바뀌었으면 {@code CONFLICT}.
 *       규칙은 수업 문서의 소유자·필드만 검사하므로 이 보호는 클라이언트 트랜잭션이 맡는다.</li>
 *   <li>트랜잭션은 오프라인에서 실패한다({@code NETWORK}). 콜백은 메인 스레드에서 부른다.</li>
 * </ul>
 */
public final class FirestoreTimetableRepository implements AsyncTimetableRepository {

    private static final String TAG = "FirestoreTimetable";
    private static FirestoreTimetableRepository instance;

    public static synchronized FirestoreTimetableRepository get(Context context) {
        if (instance == null) instance = new FirestoreTimetableRepository(context.getApplicationContext());
        return instance;
    }

    private final Context appContext;
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();

    private FirestoreTimetableRepository(Context appContext) {
        this.appContext = appContext;
    }

    // ── 조회 ───────────────────────────────────────────

    @Override
    public Registration observeTimetable(Listener<Timetable> listener) {
        if (listener == null) throw new IllegalArgumentException("listener is null");
        String uid = currentUid();
        if (uid == null) {
            listener.onError(notSignedIn());
            return () -> { };
        }
        return new TimetableObserver(uid, listener);
    }

    /** 부모 문서와 courses 컬렉션 리스너 두 개를 묶는다. 둘 다 첫 값을 받은 뒤부터 전달한다. */
    private final class TimetableObserver implements Registration {
        private final String uid;
        private final Listener<Timetable> listener;
        private final ListenerRegistration parentRegistration;
        private final ListenerRegistration coursesRegistration;
        private Map<String, Object> parent;
        private List<Course> courses;
        private boolean parentLoaded;
        private boolean coursesLoaded;
        private boolean closed;

        TimetableObserver(String uid, Listener<Timetable> listener) {
            this.uid = uid;
            this.listener = listener;
            parentRegistration = parentRef(uid).addSnapshotListener((snapshot, error) -> {
                if (closed) return;
                if (error != null) {
                    fail(error);
                    return;
                }
                parent = snapshot != null && snapshot.exists() ? snapshot.getData() : null;
                parentLoaded = true;
                emit();
            });
            coursesRegistration = coursesRef(uid).addSnapshotListener((snapshot, error) -> {
                if (closed) return;
                if (error != null) {
                    fail(error);
                    return;
                }
                courses = readCourses(snapshot);
                coursesLoaded = true;
                emit();
            });
        }

        private void emit() {
            if (closed || !parentLoaded || !coursesLoaded) return;
            listener.onData(TimetableDocuments.assemble(uid, parent, courses));
        }

        private void fail(Exception error) {
            // Firestore는 오류 뒤 리스너를 멈추므로 한 번만 알리고 둘 다 해제한다.
            remove();
            listener.onError(FirestoreFailures.from(error));
        }

        @Override
        public void remove() {
            if (closed) return;
            closed = true;
            parentRegistration.remove();
            coursesRegistration.remove();
        }
    }

    // ── 쓰기 ───────────────────────────────────────────

    @Override
    public void addCourse(Course course, Callback<Course> callback) {
        writeCourse(course, true, callback);
    }

    @Override
    public void updateCourse(Course course, Callback<Course> callback) {
        writeCourse(course, false, callback);
    }

    private void writeCourse(Course course, boolean adding, Callback<Course> callback) {
        String uid = currentUid();
        if (uid == null) {
            fail(callback, notSignedIn());
            return;
        }
        Course candidate = CourseValidator.sanitize(course);
        TimetableFailure invalid = CourseValidator.validateFields(candidate);
        if (invalid != null) {
            fail(callback, invalid);
            return;
        }
        loadState(uid, (state, error) -> {
            if (error != null) {
                fail(callback, error);
                return;
            }
            boolean exists = state.courseIds.contains(candidate.getCourseId());
            if (adding && exists) {
                fail(callback, TimetableFailure.of(TimetableFailure.Code.INVALID_COURSE,
                        "courseId already exists: " + candidate.getCourseId()));
                return;
            }
            if (!adding && !exists) {
                fail(callback, TimetableFailure.of(TimetableFailure.Code.NOT_FOUND,
                        "no course " + candidate.getCourseId()));
                return;
            }
            TimetableFailure overlap = CourseValidator.validate(candidate, state.courses);
            if (overlap != null) {
                fail(callback, overlap);
                return;
            }
            DocumentReference parentRef = parentRef(uid);
            DocumentReference courseRef = coursesRef(uid).document(candidate.getCourseId());
            db.runTransaction((Transaction.Function<Void>) tx -> {
                DocumentSnapshot parent = tx.get(parentRef);
                DocumentSnapshot existing = tx.get(courseRef);
                int revision = requireRevision(parent, state.revision);
                if (adding && existing.exists()) {
                    throw new FirestoreFailures.Abort(TimetableFailure.of(TimetableFailure.Code.CONFLICT,
                            "course created concurrently"));
                }
                if (!adding && !existing.exists()) {
                    throw new FirestoreFailures.Abort(TimetableFailure.of(TimetableFailure.Code.CONFLICT,
                            "course deleted concurrently"));
                }
                putCourse(tx, courseRef, candidate, existing.exists());
                putParent(tx, uid, parentRef, revision + 1);
                return null;
            }).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    if (callback != null) callback.onSuccess(candidate);
                } else {
                    fail(callback, FirestoreFailures.from(task.getException()));
                }
            });
        });
    }

    @Override
    public void deleteCourse(String courseId, Callback<Void> callback) {
        String uid = currentUid();
        if (uid == null) {
            fail(callback, notSignedIn());
            return;
        }
        if (!CourseValidator.isValidDocumentId(courseId)) {
            fail(callback, TimetableFailure.of(TimetableFailure.Code.INVALID_COURSE, "invalid courseId"));
            return;
        }
        DocumentReference parentRef = parentRef(uid);
        DocumentReference courseRef = coursesRef(uid).document(courseId);
        // 삭제는 다른 수업과의 겹침과 무관하므로 미리 읽은 revision과 비교하지 않는다.
        db.runTransaction((Transaction.Function<Void>) tx -> {
            DocumentSnapshot parent = tx.get(parentRef);
            DocumentSnapshot existing = tx.get(courseRef);
            if (!existing.exists()) return null; // 멱등
            tx.delete(courseRef);
            putParent(tx, uid, parentRef, revisionOf(parent) + 1);
            return null;
        }).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                if (callback != null) callback.onSuccess(null);
            } else {
                fail(callback, FirestoreFailures.from(task.getException()));
            }
        });
    }

    @Override
    public void replaceCourses(List<Course> courses, Callback<Void> callback) {
        String uid = currentUid();
        if (uid == null) {
            fail(callback, notSignedIn());
            return;
        }
        List<Course> candidates = new ArrayList<>();
        if (courses != null) {
            for (Course c : courses) candidates.add(CourseValidator.sanitize(c));
        }
        TimetableFailure invalid = CourseValidator.validateAll(courses == null ? null : candidates);
        if (invalid != null) {
            fail(callback, invalid);
            return;
        }
        loadState(uid, (state, error) -> {
            if (error != null) {
                fail(callback, error);
                return;
            }
            DocumentReference parentRef = parentRef(uid);
            Set<String> keep = new HashSet<>();
            for (Course c : candidates) keep.add(c.getCourseId());
            db.runTransaction((Transaction.Function<Void>) tx -> {
                DocumentSnapshot parent = tx.get(parentRef);
                int revision = requireRevision(parent, state.revision);
                for (String id : state.courseIds) {
                    if (!keep.contains(id)) tx.delete(coursesRef(uid).document(id));
                }
                for (Course c : candidates) {
                    putCourse(tx, coursesRef(uid).document(c.getCourseId()), c,
                            state.courseIds.contains(c.getCourseId()));
                }
                putParent(tx, uid, parentRef, revision + 1);
                return null;
            }).addOnCompleteListener(task -> {
                if (task.isSuccessful()) {
                    if (callback != null) callback.onSuccess(null);
                } else {
                    fail(callback, FirestoreFailures.from(task.getException()));
                }
            });
        });
    }

    // ── 내부 ───────────────────────────────────────────

    /** 쓰기 전 읽은 상태. revision은 부모 문서를 먼저 읽은 값(없으면 0). */
    private static final class State {
        final int revision;
        final List<Course> courses;
        final Set<String> courseIds;

        State(int revision, List<Course> courses, Set<String> courseIds) {
            this.revision = revision;
            this.courses = courses;
            this.courseIds = courseIds;
        }
    }

    private interface StateCallback {
        void onResult(State state, TimetableFailure error);
    }

    /**
     * 부모 문서를 먼저 읽고 수업 목록을 읽는다. 그 사이 다른 쓰기가 있으면 트랜잭션에서 revision이 달라
     * CONFLICT가 되므로, 수업 목록이 revision보다 오래될 수는 없다.
     */
    private void loadState(String uid, StateCallback callback) {
        parentRef(uid).get().addOnCompleteListener(parentTask -> {
            if (!parentTask.isSuccessful()) {
                callback.onResult(null, FirestoreFailures.from(parentTask.getException()));
                return;
            }
            int revision = revisionOf(parentTask.getResult());
            coursesRef(uid).get().addOnCompleteListener(coursesTask -> {
                if (!coursesTask.isSuccessful()) {
                    callback.onResult(null, FirestoreFailures.from(coursesTask.getException()));
                    return;
                }
                Set<String> ids = new HashSet<>();
                for (QueryDocumentSnapshot doc : coursesTask.getResult()) ids.add(doc.getId());
                callback.onResult(new State(revision, readCourses(coursesTask.getResult()), ids), null);
            });
        });
    }

    /** 트랜잭션에서 읽은 revision이 미리 읽은 값과 다르면 CONFLICT로 끝낸다. */
    private static int requireRevision(DocumentSnapshot parent, int expected) {
        int revision = revisionOf(parent);
        if (revision != expected) {
            throw new FirestoreFailures.Abort(TimetableFailure.of(TimetableFailure.Code.CONFLICT,
                    "revision changed " + expected + " -> " + revision));
        }
        return revision;
    }

    private static int revisionOf(DocumentSnapshot parent) {
        if (parent == null || !parent.exists()) return 0;
        Integer revision = TimetableDocuments.readInt(parent.get(TimetableDocuments.F_REVISION));
        return revision == null ? 0 : revision;
    }

    /** 부모 문서: uid·revision·updatedAt만 덮어쓰고 semester 등 나머지는 유지(merge). */
    private static void putParent(Transaction tx, String uid, DocumentReference parentRef, int revision) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put(TimetableDocuments.F_UID, uid);
        doc.put(TimetableDocuments.F_REVISION, revision);
        doc.put(TimetableDocuments.F_UPDATED_AT, FieldValue.serverTimestamp());
        tx.set(parentRef, doc, SetOptions.merge());
    }

    /**
     * 수업 문서. 생성은 createdAt을 서버 시간으로 넣고, 수정은 createdAt을 보내지 않아 기존 값을 유지한다
     * (규칙: 생성 시 request.time, 수정 시 기존 값과 같아야 함). 수정 때 비운 선택 필드는 지운다.
     */
    private static void putCourse(Transaction tx, DocumentReference ref, Course course, boolean exists) {
        Map<String, Object> doc = TimetableDocuments.toCourseDocument(course);
        doc.put(TimetableDocuments.F_UPDATED_AT, FieldValue.serverTimestamp());
        if (!exists) {
            doc.put(TimetableDocuments.F_CREATED_AT, FieldValue.serverTimestamp());
            tx.set(ref, doc);
            return;
        }
        for (String optional : new String[]{TimetableDocuments.F_PROFESSOR,
                TimetableDocuments.F_CLASSROOM, TimetableDocuments.F_COLOR_CODE}) {
            if (!doc.containsKey(optional)) doc.put(optional, FieldValue.delete());
        }
        tx.set(ref, doc, SetOptions.merge());
    }

    private static List<Course> readCourses(QuerySnapshot snapshot) {
        List<Course> courses = new ArrayList<>();
        if (snapshot == null) return courses;
        for (QueryDocumentSnapshot doc : snapshot) {
            Course course = TimetableDocuments.fromCourseDocument(doc.getId(), doc.getData());
            if (course == null) {
                Log.w(TAG, "skip malformed course document " + doc.getId());
            } else {
                courses.add(course);
            }
        }
        return courses;
    }

    private DocumentReference parentRef(String uid) {
        return db.collection(TimetableDocuments.TIMETABLES).document(uid);
    }

    private CollectionReference coursesRef(String uid) {
        return parentRef(uid).collection(TimetableDocuments.COURSES);
    }

    private String currentUid() {
        return AuthRepositories.get(appContext).getCurrentUid();
    }

    private static TimetableFailure notSignedIn() {
        return TimetableFailure.of(TimetableFailure.Code.NOT_SIGNED_IN, "no current uid");
    }

    private static <T> void fail(Callback<T> callback, TimetableFailure failure) {
        if (callback != null) callback.onError(failure);
    }
}
