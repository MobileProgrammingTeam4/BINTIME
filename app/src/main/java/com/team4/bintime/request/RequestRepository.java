package com.team4.bintime.request;

import java.io.Serializable;
import java.util.List;

/** BT-019/020 공통 요청 계약. 날짜와 분 단위 시간은 생성 후 변경하지 않는다. */
public interface RequestRepository {
    enum Status { PENDING, ACCEPTED, REJECTED, CANCELED }
    enum MatchStatus { ACTIVE, ENDED }
    enum Tab { SENT, RECEIVED, COMPLETED }

    final class Item implements Serializable {
        private static final long serialVersionUID = 2L;
        public final String id, fromUid, toUid, profileUid, purpose, meetingDate;
        public final boolean sent;
        public final int startMinute, endMinute, overlapMinutes;
        public final long createdAt;
        Status status;
        MatchStatus matchStatus;

        Item(String id, String uid, boolean sent, String date, int start, int end,
             Status status, MatchStatus matchStatus) {
            this(id, sent ? "mock-me" : uid, sent ? uid : "mock-me", "mock-me", "스터디",
                    date, start, end, status, matchStatus, 0);
        }

        public Item(String id, String fromUid, String toUid, String viewerUid, String purpose,
                    String date, int start, int end, Status status, MatchStatus matchStatus, long createdAt) {
            this.id = id;
            this.fromUid = fromUid;
            this.toUid = toUid;
            sent = fromUid.equals(viewerUid);
            profileUid = sent ? toUid : fromUid;
            this.purpose = purpose;
            meetingDate = date;
            startMinute = start;
            endMinute = end;
            overlapMinutes = end - start;
            this.status = status;
            this.matchStatus = matchStatus;
            this.createdAt = createdAt;
        }
        public Status status() { return status; }
        public MatchStatus matchStatus() { return matchStatus; }
    }

    interface Callback {
        void onSuccess(Item item);
        void onError(String message);
    }
    interface Registration { void remove(); }

    List<Item> list(Tab tab);
    Item find(String id);
    void create(RequestDraft draft, Callback callback);
    boolean hasPendingRequest(String toUid);
    boolean isSubmitting(String toUid);
    boolean isMock();
    Registration observe(Runnable listener);

    /** 기존 BT-007 수락/거절 시연용. 실제 상태 변경은 BT-031 서버 API에서 연결한다. */
    boolean respond(String id, Status next);
}
