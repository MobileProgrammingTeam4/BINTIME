package com.team4.bintime.data.recognition;

import com.team4.bintime.data.repository.CourseValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * [BT-026] 여러 이미지의 인식 결과를 검수용 수업 목록으로 합친다(순수 Java).
 * <ol>
 *   <li><b>완전 중복</b>: 정규화한 이름(공백 제거·소문자)·요일·시작·끝이 같으면 한 시간대로 합친다. 신뢰도는 큰 값,
 *       건물·강의실은 신뢰도 높은 쪽 값을 쓰되 null 이면 다른 쪽 값으로 채운다. 출처 이미지 목록을 모두 남긴다.</li>
 *   <li><b>같은 이름의 다른 시간대</b>: 한 수업(여러 시간대)으로 묶는다. 한 수업은 최대
 *       {@link CourseValidator#MAX_SLOTS_PER_COURSE}개 시간대이며, 넘으면 다음 수업으로 나눈다. 강의실이 서로 다른
 *       (둘 다 값이 있는) 시간대는 저장 모델이 수업당 강의실 하나라서 다른 수업으로 둔다.</li>
 * </ol>
 * 충돌·낮은 신뢰도·길이 초과·주말 판정은 {@link RecognitionReview}가 한다(검수 중 수정해도 다시 판정하도록).
 * 이름·강의실은 자르거나 고치지 않는다. 결과 순서는 첫 시간대의 요일·시작 시각 순서다.
 */
public final class RecognitionMerger {

    private RecognitionMerger() {
    }

    /** 합친 시간대 하나. */
    public static final class MergedSlot {
        public final int dayOfWeek;
        public final int startMinute;
        public final int endMinute;
        public final double confidence;
        /** 이 시간대가 나온 이미지 index(오름차순, 중복 없음). */
        public final List<Integer> sourceImages;
        /** 합친 원본 항목 수(1이면 중복 없음). */
        public final int mergedCount;

        MergedSlot(int dayOfWeek, int startMinute, int endMinute, double confidence, List<Integer> sourceImages,
                   int mergedCount) {
            this.dayOfWeek = dayOfWeek;
            this.startMinute = startMinute;
            this.endMinute = endMinute;
            this.confidence = confidence;
            this.sourceImages = Collections.unmodifiableList(new ArrayList<>(sourceImages));
            this.mergedCount = mergedCount;
        }
    }

    /** 합친 수업 하나(시간대 1~7개). */
    public static final class MergedCourse {
        public final String name;
        /** "건물 강의실". 둘 다 없으면 null. */
        public final String classroom;
        public final List<MergedSlot> slots;

        MergedCourse(String name, String classroom, List<MergedSlot> slots) {
            this.name = name;
            this.classroom = classroom;
            this.slots = Collections.unmodifiableList(new ArrayList<>(slots));
        }
    }

    /** 공백(연속 공백 포함)을 모두 지우고 소문자로. 비교용 키. */
    public static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    /** 앞뒤 공백 제거 + 연속 공백 하나로. 화면에 보일 값. */
    static String tidy(String text) {
        return text == null ? null : text.trim().replaceAll("\\s+", " ");
    }

    /** 건물·강의실 → 화면 강의실 문자열. 둘 다 없으면 null. */
    public static String classroom(String building, String room) {
        String b = tidy(building);
        String r = tidy(room);
        if (b != null && b.isEmpty()) b = null;
        if (r != null && r.isEmpty()) r = null;
        if (b == null) return r;
        if (r == null) return b;
        return b + " " + r;
    }

    public static List<MergedCourse> merge(RecognitionResult result) {
        if (result == null || result.courses.isEmpty()) return new ArrayList<>();

        // 1) 완전 중복 합치기. 같은 키 안에서는 신뢰도 높은 항목이 대표.
        Map<String, List<RecognizedCourse>> exact = new LinkedHashMap<>();
        for (RecognizedCourse c : result.courses) {
            String key = normalize(c.name) + '|' + c.dayOfWeek + '|' + c.startMinute + '|' + c.endMinute;
            List<RecognizedCourse> same = exact.get(key);
            if (same == null) {
                same = new ArrayList<>();
                exact.put(key, same);
            }
            same.add(c);
        }
        List<Entry> entries = new ArrayList<>();
        for (List<RecognizedCourse> same : exact.values()) entries.add(Entry.of(same));

        // 2) 이름별로 묶고, 강의실이 다르거나 7개를 넘으면 나눈다.
        Map<String, List<Entry>> byName = new LinkedHashMap<>();
        for (Entry e : entries) {
            String key = normalize(e.name);
            List<Entry> list = byName.get(key);
            if (list == null) {
                list = new ArrayList<>();
                byName.put(key, list);
            }
            list.add(e);
        }
        List<Group> groups = new ArrayList<>();
        for (List<Entry> sameName : byName.values()) {
            Collections.sort(sameName, Entry.ORDER);
            List<Group> nameGroups = new ArrayList<>();
            for (Entry e : sameName) {
                Group target = null;
                for (Group g : nameGroups) {
                    if (g.accepts(e)) {
                        target = g;
                        break;
                    }
                }
                if (target == null) {
                    target = new Group();
                    nameGroups.add(target);
                }
                target.add(e);
            }
            groups.addAll(nameGroups);
        }

        List<MergedCourse> merged = new ArrayList<>();
        for (Group g : groups) merged.add(g.toCourse());
        Collections.sort(merged, (a, b) -> {
            MergedSlot x = a.slots.get(0);
            MergedSlot y = b.slots.get(0);
            if (x.dayOfWeek != y.dayOfWeek) return Integer.compare(x.dayOfWeek, y.dayOfWeek);
            return Integer.compare(x.startMinute, y.startMinute);
        });
        return merged;
    }

    /** 완전 중복을 합친 시간대 하나와 그 대표 이름·강의실. */
    private static final class Entry {
        static final Comparator<Entry> ORDER = (a, b) -> {
            if (a.slot.dayOfWeek != b.slot.dayOfWeek) return Integer.compare(a.slot.dayOfWeek, b.slot.dayOfWeek);
            return Integer.compare(a.slot.startMinute, b.slot.startMinute);
        };

        final String name;
        final String classroom;
        final double confidence;
        final MergedSlot slot;

        Entry(String name, String classroom, double confidence, MergedSlot slot) {
            this.name = name;
            this.classroom = classroom;
            this.confidence = confidence;
            this.slot = slot;
        }

        static Entry of(List<RecognizedCourse> same) {
            List<RecognizedCourse> byConfidence = new ArrayList<>(same);
            // 신뢰도 내림차순, 같으면 먼저 나온 것(안정 정렬).
            Collections.sort(byConfidence, (a, b) -> Double.compare(b.confidence, a.confidence));
            RecognizedCourse best = byConfidence.get(0);
            String building = null;
            String room = null;
            TreeSet<Integer> sources = new TreeSet<>();
            for (RecognizedCourse c : byConfidence) {
                if (building == null) building = c.building;
                if (room == null) room = c.room;
                sources.add(c.sourceImageIndex);
            }
            MergedSlot slot = new MergedSlot(best.dayOfWeek, best.startMinute, best.endMinute, best.confidence,
                    new ArrayList<>(sources), same.size());
            return new Entry(tidy(best.name), classroom(building, room), best.confidence, slot);
        }
    }

    /** 같은 이름의 시간대 묶음(한 수업). */
    private static final class Group {
        final List<Entry> entries = new ArrayList<>();
        String classroom;

        boolean accepts(Entry e) {
            if (entries.size() >= CourseValidator.MAX_SLOTS_PER_COURSE) return false;
            return classroom == null || e.classroom == null || normalize(classroom).equals(normalize(e.classroom));
        }

        void add(Entry e) {
            entries.add(e);
            if (classroom == null) classroom = e.classroom;
        }

        MergedCourse toCourse() {
            // 표시 이름은 신뢰도가 가장 높은 시간대의 이름.
            Entry best = entries.get(0);
            for (Entry e : entries) if (e.confidence > best.confidence) best = e;
            List<MergedSlot> slots = new ArrayList<>();
            for (Entry e : entries) slots.add(e.slot);
            return new MergedCourse(best.name, classroom, slots);
        }
    }
}
