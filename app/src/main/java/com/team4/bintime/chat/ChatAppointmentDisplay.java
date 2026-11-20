package com.team4.bintime.chat;

import com.team4.bintime.request.MatchAppointment;
import com.team4.bintime.request.MatchAppointmentPolicy;

import java.util.Comparator;
import java.util.List;

/**
 * 채팅 상단 시간 카드에 보여 줄 약속을 고른다(BT-035). 선택 정책은 {@link MatchAppointmentPolicy} 를 그대로 쓰고,
 * 그 정책이 고를 약속이 없는 "서버 종료 지연" 구간만 여기서 채운다.
 *
 * <p>서버 종료 지연: 종료 시각이 지났지만 BT-038 서버가 아직 matches.status 를 ENDED 로 바꾸지 않은 상태.
 * 이때 방은 여전히 ACTIVE 라 종료 배너도 뜨지 않으므로, 마지막으로 끝난 ACTIVE 약속의 카드를 유지하고
 * 남은 시간은 0:00:00 으로 보여 준다. 단말 시각으로 종료를 판정하거나 status 를 쓰지 않는다.
 */
final class ChatAppointmentDisplay {

    private ChatAppointmentDisplay() {}

    /** 대표 약속. 진행 중·미래 ACTIVE 가 없으면 종료 시각이 지난 ACTIVE 중 가장 늦게 끝난 약속, 그것도 없으면 null. */
    static MatchAppointment pick(List<MatchAppointment> all, long now) {
        MatchAppointment representative = MatchAppointmentPolicy.representative(all, now);
        if (representative != null) return representative;
        return MatchAppointmentPolicy.targets(all, MatchAppointmentPolicy.Action.END).stream()
                .max(Comparator.comparingLong(MatchAppointment::endAtMillis).thenComparing(a -> a.matchId))
                .orElse(null);
    }
}
