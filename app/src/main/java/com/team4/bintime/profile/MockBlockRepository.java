package com.team4.bintime.profile;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * BT-046 Mock 차단 저장소(google-services.json 없는 빌드·단위 테스트). 앱 프로세스 동안만 기억한다.
 * 실제 검색 제외·요청 정리·채팅 전송 차단은 서버(blockUser)에서만 일어나므로 Mock 성공을 실제 차단으로 보지 않는다.
 * 콜백은 호출한 스레드에서 바로 온다.
 */
public final class MockBlockRepository implements BlockRepository {

    private static MockBlockRepository shared;

    public static synchronized MockBlockRepository shared(ProfileRepository profiles) {
        if (shared == null) shared = new MockBlockRepository(profiles);
        return shared;
    }

    private final ProfileRepository profiles;
    /** 차단한 순서. 목록은 최근 순으로 뒤집어 보여 준다. */
    private final LinkedHashSet<String> blocked = new LinkedHashSet<>();

    public MockBlockRepository(ProfileRepository profiles) {
        this.profiles = profiles;
    }

    @Override
    public synchronized void getBlockedUsers(Callback<List<BlockedUser>> callback) {
        List<String> uids = new ArrayList<>(blocked);
        List<BlockedUser> result = new ArrayList<>();
        for (int i = uids.size() - 1; i >= 0; i--) {
            String uid = uids.get(i);
            final UserProfile[] found = {null};
            profiles.getPublicProfile(uid, new ProfileRepository.Callback<UserProfile>() {
                @Override public void onSuccess(UserProfile profile) { found[0] = profile; }
                @Override public void onError(String message) { }
            });
            result.add(new BlockedUser(uid, found[0]));
        }
        callback.onSuccess(result);
    }

    @Override
    public synchronized void block(String targetUid, Callback<Void> callback) {
        if (targetUid == null || targetUid.isEmpty()) {
            callback.onError("사용자를 찾을 수 없어요.");
            return;
        }
        blocked.remove(targetUid); // 다시 차단하면 최근 순으로 올린다
        blocked.add(targetUid);
        callback.onSuccess(null);
    }

    @Override
    public synchronized void unblock(String targetUid, Callback<Void> callback) {
        blocked.remove(targetUid);
        callback.onSuccess(null);
    }

    public synchronized boolean isBlocked(String uid) {
        return blocked.contains(uid);
    }
}
