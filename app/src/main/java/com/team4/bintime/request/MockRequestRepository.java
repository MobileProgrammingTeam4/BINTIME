package com.team4.bintime.request;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** BT-007 UI 시연 전용. 실제 인증·트랜잭션·서버 상태 변경을 대신하지 않는다. */
public final class MockRequestRepository implements RequestRepository, Serializable {
    private static final long serialVersionUID = 1L;
    public final String ownerUid;
    private final Map<String, Item> items = new LinkedHashMap<>();
    private final Map<String, String> attempts = new LinkedHashMap<>();
    private transient List<Runnable> listeners;

    public MockRequestRepository() {
        this("mock-me", true);
    }

    public MockRequestRepository(String ownerUid, boolean seed) {
        this.ownerUid = ownerUid;
        if (!seed || ownerUid == null) return;
        LocalDate date = LocalDate.now(ZoneId.of("Asia/Seoul"));
        add(new Item("sent-1", ownerUid, "u1", ownerUid, "스터디", date.toString(), 840, 930, Status.PENDING, null, 0));
        add(new Item("received-1", "u1", ownerUid, ownerUid, "스터디", date.toString(), 840, 930, Status.PENDING, null, 0));
        add(new Item("completed-1", "u1", ownerUid, ownerUid, "스터디", date.minusDays(1).toString(), 840, 930,
                Status.ACCEPTED, MatchStatus.ENDED, 0));
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

    @Override public void create(RequestDraft draft, Callback callback) {
        if (!draft.mock || ownerUid == null || ownerUid.equals(draft.toUid)
                || draft.toUid == null || draft.toUid.isBlank() || draft.purpose == null || draft.purpose.isBlank()
                || draft.startMinute < 0 || draft.endMinute > 1440 || draft.endMinute - draft.startMinute < 60) {
            callback.onError("요청할 상대와 공강시간을 확인해주세요.");
            return;
        }
        try { LocalDate.parse(draft.meetingDate); }
        catch (RuntimeException invalid) { callback.onError("요청 날짜를 확인해주세요."); return; }
        Item previous = find(attempts.get(draft.clientRequestId));
        if (previous != null) { callback.onSuccess(previous); return; }
        for (Item item : items.values()) {
            if (item.sent && item.profileUid.equals(draft.toUid) && item.status == Status.PENDING) {
                callback.onSuccess(item);
                return;
            }
        }
        Item item = new Item("mock-request-" + UUID.randomUUID(), ownerUid, draft.toUid, ownerUid,
                draft.purpose, draft.meetingDate, draft.startMinute, draft.endMinute, Status.PENDING, null,
                System.currentTimeMillis());
        add(item);
        attempts.put(draft.clientRequestId, item.id);
        changed();
        callback.onSuccess(item);
    }

    @Override public boolean hasPendingRequest(String toUid) {
        for (Item item : list(Tab.SENT)) if (item.toUid.equals(toUid)) return true;
        return false;
    }
    @Override public boolean isSubmitting(String toUid) { return false; }
    @Override public boolean isMock() { return true; }
    @Override public Registration observe(Runnable listener) {
        if (listeners == null) listeners = new ArrayList<>();
        listeners.add(listener);
        listener.run();
        return () -> listeners.remove(listener);
    }
    private void changed() {
        if (listeners != null) for (Runnable listener : new ArrayList<>(listeners)) listener.run();
    }

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
        changed();
        return true;
    }
}
