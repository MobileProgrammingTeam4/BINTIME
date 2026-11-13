package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 메시지 전송 규칙 (BT-022). Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다.
 * <ul>
 *   <li>본문: 앞뒤 공백을 지우고 1~{@link ChatRtdbPaths#MAX_CONTENT_LENGTH}자. database.rules.json 과 같은 기준</li>
 *   <li>중복 전송 방지: 실패·시간 초과한 메시지를 같은 내용으로 다시 보내면 같은 push 키를 다시 쓴다.
 *       첫 쓰기가 늦게 서버에 반영됐어도 규칙(!data.exists())이 두 번째 쓰기를 거부하므로 메시지가 두 번 생기지 않는다.</li>
 * </ul>
 */
final class ChatSendPolicy {

    interface KeyFactory {
        String newKey();
    }

    private static final class Pending {
        final String key;
        final String content;

        Pending(String key, String content) {
            this.key = key;
            this.content = content;
        }
    }

    /** 방별로 아직 성공을 확인하지 못한 마지막 메시지. */
    private final Map<String, Pending> pending = new HashMap<>();

    /** 보낼 본문. 공백뿐이거나 너무 길면 null. */
    @Nullable
    static String normalize(@Nullable String content) {
        if (content == null) return null;
        String text = content.trim();
        if (text.isEmpty() || text.length() > ChatRtdbPaths.MAX_CONTENT_LENGTH) return null;
        return text;
    }

    /** 같은 방·같은 내용의 확인 전 메시지가 있으면 그 키를, 없으면 새 키를 준다. */
    String keyFor(String matchId, String content, KeyFactory factory) {
        Pending previous = pending.get(matchId);
        if (previous != null && previous.content.equals(content)) return previous.key;
        String key = factory.newKey();
        pending.put(matchId, new Pending(key, content));
        return key;
    }

    /** 서버 반영을 확인했다. 그 사이 다른 메시지로 바뀌었으면 그대로 둔다. */
    void confirmed(String matchId, String key) {
        Pending current = pending.get(matchId);
        if (current != null && current.key.equals(key)) pending.remove(matchId);
    }

    /**
     * 쓰기가 규칙에 거부됐을 때, 그 키에 이미 저장된 값이 내가 보낸 메시지인지.
     * 맞으면 앞선 시도가 이미 반영된 것이라 성공으로 본다.
     */
    static boolean isOwnMessage(@Nullable Object stored, String uid, String content) {
        if (!(stored instanceof Map)) return false;
        Map<?, ?> map = (Map<?, ?>) stored;
        return Objects.equals(map.get(ChatRtdbPaths.SENDER_ID), uid)
                && Objects.equals(map.get(ChatRtdbPaths.CONTENT), content);
    }
}
