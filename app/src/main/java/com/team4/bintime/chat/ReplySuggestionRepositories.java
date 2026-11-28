package com.team4.bintime.chat;

import android.content.Context;

import androidx.annotation.Nullable;

import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.request.MatchAppointment;

import java.util.Comparator;
import java.util.List;

/**
 * 답장 추천 저장소 선택. Firebase 가 설정되면 suggestChatReplies(Gemini), 아니면 BT-010 Mock 을 쓴다.
 */
public final class ReplySuggestionRepositories {

    private static FunctionsReplySuggestionRepository firebase;

    private ReplySuggestionRepositories() {}

    public static synchronized ReplySuggestionRepository get(Context context) {
        if (!AuthRepositories.isFirebaseEnabled(context)) return MockReplySuggestionRepository.shared();
        if (firebase == null) firebase = new FunctionsReplySuggestionRepository();
        return firebase;
    }

    /**
     * 서버에 보낼 이 방의 약속 ID. 서버는 이 약속의 chatRoomId 로 방을 찾으므로 같은 방의 어떤 약속이어도 된다.
     * 상단 대표 약속을 먼저 쓰고, 모두 종료됐으면 가장 늦게 끝난 약속, 약속 목록이 없는 Mock·이전 방이면 방 키를 쓴다.
     */
    @Nullable
    static String targetMatchId(List<MatchAppointment> appointments, @Nullable String roomKey, long now) {
        MatchAppointment picked = ChatAppointmentDisplay.pick(appointments, now);
        if (picked == null) {
            picked = appointments.stream()
                    .max(Comparator.comparingLong(MatchAppointment::endAtMillis).thenComparing(a -> a.matchId))
                    .orElse(null);
        }
        return picked != null ? picked.matchId : roomKey;
    }
}
