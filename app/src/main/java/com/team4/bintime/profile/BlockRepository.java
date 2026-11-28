package com.team4.bintime.profile;

import java.util.List;

/**
 * BT-046 사용자 차단·해제 (docs/BT-046.md).
 * 차단·해제는 서버 Callable(blockUser/unblockUser)만 한다. 서버가 대기 요청 정리와 채팅 전송 차단을 함께 처리하고,
 * 차단하는 사람은 로그인 토큰의 UID 로 정한다. 앱은 본인 차단 목록(users/{uid}/blockedUsers)을 읽기만 한다.
 * <p>
 * 콜백은 onSuccess/onError 중 하나가 정확히 한 번, 메인 스레드로 온다. 취소 API 가 없으므로 화면이 닫힌 뒤 온 결과는
 * 호출한 쪽에서 무시한다.
 */
public interface BlockRepository {

    interface Callback<T> {
        void onSuccess(T result);
        void onError(String message);
    }

    /** 최근에 차단한 순. 상대 공개 프로필을 함께 읽는다. */
    void getBlockedUsers(Callback<List<BlockedUser>> callback);

    /** 같은 상대를 다시 차단해도 성공(서버 멱등). */
    void block(String targetUid, Callback<Void> callback);

    /** 차단한 적 없는 상대를 해제해도 성공. */
    void unblock(String targetUid, Callback<Void> callback);
}
