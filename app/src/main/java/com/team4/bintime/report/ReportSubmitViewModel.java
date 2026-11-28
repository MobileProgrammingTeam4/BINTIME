package com.team4.bintime.report;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

/**
 * BT-051 신고 제출 상태. 제출 중 화면이 회전해도 결과를 잃지 않고, 제출 중 다시 누르면 무시한다(중복 제출 방지).
 * 실패는 {@link State#consumeError()} 로 한 번만 안내한다.
 */
public class ReportSubmitViewModel extends ViewModel {

    public enum Status { SUBMITTING, DONE, FAILED }

    public static final class State {
        public final Status status;
        public final ReportRepository.Result result;
        public final String message;
        private boolean errorShown;

        State(Status status, ReportRepository.Result result, String message) {
            this.status = status;
            this.result = result;
            this.message = message;
        }

        public boolean consumeError() {
            if (status != Status.FAILED || errorShown) return false;
            errorShown = true;
            return true;
        }
    }

    private final MutableLiveData<State> state = new MutableLiveData<>();

    public LiveData<State> getState() {
        return state;
    }

    public boolean isBusyOrDone() {
        State current = state.getValue();
        return current != null && current.status != Status.FAILED;
    }

    public void submit(ReportRepository repository, ReportForm form) {
        if (isBusyOrDone()) return;
        state.setValue(new State(Status.SUBMITTING, null, null));
        repository.submit(form, new ReportRepository.Callback() {
            @Override
            public void onSuccess(ReportRepository.Result result) {
                state.setValue(new State(Status.DONE, result, null));
            }

            @Override
            public void onError(String message) {
                state.setValue(new State(Status.FAILED, null, message));
            }
        });
    }
}
