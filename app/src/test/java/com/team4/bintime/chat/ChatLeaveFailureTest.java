package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Collections;

/** BT-043: leaveChatRoom 오류 → 화면 처리 분류. */
public class ChatLeaveFailureTest {

    @Test
    public void policyReasons_fromFailedPrecondition() {
        assertEquals(ChatLeaveFailure.ACTIVE_MATCH, ChatLeaveFailure.from("FAILED_PRECONDITION", Collections.singletonMap("reason", "ACTIVE_MATCH")));
        assertEquals(ChatLeaveFailure.RETRY_LATER, ChatLeaveFailure.from("FAILED_PRECONDITION", Collections.singletonMap("reason", "RETRY_LATER")));
        assertEquals(ChatLeaveFailure.GENERIC, ChatLeaveFailure.from("FAILED_PRECONDITION", Collections.singletonMap("reason", "INVALID_ROOM")));
        assertEquals(ChatLeaveFailure.GENERIC, ChatLeaveFailure.from("FAILED_PRECONDITION", null));
    }

    @Test
    public void platformCodes() {
        assertEquals(ChatLeaveFailure.RETRY_LATER, ChatLeaveFailure.from(null, null)); // 네트워크
        assertEquals(ChatLeaveFailure.SIGNED_OUT, ChatLeaveFailure.from("UNAUTHENTICATED", null));
        assertEquals(ChatLeaveFailure.PERMISSION, ChatLeaveFailure.from("PERMISSION_DENIED", null));
        assertEquals(ChatLeaveFailure.RETRY_LATER, ChatLeaveFailure.from("UNAVAILABLE", null));
        assertEquals(ChatLeaveFailure.RETRY_LATER, ChatLeaveFailure.from("DEADLINE_EXCEEDED", null));
        assertEquals(ChatLeaveFailure.GENERIC, ChatLeaveFailure.from("INVALID_ARGUMENT", null));
        assertEquals(ChatLeaveFailure.GENERIC, ChatLeaveFailure.from("NOT_FOUND", null));
    }
}
