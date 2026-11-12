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

    private final Map<String, ChatRepository.Registration> matchRegistrations = new HashMap<>();
    private final Map<String, ChatRepository.Registration> partnerRegistrations = new HashMap<>();
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
                matchRegistrations.put(matchId, matches.observe(matchId, listener(matchData, matchId)));
            }
        }
        for (String uid : partnerUids) {
            if (!partnerRegistrations.containsKey(uid)) {
                partnerRegistrations.put(uid, partners.observe(uid, listener(partnerData, uid)));
            }
        }
        updating = false;
        emit();
    }

    /** 모든 하위 구독을 해제한다. 이후 늦게 온 콜백은 무시한다. */
    void release() {
        released = true;
        for (ChatRepository.Registration r : matchRegistrations.values()) r.remove();
        for (ChatRepository.Registration r : partnerRegistrations.values()) r.remove();
        matchRegistrations.clear();
        partnerRegistrations.clear();
        matchData.clear();
        partnerData.clear();
    }

    private <T> ChatRepository.Listener<T> listener(Map<String, T> store, String key) {
        return new ChatRepository.Listener<T>() {
            @Override
            public void onData(T data) {
                if (released) return;
                if (data == null) store.remove(key);
                else store.put(key, data);
                emit();
            }

            @Override
            public void onError() {
                if (released) return;
                store.remove(key);
                emit();
            }
        };
    }

    private static <T> void retain(Map<String, ChatRepository.Registration> registrations,
                                   Map<String, T> data, Set<String> keep) {
        for (String key : new ArrayList<>(registrations.keySet())) {
            if (!keep.contains(key)) {
                registrations.remove(key).remove();
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
