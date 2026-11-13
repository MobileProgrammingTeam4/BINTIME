package com.team4.bintime.timetable;

import com.team4.bintime.data.availability.FreeCells;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.model.Timetable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * [BT-015/016] 저장소 모델({@link Timetable}, {@code data.model.Course}) ↔ 화면 모델({@link TimetableDraft},
 * {@link Course}) 변환.
 * <ul>
 *   <li>저장소 수업의 시간대 하나가 화면 행 하나가 된다. 여러 시간대 수업은 같은 courseId의 여러 행이 되고,
 *       저장할 때 courseId로 다시 묶는다(이름·강의실·교수·색상은 첫 행 기준).</li>
 *   <li>화면에 없는 professor·colorCode는 행에 실어 두었다가 그대로 저장한다.</li>
 *   <li>공강 선택 원본({@link Timetable#getFreeSlots()})은 요일별 칸 마스크로 바꿔 draft에 넣는다.</li>
 * </ul>
 * Android 화면 코드에 의존하지 않아 JVM 단위 테스트로 검증한다.
 */
final class TimetableDraftMapper {

    private TimetableDraftMapper() {
    }

    /** 저장소 시간표 → 화면 draft. null(미등록)이면 빈 draft. 저장된 시간표면 공강 자동 제안을 끈다. */
    static TimetableDraft toDraft(Timetable timetable) {
        List<Course> rows = new ArrayList<>();
        if (timetable == null) return new TimetableDraft(rows);
        if (timetable.getCourses() != null) {
            for (com.team4.bintime.data.model.Course c : timetable.getCourses()) {
                if (c == null || c.getTimeSlots() == null) continue;
                for (TimeSlot s : c.getTimeSlots()) {
                    if (s == null) continue;
                    Course row = new Course(c.getName(), c.getClassroom(),
                            dayLabel(s.getDayOfWeek()),
                            TimeSlot.minutesToTimeString(s.getStartMinute()),
                            TimeSlot.minutesToTimeString(s.getEndMinute()));
                    row.setCourseId(c.getCourseId());
                    row.setProfessor(c.getProfessor());
                    row.setColorCode(c.getColorCode());
                    rows.add(row);
                }
            }
        }
        TimetableDraft draft = new TimetableDraft(rows);
        long[] cells;
        try {
            cells = FreeCells.fromSlots(timetable.getFreeSlots());
        } catch (IllegalArgumentException malformed) {
            cells = FreeCells.empty();
        }
        draft.setFreeCells(cells);
        draft.markSuggested();
        return draft;
    }

    /** 화면 draft → 저장소 수업 목록. courseId가 같은 행은 한 수업의 여러 시간대로 묶는다. */
    static List<com.team4.bintime.data.model.Course> toCourses(TimetableDraft draft) {
        Map<String, com.team4.bintime.data.model.Course> byId = new LinkedHashMap<>();
        if (draft == null) return new ArrayList<>();
        for (Course row : draft.courses) {
            if (row == null) continue;
            if (row.getCourseId() == null) row.setCourseId(UUID.randomUUID().toString());
            TimeSlot slot = new TimeSlot(TimeSlot.parseDayOfWeek(row.getDay()),
                    row.getStartMinute(), row.getEndMinute());
            com.team4.bintime.data.model.Course course = byId.get(row.getCourseId());
            if (course == null) {
                List<TimeSlot> slots = new ArrayList<>();
                slots.add(slot);
                course = new com.team4.bintime.data.model.Course(row.getCourseId(), row.getName(),
                        row.getProfessor(), row.getClassroom(), row.getColorCode(), slots);
                byId.put(row.getCourseId(), course);
            } else {
                course.getTimeSlots().add(slot);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** 화면 draft의 공강 선택 → 저장소에 넘길 구간(30분 칸을 이어 붙인 구간, 60분 미만 포함). */
    static List<TimeSlot> toFreeSlots(TimetableDraft draft) {
        return FreeCells.toSlots(draft == null ? FreeCells.empty() : draft.freeCells());
    }

    private static String dayLabel(int dayOfWeek) {
        return dayOfWeek >= 1 && dayOfWeek <= 7 ? "월화수목금토일".substring(dayOfWeek - 1, dayOfWeek) : null;
    }
}
