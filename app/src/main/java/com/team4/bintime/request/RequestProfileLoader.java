package com.team4.bintime.request;

import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.profile.UserProfile;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 요청 카드의 공개 프로필 조회. 실패는 명시적으로 재시도하며 화면 재바인딩으로 반복 호출하지 않는다. */
final class RequestProfileLoader {
    interface Source { void load(String uid, ProfileRepository.Callback<UserProfile> callback); }
    private final Source source;
    private final Runnable changed;
    private final Map<String, UserProfile> profiles = new HashMap<>();
    private final Set<String> loading = new HashSet<>(), failed = new HashSet<>();
    private boolean active = true;

    RequestProfileLoader(Source source, Runnable changed) { this.source = source; this.changed = changed; }
    UserProfile get(String uid) { return profiles.get(uid); }
    boolean failed(String uid) { return failed.contains(uid); }

    void load(String uid) {
        if (!active || profiles.containsKey(uid) || failed.contains(uid) || !loading.add(uid)) return;
        source.load(uid, new ProfileRepository.Callback<UserProfile>() {
            @Override public void onSuccess(UserProfile result) { complete(uid, result); }
            @Override public void onError(String message) { complete(uid, null); }
        });
    }

    private void complete(String uid, UserProfile result) {
        if (!active || !loading.remove(uid)) return;
        if (result == null) failed.add(uid);
        else profiles.put(uid, result.copy());
        changed.run();
    }

    void retry(String uid) {
        if (!active || loading.contains(uid)) return;
        failed.remove(uid);
        load(uid);
        changed.run();
    }

    void close() { active = false; profiles.clear(); loading.clear(); failed.clear(); }
}
