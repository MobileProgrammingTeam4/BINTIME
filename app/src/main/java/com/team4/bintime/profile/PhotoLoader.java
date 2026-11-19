package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 프로필 사진 불러오기 흐름 (BT-014 후속, #94 리뷰). Android·Firebase 의존성이 없어 단위 테스트로 검증한다.
 * <ol>
 *   <li>캐시에 유효한 사진이 있으면 바로 돌려준다(다운로드 없음).</li>
 *   <li>같은 경로를 이미 받는 중이면 새로 받지 않고 그 결과를 함께 받는다(카드 여러 장이 같은 사진을 요청할 때).</li>
 *   <li>받은 바이트는 백그라운드 실행기에서 디코딩하고, 결과는 메인 실행기에서 전달한다(스크롤 끊김 방지).</li>
 *   <li>{@link #invalidate} 뒤에 끝난 이전 다운로드 결과는 캐시에 넣지 않고, 무효화 뒤에 온 요청은 이전 다운로드에
 *       합치지 않고 새로 받는다(내 사진을 같은 경로에 덮어쓴 경우 업로드 후 화면이 이전 사진을 받지 않게).</li>
 * </ol>
 * 콜백은 요청마다 정확히 한 번, 메인 실행기에서 호출된다(캐시 적중은 호출한 스레드에서 즉시).
 */
final class PhotoLoader<V> {

    /** 경로의 바이트를 받는다. 콜백은 한 번만 호출해야 한다. */
    interface Fetcher {
        void fetch(String path, ProfileRepository.Callback<byte[]> callback);
    }

    interface Decoder<V> {
        /** 실패하면 null. 백그라운드 스레드에서 불린다. */
        V decode(byte[] bytes);

        /** 캐시 용량 계산용 무게(바이트). */
        long weight(V value);
    }

    static final String DECODE_FAILED = "사진을 불러오지 못했어요.";

    private final Fetcher fetcher;
    private final Decoder<V> decoder;
    private final PhotoMemoryCache<V> cache;
    private final Executor background;
    private final Executor main;
    private final Object lock = new Object();

    /** 다운로드 1번과 그 결과를 기다리는 콜백들. 시작할 때의 무효화 세대를 기억한다. */
    private final class Batch {
        final int generation;
        final List<ProfileRepository.Callback<V>> callbacks = new ArrayList<>();

        Batch(int generation) {
            this.generation = generation;
        }
    }

    /**
     * 경로 → 지금 세대에서 진행 중인 다운로드. 새 요청은 같은 세대의 다운로드에만 합친다.
     * 무효화 뒤의 요청은 새 Batch 를 만들고, 이전 Batch 는 자기 콜백에만 이전 결과를 전달한다(#95 리뷰).
     */
    private final Map<String, Batch> inFlight = new HashMap<>();
    /** 경로 → 무효화 세대. 다운로드 시작 후 바뀌었으면 결과를 캐시에 넣지 않는다. */
    private final Map<String, Integer> generations = new HashMap<>();

    PhotoLoader(Fetcher fetcher, Decoder<V> decoder, PhotoMemoryCache<V> cache, Executor background, Executor main) {
        this.fetcher = fetcher;
        this.decoder = decoder;
        this.cache = cache;
        this.background = background;
        this.main = main;
    }

    void load(String path, ProfileRepository.Callback<V> callback) {
        V cached = cache.get(path);
        if (cached != null) {
            if (callback != null) callback.onSuccess(cached);
            return;
        }
        final Batch batch;
        synchronized (lock) {
            int generation = generationOf(path);
            Batch running = inFlight.get(path);
            if (running != null && running.generation == generation) {
                running.callbacks.add(callback);
                return;
            }
            // 진행 중인 다운로드가 없거나, 무효화 전에 시작한(이전 사진일 수 있는) 다운로드뿐이면 새로 받는다.
            batch = new Batch(generation);
            batch.callbacks.add(callback);
            inFlight.put(path, batch);
        }
        fetcher.fetch(path, new ProfileRepository.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] bytes) {
                background.execute(() -> {
                    V value = bytes == null ? null : decoder.decode(bytes);
                    main.execute(() -> {
                        if (value == null) {
                            finish(path, batch, null, DECODE_FAILED);
                            return;
                        }
                        synchronized (lock) {
                            if (generationOf(path) == batch.generation) cache.put(path, value, decoder.weight(value));
                        }
                        finish(path, batch, value, null);
                    });
                });
            }

            @Override
            public void onError(String message) {
                main.execute(() -> finish(path, batch, null, message));
            }
        });
    }

    /** 이 경로의 캐시를 지우고, 진행 중인 다운로드 결과도 캐시에 넣지 않게 한다. */
    void invalidate(String path) {
        synchronized (lock) {
            generations.put(path, generationOf(path) + 1);
            cache.remove(path);
        }
    }

    /**
     * BT-030: 로그아웃 시 전부 비운다. 진행 중인 다운로드 결과도 캐시에 넣지 않는다(기다리던 화면에는 전달).
     * 다음 계정이 이전 계정에서 받은 사진을 캐시에서 보지 않게 한다.
     */
    void invalidateAll() {
        synchronized (lock) {
            for (String path : new java.util.ArrayList<>(inFlight.keySet())) {
                generations.put(path, generationOf(path) + 1);
            }
            for (String path : new java.util.ArrayList<>(generations.keySet())) {
                generations.put(path, generationOf(path) + 1);
            }
            cache.clear();
        }
    }

    private int generationOf(String path) {
        Integer g = generations.get(path);
        return g == null ? 0 : g;
    }

    /** 이 다운로드(batch)를 기다린 콜백에만 결과를 준다. 같은 경로의 더 새 다운로드는 건드리지 않는다. */
    private void finish(String path, Batch batch, V value, String error) {
        List<ProfileRepository.Callback<V>> list;
        synchronized (lock) {
            if (inFlight.get(path) == batch) inFlight.remove(path);
            list = new ArrayList<>(batch.callbacks);
            batch.callbacks.clear();
        }
        for (ProfileRepository.Callback<V> cb : list) {
            if (cb == null) continue;
            if (error == null) cb.onSuccess(value);
            else cb.onError(error);
        }
    }
}
