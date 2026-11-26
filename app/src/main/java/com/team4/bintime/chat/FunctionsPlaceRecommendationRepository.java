package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * recommendPlace Callable(asia-northeast3, BT-024·BT-042). 앱은 matchId 만 보낸다.
 * 시간·목적·수업 건물은 서버가 matches 스냅샷과 시간표에서 읽는다. 같은 약속은 서버에 저장된 추천을 돌려받는다.
 * 취소됐거나 호출 중 로그인 계정이 바뀌었으면 결과를 쓰지 않는다.
 */
final class FunctionsPlaceRecommendationRepository implements PlaceRecommendationRepository {

    private static final String REGION = "asia-northeast3";
    private static final String FUNCTION = "recommendPlace";
    static final int MAX_PLACES = 3;

    @Override
    public Request recommend(String matchId, Callback callback) {
        boolean[] cancelled = {false};
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null || matchId == null || matchId.isEmpty()) {
            callback.onError();
            return () -> {};
        }
        FirebaseFunctions.getInstance(REGION).getHttpsCallable(FUNCTION)
                .call(Collections.singletonMap("matchId", matchId))
                .addOnCompleteListener(task -> {
                    if (cancelled[0]) return;
                    if (!Objects.equals(uid, FirebaseAuth.getInstance().getUid()) || !task.isSuccessful()) {
                        callback.onError();
                        return;
                    }
                    List<String> names = parseNames(task.getResult() == null ? null : task.getResult().getData());
                    if (names == null) callback.onError();
                    else callback.onSuccess(names);
                });
        return () -> cancelled[0] = true;
    }

    /** 응답 {@code {task, data: {places: [{placeId, name, reason}]}, meta}} 에서 이름만 꺼낸다. 형식이 틀리면 null. */
    @Nullable
    static List<String> parseNames(@Nullable Object raw) {
        Object data = raw instanceof Map ? ((Map<?, ?>) raw).get("data") : null;
        Object list = data instanceof Map ? ((Map<?, ?>) data).get("places") : null;
        if (!(list instanceof List)) return null;
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : (List<?>) list) {
            Object name = item instanceof Map ? ((Map<?, ?>) item).get("name") : null;
            if (!(name instanceof String)) return null;
            String trimmed = ((String) name).trim();
            if (!trimmed.isEmpty() && seen.add(trimmed)) names.add(trimmed);
        }
        if (names.isEmpty()) return null;
        return Collections.unmodifiableList(names.size() > MAX_PLACES ? names.subList(0, MAX_PLACES) : names);
    }
}
