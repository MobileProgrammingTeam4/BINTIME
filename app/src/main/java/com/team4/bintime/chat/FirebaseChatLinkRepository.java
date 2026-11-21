package com.team4.bintime.chat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * ensureMatchChat Callable(asia-northeast3, BT-033). 매칭 참여자·학교 이메일·방 상태는 서버가 검증한다.
 * 취소됐으면 결과를 버리고, 호출 뒤 로그인 계정이 바뀌었으면 결과 대신 UNAUTHENTICATED 로 끝낸다.
 */
final class FirebaseChatLinkRepository implements ChatLinkRepository {

    private static final String REGION = "asia-northeast3";
    private static final String FUNCTION = "ensureMatchChat";

    @Override
    public Request ensure(String matchId, Callback callback) {
        boolean[] cancelled = {false};
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            callback.onFailure(ChatLinkFailure.from("UNAUTHENTICATED", null));
            return () -> {};
        }
        FirebaseFunctions.getInstance(REGION).getHttpsCallable(FUNCTION)
                .call(Collections.singletonMap("matchId", matchId))
                .addOnCompleteListener(task -> {
                    if (cancelled[0]) return;
                    // 호출 중 계정이 바뀌면 결과를 쓰지 않는다. 버리기만 하면 호출한 쪽이 '준비 중'에 갇히므로 로그인 필요로 끝낸다.
                    if (!Objects.equals(uid, FirebaseAuth.getInstance().getUid())) {
                        callback.onFailure(ChatLinkFailure.from("UNAUTHENTICATED", null));
                        return;
                    }
                    if (task.isSuccessful()) {
                        Object raw = task.getResult() == null ? null : task.getResult().getData();
                        Map<?, ?> data = raw instanceof Map ? (Map<?, ?>) raw : null;
                        Object roomId = data == null ? null : data.get("chatRoomId");
                        callback.onResult(roomId instanceof String ? (String) roomId : null,
                                data != null && Boolean.TRUE.equals(data.get("chatReady")));
                        return;
                    }
                    Exception error = task.getException();
                    if (error instanceof FirebaseFunctionsException) {
                        FirebaseFunctionsException e = (FirebaseFunctionsException) error;
                        callback.onFailure(ChatLinkFailure.from(e.getCode().name(), e.getDetails()));
                    } else {
                        callback.onFailure(ChatLinkFailure.network());
                    }
                });
        return () -> cancelled[0] = true;
    }
}
