package com.team4.bintime.timetable;

import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.recognition.RecognitionMerger;
import com.team4.bintime.data.recognition.RecognitionResult;
import com.team4.bintime.data.recognition.RecognitionReview;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * [BT-026] 인식 결과 ↔ 검수 화면 draft. 합친 수업 하나에 courseId 하나를 주고 시간대마다 행 하나를 만든다
 * ({@link TimetableDraftMapper}와 같은 규칙: 같은 courseId 행 = 한 수업의 여러 시간대). 저장소에 쓰지 않는다.
 * 저장은 검수 후 TimetableRegistrationActivity → replaceCourses 경로로만 한다. JVM 단위 테스트로 검증한다.
 */
final class RecognitionDrafts {

    private static final String DAY_LABELS = "월화수목금토일";

    private RecognitionDrafts() {
    }

    /** 인식 결과 → 새 시간표 draft(저장하면 기존 수업 교체). */
    static TimetableDraft toDraft(RecognitionResult result) {
        return toDraft(RecognitionMerger.merge(result));
    }

    static TimetableDraft toDraft(List<RecognitionMerger.MergedCourse> merged) {
        List<Course> rows = new ArrayList<>();
        for (RecognitionMerger.MergedCourse course : merged) {
            String courseId = UUID.randomUUID().toString();
            for (RecognitionMerger.MergedSlot slot : course.slots) {
                Course row = new Course(course.name, course.classroom,
                        DAY_LABELS.substring(slot.dayOfWeek - 1, slot.dayOfWeek),
                        TimeSlot.minutesToTimeString(slot.startMinute),
                        TimeSlot.minutesToTimeString(slot.endMinute));
                row.setCourseId(courseId);
                row.markRecognized(slot.confidence, slot.mergedCount);
                rows.add(row);
            }
        }
        return new TimetableDraft(rows).markReplacesExisting();
    }

    /** 검수 판정용 행(draft 순서). */
    static List<RecognitionReview.Row> reviewRows(TimetableDraft draft) {
        List<RecognitionReview.Row> rows = new ArrayList<>();
        if (draft == null) return rows;
        for (Course c : draft.courses) {
            if (c == null) {
                rows.add(null);
                continue;
            }
            rows.add(new RecognitionReview.Row(c.getCourseId(), c.getName(), c.getClassroom(),
                    TimeSlot.parseDayOfWeek(c.getDay()), minutes(c.getStartTime()), minutes(c.getEndTime()),
                    c.getRecognitionConfidence(), c.getRecognitionMergedCount()));
        }
        return rows;
    }

    static List<Set<RecognitionReview.Flag>> check(TimetableDraft draft) {
        return RecognitionReview.check(reviewRows(draft));
    }

    /** "HH:mm" → 분. 자정 끝("24:00")은 1440. 틀린 값은 -1(시간 수정 필요로 표시된다). */
    static int minutes(String time) {
        if (time != null && time.trim().equals("24:00")) return 1440;
        return TimeSlot.parseTimeToMinutes(time);
    }
}
