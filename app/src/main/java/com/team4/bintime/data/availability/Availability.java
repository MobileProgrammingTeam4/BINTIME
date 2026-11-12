package com.team4.bintime.data.availability;

import com.team4.bintime.data.model.TimeSlot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [BT-016 ③] 매칭 대상 공강 {@code availability/{uid}} (팀 결정, A).
 * <pre>
 * availability/{uid}
 *   slots: list&lt;{dayOfWeek, startMinute, endMinute}&gt;   matchableFreeSlots 결과(붙은 칸 병합 + 60분 이상)
 *   sourceRevision: int                                 계산 기준 timetables/{uid}.revision
 *   updatedAt: timestamp                                서버 시간 (저장소가 채움)
 * </pre>
 * 분 단위, 반열린 구간 [start, end), 요일 1(월)~7(일). 본인만 읽고 쓰며 다른 사용자는 서버가 읽는다.
 * {@code sourceRevision < timetables.revision}이면 수업이 바뀐 뒤 공강을 다시 저장하지 않은 상태다.
 */
public final class Availability {

    public static final String COLLECTION = "availability";
    public static final String F_SLOTS = "slots";
    public static final String F_SOURCE_REVISION = "sourceRevision";
    public static final String F_UPDATED_AT = "updatedAt";
    public static final String F_DAY_OF_WEEK = "dayOfWeek";
    public static final String F_START_MINUTE = "startMinute";
    public static final String F_END_MINUTE = "endMinute";

    /** slots 상한(팀 결정). 규칙이 원소를 하나씩 풀어 검사하므로 상한이 필요하다. 넘으면 저장 전에 거부한다. */
    public static final int MAX_SLOTS = 40;

    private final List<TimeSlot> slots;
    private final int sourceRevision;

    public Availability(List<TimeSlot> slots, int sourceRevision) {
        List<TimeSlot> copy = new ArrayList<>();
        if (slots != null) {
            for (TimeSlot s : slots) copy.add(new TimeSlot(s.getDayOfWeek(), s.getStartMinute(), s.getEndMinute()));
        }
        this.slots = Collections.unmodifiableList(copy);
        this.sourceRevision = sourceRevision;
    }

    /** 선택 원본 마스크와 시간표 revision으로 만든다. */
    public static Availability fromCells(long[] freeCells, int sourceRevision) {
        return new Availability(FreeCells.matchable(freeCells), sourceRevision);
    }

    public List<TimeSlot> getSlots() {
        return slots;
    }

    public int getSourceRevision() {
        return sourceRevision;
    }

    public static String path(String uid) {
        return COLLECTION + "/" + uid;
    }

    /** availability 문서(updatedAt 제외). */
    public Map<String, Object> toDocument() {
        Map<String, Object> doc = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (TimeSlot s : slots) {
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put(F_DAY_OF_WEEK, s.getDayOfWeek());
            slot.put(F_START_MINUTE, s.getStartMinute());
            slot.put(F_END_MINUTE, s.getEndMinute());
            list.add(slot);
        }
        doc.put(F_SLOTS, list);
        doc.put(F_SOURCE_REVISION, sourceRevision);
        return doc;
    }

    /** 문서 → Availability. 형식이 깨졌으면 null. */
    public static Availability fromDocument(Map<String, Object> data) {
        if (data == null) return null;
        Integer revision = readInt(data.get(F_SOURCE_REVISION));
        Object raw = data.get(F_SLOTS);
        if (revision == null || !(raw instanceof List)) return null;
        List<TimeSlot> slots = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) item;
            Integer day = readInt(m.get(F_DAY_OF_WEEK));
            Integer start = readInt(m.get(F_START_MINUTE));
            Integer end = readInt(m.get(F_END_MINUTE));
            if (day == null || start == null || end == null || day < 1 || day > 7
                    || start < 0 || start >= end || end > SlotGrid.MINUTES_PER_DAY) return null;
            slots.add(new TimeSlot(day, start, end));
        }
        return new Availability(slots, revision);
    }

    private static Integer readInt(Object value) {
        if (value instanceof Integer) return (Integer) value;
        if (value instanceof Long) {
            long v = (Long) value;
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) return null;
            return (int) v;
        }
        return null;
    }
}
