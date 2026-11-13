package com.team4.bintime.timetable;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.team4.bintime.data.model.Course;
import com.team4.bintime.data.model.TimeSlot;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;

import java.util.List;

/**
 * [BT-015/016] 시간표 등록 완료 저장을 화면 회전 뒤에도 이어 가기 위한 ViewModel (ProfileSaveViewModel과 같은 방식).
 * <ol>
 *   <li>{@code replaceCourses}: 수업 전체 교체. 같은 트랜잭션에서 새 수업과 겹치는 공강 칸을 지우고
 *       availability를 다시 계산한다.</li>
 *   <li>{@code saveFreeSlots}: 이 화면에서 고른 공강 선택을 저장하고 availability를 갱신한다.</li>
 * </ol>
 * 두 번째 단계가 실패해도 수업은 저장되어 있고 availability는 남은 선택 기준으로 최신이다. 화면은 오류를 안내하고
 * 다시 시도하게 한다. 다른 기기와 겹쳐 CONFLICT가 나면 최신 상태로 한 번 자동 재시도한다(전체 교체라 안전).
 */
public class TimetableSaveViewModel extends ViewModel {

    public static final class Result {
        /** 성공이면 null. */
        public final TimetableFailure failure;
        private boolean consumed;

        Result(TimetableFailure failure) {
            this.failure = failure;
        }

        public boolean isSuccess() {
            return failure == null;
        }

        /** 처음 부르면 true, 이미 처리한 결과면 false. */
        public boolean consume() {
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    private final MutableLiveData<Result> result = new MutableLiveData<>();
    private boolean saving;

    public LiveData<Result> getResult() {
        return result;
    }

    public boolean isSaving() {
        return saving;
    }

    /** 저장을 시작한다. 이미 저장 중이면 무시한다(중복 클릭 방지). 메인 스레드에서 호출한다. */
    void save(AsyncTimetableRepository repository, List<Course> courses, List<TimeSlot> freeSlots) {
        if (saving) return;
        saving = true;
        replace(repository, courses, freeSlots, true);
    }

    private void replace(AsyncTimetableRepository repository, List<Course> courses, List<TimeSlot> freeSlots,
                         boolean retry) {
        repository.replaceCourses(courses, new AsyncTimetableRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void ignored) {
                saveFree(repository, freeSlots, true);
            }

            @Override
            public void onError(TimetableFailure failure) {
                if (retry && failure.getCode() == TimetableFailure.Code.CONFLICT) {
                    replace(repository, courses, freeSlots, false);
                } else {
                    finish(failure);
                }
            }
        });
    }

    private void saveFree(AsyncTimetableRepository repository, List<TimeSlot> freeSlots, boolean retry) {
        repository.saveFreeSlots(freeSlots, new AsyncTimetableRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void ignored) {
                finish(null);
            }

            @Override
            public void onError(TimetableFailure failure) {
                if (retry && failure.getCode() == TimetableFailure.Code.CONFLICT) {
                    saveFree(repository, freeSlots, false);
                } else {
                    finish(failure);
                }
            }
        });
    }

    private void finish(TimetableFailure failure) {
        saving = false;
        result.setValue(new Result(failure));
    }
}
