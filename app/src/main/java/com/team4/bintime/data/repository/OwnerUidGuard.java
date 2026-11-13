package com.team4.bintime.data.repository;

import java.util.function.Supplier;

/**
 * [BT-015/016] PR #88 D 리뷰 P1: 큐를 만든 계정(소유 UID)과 지금 로그인한 계정이 같을 때만 저장을 시작하게 한다.
 * <p>
 * 저장소({@code saveFreeSlots})는 호출할 때마다 현재 UID를 다시 읽으므로, A 계정의 대기 선택이 로그아웃 뒤 B 계정으로
 * 저장될 수 있었다. 저장 요청 직전마다 현재 UID를 다시 읽어 소유 UID와 비교하고, 다르거나 null이면 거부한다.
 * 화면 회전·Fragment 재생성은 UID가 같아 그대로 허용된다.
 * <p>
 * 현재 UID는 앱에서 {@code AuthRepositories.get(context).getCurrentUid()}를 넘긴다(테스트는 가짜 공급자).
 */
public final class OwnerUidGuard implements LatestValueSaveQueue.StartGuard {

    private final String ownerUid;
    private final Supplier<String> currentUid;

    /** @param ownerUid 큐를 만든 시점의 UID. null이면 어떤 저장도 허용하지 않는다. */
    public OwnerUidGuard(String ownerUid, Supplier<String> currentUid) {
        if (currentUid == null) throw new IllegalArgumentException("currentUid");
        this.ownerUid = ownerUid;
        this.currentUid = currentUid;
    }

    public String ownerUid() {
        return ownerUid;
    }

    @Override
    public boolean canStart() {
        return ownerUid != null && ownerUid.equals(currentUid.get());
    }
}
