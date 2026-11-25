package com.team4.bintime.profile;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.team4.bintime.auth.AuthRepository;

/**
 * BT-045 회원 탈퇴 진행 상태. 서버 처리(최대 2분) 중 화면이 회전해도 결과를 잃지 않게 ViewModel 이 요청을 들고 있다.
 * 진행 중에는 다시 시작하지 않는다(중복 탈퇴 요청 방지). 결과는 {@link State#consume()} 로 한 번만 처리한다.
 */
public class AccountDeletionViewModel extends ViewModel {

    public enum Status { RUNNING, DONE, FAILED }

    public static final class State {
        public final Status status;
        public final String message;
        private boolean consumed;

        State(Status status, String message) {
            this.status = status;
            this.message = message;
        }

        /** 처음 부르면 true. 진행 중 상태는 회전 후에도 다시 보여야 하므로 소비하지 않는다. */
        public boolean consume() {
            if (status == Status.RUNNING) return true;
            if (consumed) return false;
            consumed = true;
            return true;
        }
    }

    private final MutableLiveData<State> state = new MutableLiveData<>();

    public LiveData<State> getState() {
        return state;
    }

    public boolean isRunning() {
        State current = state.getValue();
        return current != null && current.status == Status.RUNNING;
    }

    /** 메인 스레드에서 호출한다. */
    public void delete(AuthRepository repository) {
        if (isRunning()) return;
        state.setValue(new State(Status.RUNNING, null));
        repository.deleteAccount(new AuthRepository.Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                state.setValue(new State(Status.DONE, null));
            }

            @Override
            public void onError(String message) {
                state.setValue(new State(Status.FAILED, message));
            }
        });
    }
}
