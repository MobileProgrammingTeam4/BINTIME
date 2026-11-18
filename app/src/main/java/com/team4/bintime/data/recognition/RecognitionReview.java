package com.team4.bintime.data.recognition;

import com.team4.bintime.data.repository.CourseValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * [BT-026] 검수 화면 행(시간대 하나)마다 표시할 판정. 사용자가 수정·삭제할 때마다 다시 계산한다(순수 Java).
 * <ul>
 *   <li>{@link Flag#CONFLICT}: 같은 요일에 시간이 겹치는 다른 행. 끝과 시작이 맞닿으면([a,b), [b,c)) 충돌이 아니다.
 *       같은 수업(같은 courseKey)의 시간대끼리 겹쳐도 저장할 수 없으므로 충돌이다.</li>
 *   <li>수정 필요(NEEDS_EDIT_*): 앱 저장 제한({@link CourseValidator}: 이름 1~30자, 강의실 30자 이하, 시간 범위,
 *       수업당 시간대 7개 이하)을 넘는다. 자르지 않고 사용자가 고치게 한다.</li>
 *   <li>{@link Flag#LOW_CONFIDENCE}: 인식 신뢰도 {@link #LOW_CONFIDENCE} 미만. 강조만 하고 막지 않는다.</li>
 *   <li>{@link Flag#MERGED_DUPLICATE}: 여러 이미지(또는 같은 이미지의 여러 항목)에서 합쳐졌다. 안내만.</li>
 *   <li>{@link Flag#WEEKEND}: 토·일 수업. 저장은 되지만 주간 시간표(월~금)에는 보이지 않는다(BT-036과 같은 처리).</li>
 *   <li>{@link Flag#NO_CLASSROOM}: 강의실을 읽지 못했다. 저장은 되므로 안내만.</li>
 * </ul>
 * 충돌·수정 필요가 하나라도 남으면 확인(다음 단계)을 막는다({@link #canConfirm}).
 */
public final class RecognitionReview {

    /** 이 값보다 낮으면 "확인 필요"로 강조한다. */
    public static final double LOW_CONFIDENCE = 0.7;

    public enum Flag {
        CONFLICT(true),
        NEEDS_EDIT_NAME_EMPTY(true),
        NEEDS_EDIT_NAME_LENGTH(true),
        NEEDS_EDIT_CLASSROOM_LENGTH(true),
        NEEDS_EDIT_TIME(true),
        NEEDS_EDIT_TOO_MANY_SLOTS(true),
        LOW_CONFIDENCE(false),
        MERGED_DUPLICATE(false),
        WEEKEND(false),
        NO_CLASSROOM(false);

        /** 남아 있으면 저장(다음 단계)을 막는다. */
        public final boolean blocking;

        Flag(boolean blocking) {
            this.blocking = blocking;
        }
    }

    /** 판정할 행. confidence 가 NaN 이면 인식 값이 아니다(사용자가 추가·수정한 행). */
    public static final class Row {
        final String courseKey;
        final String name;
        final String classroom;
        final int dayOfWeek;
        final int startMinute;
        final int endMinute;
        final double confidence;
        final int mergedCount;

        public Row(String courseKey, String name, String classroom, int dayOfWeek, int startMinute, int endMinute,
                   double confidence, int mergedCount) {
            this.courseKey = courseKey;
            this.name = name;
            this.classroom = classroom;
            this.dayOfWeek = dayOfWeek;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.confidence = confidence;
            this.mergedCount = mergedCount;
        }
    }

    private RecognitionReview() {
    }

    /** 행마다 판정(입력과 같은 순서). */
    public static List<Set<Flag>> check(List<Row> rows) {
        List<Set<Flag>> flags = new ArrayList<>();
        if (rows == null) return flags;
        Map<String, Integer> slotsPerCourse = new HashMap<>();
        for (Row r : rows) {
            if (r != null && r.courseKey != null) {
                Integer n = slotsPerCourse.get(r.courseKey);
                slotsPerCourse.put(r.courseKey, n == null ? 1 : n + 1);
            }
        }
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            Set<Flag> f = EnumSet.noneOf(Flag.class);
            flags.add(f);
            if (r == null) {
                f.add(Flag.NEEDS_EDIT_NAME_EMPTY);
                continue;
            }
            String name = r.name == null ? "" : r.name.trim();
            if (name.isEmpty()) f.add(Flag.NEEDS_EDIT_NAME_EMPTY);
            else if (name.length() > CourseValidator.MAX_NAME_LENGTH) f.add(Flag.NEEDS_EDIT_NAME_LENGTH);
            String classroom = r.classroom == null ? "" : r.classroom.trim();
            if (classroom.isEmpty()) f.add(Flag.NO_CLASSROOM);
            else if (classroom.length() > CourseValidator.MAX_TEXT_LENGTH) f.add(Flag.NEEDS_EDIT_CLASSROOM_LENGTH);
            if (!validTime(r)) f.add(Flag.NEEDS_EDIT_TIME);
            Integer n = r.courseKey == null ? null : slotsPerCourse.get(r.courseKey);
            if (n != null && n > CourseValidator.MAX_SLOTS_PER_COURSE) f.add(Flag.NEEDS_EDIT_TOO_MANY_SLOTS);
            if (!Double.isNaN(r.confidence) && r.confidence < LOW_CONFIDENCE) f.add(Flag.LOW_CONFIDENCE);
            if (r.mergedCount > 1) f.add(Flag.MERGED_DUPLICATE);
            if (r.dayOfWeek == 6 || r.dayOfWeek == 7) f.add(Flag.WEEKEND);
            for (int j = 0; j < rows.size(); j++) {
                if (j != i && overlaps(r, rows.get(j))) {
                    f.add(Flag.CONFLICT);
                    break;
                }
            }
        }
        return flags;
    }

    /** 행이 하나 이상이고 막는 판정이 없으면 true. */
    public static boolean canConfirm(List<Set<Flag>> flags) {
        if (flags == null || flags.isEmpty()) return false;
        for (Set<Flag> f : flags) {
            for (Flag flag : f) if (flag.blocking) return false;
        }
        return true;
    }

    /** 막는 판정이 있는 행 수. */
    public static int blockingCount(List<Set<Flag>> flags) {
        int count = 0;
        for (Set<Flag> f : flags == null ? Collections.<Set<Flag>>emptyList() : flags) {
            for (Flag flag : f) {
                if (flag.blocking) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private static boolean validTime(Row r) {
        return r.dayOfWeek >= 1 && r.dayOfWeek <= 7 && r.startMinute >= 0 && r.startMinute < r.endMinute
                && r.endMinute <= CourseValidator.MINUTES_PER_DAY;
    }

    /** 같은 요일, [start, end) 가 겹침. 맞닿으면 false. 시간이 틀린 행은 충돌 판정에서 뺀다(수정 필요로 따로 표시). */
    static boolean overlaps(Row a, Row b) {
        if (a == null || b == null || !validTime(a) || !validTime(b)) return false;
        return a.dayOfWeek == b.dayOfWeek && a.startMinute < b.endMinute && b.startMinute < a.endMinute;
    }
}
