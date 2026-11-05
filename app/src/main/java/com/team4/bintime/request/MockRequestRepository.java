package com.team4.bintime.request;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** BT-007 UI 시연 전용. 실제 인증·트랜잭션·서버 상태 변경을 대신하지 않는다. */
public final class MockRequestRepository implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Status { PENDING, ACCEPTED, REJECTED, CANCELED }
    public enum MatchStatus { ACTIVE, ENDED }
    public enum Tab { SENT, RECEIVED, COMPLETED }

    /** 요청 시점의 시간 스냅샷. 상태 변경 후에도 날짜·시간은 바꾸지 않는다. */
    public static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        public final String id, profileUid, meetingDate;
        public final boolean sent;
        public final int startMinute, endMinute;
        private Status status;
        private MatchStatus matchStatus;

        Item(String id, String uid, boolean sent, String date, int start, int end,
             Status status, MatchStatus matchStatus) {
            this.id = id;
            profileUid = uid;
            this.sent = sent;
            meetingDate = date;
            startMinute = start;
            endMinute = end;
            this.status = status;
            this.matchStatus = matchStatus;
        }
        public Status status() { return status; }
        public MatchStatus matchStatus() { return matchStatus; }
    }

    private final Map<String, Item> items = new LinkedHashMap<>();

    public MockRequestRepository() {
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Seoul"));
        add(new Item("sent-1", "u1", true, date.toString(), 840, 930, Status.PENDING, null));
        add(new Item("received-1", "u1", false, date.toString(), 840, 930, Status.PENDING, null));
        add(new Item("completed-1", "u1", false, date.minusDays(1).toString(), 840, 930,
                Status.ACCEPTED, MatchStatus.ENDED));
    }

    private void add(Item item) { items.put(item.id, item); }

    public List<Item> list(Tab tab) {
        List<Item> result = new ArrayList<>();
        for (Item item : items.values()) {
            if (tab == Tab.COMPLETED ? item.status == Status.ACCEPTED
                    : item.status == Status.PENDING && item.sent == (tab == Tab.SENT)) result.add(item);
        }
        return result;
    }

    public Item find(String id) { return items.get(id); }

    /** 1회만 처리. 수락 시 같은 날짜에 겹치는 대기 요청은 방향별로 정리한다(Mock). */
    public boolean respond(String id, Status next) {
        Item item = items.get(id);
        if (item == null || next == null || item.status != Status.PENDING || next == Status.PENDING
                || (item.sent ? next != Status.CANCELED
                : next != Status.ACCEPTED && next != Status.REJECTED)) return false;
        item.status = next;
        if (next == Status.ACCEPTED) {
            item.matchStatus = MatchStatus.ACTIVE;
            for (Item other : items.values()) {
                if (other.status == Status.PENDING && item.meetingDate.equals(other.meetingDate)
                        && Math.max(item.startMinute, other.startMinute) < Math.min(item.endMinute, other.endMinute)) {
                    other.status = other.sent ? Status.CANCELED : Status.REJECTED;
                }
            }
        }
        return true;
    }
}
