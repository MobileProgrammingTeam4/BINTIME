package com.team4.bintime.timetable;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.team4.bintime.data.model.TimeSlot;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared timetable data for manual entry, review, registration, and weekly view.
 * [BT-015/016] 저장소와는 {@link TimetableDraftMapper}로 변환한다. 공강 선택은 요일(월~일)별 48칸
 * 비트마스크이며 화면은 월~금만 보여 주고 토·일 값은 저장할 때 그대로 돌려준다.
 */
final class TimetableDraft implements Serializable {
    private static final String EXTRA_DRAFT = "timetable_draft";
    static final int SLOT_MINUTES = 30;
    static final String[] DAYS = {"월", "화", "수", "목", "금"};

    final ArrayList<Course> courses;
    /** 요일(월~일)별 선택 칸. 비트 i = 자정 기준 30분 칸 i. 화면은 앞의 5개(월~금)만 쓴다. */
    private final long[] freeSlots = new long[7];
    private boolean suggested;
    /**
     * [BT-026 결정 0-3] 새 시간표 등록(직접 입력 새로 시작·이미지 인식)이라 저장하면 기존 수업이 모두 바뀐다.
     * 저장 전에 "기존 수업이 새 시간표로 교체됩니다" 확인을 받는다. 기존 시간표 수정이면 false.
     */
    private boolean replacesExisting;

    TimetableDraft(List<Course> courses) {
        this.courses = new ArrayList<>(courses);
    }

    TimetableDraft copy() {
        TimetableDraft copy = new TimetableDraft(courses);
        System.arraycopy(freeSlots, 0, copy.freeSlots, 0, Math.min(freeSlots.length, copy.freeSlots.length));
        copy.suggested = suggested;
        copy.replacesExisting = replacesExisting;
        return copy;
    }

    /** 새 시간표 등록으로 표시한다(저장 시 기존 수업 교체 확인). */
    TimetableDraft markReplacesExisting() {
        replacesExisting = true;
        return this;
    }

    boolean replacesExisting() {
        return replacesExisting;
    }

    boolean overlaps(Course candidate, int excludedIndex) {
        for (int i = 0; i < courses.size(); i++) {
            Course existing = courses.get(i);
            if (i != excludedIndex && existing.getDayIndex() == candidate.getDayIndex()
                    && existing.getStartMinute() < candidate.getEndMinute()
                    && candidate.getStartMinute() < existing.getEndMinute()) return true;
        }
        return false;
    }

    Course courseAt(int day, int slot) {
        int start = slot * SLOT_MINUTES;
        for (Course course : courses) {
            if (course.getDayIndex() == day && course.getStartMinute() < start + SLOT_MINUTES
                    && start < course.getEndMinute()) return course;
        }
        return null;
    }

    boolean isFreeSelected(int day, int slot) {
        return courseAt(day, slot) == null && (freeSlots[day] & (1L << slot)) != 0;
    }

    void toggleFree(int day, int slot) {
        if (courseAt(day, slot) == null) freeSlots[day] ^= 1L << slot;
    }

    /** 저장용 선택 칸 사본(길이 7). */
    long[] freeCells() {
        long[] cells = new long[7];
        System.arraycopy(freeSlots, 0, cells, 0, Math.min(freeSlots.length, cells.length));
        return cells;
    }

    void setFreeCells(long[] cells) {
        Arrays.fill(freeSlots, 0L);
        if (cells != null) System.arraycopy(cells, 0, freeSlots, 0, Math.min(cells.length, freeSlots.length));
    }

    /** 이미 저장된 시간표는 빈 시간을 공강으로 다시 자동 추가하지 않는다. */
    void markSuggested() {
        suggested = true;
    }

    void discardOccupiedFreeSlots() {
        for (Course course : courses) {
            int day = dayIndexIncludingWeekend(course);
            if (day < 0) continue;
            for (int slot = course.getStartSlotIndex(0); slot < course.getEndSlotIndex(0); slot++) {
                freeSlots[day] &= ~(1L << slot);
            }
        }
    }

    /**
     * [BT-036] 월~일 → 0~6, 알 수 없으면 -1. 토·일은 화면 칸이 없지만 저장할 때 7일을 모두 보내므로
     * 주말 수업과 겹친 주말 칸도 지워야 한다(남기면 저장이 계속 INVALID_FREE_SLOT으로 거부된다).
     */
    private static int dayIndexIncludingWeekend(Course course) {
        int weekday = course.getDayIndex();
        if (weekday >= 0) return weekday;
        int dayOfWeek = TimeSlot.parseDayOfWeek(course.getDay());
        return dayOfWeek == 6 || dayOfWeek == 7 ? dayOfWeek - 1 : -1;
    }

    boolean hasFreeTime() {
        for (long slots : freeSlots) if (slots != 0) return true;
        return false;
    }

    boolean hasHourOfFreeTime() {
        for (long slots : freeSlots) if ((slots & (slots >>> 1)) != 0) return true;
        return false;
    }

    FreeWindow nextFreeWindow(LocalDateTime now) {
        if (!hasHourOfFreeTime()) return null;
        int minute = now.getHour() * 60 + now.getMinute();
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = now.toLocalDate().plusDays(offset);
            int day = date.getDayOfWeek().getValue() - 1;
            if (day >= DAYS.length) continue;
            int slot = 0;
            while (slot < 48) {
                if ((freeSlots[day] & (1L << slot)) == 0) {
                    slot++;
                    continue;
                }
                int start = slot;
                while (slot < 48 && (freeSlots[day] & (1L << slot)) != 0) slot++;
                if (slot - start >= 2 && (offset > 0 || slot * SLOT_MINUTES > minute)) {
                    return new FreeWindow(date, start, slot);
                }
            }
        }
        return null;
    }

    static final class FreeWindow {
        final LocalDate date;
        final int start;
        final int end;

        FreeWindow(LocalDate date, int start, int end) {
            this.date = date;
            this.start = start;
            this.end = end;
        }
    }

    void suggestGaps() {
        if (suggested) return;
        suggested = true;
        for (int day = 0; day < DAYS.length; day++) {
            int endOfLastClass = -1;
            for (int slot = 0; slot < 48; slot++) {
                if (courseAt(day, slot) == null) continue;
                if (endOfLastClass >= 0) {
                    for (int gap = endOfLastClass; gap < slot; gap++) freeSlots[day] |= 1L << gap;
                }
                endOfLastClass = slot + 1;
            }
        }
    }

    static Intent open(Context context, Class<?> screen, TimetableDraft draft) {
        return new Intent(context, screen).putExtra(EXTRA_DRAFT, draft);
    }

    Intent result() {
        return new Intent().putExtra(EXTRA_DRAFT, this);
    }

    void save(Bundle outState) {
        outState.putSerializable(EXTRA_DRAFT, this);
    }

    static TimetableDraft read(Intent intent) {
        return intent == null ? null : (TimetableDraft) intent.getSerializableExtra(EXTRA_DRAFT);
    }

    static TimetableDraft restore(Bundle state, Intent intent) {
        return state == null ? read(intent) : (TimetableDraft) state.getSerializable(EXTRA_DRAFT);
    }
}
