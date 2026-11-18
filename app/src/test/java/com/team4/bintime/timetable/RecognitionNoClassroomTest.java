package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.RecognitionReview;
import com.team4.bintime.data.recognition.RecognizedCourse;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.CourseValidator;
import com.team4.bintime.data.repository.MockAsyncTimetableRepository;
import com.team4.bintime.data.repository.MockTimetableRepositoryImpl;
import com.team4.bintime.data.repository.TimetableDocuments;
import com.team4.bintime.data.repository.TimetableFailure;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [BT-026 확인] building·room 이 모두 null 인 인식 수업이 검수 → 등록 → replaceCourses 까지 통과하는지.
 * 수정 화면(CourseFields.validated)은 거치지 않는 경로다(수정하지 않고 확인한 경우).
 */
public class RecognitionNoClassroomTest {

    private static TimetableDraft draftWithoutClassroom() {
        return RecognitionDrafts.toDraft(new RecognitionResult(Arrays.asList(
                new RecognizedCourse("확률과 통계", 4, 540, 630, null, null, 0.52, 0),
                new RecognizedCourse("자료구조", 3, 780, 870, "미래관", "407호", 0.88, 0)),
                Collections.emptyList(), 0, 1));
    }

    @Test
    public void review_noClassroomIsInfoOnly_confirmAllowed() {
        TimetableDraft draft = draftWithoutClassroom();
        int row = indexOf(draft, "확률과 통계");
        assertNull(draft.courses.get(row).getClassroom());
        List<Set<RecognitionReview.Flag>> flags = RecognitionDrafts.check(draft);
        assertTrue(flags.get(row).contains(RecognitionReview.Flag.NO_CLASSROOM));
        assertTrue(RecognitionReview.canConfirm(flags));
    }

    @Test
    public void mapperAndValidator_allowNullClassroom() {
        List<com.team4.bintime.data.model.Course> courses = TimetableDraftMapper.toCourses(draftWithoutClassroom());
        com.team4.bintime.data.model.Course course = find(courses, "확률과 통계");
        assertNull(course.getClassroom());
        assertNull(CourseValidator.validateAll(courses));
        // 빈 문자열도 sanitize 가 null 로 바꿔 허용한다.
        course.setClassroom("  ");
        assertNull(CourseValidator.sanitize(course).getClassroom());
    }

    @Test
    public void firestoreDocument_omitsClassroom_andReadsBackNull() {
        // Rules: optionalText(d, 'classroom', 30) — 키가 없거나 null 이면 허용(firestore.rules validCourse).
        com.team4.bintime.data.model.Course course =
                find(TimetableDraftMapper.toCourses(draftWithoutClassroom()), "확률과 통계");
        Map<String, Object> doc = TimetableDocuments.toCourseDocument(course);
        assertFalse(doc.containsKey("classroom"));
        assertNull(TimetableDocuments.fromCourseDocument(course.getCourseId(), doc).getClassroom());
    }

    @Test
    public void mockReplaceCourses_savesNullClassroom() {
        MockTimetableRepositoryImpl.getInstance().resetToMockData();
        MockAsyncTimetableRepository repo = new MockAsyncTimetableRepository(
                MockTimetableRepositoryImpl.getInstance(), () -> "uid-no-classroom");
        TimetableFailure[] error = {null};
        repo.replaceCourses(TimetableDraftMapper.toCourses(draftWithoutClassroom()),
                new AsyncTimetableRepository.Callback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                    }

                    @Override
                    public void onError(TimetableFailure failure) {
                        error[0] = failure;
                    }
                });
        assertNull(error[0]);
        Timetable[] saved = {null};
        TimetableLoader.loadOnce(repo, new AsyncTimetableRepository.Callback<Timetable>() {
            @Override
            public void onSuccess(Timetable result) {
                saved[0] = result;
            }

            @Override
            public void onError(TimetableFailure failure) {
                error[0] = failure;
            }
        });
        assertEquals(2, saved[0].getCourseCount());
        assertNull(find(saved[0].getCourses(), "확률과 통계").getClassroom());
    }

    @Test
    public void editScreenValueDiffersFromNullOriginal() {
        // 수정 화면은 null 강의실을 "" 로 보여 주므로, 아무것도 바꾸지 않아도 원본(null)과 다르다고 본다.
        TimetableDraft draft = draftWithoutClassroom();
        Course original = draft.courses.get(indexOf(draft, "확률과 통계"));
        Course shown = new Course(original.getName(), "", original.getDay(), original.getStartTime(),
                original.getEndTime());
        assertFalse(original.equals(shown));
    }

    private static int indexOf(TimetableDraft draft, String name) {
        for (int i = 0; i < draft.courses.size(); i++) if (name.equals(draft.courses.get(i).getName())) return i;
        throw new AssertionError(name);
    }

    private static com.team4.bintime.data.model.Course find(List<com.team4.bintime.data.model.Course> courses,
                                                            String name) {
        for (com.team4.bintime.data.model.Course c : courses) if (name.equals(c.getName())) return c;
        throw new AssertionError(name);
    }
}
