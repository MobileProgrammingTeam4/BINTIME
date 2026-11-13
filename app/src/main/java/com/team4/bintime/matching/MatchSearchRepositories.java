package com.team4.bintime.matching;

import android.content.Context;

import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.data.MockMatchRepository;
import com.team4.bintime.model.MatchCandidate;

import java.util.Collections;
import java.util.List;

/** 매칭 찾기 저장소 선택. 인증 저장소와 같은 기준으로 Firebase/Mock 을 고른다. */
public final class MatchSearchRepositories {

    /** 마지막 검색 결과. 프로필 상세가 uid 로 카드를 찾는다. */
    private static List<MatchCandidate> lastCandidates = Collections.emptyList();

    private MatchSearchRepositories() {}

    public static MatchSearchRepository get(Context context) {
        if (AuthRepositories.isFirebaseEnabled(context)) return new FirebaseMatchSearchRepository();
        return (matchingOn, filter, callback) -> {
            if (!matchingOn) callback.onResult(MatchSearchRepository.Result.of(MatchSearchRepository.Status.MATCHING_OFF));
            else if (!MockMatchRepository.hasFreeSlot) callback.onResult(MatchSearchRepository.Result.of(MatchSearchRepository.Status.NO_FREE_SLOT));
            else callback.onResult(new MatchSearchRepository.Result(MatchSearchRepository.Status.OK,
                    MockMatchRepository.MY_SLOT_LABEL, MockMatchRepository.search(filter)));
        };
    }

    static void remember(List<MatchCandidate> candidates) {
        lastCandidates = candidates;
    }

    /**
     * 프로필 상세용 카드 찾기. 없으면 null(상세 화면이 닫힌다).
     * shortcut: 메모리 보관이라 프로세스가 복원되면 상세가 닫힌다, 필요하면 publicProfiles 재조회로 바꾼다.
     */
    public static MatchCandidate find(String uid) {
        for (MatchCandidate c : lastCandidates) if (c.uid.equals(uid)) return c;
        return MockMatchRepository.find(uid);
    }
}
