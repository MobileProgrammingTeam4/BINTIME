package com.team4.bintime.timetable;

import com.team4.bintime.R;
import com.team4.bintime.data.repository.AsyncTimetableRepository;
import com.team4.bintime.data.repository.TimetableFailure;

/**
 * [BT-026 결정 0-3] 새 시간표로 교체하기 전 확인 판단. 기존 수업 수는 서버에서 읽은 값만 쓴다
 * ({@link AsyncTimetableRepository#countCoursesFromServer}). 캐시의 0개를 믿고 확인 없이 지우지 않도록,
 * 서버 값을 얻지 못하면 수를 뺀 확인을 띄운다.
 * <p>
 * [BT-026 후속, B 제안 ①] 읽기 실패 확인은 "수를 확인하지 못했다"는 별도 문구와 [다시 확인]을 갖는다.
 * 다시 확인은 {@link #check}를 다시 부르는 것이며, 결과는 처음 확인과 같은 규칙으로 나뉜다.
 * 읽기 실패는 어떤 경우에도 {@link Screen#saveWithoutConfirm()}으로 이어지지 않는다.
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

    /** 판단 결과를 받는 화면. 셋 중 하나만 한 번 불린다. */
    interface Screen {
        /** 서버에서 기존 수업 0개를 확인했다. 확인 없이 저장한다. */
        void saveWithoutConfirm();

        /** 서버에서 기존 수업 {@code count}개를 확인했다. 수를 보여 주고 확인받는다. */
        void confirmWithCount(int count);

        /** 서버 값을 얻지 못했다. 확인하지 못했다는 문구와 [다시 확인]으로 확인받는다. 저장하지 않는다. */
        void confirmUnknown();
    }

    private TimetableReplaceCheck() {
    }

    static Decision decide(Integer serverCount, TimetableFailure failure) {
        if (failure != null || serverCount == null || serverCount < 0) {
            return new Decision(Kind.CONFIRM_UNKNOWN, 0);
        }
        return serverCount == 0 ? new Decision(Kind.SAVE, 0) : new Decision(Kind.CONFIRM_WITH_COUNT, serverCount);
    }

    /** 확인 창 제목. 읽기 실패는 수를 아는 경우와 다른 제목을 쓴다. SAVE 는 창이 없어 0. */
    static int titleRes(Kind kind) {
        switch (kind) {
            case CONFIRM_WITH_COUNT:
                return R.string.timetable_replace_title;
            case CONFIRM_UNKNOWN:
                return R.string.timetable_replace_unknown_title;
            default:
                return 0;
        }
    }

    /** 확인 창 본문. CONFIRM_WITH_COUNT 는 수(%1$d)를 넣는다. SAVE 는 창이 없어 0. */
    static int messageRes(Kind kind) {
        switch (kind) {
            case CONFIRM_WITH_COUNT:
                return R.string.timetable_replace_message;
            case CONFIRM_UNKNOWN:
                return R.string.timetable_replace_message_unknown;
            default:
                return 0;
        }
    }

    /** [다시 확인]은 서버 값을 얻지 못했을 때만 둔다. */
    static boolean canRetry(Kind kind) {
        return kind == Kind.CONFIRM_UNKNOWN;
    }

    static void route(Decision decision, Screen screen) {
        switch (decision.kind) {
            case SAVE:
                screen.saveWithoutConfirm();
                break;
            case CONFIRM_WITH_COUNT:
                screen.confirmWithCount(decision.existingCount);
                break;
            default:
                screen.confirmUnknown();
                break;
        }
    }

    /** 서버에서 기존 수업 수를 읽고 화면에 알린다. 처음 확인과 [다시 확인] 모두 이것을 쓴다. */
    static void check(AsyncTimetableRepository repository, Screen screen) {
        run(repository, decision -> route(decision, screen));
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
