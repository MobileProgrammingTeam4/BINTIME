package com.team4.bintime.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.team4.bintime.data.model.Timetable;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * [BT-015/016] PR #88 D 리뷰 P1: 저장 대기 중 로그아웃·계정 변경 시 이전 계정의 선택이 새 계정에 저장되지 않는다.
 */
public class OwnerUidSaveQueueTest {

    /** 요청을 기록만 하고 테스트가 직접 완료시키는 저장기. 요청을 보낸 시점의 UID도 기록한다. */
    private final class ManualSaver implements LatestValueSaveQueue.Saver<String, String> {
        final List<String> values = new ArrayList<>();
        final List<String> uidsAtStart = new ArrayList<>();
        final List<LatestValueSaveQueue.Completion<String>> pending = new ArrayList<>();

        @Override
        public void save(String value, LatestValueSaveQueue.Completion<String> completion) {
            values.add(value);
            uidsAtStart.add(currentUid);
            pending.add(completion);
        }

        void succeed() {
            pending.remove(0).onSuccess();
        }
    }

    private String currentUid;
    private ManualSaver saver;
    private LatestValueSaveQueue<String, String> queue;
    private int discarded;
    private int allSaved;

    @Before
    public void setUp() {
        currentUid = "uid-a";
        saver = new ManualSaver();
        queue = new LatestValueSaveQueue<>(saver, new OwnerUidGuard("uid-a", () -> currentUid));
        queue.setListener(new LatestValueSaveQueue.Listener<String>() {
            @Override
            public void onAllSaved() {
                allSaved++;
            }

            @Override
            public void onSaveFailed(String failure) {
            }

            @Override
            public void onDiscarded() {
                discarded++;
            }
        });
    }

    /** A 저장 중 선택 B가 대기 → 다른 계정 로그인 → 첫 저장 완료. */
    @Test
    public void pendingValueIsNotSavedAfterAccountChange() {
        queue.update("A1");
        queue.flush();
        queue.update("A2");
        queue.flush(); // 저장 중 → 대기

        currentUid = "uid-b";
        saver.succeed(); // A1 완료(시작 시점 UID = uid-a로 이미 나간 요청)

        assertEquals(List.of("A1"), saver.values);
        assertEquals(List.of("uid-a"), saver.uidsAtStart);
        assertFalse(queue.isSaving());
        assertFalse(queue.needsSave());
        assertTrue(queue.isClosed());
        assertNull(queue.latest());
        assertEquals(1, discarded);
        assertEquals(0, allSaved);

        // 닫힌 큐는 이후 변경·flush도 보내지 않는다
        queue.update("A3");
        queue.flush();
        assertEquals(List.of("A1"), saver.values);
        assertFalse(queue.needsSave());
    }

    @Test
    public void pendingValueIsDiscardedAfterSignOut() {
        queue.update("A1");
        queue.flush();
        queue.update("A2");

        currentUid = null; // 로그아웃
        saver.succeed();

        assertEquals(List.of("A1"), saver.values);
        assertTrue(queue.isClosed());
        assertFalse(queue.needsSave());
        assertEquals(1, discarded);
    }

    @Test
    public void flushAfterSignOutWithoutSaveInProgressSendsNothing() {
        queue.update("A1");
        currentUid = null; // 디바운스 대기 중 로그아웃
        queue.flush();
        assertTrue(saver.values.isEmpty());
        assertTrue(queue.isClosed());
    }

    /** 회전·Fragment 재생성: UID가 같으면 리스너가 바뀌어도 대기 값을 정상 저장한다. */
    @Test
    public void pendingValueIsSavedWhenUidUnchanged() {
        queue.update("A1");
        queue.flush();
        queue.update("A2");
        queue.flush();
        queue.setListener(null); // 이전 Fragment 소멸
        int[] savedAfterRotation = {0};
        queue.setListener(new LatestValueSaveQueue.Listener<String>() { // 새 Fragment
            @Override
            public void onAllSaved() {
                savedAfterRotation[0]++;
            }

            @Override
            public void onSaveFailed(String failure) {
            }
        });

        saver.succeed();
        assertEquals(List.of("A1", "A2"), saver.values);
        assertEquals(List.of("uid-a", "uid-a"), saver.uidsAtStart);
        saver.succeed();
        assertFalse(queue.needsSave());
        assertFalse(queue.isClosed());
        assertEquals(1, savedAfterRotation[0]);
        assertEquals(0, discarded);
    }

    @Test
    public void nullOwnerNeverSaves() {
        LatestValueSaveQueue<String, String> signedOut =
                new LatestValueSaveQueue<>(saver, new OwnerUidGuard(null, () -> null));
        signedOut.update("X");
        signedOut.flush();
        assertTrue(saver.values.isEmpty());
        assertTrue(signedOut.isClosed());
    }

    /** 새 소유 UID의 큐는 이전 큐의 대기 값을 물려받지 않고, 이전 큐도 새 계정에 저장하지 않는다. */
    @Test
    public void newOwnerQueueDoesNotInheritPreviousPendingValue() {
        MockTimetableRepositoryImpl.getInstance().resetToMockData();
        MockAsyncTimetableRepository repo =
                new MockAsyncTimetableRepository(MockTimetableRepositoryImpl.getInstance(), () -> currentUid);
        long[] monday9to11 = new long[7];
        for (int cell = 18; cell < 22; cell++) monday9to11[0] |= 1L << cell; // 09:00~11:00, 자정 기준 칸 번호

        LatestValueSaveQueue<long[], TimetableFailure> queueA =
                FreeSlotSaveQueues.create("uid-a", () -> currentUid, repo);
        queueA.update(monday9to11.clone()); // 디바운스 대기 중

        currentUid = "uid-b";
        LatestValueSaveQueue<long[], TimetableFailure> queueB =
                FreeSlotSaveQueues.create("uid-b", () -> currentUid, repo);
        assertFalse(queueB.needsSave());
        assertNull(queueB.latest());
        queueB.flush();

        queueA.flush(); // 이전 Fragment의 onStop·onDestroyView flush
        assertTrue(queueA.isClosed());
        assertTrue(freeSlotsOf(repo).isEmpty());
        assertNull(repo.getAvailability("uid-b"));
        assertNull(repo.getAvailability("uid-a"));

        // B 자신의 선택은 정상 저장
        long[] tuesday = new long[7];
        tuesday[1] = monday9to11[0];
        queueB.update(tuesday.clone());
        queueB.flush();
        assertFalse(queueB.needsSave());
        assertEquals(1, freeSlotsOf(repo).size());
        assertEquals(2, freeSlotsOf(repo).get(0).getDayOfWeek());
    }

    private static List<com.team4.bintime.data.model.TimeSlot> freeSlotsOf(MockAsyncTimetableRepository repo) {
        Timetable[] holder = new Timetable[1];
        repo.observeTimetable(new AsyncTimetableRepository.Listener<Timetable>() {
            @Override
            public void onData(Timetable data) {
                holder[0] = data;
            }

            @Override
            public void onError(TimetableFailure failure) {
                throw new AssertionError(failure.getCode());
            }
        }).remove();
        return holder[0] == null ? new ArrayList<>() : holder[0].getFreeSlots(); // null: 아무것도 저장 안 됨
    }
}
