package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * suggestChatReplies Callable(asia-northeast3, BT-024·BT-041). 앱은 matchId 만 보낸다.
 * 메시지 본문·UID 는 보내지 않고, 서버가 참여·방 멤버 여부를 확인한 뒤 RTDB 최근 대화를 직접 읽는다.
 * 취소됐거나 호출 중 로그인 계정이 바뀌었으면 결과를 쓰지 않는다.
 */
final class FunctionsReplySuggestionRepository implements ReplySuggestionRepository {

    private static final String REGION = "asia-northeast3";
    private static final String FUNCTION = "suggestChatReplies";

    @Override
    public Request requestReplies(String matchId, Callback callback) {
        boolean[] cancelled = {false};
        String uid = FirebaseAuth.getInstance().getUid();
        if (uid == null) {
            callback.onError(ReplySuggestionFailure.SIGNED_OUT);
            return () -> {};
        }
        if (matchId == null || matchId.isEmpty()) {
            callback.onError(ReplySuggestionFailure.GENERIC);
            return () -> {};
        }
        FirebaseFunctions.getInstance(REGION).getHttpsCallable(FUNCTION)
                .call(Collections.singletonMap("matchId", matchId))
                .addOnCompleteListener(task -> {
                    if (cancelled[0]) return;
                    if (!Objects.equals(uid, FirebaseAuth.getInstance().getUid())) {
                        callback.onError(ReplySuggestionFailure.SIGNED_OUT);
                        return;
                    }
                    if (task.isSuccessful()) {
                        List<String> replies = parseReplies(task.getResult() == null ? null : task.getResult().getData());
                        if (replies == null) callback.onError(ReplySuggestionFailure.RETRY);
                        else callback.onSuccess(replies);
                        return;
                    }
                    Exception error = task.getException();
                    if (error instanceof FirebaseFunctionsException) {
                        FirebaseFunctionsException e = (FirebaseFunctionsException) error;
                        callback.onError(ReplySuggestionFailure.from(e.getCode().name(), e.getDetails()));
                    } else {
                        callback.onError(ReplySuggestionFailure.from(null, null));
                    }
                });
        return () -> cancelled[0] = true;
    }

    /**
     * 응답 {@code {task, data: {replies}, meta}} 에서 답장을 꺼낸다. 서버가 이미 검증하지만,
     * 비었거나 개수가 다르거나 중복이면 칩을 만들지 않도록 null 을 돌려준다.
     */
    @Nullable
    static List<String> parseReplies(@Nullable Object raw) {
        Object data = raw instanceof Map ? ((Map<?, ?>) raw).get("data") : null;
        Object list = data instanceof Map ? ((Map<?, ?>) data).get("replies") : null;
        if (!(list instanceof List)) return null;
        List<String> replies = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object item : (List<?>) list) {
            if (!(item instanceof String)) return null;
            String reply = ((String) item).trim();
            if (reply.isEmpty() || !seen.add(reply)) return null;
            replies.add(reply);
        }
        return replies.size() == REPLY_COUNT ? Collections.unmodifiableList(replies) : null;
    }
}
