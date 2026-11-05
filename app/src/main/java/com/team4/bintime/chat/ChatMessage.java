package com.team4.bintime.chat;

/** BT-009 임시 모델. 필드 이름은 BT-012 계약이 확정되면 맞춘다. */
public final class ChatMessage {
    public final String messageId, senderId, content;
    public final long createdAt;

    ChatMessage(String messageId, String senderId, String content, long createdAt) {
        this.messageId = messageId;
        this.senderId = senderId;
        this.content = content;
        this.createdAt = createdAt;
    }
}
