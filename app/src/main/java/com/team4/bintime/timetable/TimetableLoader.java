package com.team4.bintime.timetable;

import com.team4.bintime.data.model.Timetable;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;

/**
 * [BT-015] 시간표를 한 번만 읽는다. {@code observeTimetable}의 첫 값을 받으면 구독을 해제한다.
 * Mock은 등록 중에 바로 값을 주므로 등록이 끝난 뒤에도 해제한다. 화면은 돌려받은 Registration을
 * onDestroy에서 해제해 종료된 화면에 결과가 전달되지 않게 한다.
 */
final class TimetableLoader {

    private TimetableLoader() {
    }

    static AsyncTimetableRepository.Registration loadOnce(AsyncTimetableRepository repository,
                                                          AsyncTimetableRepository.Callback<Timetable> callback) {
        final boolean[] done = {false};
        final AsyncTimetableRepository.Registration[] holder = {null};
        AsyncTimetableRepository.Registration registration = repository.observeTimetable(
                new AsyncTimetableRepository.Listener<Timetable>() {
                    @Override
                    public void onData(Timetable data) {
                        if (done[0]) return;
                        done[0] = true;
                        if (holder[0] != null) holder[0].remove();
                        callback.onSuccess(data);
                    }

                    @Override
                    public void onError(TimetableFailure failure) {
                        if (done[0]) return;
                        done[0] = true;
                        if (holder[0] != null) holder[0].remove();
                        callback.onError(failure);
                    }
                });
        if (done[0]) registration.remove();
        else holder[0] = registration;
        return () -> {
            done[0] = true;
            registration.remove();
        };
    }
}
