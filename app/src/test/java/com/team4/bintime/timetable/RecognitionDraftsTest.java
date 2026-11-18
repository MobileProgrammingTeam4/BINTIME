package com.team4.bintime.timetable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.R;
import com.team4.bintime.data.recognition.RecognitionFailure;
import com.team4.bintime.data.recognition.RecognitionParser;
import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.RecognitionReview;
import com.team4.bintime.data.recognition.RecognizedCourse;
import com.team4.bintime.data.repository.CourseValidator;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [BT-026] 인식 결과 → 검수 draft → 저장 모델. 변환 뒤 CourseValidator.validateAll 통과, 검수 판정, 교체 확인 표시,
 * 오류 사유 → 문구·재시도 매핑.
 */
public class RecognitionDraftsTest {

    private static RecognitionResult result(RecognizedCourse... courses) {
        return new RecognitionResult(Arrays.asList(courses), Collections.emptyList(), 0, 2);
    }

    private static RecognizedCourse c(String name, int day, int start, int end, String building, String room,
                                      double confidence, int image) {
        return new RecognizedCourse(name, day, start, end, building, room, confidence, image);
    }

    private static RecognitionResult serverMock() {
        // Mock 저장소와 같은 서버 mock 응답을 파서로 읽는다(2장).
        Map<String, Object> response = new HashMap<>();
        response.put("courses", Arrays.asList(
                map("컴퓨터 구조론", 1, 600, 690, "공학관", "302호", 0.94, 0),
                map("자료구조", 3, 780, 870, "미래관", "407호", 0.88, 0),
                map("자료구조", 3, 780, 870, "미래관", "407호", 0.81, 1),
                map("확률과 통계", 4, 540, 630, null, null, 0.52, 1)));
        response.put("warnings", Collections.singletonList("여러 이미지에서 같은 수업이 발견되었습니다."));
        return RecognitionParser.parse(response, 2);
    }

    private static Map<String, Object> map(String name, int day, int start, int end, String building, String room,
                                           double confidence, int image) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("dayOfWeek", day);
        m.put("startMinute", start);
        m.put("endMinute", end);
        m.put("building", building);
        m.put("room", room);
        m.put("confidence", confidence);
        m.put("sourceImageIndex", image);
        return m;
    }

    @Test
    public void serverMock_toDraft_passesValidateAll_andMarksReplace() {
        TimetableDraft draft = RecognitionDrafts.toDraft(serverMock());
        assertEquals(3, draft.courses.size());
        assertTrue(draft.replacesExisting());
        assertTrue(draft.copy().replacesExisting());

        Course structure = draft.courses.get(1);
        assertEquals("자료구조", structure.getName());
        assertEquals("미래관 407호", structure.getClassroom());
        assertEquals("수", structure.getDay());
        assertEquals("13:00", structure.getStartTime());
        assertEquals("14:30", structure.getEndTime());
        assertEquals(2, structure.getRecognitionMergedCount());

        List<com.team4.bintime.data.model.Course> courses = TimetableDraftMapper.toCourses(draft);
        assertEquals(3, courses.size());
        assertNull(CourseValidator.validateAll(courses));
    }

    @Test
    public void serverMock_reviewFlags() {
        List<Set<RecognitionReview.Flag>> flags = RecognitionDrafts.check(RecognitionDrafts.toDraft(serverMock()));
        assertTrue(flags.get(0).isEmpty());
        assertTrue(flags.get(1).contains(RecognitionReview.Flag.MERGED_DUPLICATE));
        assertTrue(flags.get(2).contains(RecognitionReview.Flag.LOW_CONFIDENCE));
        assertTrue(flags.get(2).contains(RecognitionReview.Flag.NO_CLASSROOM));
        assertTrue(RecognitionReview.canConfirm(flags));
    }

    @Test
    public void sameNameSlots_shareCourseId_andSaveAsOneCourse() {
        TimetableDraft draft = RecognitionDrafts.toDraft(result(
                c("미적분학", 2, 540, 630, "공학관", "201호", 0.9, 0),
                c("미적분학", 4, 540, 630, "공학관", "201호", 0.9, 1)));
        assertEquals(2, draft.courses.size());
        assertEquals(draft.courses.get(0).getCourseId(), draft.courses.get(1).getCourseId());
        List<com.team4.bintime.data.model.Course> courses = TimetableDraftMapper.toCourses(draft);
        assertEquals(1, courses.size());
        assertEquals(2, courses.get(0).getTimeSlots().size());
        assertNull(CourseValidator.validateAll(courses));
    }

    @Test
    public void weekendAndMidnightEnd_convertAndValidate() {
        TimetableDraft draft = RecognitionDrafts.toDraft(result(
                c("토요 특강", 6, 1380, 1440, null, null, 0.9, 0),
                c("일요 세미나", 7, 600, 660, "본관", null, 0.9, 0)));
        assertEquals("토", draft.courses.get(0).getDay());
        assertEquals("24:00", draft.courses.get(0).getEndTime());
        assertEquals("일", draft.courses.get(1).getDay());
        List<Set<RecognitionReview.Flag>> flags = RecognitionDrafts.check(draft);
        assertTrue(flags.get(0).contains(RecognitionReview.Flag.WEEKEND));
        assertFalse(flags.get(0).contains(RecognitionReview.Flag.NEEDS_EDIT_TIME));
        assertTrue(RecognitionReview.canConfirm(flags));
        List<com.team4.bintime.data.model.Course> courses = TimetableDraftMapper.toCourses(draft);
        assertEquals(1440, courses.get(0).getTimeSlots().get(0).getEndMinute());
        assertNull(CourseValidator.validateAll(courses));
        // BT-036: 주말 수업과 겹친 주말 공강 칸은 저장 전에 지운다.
        long[] cells = new long[7];
        cells[5] = 1L << 47; // 토 23:30
        draft.setFreeCells(cells);
        draft.discardOccupiedFreeSlots();
        assertEquals(0L, draft.freeCells()[5]);
    }

    @Test
    public void conflictingCourses_blockConfirm_untilOneIsRemoved() {
        TimetableDraft draft = RecognitionDrafts.toDraft(result(
                c("자료구조", 3, 780, 870, null, null, 0.9, 0),
                c("운영체제", 3, 840, 930, null, null, 0.9, 1)));
        assertFalse(RecognitionReview.canConfirm(RecognitionDrafts.check(draft)));
        assertTrue(CourseValidator.validateAll(TimetableDraftMapper.toCourses(draft)) != null);
        draft.courses.remove(1);
        assertTrue(RecognitionReview.canConfirm(RecognitionDrafts.check(draft)));
    }

    @Test
    public void longName_blocksConfirm_untilEdited_andEditedRowLosesRecognitionMarks() {
        String longName = "가나다라마바사아자차카타파하가나다라마바사아자차카타파하가나다";
        TimetableDraft draft = RecognitionDrafts.toDraft(result(c(longName, 1, 540, 600, null, null, 0.5, 0)));
        assertEquals(longName, draft.courses.get(0).getName()); // 자르지 않는다
        assertTrue(RecognitionDrafts.check(draft).get(0).contains(RecognitionReview.Flag.NEEDS_EDIT_NAME_LENGTH));
        assertFalse(RecognitionReview.canConfirm(RecognitionDrafts.check(draft)));

        // 수정 화면이 새 행으로 바꾼다(courseId 유지). 사용자가 확인한 행이라 신뢰도 표시도 사라진다.
        Course edited = new Course("짧은 이름", "본관", "월", "09:00", "10:00");
        edited.keepIdentityOf(draft.courses.get(0));
        draft.courses.set(0, edited);
        List<Set<RecognitionReview.Flag>> flags = RecognitionDrafts.check(draft);
        assertFalse(flags.get(0).contains(RecognitionReview.Flag.LOW_CONFIDENCE));
        assertTrue(RecognitionReview.canConfirm(flags));
        assertNull(CourseValidator.validateAll(TimetableDraftMapper.toCourses(draft)));
    }

    @Test
    public void zeroCourses_emptyDraft_cannotConfirm() {
        TimetableDraft draft = RecognitionDrafts.toDraft(result());
        assertTrue(draft.courses.isEmpty());
        assertTrue(draft.replacesExisting());
        assertFalse(RecognitionReview.canConfirm(RecognitionDrafts.check(draft)));
    }

    @Test
    public void minutes_parsesMidnightEnd() {
        assertEquals(1440, RecognitionDrafts.minutes("24:00"));
        assertEquals(570, RecognitionDrafts.minutes("09:30"));
        assertEquals(-1, RecognitionDrafts.minutes("25:00"));
        assertEquals(-1, RecognitionDrafts.minutes(null));
    }

    @Test
    public void manualAndEditDrafts_doNotReplaceByDefault() {
        assertFalse(new TimetableDraft(Collections.emptyList()).replacesExisting());
        assertFalse(TimetableDraftMapper.toDraft(null).replacesExisting());
    }

    // ── 오류 사유 → 문구·재시도 ─────────────────────────────────────────

    @Test
    public void everyReasonHasMessage_andRetryFollowsRetryable() {
        for (RecognitionFailure.Reason reason : RecognitionFailure.Reason.values()) {
            RecognitionFailure failure = RecognitionFailure.of(reason);
            RecognitionMessages.of(reason); // 문구가 있어야 한다
            assertEquals(reason.name(), failure.isRetryable(), RecognitionMessages.showsRetry(failure));
            assertTrue(RecognitionMessages.offersManualInput(failure)); // 어떤 실패든 수동 입력으로
        }
        assertFalse(RecognitionMessages.showsRetry(null));
        assertFalse(RecognitionMessages.offersManualInput(null));
    }

    @Test
    public void specificReasons_haveSpecificMessages() {
        assertEquals(R.string.timetable_recognition_error_quota,
                RecognitionMessages.of(RecognitionFailure.Reason.QUOTA_EXCEEDED));
        assertEquals(R.string.timetable_recognition_error_timeout,
                RecognitionMessages.of(RecognitionFailure.Reason.AI_TIMEOUT));
        assertEquals(R.string.timetable_recognition_error_invalid_output,
                RecognitionMessages.of(RecognitionFailure.Reason.AI_INVALID_OUTPUT));
        assertEquals(R.string.timetable_recognition_error_not_implemented,
                RecognitionMessages.of(RecognitionFailure.Reason.AI_NOT_IMPLEMENTED));
        assertFalse(RecognitionMessages.showsRetry(RecognitionFailure.of(RecognitionFailure.Reason.QUOTA_EXCEEDED)));
        assertFalse(RecognitionMessages.showsRetry(
                RecognitionFailure.of(RecognitionFailure.Reason.AI_NOT_IMPLEMENTED)));
        assertTrue(RecognitionMessages.showsRetry(RecognitionFailure.of(RecognitionFailure.Reason.AI_TIMEOUT)));
        // 서버가 retryable=false 를 주면 다시 시도 버튼을 숨긴다.
        assertFalse(RecognitionMessages.showsRetry(RecognitionFailure.fromServer("AI_UNAVAILABLE", false, null)));
    }

    @Test
    public void everyFlagHasMessage() {
        for (RecognitionReview.Flag flag : RecognitionReview.Flag.values()) RecognitionMessages.flag(flag);
    }
}
