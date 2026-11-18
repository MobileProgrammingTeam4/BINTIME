package com.team4.bintime.chat;

import androidx.annotation.Nullable;

import com.team4.bintime.profile.ProfileRepository;
import com.team4.bintime.profile.UserProfile;

/**
 * 상대 공개 프로필(publicProfiles/{uid})을 B 의 ProfileRepository.getPublicProfile 로 한 번 읽는다 (BT-034, #91).
 * 실시간 구독이 아니라 구독 시작마다 한 번 조회한다. 닉네임 변경은 목록·방을 다시 열 때 반영된다.
 * 해제 뒤 늦게 온 결과와 같은 조회의 중복 콜백은 버린다. 실패하면 onError → 화면은 기본 이름("상대")을 쓴다.
 * B 코드는 수정하지 않고 호출만 한다. Firebase 에 의존하지 않아 JVM 단위 테스트로 검증한다.
 */
final class ProfilePartnerSource implements PartnerSource {

    interface Fetcher {
        void load(String uid, ProfileRepository.Callback<UserProfile> callback);
    }

    private final Fetcher fetcher;
    private final String fallbackName;

    ProfilePartnerSource(Fetcher fetcher, String fallbackName) {
        this.fetcher = fetcher;
        this.fallbackName = fallbackName;
    }

    @Override
    public ChatRepository.Registration observe(String uid, ChatRepository.Listener<PartnerInfo> listener) {
        boolean[] active = {true};
        boolean[] done = {false};
        fetcher.load(uid, new ProfileRepository.Callback<UserProfile>() {
            @Override
            public void onSuccess(UserProfile result) {
                if (!active[0] || done[0]) return;
                done[0] = true;
                listener.onData(toPartner(uid, result, fallbackName));
            }

            @Override
            public void onError(String message) {
                if (!active[0] || done[0]) return;
                done[0] = true;
                listener.onError();
            }
        });
        return () -> active[0] = false;
    }

    /** 프로필이 없으면 null(화면은 기본 이름). 이메일 등 비공개 필드는 쓰지 않는다. */
    @Nullable
    static PartnerInfo toPartner(String uid, @Nullable UserProfile profile, String fallbackName) {
        if (profile == null) return null;
        String name = trimmed(profile.getNickname());
        String info = trimmed(profile.getFormattedSummary());
        String photoPath = trimmed(profile.getPhotoPath());
        return new PartnerInfo(uid, name == null ? fallbackName : name, info, photoPath != null, photoPath);
    }

    @Nullable
    private static String trimmed(@Nullable String value) {
        if (value == null) return null;
        String text = value.trim();
        return text.isEmpty() ? null : text;
    }
}
