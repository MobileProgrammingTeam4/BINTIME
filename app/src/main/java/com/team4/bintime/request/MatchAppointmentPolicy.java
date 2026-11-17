package com.team4.bintime.request;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** BT-034/035 공용 선택 정책. 단말 시계로 종료 상태나 후기 자격을 만들어 내지 않는다. */
public final class MatchAppointmentPolicy {
    public enum Action { END, REVIEW }
    private MatchAppointmentPolicy() {}

    public static List<MatchAppointment> targets(List<MatchAppointment> all, Action action) {
        List<MatchAppointment> result = new ArrayList<>();
        for (MatchAppointment item : all) {
            if (action == Action.END ? item.status == MatchAppointment.Status.ACTIVE
                    : item.status == MatchAppointment.Status.ENDED && Boolean.TRUE.equals(item.reviewEligible)) result.add(item);
        }
        result.sort(Comparator.comparingLong(MatchAppointment::startAtMillis).thenComparing(a -> a.matchId));
        return result;
    }

    public static MatchAppointment representative(List<MatchAppointment> all, long now) {
        List<MatchAppointment> unfinished = unfinished(all, now);
        for (MatchAppointment item : unfinished) if (item.startAtMillis() <= now) return item;
        return unfinished.isEmpty() ? null : unfinished.get(0);
    }

    public static int additionalCount(List<MatchAppointment> all, long now) { return Math.max(0, unfinished(all, now).size() - 1); }

    /** 후기 제외 약속은 집계하지 않으며, 대상 전부 작성 전까지 방의 버튼은 남기기다. */
    public static boolean allReviewsWritten(List<MatchAppointment> all) {
        List<MatchAppointment> eligible = targets(all, Action.REVIEW);
        return !eligible.isEmpty() && eligible.stream().allMatch(item -> item.reviewWritten);
    }

    private static List<MatchAppointment> unfinished(List<MatchAppointment> all, long now) {
        List<MatchAppointment> result = targets(all, Action.END);
        result.removeIf(item -> item.endAtMillis() <= now);
        return result;
    }
}
