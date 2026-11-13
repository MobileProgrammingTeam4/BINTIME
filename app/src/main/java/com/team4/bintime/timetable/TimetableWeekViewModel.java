package com.team4.bintime.timetable;

import androidx.lifecycle.ViewModel;

import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.FreeSlotSaveQueues;
import com.team4.bintime.data.repository.LatestValueSaveQueue;
import com.team4.bintime.data.repository.TimetableFailure;

import java.util.function.Supplier;

/**
 * [BT-015/016] 주간 탭 공강 선택 저장 큐를 화면 View·회전과 분리해 들고 있는다.
 * 값은 요일별 칸 마스크(long[7] 사본)이고, 저장은 {@code saveFreeSlots} 하나씩 직렬로 보낸다
 * ({@link LatestValueSaveQueue}). onCleared에서도 진행 중·대기 중 저장은 취소하지 않는다
 * (저장소는 앱 전역 인스턴스라 콜백이 끝까지 돈다).
 * <p>
 * [PR #88 D 리뷰 P1] 한 인스턴스는 소유 UID 하나에만 묶인다. Fragment는 {@link #key(String)}로 UID별 인스턴스를 받아,
 * 새 계정이 이전 계정의 대기 선택·dirty 상태를 물려받지 않는다. 큐는 저장 요청 직전마다 현재 UID를 다시 읽어
 * 소유 UID와 다르거나 로그아웃(null)이면 대기 선택을 버린다. 회전(같은 UID)은 그대로 저장을 이어간다.
 */
public class TimetableWeekViewModel extends ViewModel {

    private static final String KEY_PREFIX = TimetableWeekViewModel.class.getName() + ":";

    private LatestValueSaveQueue<long[], TimetableFailure> queue;
    private String ownerUid;

    /** UID별 ViewModelProvider 키. 로그아웃 상태(null)도 별도 키를 쓴다. */
    static String key(String uid) {
        return KEY_PREFIX + (uid == null ? "signed-out" : "uid:" + uid);
    }

    /**
     * 메인 스레드에서 호출한다. 처음 부를 때 소유 UID와 저장소를 묶는다.
     *
     * @param ownerUid   {@link #key(String)}에 쓴 UID와 같아야 한다
     * @param currentUid {@code AuthRepositories.get(context).getCurrentUid()} 공급자
     */
    LatestValueSaveQueue<long[], TimetableFailure> freeSlotQueue(String ownerUid, Supplier<String> currentUid,
                                                                 AsyncTimetableRepository repository) {
        if (queue == null) {
            this.ownerUid = ownerUid;
            queue = FreeSlotSaveQueues.create(ownerUid, currentUid, repository);
        } else if (ownerUid == null ? this.ownerUid != null : !ownerUid.equals(this.ownerUid)) {
            throw new IllegalStateException("ViewModel은 소유 UID별로 key(uid)로 받아야 한다");
        }
        return queue;
    }

    @Override
    protected void onCleared() {
        if (queue != null) queue.setListener(null); // 저장은 계속, 화면 통지만 끊는다
        super.onCleared();
    }
}
