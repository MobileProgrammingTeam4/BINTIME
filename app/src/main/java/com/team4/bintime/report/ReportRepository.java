package com.team4.bintime.report;

/**
 * BT-051 신고 접수 (docs/BT-051.md). 서버 submitReport 가 저장하고, 신고 기록은 일반 사용자에게 공개하지 않는다.
 * 신고자는 서버가 로그인 토큰으로 정한다. 콜백은 정확히 한 번, 메인 스레드로 온다.
 */
public interface ReportRepository {

    /** 접수 결과. 이미 신고한 경우도 사용자가 할 일이 없으므로 결과로 돌려준다. */
    final class Result {
        public final boolean alreadyReported;
        /** 함께 차단을 골랐고 실제로 차단됐는지. */
        public final boolean blocked;

        public Result(boolean alreadyReported, boolean blocked) {
            this.alreadyReported = alreadyReported;
            this.blocked = blocked;
        }
    }

    interface Callback {
        void onSuccess(Result result);
        void onError(String message);
    }

    void submit(ReportForm form, Callback callback);
}
