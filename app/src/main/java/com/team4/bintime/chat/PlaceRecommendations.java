package com.team4.bintime.chat;

import android.content.Context;

import androidx.annotation.Nullable;

import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.request.MatchAppointment;

import java.util.List;

/**
 * BT-042 추천 장소 저장소 선택과 표시 규칙.
 * Firebase 가 설정되면 recommendPlace(Gemini) 를 쓰고, Mock 모드는 MockChatRepository 의 시안 문구(room.recommendedPlace)를 그대로 쓴다.
 */
public final class PlaceRecommendations {

    private static FunctionsPlaceRecommendationRepository firebase;

    private PlaceRecommendations() {}

    /** Mock 모드면 null(방 데이터의 시안 문구를 쓴다). */
    @Nullable
    public static synchronized PlaceRecommendationRepository get(Context context) {
        if (!AuthRepositories.isFirebaseEnabled(context)) return null;
        if (firebase == null) firebase = new FunctionsPlaceRecommendationRepository();
        return firebase;
    }

    /**
     * 추천을 받을 약속 ID. 상단 카드에 보이는 약속(ChatAppointmentDisplay.pick)을 쓰고,
     * 약속 목록이 없는 이전 방이면 방 키(= 첫 매칭 ID)를 쓴다. 보일 약속이 없으면 null(요청하지 않음).
     */
    @Nullable
    static String targetMatchId(List<MatchAppointment> appointments, @Nullable String roomKey, long now) {
        if (appointments.isEmpty()) return roomKey;
        MatchAppointment picked = ChatAppointmentDisplay.pick(appointments, now);
        return picked != null ? picked.matchId : null;
    }

    /** Figma 시안처럼 " / " 로 잇는다. 비었으면 null(장소 줄 숨김). */
    @Nullable
    static String join(@Nullable List<String> names) {
        if (names == null || names.isEmpty()) return null;
        return String.join(" / ", names);
    }
}
