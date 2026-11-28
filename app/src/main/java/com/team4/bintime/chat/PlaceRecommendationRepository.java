package com.team4.bintime.chat;

import java.util.List;

/**
 * BT-042 채팅방 상단 추천 장소의 데이터 경계. 화면은 Firebase 를 직접 부르지 않는다.
 * 서버(recommendPlace)가 검증된 국민대 장소 목록 안에서만 고르고 이름도 서버 목록 값으로 돌려준다.
 * 콜백은 메인 스레드에서 호출한다.
 */
public interface PlaceRecommendationRepository {

    interface Callback {
        /** 화면에 보일 장소 이름 1~3개(서버 순서) */
        void onSuccess(List<String> names);

        /** 시간 초과·형식 오류·쿼터 초과·여는 장소 없음 등. 화면은 장소 줄을 숨긴다. */
        void onError();
    }

    interface Request {
        /** 화면이 닫히면 결과를 전달하지 않는다. */
        void cancel();
    }

    /** matchId 는 개별 약속 ID(재사용 방이면 방 키와 다를 수 있다, BT-034). */
    Request recommend(String matchId, Callback callback);
}
