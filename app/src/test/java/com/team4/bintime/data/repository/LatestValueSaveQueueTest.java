package com.team4.bintime.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** [BT-015/016] PR #88 리뷰 P2: 공강 선택 저장 직렬화. */
public class LatestValueSaveQueueTest {

    /** 요청을 기록만 하고 테스트가 직접 완료시키는 저장기(서버 응답 순서를 통제). */
    private static final class ManualSaver implements LatestValueSaveQueue.Saver<String, String> {
        final List<String> values = new ArrayList<>();
        final List<LatestValueSaveQueue.Completion<String>> pending = new ArrayList<>();
        /** 동시에 진행 중이던 요청의 최대 수. */
        int maxInFlight;

        @Override
        public void save(String value, LatestValueSaveQueue.Completion<String> completion) {
            values.add(value);
            pending.add(completion);
            maxInFlight = Math.max(maxInFlight, pending.size());
        }

        void succeed() {
            pending.remove(0).onSuccess();
        }

        void fail(String failure) {
            pending.remove(0).onFailure(failure);
        }
    }

    private ManualSaver saver;
    private LatestValueSaveQueue<String, String> queue;
    private int allSaved;
    private final List<String> failures = new ArrayList<>();

    @Before
    public void setUp() {
        saver = new ManualSaver();
        queue = new LatestValueSaveQueue<>(saver);
        queue.setListener(new LatestValueSaveQueue.Listener<String>() {
            @Override
            public void onAllSaved() {
                allSaved++;
            }

            @Override
            public void onSaveFailed(String failure) {
                failures.add(failure);
            }
        });
    }

    @Test
    public void flushWithoutChangesDoesNothing() {
        queue.flush();
        assertTrue(saver.values.isEmpty());
        assertFalse(queue.needsSave());
    }

    @Test
    public void updateAloneDoesNotSaveUntilFlush() {
        queue.update("A");
        assertTrue(saver.values.isEmpty());
        assertTrue(queue.needsSave());
        queue.flush();
        assertEquals(List.of("A"), saver.values);
    }

    @Test
    public void changeDuringSaveStartsSecondRequestOnlyAfterFirstCompletes() {
        queue.update("A");
        queue.flush();
        queue.update("B");
        queue.flush(); // 진행 중 → 다시 저장 필요만 표시

        assertEquals(List.of("A"), saver.values);
        assertTrue(queue.isSaving());

        saver.succeed(); // A 완료 → 이제 B
        assertEquals(List.of("A", "B"), saver.values);
        assertEquals(1, saver.maxInFlight);
        assertEquals(0, allSaved);

        saver.succeed();
        assertFalse(queue.needsSave());
        assertFalse(queue.isSaving());
        assertEquals(1, allSaved);
    }

    @Test
    public void changeDuringSaveIsSavedAfterCompletionEvenWithoutSecondFlush() {
        queue.update("A");
        queue.flush();
        queue.update("B"); // 디바운스 대기 중이어도 현재 요청이 끝나면 최신 값으로 저장
        saver.succeed();
        assertEquals(List.of("A", "B"), saver.values);
    }

    @Test
    public void rapidChangesConvergeToLastValue() {
        queue.update("A");
        queue.flush();
        for (String v : new String[]{"B", "C", "D", "E"}) {
            queue.update(v);
            queue.flush();
        }
        saver.succeed();
        // 중간 값(B·C·D)은 건너뛰고 마지막 값 하나만 보낸다
        assertEquals(List.of("A", "E"), saver.values);
        saver.succeed();
        assertEquals("E", saver.values.get(saver.values.size() - 1));
        assertFalse(queue.needsSave());
        assertEquals(1, saver.maxInFlight);
        assertEquals(1, allSaved);
    }

    @Test
    public void failureKeepsNeedsSaveAndRetriesOnNextFlushWithLatest() {
        queue.update("A");
        queue.flush();
        saver.fail("NETWORK");

        assertEquals(List.of("NETWORK"), failures);
        assertTrue(queue.needsSave());
        assertFalse(queue.isSaving());
        assertEquals(1, saver.values.size()); // 자동 반복 재시도 없음
        assertEquals(0, allSaved);

        queue.update("B");
        queue.flush();
        assertEquals(List.of("A", "B"), saver.values);
        saver.succeed();
        assertFalse(queue.needsSave());
        assertEquals(1, allSaved);
    }

    @Test
    public void failureRetriesSameValueOnNextFlush() {
        queue.update("A");
        queue.flush();
        saver.fail("NETWORK");
        queue.flush(); // 탭 이탈 등 다음 저장 시도
        assertEquals(List.of("A", "A"), saver.values);
        saver.succeed();
        assertFalse(queue.needsSave());
    }

    @Test
    public void failureWithNewerChangeRetriesImmediatelyWithLatest() {
        queue.update("A");
        queue.flush();
        queue.update("B");
        saver.fail("NETWORK");
        assertEquals(List.of("A", "B"), saver.values);
        saver.succeed();
        assertFalse(queue.needsSave());
        assertEquals(1, allSaved);
    }

    @Test
    public void staleSuccessOfOlderRequestDoesNotMarkNewerValueSaved() {
        queue.update("A");
        queue.flush();
        queue.update("B");
        saver.succeed(); // A 성공, B 시작
        assertTrue(queue.needsSave());
        saver.fail("NETWORK"); // B 실패
        assertTrue(queue.needsSave());
        assertEquals("B", queue.latest());
    }

    @Test
    public void duplicateCompletionIsIgnored() {
        List<LatestValueSaveQueue.Completion<String>> captured = new ArrayList<>();
        List<String> sent = new ArrayList<>();
        LatestValueSaveQueue<String, String> q = new LatestValueSaveQueue<>((value, done) -> {
            sent.add(value);
            captured.add(done);
        });
        q.update("A");
        q.flush();
        captured.get(0).onSuccess();
        q.update("B");
        q.flush();
        captured.get(0).onSuccess(); // 이전 요청의 중복 완료 → B를 저장된 것으로 보지 않는다
        assertTrue(q.isSaving());
        assertTrue(q.needsSave());
        assertEquals(List.of("A", "B"), sent);
    }

    @Test
    public void synchronousSaverCompletesInline() {
        List<String> sent = new ArrayList<>();
        LatestValueSaveQueue<String, String> q = new LatestValueSaveQueue<>((value, done) -> {
            sent.add(value);
            done.onSuccess();
        });
        q.update("A");
        q.flush();
        q.update("B");
        q.flush();
        assertEquals(List.of("A", "B"), sent);
        assertFalse(q.isSaving());
        assertFalse(q.needsSave());
    }

    @Test
    public void savingContinuesAfterListenerRemoved() {
        queue.update("A");
        queue.flush();
        queue.update("B");
        queue.flush();
        queue.setListener(null); // 화면 종료
        saver.succeed();
        saver.succeed();
        assertEquals(List.of("A", "B"), saver.values);
        assertFalse(queue.needsSave());
        assertEquals(0, allSaved);
    }
}
