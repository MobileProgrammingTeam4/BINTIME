package com.team4.bintime.timetable;

import androidx.lifecycle.ViewModel;

import com.team4.bintime.data.availability.FreeCells;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.LatestValueSaveQueue;
import com.team4.bintime.data.repository.TimetableFailure;

/**
 * [BT-015/016] 주간 탭 공강 선택 저장 큐를 화면 View·회전과 분리해 들고 있는다.
 * 값은 요일별 칸 마스크(long[7] 사본)이고, 저장은 {@code saveFreeSlots} 하나씩 직렬로 보낸다
 * ({@link LatestValueSaveQueue}). onCleared에서도 진행 중·대기 중 저장은 취소하지 않는다
 * (저장소는 앱 전역 인스턴스라 콜백이 끝까지 돈다).
 */
public class TimetableWeekViewModel extends ViewModel {

    private LatestValueSaveQueue<long[], TimetableFailure> queue;

    /** 메인 스레드에서 호출한다. 처음 부를 때 저장소를 묶는다. */
    LatestValueSaveQueue<long[], TimetableFailure> freeSlotQueue(AsyncTimetableRepository repository) {
        if (queue == null) {
            queue = new LatestValueSaveQueue<>((cells, done) -> repository.saveFreeSlots(FreeCells.toSlots(cells),
                    new AsyncTimetableRepository.Callback<Void>() {
                        @Override
                        public void onSuccess(Void ignored) {
                            done.onSuccess();
                        }

                        @Override
                        public void onError(TimetableFailure failure) {
                            done.onFailure(failure);
                        }
                    }));
        }
        return queue;
    }

    @Override
    protected void onCleared() {
        if (queue != null) queue.setListener(null); // 저장은 계속, 화면 통지만 끊는다
        super.onCleared();
    }
}
