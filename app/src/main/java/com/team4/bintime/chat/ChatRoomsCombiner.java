package com.team4.bintime.chat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RTDB userChatRooms 항목에 매칭 스냅샷(MatchInfoSource)과 상대 프로필(PartnerSource)을 붙여 ChatRoom 목록을 만든다.
 * 항목이 바뀌면 새로 생긴 방·상대만 구독하고 사라진 것은 해제한다. 출처가 오류를 내거나 아직 값이 없으면
 * 해당 정보 없이(null) 방을 만들어 채팅 자체는 계속 쓸 수 있게 한다.
 * 구독마다 {@link Subscription} 토큰을 두어, 해제된 구독(같은 키로 다시 구독한 경우 포함)의 늦은 콜백은 버린다.
 * Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다. 메인 스레드에서만 호출한다.
 */
final class ChatRoomsCombiner {

    interface Output {
        void onRooms(List<ChatRoom> rooms);
    }

    private final String myUid;
    private final String fallbackName;
    private final MatchInfoSource matches;
    private final PartnerSource partners;
    private final Output output;

    /** 하위 구독 하나. 해제하면 inactive 가 되어 이 구독의 콜백은 더 이상 반영하지 않는다. */
    private static final class Subscription {
        ChatRepository.Registration registration;
        boolean active = true;
        /** onError 를 받았는지. Firestore·RTDB 는 오류 뒤 같은 리스너를 다시 부르지 않는다. */
        boolean failed;

        void cancel() {
            active = false;
            if (registration != null) registration.remove();
        }
    }

    private final Map<String, Subscription> matchRegistrations = new HashMap<>();
    private final Map<String, Subscription> partnerRegistrations = new HashMap<>();
    private final Map<String, MatchInfo> matchData = new HashMap<>();
    private final Map<String, PartnerInfo> partnerData = new HashMap<>();
    private List<UserChatRoomEntry> visible = Collections.emptyList();
    private boolean hasEntries;
    private boolean updating;
    private boolean released;

    ChatRoomsCombiner(String myUid, String fallbackName, MatchInfoSource matches, PartnerSource partners,
                      Output output) {
        this.myUid = myUid;
        this.fallbackName = fallbackName;
        this.matches = matches;
        this.partners = partners;
        this.output = output;
    }

    /** 최신 userChatRooms 항목 전체. 나간 방은 빼고 최근 메시지 순으로 전달한다. */
    void update(List<UserChatRoomEntry> entries) {
        if (released) return;
        updating = true;
        visible = ChatRtdbMapper.visibleRooms(entries);
        hasEntries = true;

        Set<String> matchIds = new HashSet<>();
        Set<String> partnerUids = new HashSet<>();
        for (UserChatRoomEntry entry : visible) {
            matchIds.add(entry.matchId);
            partnerUids.add(entry.otherUid);
        }
        retain(matchRegistrations, matchData, matchIds);
        retain(partnerRegistrations, partnerData, partnerUids);
        for (String matchId : matchIds) {
            if (!matchRegistrations.containsKey(matchId)) {
                Subscription sub = new Subscription();
                matchRegistrations.put(matchId, sub);
                sub.registration = matches.observe(matchId, listener(matchData, matchId, sub));
            }
        }
        for (String uid : partnerUids) {
            if (!partnerRegistrations.containsKey(uid)) {
                Subscription sub = new Subscription();
                partnerRegistrations.put(uid, sub);
                sub.registration = partners.observe(uid, listener(partnerData, uid, sub));
            }
        }
        updating = false;
        emit();
    }

    /**
     * 오류로 끊긴 매칭 스냅샷 구독만 한 번씩 다시 연다(BT-035, 화면 복귀 시 호출).
     * 자동 반복은 하지 않는다. 다시 실패하면 다음 복귀 때까지 시간 정보 없이 둔다.
     * 상대 프로필은 BT-034 방침(재조회 루프 없음)대로 다시 열지 않는다.
     */
    void retryFailedMatches() {
        if (released) return;
        boolean retried = false;
        updating = true; // 다시 연 구독이 바로(동기로) 응답해도 한 번만 내보낸다
        for (Map.Entry<String, Subscription> entry : new ArrayList<>(matchRegistrations.entrySet())) {
            if (!entry.getValue().failed) continue;
            entry.getValue().cancel(); // 끊긴 구독의 늦은 콜백은 active=false 로 버린다
            Subscription sub = new Subscription();
            matchRegistrations.put(entry.getKey(), sub);
            sub.registration = matches.observe(entry.getKey(), listener(matchData, entry.getKey(), sub));
            retried = true;
        }
        updating = false;
        if (retried) emit(); // 실패한 구독이 없으면 화면을 다시 그리지 않는다
    }

    /** 모든 하위 구독을 해제한다. 이후 늦게 온 콜백은 무시한다. */
    void release() {
        released = true;
        for (Subscription sub : matchRegistrations.values()) sub.cancel();
        for (Subscription sub : partnerRegistrations.values()) sub.cancel();
        matchRegistrations.clear();
        partnerRegistrations.clear();
        matchData.clear();
        partnerData.clear();
    }

    private <T> ChatRepository.Listener<T> listener(Map<String, T> store, String key, Subscription sub) {
        return new ChatRepository.Listener<T>() {
            @Override
            public void onData(T data) {
                if (released || !sub.active) return;
                if (data == null) store.remove(key);
                else store.put(key, data);
                emit();
            }

            @Override
            public void onError() {
                if (released || !sub.active) return;
                sub.failed = true;
                store.remove(key);
                emit();
            }
        };
    }

    private static <T> void retain(Map<String, Subscription> registrations,
                                   Map<String, T> data, Set<String> keep) {
        for (String key : new ArrayList<>(registrations.keySet())) {
            if (!keep.contains(key)) {
                registrations.remove(key).cancel();
                data.remove(key);
            }
        }
    }

    private void emit() {
        if (released || updating || !hasEntries) return;
        List<ChatRoom> rooms = new ArrayList<>(visible.size());
        for (UserChatRoomEntry entry : visible) {
            rooms.add(ChatRtdbMapper.room(myUid, entry, matchData.get(entry.matchId),
                    partnerData.get(entry.otherUid), fallbackName));
        }
        output.onRooms(Collections.unmodifiableList(rooms));
    }
}
