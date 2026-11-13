package com.team4.bintime.request;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.team4.bintime.request.RequestRepository.Item;
import com.team4.bintime.request.RequestRepository.Status;

/** Callable 결과와 발신/수신 스냅샷이 공유하는 요청 상태 저장소. */
final class RequestItemCache {
    private final Map<String, Item> sent = new HashMap<>();
    private final Map<String, Item> received = new HashMap<>();

    Item get(String id) {
        Item item = sent.get(id);
        return item != null ? item : received.get(id);
    }

    Collection<Item> values() {
        Collection<Item> result = new ArrayList<>(sent.values());
        result.addAll(received.values());
        return result;
    }

    Item applyResponse(Item item) {
        Map<String, Item> target = item.sent ? sent : received;
        Item merged = merge(target.get(item.id), item);
        target.put(item.id, merged);
        return merged;
    }

    void replaceSnapshot(boolean isSent, Map<String, Item> next) {
        Map<String, Item> target = isSent ? sent : received;
        Map<String, Item> merged = new HashMap<>();
        for (Item item : next.values()) merged.put(item.id, merge(target.get(item.id), item));
        target.clear();
        target.putAll(merged);
    }

    void clear() {
        sent.clear();
        received.clear();
    }

    private static Item merge(Item previous, Item incoming) {
        // 요청 ID는 재사용되지 않고, 서버는 종결 상태에서 PENDING으로 되돌리지 않는다.
        // 지연된 Callable 응답과 캐시 스냅샷 모두 같은 규칙으로 막는다.
        if (previous != null && previous.status() != Status.PENDING
                && incoming.status() == Status.PENDING) return previous;
        return incoming;
    }
}
