package com.team4.bintime.profile;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 프로필 사진 메모리 캐시 (BT-014 후속). Android 의존성이 없어 단위 테스트로 검증한다.
 * <p>
 * - 용량(무게 합) 상한을 넘으면 가장 오래 안 쓴 항목부터 버린다(LRU).
 * - 사진은 같은 경로({@code profileImages/{uid}/profile.jpg})에 덮어쓰므로, 다른 사용자가 사진을 바꿔도
 *   알 수 없다. 그래서 항목마다 유효 시간을 두고 지나면 다시 받는다. 내 사진은 업로드 직후 {@link #remove} 한다.
 * 스레드 안전(synchronized).
 */
final class PhotoMemoryCache<V> {

    private static final class Entry<V> {
        final V value;
        final long weight;
        final long storedAt;

        Entry(V value, long weight, long storedAt) {
            this.value = value;
            this.weight = weight;
            this.storedAt = storedAt;
        }
    }

    private final long maxWeight;
    private final long ttlMillis;
    private final LongSupplier clock;
    /** accessOrder=true: get 할 때마다 맨 뒤로 이동 → 맨 앞이 가장 오래 안 쓴 항목. */
    private final LinkedHashMap<String, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private long totalWeight;

    PhotoMemoryCache(long maxWeight, long ttlMillis, LongSupplier clock) {
        this.maxWeight = maxWeight;
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    /** 유효한 값이 있으면 돌려주고, 없거나 유효 시간이 지났으면 null(지난 항목은 지운다). */
    synchronized V get(String key) {
        Entry<V> entry = entries.get(key);
        if (entry == null) return null;
        if (clock.getAsLong() - entry.storedAt >= ttlMillis) {
            removeEntry(key);
            return null;
        }
        return entry.value;
    }

    /** 무게가 상한보다 크면 넣지 않는다. 같은 키는 덮어쓴다. */
    synchronized void put(String key, V value, long weight) {
        if (key == null || value == null || weight <= 0 || weight > maxWeight) return;
        removeEntry(key);
        entries.put(key, new Entry<>(value, weight, clock.getAsLong()));
        totalWeight += weight;
        Iterator<Map.Entry<String, Entry<V>>> it = entries.entrySet().iterator();
        while (totalWeight > maxWeight && it.hasNext()) {
            Map.Entry<String, Entry<V>> eldest = it.next();
            totalWeight -= eldest.getValue().weight;
            it.remove();
        }
    }

    synchronized void remove(String key) {
        removeEntry(key);
    }

    synchronized void clear() {
        entries.clear();
        totalWeight = 0;
    }

    synchronized int size() {
        return entries.size();
    }

    synchronized long totalWeight() {
        return totalWeight;
    }

    private void removeEntry(String key) {
        Entry<V> old = entries.remove(key);
        if (old != null) totalWeight -= old.weight;
    }
}
