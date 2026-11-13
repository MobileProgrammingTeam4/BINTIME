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
 *   <li>{@link #invalidate} 뒤에 끝난 이전 다운로드 결과는 캐시에 넣지 않는다(내 사진을 같은 경로에 덮어쓴 경우).</li>
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
    /** 경로 → 결과를 기다리는 콜백들. */
    private final Map<String, List<ProfileRepository.Callback<V>>> waiting = new HashMap<>();
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
        final int generation;
        synchronized (lock) {
            List<ProfileRepository.Callback<V>> list = waiting.get(path);
            if (list != null) {
                list.add(callback);
                return;
            }
            list = new ArrayList<>();
            list.add(callback);
            waiting.put(path, list);
            generation = generationOf(path);
        }
        fetcher.fetch(path, new ProfileRepository.Callback<byte[]>() {
            @Override
            public void onSuccess(byte[] bytes) {
                background.execute(() -> {
                    V value = bytes == null ? null : decoder.decode(bytes);
                    main.execute(() -> {
                        if (value == null) {
                            finish(path, null, DECODE_FAILED);
                            return;
                        }
                        synchronized (lock) {
                            if (generationOf(path) == generation) cache.put(path, value, decoder.weight(value));
                        }
                        finish(path, value, null);
                    });
                });
            }

            @Override
            public void onError(String message) {
                main.execute(() -> finish(path, null, message));
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

    private int generationOf(String path) {
        Integer g = generations.get(path);
        return g == null ? 0 : g;
    }

    private void finish(String path, V value, String error) {
        List<ProfileRepository.Callback<V>> list;
        synchronized (lock) {
            list = waiting.remove(path);
        }
        if (list == null) return;
        for (ProfileRepository.Callback<V> cb : list) {
            if (cb == null) continue;
            if (error == null) cb.onSuccess(value);
            else cb.onError(error);
        }
    }
}
