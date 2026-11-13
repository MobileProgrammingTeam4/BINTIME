package com.team4.bintime.data.repository;

import com.team4.bintime.data.availability.FreeCells;

import java.util.function.Supplier;

/**
 * [BT-015/016] 주간 탭 공강 선택 저장 큐를 만든다. 값은 요일별 칸 마스크(long[7] 사본)이고,
 * {@link AsyncTimetableRepository#saveFreeSlots}를 한 번에 하나씩 보낸다({@link LatestValueSaveQueue}).
 * <p>
 * 큐는 소유 UID 하나에 묶인다({@link OwnerUidGuard}). 저장 요청 직전마다 현재 UID가 소유 UID와 같은지 확인하고,
 * 다르거나 로그아웃(null)이면 대기 선택을 버리고 큐를 닫는다. 계정마다 새 큐를 만들어 쓴다.
 */
public final class FreeSlotSaveQueues {

    private FreeSlotSaveQueues() {
    }

    /**
     * @param ownerUid   큐를 만드는 시점의 로그인 UID
     * @param currentUid 지금 로그인 UID 공급자. 앱에서는 {@code AuthRepositories.get(context).getCurrentUid()}
     */
    public static LatestValueSaveQueue<long[], TimetableFailure> create(String ownerUid, Supplier<String> currentUid,
                                                                        AsyncTimetableRepository repository) {
        if (repository == null) throw new IllegalArgumentException("repository");
        return new LatestValueSaveQueue<>((cells, done) -> repository.saveFreeSlots(FreeCells.toSlots(cells),
                new AsyncTimetableRepository.Callback<Void>() {
                    @Override
                    public void onSuccess(Void ignored) {
                        done.onSuccess();
                    }

                    @Override
                    public void onError(TimetableFailure failure) {
                        done.onFailure(failure);
                    }
                }), new OwnerUidGuard(ownerUid, currentUid));
    }
}
