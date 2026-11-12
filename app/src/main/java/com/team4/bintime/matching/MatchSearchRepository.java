package com.team4.bintime.matching;

import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;

import java.util.Collections;
import java.util.List;

/**
 * A · BT-017/018 매칭 찾기 데이터 계약. 구현체는 {@link MatchSearchRepositories#get} 으로 고른다.
 * Firebase: matchSettings/{uid} 저장 후 서버 searchMatches 호출, 후보 프로필은 publicProfiles 에서 읽는다.
 */
public interface MatchSearchRepository {

    /** 서버 searchMatches 의 status 와 같은 이름. */
    enum Status { MATCHING_OFF, NO_FREE_SLOT, OK }

    final class Result {
        public final Status status;
        /** OK 일 때 내 기준 공강 표시(예: "오늘(월) 14:10 ~ 16:00"), 아니면 null */
        public final String slotLabel;
        public final List<MatchCandidate> candidates;

        public Result(Status status, String slotLabel, List<MatchCandidate> candidates) {
            this.status = status;
            this.slotLabel = slotLabel;
            this.candidates = candidates;
        }

        public static Result of(Status status) {
            return new Result(status, null, Collections.emptyList());
        }
    }

    interface Callback {
        void onResult(Result result);
        void onError(String message);
    }

    /** 스위치·필터를 서버 기준값으로 저장하고 후보를 검색한다. 콜백은 메인 스레드. */
    void search(boolean matchingOn, MatchFilter filter, Callback callback);
}
