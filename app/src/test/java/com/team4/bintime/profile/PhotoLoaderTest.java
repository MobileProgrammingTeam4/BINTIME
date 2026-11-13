package com.team4.bintime.profile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/** BT-014 후속(#94 리뷰): 프로필 사진 캐시·같은 경로 요청 합치기·백그라운드 디코딩·업로드 후 무효화. */
public class PhotoLoaderTest {

    /** 실행을 모아 두었다가 직접 흘려보내는 실행기(백그라운드/메인 스레드 흉내). */
    private static final class QueueExecutor implements Executor {
        final List<Runnable> queue = new ArrayList<>();

        @Override public void execute(Runnable command) { queue.add(command); }

        void runAll() { while (!queue.isEmpty()) queue.remove(0).run(); }
    }

    /** 다운로드를 기록만 하고 응답은 테스트가 직접 돌려준다. */
    private static final class FakeFetcher implements PhotoLoader.Fetcher {
        final List<String> paths = new ArrayList<>();
        final List<ProfileRepository.Callback<byte[]>> callbacks = new ArrayList<>();

        @Override public void fetch(String path, ProfileRepository.Callback<byte[]> callback) {
            paths.add(path);
            callbacks.add(callback);
        }
    }

    /** "디코딩" = 바이트를 문자열로. 빈 바이트는 디코딩 실패(null). 무게 = 길이. */
    private static final PhotoLoader.Decoder<String> DECODER = new PhotoLoader.Decoder<String>() {
        @Override public String decode(byte[] bytes) { return bytes.length == 0 ? null : new String(bytes); }

        @Override public long weight(String value) { return value.length(); }
    };

    private static final class Result implements ProfileRepository.Callback<String> {
        final List<String> values = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override public void onSuccess(String result) { values.add(result); }

        @Override public void onError(String message) { errors.add(message); }

        int calls() { return values.size() + errors.size(); }
    }

    private final AtomicLong now = new AtomicLong(0);
    private FakeFetcher fetcher;
    private QueueExecutor background;
    private QueueExecutor main;
    private PhotoLoader<String> loader;

    @Before
    public void setUp() {
        fetcher = new FakeFetcher();
        background = new QueueExecutor();
        main = new QueueExecutor();
        loader = new PhotoLoader<>(fetcher, DECODER, new PhotoMemoryCache<>(100, 1000, now::get), background, main);
    }

    private void respond(int index, String body) {
        fetcher.callbacks.get(index).onSuccess(body.getBytes());
    }

    @Test
    public void decodesInBackground_thenDeliversOnMain() {
        Result r = new Result();
        loader.load("p/a.jpg", r);
        respond(0, "A");

        assertEquals(0, r.calls());            // 아직 디코딩 전
        assertEquals(1, background.queue.size());
        background.runAll();
        assertEquals(0, r.calls());            // 디코딩은 끝났지만 메인 전달 전
        main.runAll();
        assertEquals(List.of("A"), r.values);
    }

    @Test
    public void cacheHit_skipsDownload() {
        Result first = new Result();
        loader.load("p/a.jpg", first);
        respond(0, "A");
        background.runAll();
        main.runAll();

        Result second = new Result();
        loader.load("p/a.jpg", second);
        assertEquals(1, fetcher.paths.size()); // 다시 받지 않음
        assertEquals(List.of("A"), second.values);
    }

    @Test
    public void concurrentRequestsForSamePath_shareOneDownload() {
        Result a = new Result();
        Result b = new Result();
        Result c = new Result();
        loader.load("p/a.jpg", a);
        loader.load("p/a.jpg", b);
        loader.load("p/other.jpg", c);
        assertEquals(List.of("p/a.jpg", "p/other.jpg"), fetcher.paths);

        respond(0, "A");
        background.runAll();
        main.runAll();
        assertEquals(List.of("A"), a.values);
        assertEquals(List.of("A"), b.values);
        assertEquals(0, c.calls());            // 다른 경로는 아직
    }

    @Test
    public void downloadError_reachesAllWaitersOnce_andIsNotCached() {
        Result a = new Result();
        Result b = new Result();
        loader.load("p/a.jpg", a);
        loader.load("p/a.jpg", b);
        fetcher.callbacks.get(0).onError("네트워크 연결을 확인해 주세요.");
        main.runAll();
        assertEquals(List.of("네트워크 연결을 확인해 주세요."), a.errors);
        assertEquals(1, b.calls());

        loader.load("p/a.jpg", new Result());
        assertEquals(2, fetcher.paths.size()); // 실패는 캐시하지 않아 다시 받는다
    }

    @Test
    public void decodeFailure_reportsError() {
        Result r = new Result();
        loader.load("p/a.jpg", r);
        fetcher.callbacks.get(0).onSuccess(new byte[0]);
        background.runAll();
        main.runAll();
        assertEquals(List.of(PhotoLoader.DECODE_FAILED), r.errors);
    }

    @Test
    public void invalidate_dropsCachedPhoto() {
        loader.load("p/me.jpg", new Result());
        respond(0, "OLD");
        background.runAll();
        main.runAll();

        loader.invalidate("p/me.jpg");        // 내 사진 업로드 완료
        Result r = new Result();
        loader.load("p/me.jpg", r);
        assertEquals(2, fetcher.paths.size()); // 새로 받는다
        respond(1, "NEW");
        background.runAll();
        main.runAll();
        assertEquals(List.of("NEW"), r.values);
    }

    /**
     * #95 리뷰(D): 이전 다운로드 진행 → 무효화(업로드 완료) → 새 조회 → 이전 다운로드가 먼저 끝남.
     * 새 조회는 이전 다운로드에 합쳐지지 않고 새 사진을 정확히 한 번 받아야 한다.
     */
    @Test
    public void loadAfterInvalidate_doesNotJoinOldDownload_oldFinishesFirst() {
        Result before = new Result();
        Result after = new Result();
        loader.load("p/me.jpg", before);
        loader.invalidate("p/me.jpg");
        loader.load("p/me.jpg", after);
        assertEquals(2, fetcher.paths.size());  // 무효화 뒤 조회는 새로 받는다

        respond(0, "OLD");                      // 이전 다운로드 먼저 완료
        background.runAll();
        main.runAll();
        assertEquals(List.of("OLD"), before.values);
        assertEquals(0, after.calls());         // 새 조회는 아직 이전 사진을 받지 않음

        respond(1, "NEW");
        background.runAll();
        main.runAll();
        assertEquals(List.of("NEW"), after.values);
        assertEquals(1, before.calls());        // 각 콜백 정확히 한 번

        Result cached = new Result();
        loader.load("p/me.jpg", cached);
        assertEquals(2, fetcher.paths.size());  // 캐시 적중
        assertEquals(List.of("NEW"), cached.values);
    }

    /** 같은 경합에서 새 다운로드가 먼저 끝나고 이전 다운로드가 늦게 끝나도 캐시·새 조회는 새 사진을 유지한다. */
    @Test
    public void loadAfterInvalidate_newFinishesFirst_lateOldDoesNotOverwrite() {
        Result before = new Result();
        Result after = new Result();
        loader.load("p/me.jpg", before);
        loader.invalidate("p/me.jpg");
        loader.load("p/me.jpg", after);

        respond(1, "NEW");                      // 새 다운로드 먼저 완료
        background.runAll();
        main.runAll();
        assertEquals(List.of("NEW"), after.values);
        assertEquals(0, before.calls());

        Result joinedAfterNew = new Result();   // 새 다운로드가 끝난 뒤 조회 → 캐시에서 NEW
        loader.load("p/me.jpg", joinedAfterNew);
        assertEquals(List.of("NEW"), joinedAfterNew.values);

        respond(0, "OLD");                      // 이전 다운로드가 늦게 완료
        background.runAll();
        main.runAll();
        assertEquals(List.of("OLD"), before.values); // 이전 요청은 자기 결과만 받음
        assertEquals(1, after.calls());

        Result cached = new Result();
        loader.load("p/me.jpg", cached);
        assertEquals(List.of("NEW"), cached.values); // 늦은 OLD 가 캐시를 덮지 않음
        assertEquals(2, fetcher.paths.size());
    }

    /** 무효화 뒤 같은 세대의 요청끼리는 여전히 다운로드 1번으로 합친다. */
    @Test
    public void requestsAfterInvalidate_stillShareNewDownload() {
        loader.load("p/me.jpg", new Result());
        loader.invalidate("p/me.jpg");
        Result a = new Result();
        Result b = new Result();
        loader.load("p/me.jpg", a);
        loader.load("p/me.jpg", b);
        assertEquals(2, fetcher.paths.size());
        respond(1, "NEW");
        background.runAll();
        main.runAll();
        assertEquals(List.of("NEW"), a.values);
        assertEquals(List.of("NEW"), b.values);
    }

    /** 이전 사진을 받는 도중 업로드가 끝나면, 늦게 끝난 이전 사진을 캐시에 넣지 않는다. */
    @Test
    public void invalidateDuringDownload_doesNotCacheStaleResult() {
        Result r = new Result();
        loader.load("p/me.jpg", r);
        loader.invalidate("p/me.jpg");
        respond(0, "OLD");
        background.runAll();
        main.runAll();
        assertEquals(List.of("OLD"), r.values); // 기다리던 화면에는 전달

        loader.load("p/me.jpg", new Result());
        assertEquals(2, fetcher.paths.size());   // 캐시에 없어서 다시 받음
    }

    @Test
    public void expiredEntry_isDownloadedAgain() {
        loader.load("p/a.jpg", new Result());
        respond(0, "A");
        background.runAll();
        main.runAll();

        now.set(999);
        loader.load("p/a.jpg", new Result());
        assertEquals(1, fetcher.paths.size()); // 유효 시간 안
        now.set(1000);
        loader.load("p/a.jpg", new Result());
        assertEquals(2, fetcher.paths.size()); // 지나면 다시 받음
    }

    // ---- PhotoMemoryCache ----

    @Test
    public void cache_evictsLeastRecentlyUsedOverCapacity() {
        PhotoMemoryCache<String> cache = new PhotoMemoryCache<>(10, 1000, now::get);
        cache.put("a", "aaaa", 4);
        cache.put("b", "bbbb", 4);
        cache.get("a");                        // a 를 최근 사용으로
        cache.put("c", "cccc", 4);             // 12 > 10 → 가장 오래 안 쓴 b 제거
        assertEquals("aaaa", cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals("cccc", cache.get("c"));
        assertEquals(8, cache.totalWeight());
    }

    @Test
    public void cache_ignoresTooHeavyOrInvalidEntries_andOverwriteKeepsWeight() {
        PhotoMemoryCache<String> cache = new PhotoMemoryCache<>(10, 1000, now::get);
        cache.put("big", "x", 11);
        cache.put(null, "x", 1);
        cache.put("zero", "x", 0);
        assertEquals(0, cache.size());

        cache.put("k", "v1", 3);
        cache.put("k", "v2", 5);
        assertEquals(1, cache.size());
        assertEquals(5, cache.totalWeight());
        cache.remove("k");
        assertEquals(0, cache.totalWeight());
        assertTrue(cache.size() == 0);
    }
}
