package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class ChatSendPolicyTest {

    private int created;
    private final ChatSendPolicy.KeyFactory keys = () -> "-k" + (++created);

    @Test
    public void normalize_trimsAndRejectsBlankOrTooLong() {
        assertEquals("안녕", ChatSendPolicy.normalize("  안녕\n"));
        assertNull(ChatSendPolicy.normalize(" \t\n "));
        assertNull(ChatSendPolicy.normalize(null));
        StringBuilder max = new StringBuilder();
        for (int i = 0; i < ChatRtdbPaths.MAX_CONTENT_LENGTH; i++) max.append('a');
        assertEquals(max.toString(), ChatSendPolicy.normalize(max.toString()));
        assertNull(ChatSendPolicy.normalize(max + "a"));
    }

    @Test
    public void retryOfSameContent_reusesKey() {
        ChatSendPolicy policy = new ChatSendPolicy();
        String first = policy.keyFor("room", "안녕", keys);
        assertEquals(first, policy.keyFor("room", "안녕", keys));
        assertEquals(1, created);
    }

    @Test
    public void differentContentOrRoom_getsNewKey() {
        ChatSendPolicy policy = new ChatSendPolicy();
        String first = policy.keyFor("room", "안녕", keys);
        assertNotEquals(first, policy.keyFor("room", "반가워요", keys));
        assertNotEquals(first, policy.keyFor("other", "안녕", keys));
    }

    @Test
    public void afterConfirmation_sameContentIsANewMessage() {
        ChatSendPolicy policy = new ChatSendPolicy();
        String first = policy.keyFor("room", "ㅋㅋ", keys);
        policy.confirmed("room", first);
        assertNotEquals(first, policy.keyFor("room", "ㅋㅋ", keys));
    }

    @Test
    public void lateConfirmationOfOldKey_keepsNewerPending() {
        ChatSendPolicy policy = new ChatSendPolicy();
        String old = policy.keyFor("room", "a", keys);
        String newer = policy.keyFor("room", "b", keys);
        policy.confirmed("room", old);
        assertEquals(newer, policy.keyFor("room", "b", keys));
    }

    @Test
    public void isOwnMessage_checksSenderAndContent() {
        Map<String, Object> stored = new HashMap<>();
        stored.put(ChatRtdbPaths.SENDER_ID, "me");
        stored.put(ChatRtdbPaths.CONTENT, "안녕");
        stored.put(ChatRtdbPaths.CREATED_AT, 1L);
        assertTrue(ChatSendPolicy.isOwnMessage(stored, "me", "안녕"));
        assertFalse(ChatSendPolicy.isOwnMessage(stored, "other", "안녕"));
        assertFalse(ChatSendPolicy.isOwnMessage(stored, "me", "다른 말"));
        assertFalse(ChatSendPolicy.isOwnMessage(null, "me", "안녕"));
    }
}
