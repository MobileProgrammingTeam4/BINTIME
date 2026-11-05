package com.team4.bintime.notification;

import com.team4.bintime.request.MockRequestRepository;
import java.util.Arrays;
import java.util.List;

/** 시연 알림은 요청 ID를 참조하며 요청 상태 사본을 따로 저장하지 않는다. */
public final class MockNotificationRepository {
    public enum Type { MESSAGE, ACCEPTED, REQUEST, REVIEW }
    public static final class Item {
        public final Type type;
        public final String requestId;
        Item(Type type, String id) { this.type = type; requestId = id; }
    }
    public List<Item> list() {
        return Arrays.asList(new Item(Type.MESSAGE, "completed-1"), new Item(Type.ACCEPTED, "completed-1"),
                new Item(Type.REQUEST, "received-1"), new Item(Type.REVIEW, "completed-1"));
    }
    public boolean canRespond(Item event, MockRequestRepository requests) {
        MockRequestRepository.Item request = requests.find(event.requestId);
        return event.type == Type.REQUEST && request != null && !request.sent
                && request.status() == MockRequestRepository.Status.PENDING;
    }
}
