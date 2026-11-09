package com.team4.bintime.timetable;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Shared mock timetable data for manual entry, review, registration, and weekly view. */
final class TimetableDraft implements Serializable {
    private static final String EXTRA_DRAFT = "timetable_draft";
    static final int SLOT_MINUTES = 30;
    static final String[] DAYS = {"월", "화", "수", "목", "금"};

    final ArrayList<Course> courses;
    private final long[] freeSlots = new long[DAYS.length];
    private boolean suggested;

    TimetableDraft(List<Course> courses) {
        this.courses = new ArrayList<>(courses);
    }

    TimetableDraft copy() {
        TimetableDraft copy = new TimetableDraft(courses);
        System.arraycopy(freeSlots, 0, copy.freeSlots, 0, freeSlots.length);
        copy.suggested = suggested;
        return copy;
    }

    static TimetableDraft mockRecognition() {
        return new TimetableDraft(Arrays.asList(
                new Course("컴퓨터 구조론", "공학관 302호", "월", "10:00", "11:30"),
                new Course("미적분학", "공학관 201호", "화", "09:00", "10:30"),
                new Course("수학", "본관 202호", "수", "09:00", "10:00"),
                new Course("자료구조", "공학관 301호", "수", "11:00", "12:30"),
                new Course("웹 프로그래밍", "공학관 303호", "목", "13:00", "14:30"),
                new Course("영어", "인문관 101호", "금", "10:00", "11:00"),
                new Course("세미나", "본관 401호", "금", "12:00", "12:30")
        ));
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

    void discardOccupiedFreeSlots() {
        for (Course course : courses) {
            for (int slot = course.getStartSlotIndex(0); slot < course.getEndSlotIndex(0); slot++) {
                freeSlots[course.getDayIndex()] &= ~(1L << slot);
            }
        }
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
