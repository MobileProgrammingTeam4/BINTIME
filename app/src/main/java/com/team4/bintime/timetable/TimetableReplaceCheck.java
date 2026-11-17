package com.team4.bintime.timetable;

import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;

/**
 * [BT-026 결정 0-3] 새 시간표로 교체하기 전 확인 판단. 기존 수업 수는 서버에서 읽은 값만 쓴다
 * ({@link AsyncTimetableRepository#countCoursesFromServer}). 캐시의 0개를 믿고 확인 없이 지우지 않도록,
 * 서버 값을 얻지 못하면 수를 뺀 확인을 띄운다.
 */
final class TimetableReplaceCheck {

    enum Kind {
        /** 서버에 기존 수업이 없다. 확인 없이 저장한다. */
        SAVE,
        /** 기존 수업 {@link Decision#existingCount}개가 지워진다고 확인받는다. */
        CONFIRM_WITH_COUNT,
        /** 서버 값을 얻지 못했다. 수를 빼고 확인받는다. */
        CONFIRM_UNKNOWN
    }

    static final class Decision {
        final Kind kind;
        /** CONFIRM_WITH_COUNT 일 때만 의미가 있다. */
        final int existingCount;

        private Decision(Kind kind, int existingCount) {
            this.kind = kind;
            this.existingCount = existingCount;
        }
    }

    interface Receiver {
        void onDecision(Decision decision);
    }

    private TimetableReplaceCheck() {
    }

    static Decision decide(Integer serverCount, TimetableFailure failure) {
        if (failure != null || serverCount == null || serverCount < 0) {
            return new Decision(Kind.CONFIRM_UNKNOWN, 0);
        }
        return serverCount == 0 ? new Decision(Kind.SAVE, 0) : new Decision(Kind.CONFIRM_WITH_COUNT, serverCount);
    }

    static void run(AsyncTimetableRepository repository, Receiver receiver) {
        repository.countCoursesFromServer(new AsyncTimetableRepository.Callback<Integer>() {
            @Override
            public void onSuccess(Integer count) {
                receiver.onDecision(decide(count, null));
            }

            @Override
            public void onError(TimetableFailure failure) {
                receiver.onDecision(decide(null, failure));
            }
        });
    }
}
