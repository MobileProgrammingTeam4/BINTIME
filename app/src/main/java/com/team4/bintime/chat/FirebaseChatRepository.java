package com.team4.bintime.chat;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RTDB 채팅 저장소 (BT-021: 구독). 경로는 {@link ChatRtdbPaths}, 권한은 database.rules.json 이 검사한다.
 * <ul>
 *   <li>목록: userChatRooms/{uid} — 서버가 갱신하는 내 채팅 목록</li>
 *   <li>방: userChatRooms/{uid}/{matchId} + 매칭 스냅샷 + 상대 프로필</li>
 *   <li>메시지: chatRooms/{matchId}/messages 최근 {@link ChatRtdbPaths#MESSAGE_PAGE_SIZE}개</li>
 * </ul>
 * 송수신(sendMessage·markRead)은 BT-022, 수동 종료는 D 의 BT-038, 나가기는 BT-043 에서 연결한다.
 * RTDB 콜백은 메인 스레드에서 온다. 해제한 구독의 늦은 콜백은 무시한다.
 */
final class FirebaseChatRepository implements ChatRepository {

    private static final String TAG = "FirebaseChatRepository";

    @Nullable private final String uid;
    /** google-services.json 에 RTDB 주소(firebase_url)가 없으면 null. 모든 구독이 onError 를 받는다. */
    @Nullable private final FirebaseDatabase database;
    private final MatchInfoSource matches;
    private final PartnerSource partners;
    private final String fallbackName;

    FirebaseChatRepository(@Nullable String uid, @Nullable FirebaseDatabase database,
                           MatchInfoSource matches, PartnerSource partners, String fallbackName) {
        this.uid = uid;
        this.database = database;
        this.matches = matches;
        this.partners = partners;
        this.fallbackName = fallbackName;
    }

    @Override
    public String currentUid() {
        return uid;
    }

    @Override
    public Registration observeRooms(Listener<List<ChatRoom>> listener) {
        if (!ready()) return failNow(listener);
        ChatRoomsCombiner combiner = new ChatRoomsCombiner(uid, fallbackName, matches, partners,
                listener::onData);
        DatabaseReference ref = database.getReference(ChatRtdbPaths.userChatRooms(uid));
        return listen(ref, new SnapshotHandler() {
            @Override
            public void onSnapshot(DataSnapshot snapshot) {
                List<UserChatRoomEntry> entries = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    UserChatRoomEntry entry = ChatRtdbMapper.userChatRoom(child.getKey(), child.getValue());
                    if (entry != null) entries.add(entry);
                    else Log.w(TAG, "skip malformed userChatRooms entry");
                }
                combiner.update(entries);
            }

            @Override
            public void onFailure() {
                listener.onError();
            }

            @Override
            public void onRelease() {
                combiner.release();
            }
        });
    }

    @Override
    public Registration observeRoom(String matchId, Listener<ChatRoom> listener) {
        if (!ready() || isBlank(matchId)) return failNow(listener);
        // 내 목록에 없는 방(비참여·나간 방·아직 생성 전)은 Mock 과 같이 onError 로 알린다.
        ChatRoomsCombiner combiner = new ChatRoomsCombiner(uid, fallbackName, matches, partners, rooms -> {
            if (rooms.isEmpty()) listener.onError();
            else listener.onData(rooms.get(0));
        });
        DatabaseReference ref = database.getReference(ChatRtdbPaths.userChatRooms(uid)).child(matchId);
        return listen(ref, new SnapshotHandler() {
            @Override
            public void onSnapshot(DataSnapshot snapshot) {
                UserChatRoomEntry entry = ChatRtdbMapper.userChatRoom(snapshot.getKey(), snapshot.getValue());
                combiner.update(entry == null
                        ? Collections.emptyList() : Collections.singletonList(entry));
            }

            @Override
            public void onFailure() {
                listener.onError();
            }

            @Override
            public void onRelease() {
                combiner.release();
            }
        });
    }

    @Override
    public Registration observeMessages(String matchId, Listener<List<ChatMessage>> listener) {
        if (!ready() || isBlank(matchId)) return failNow(listener);
        Query query = database.getReference(ChatRtdbPaths.messages(matchId))
                .orderByChild(ChatRtdbPaths.CREATED_AT)
                .limitToLast(ChatRtdbPaths.MESSAGE_PAGE_SIZE);
        return listen(query, new SnapshotHandler() {
            @Override
            public void onSnapshot(DataSnapshot snapshot) {
                List<ChatMessage> list = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    ChatMessage message = ChatRtdbMapper.message(child.getKey(), child.getValue());
                    if (message != null) list.add(message);
                    else Log.w(TAG, "skip malformed message");
                }
                listener.onData(ChatRtdbMapper.sortMessages(list));
            }

            @Override
            public void onFailure() {
                listener.onError();
            }

            @Override
            public void onRelease() {}
        });
    }

    /** BT-022 에서 push + ServerValue.TIMESTAMP 로 구현한다. 그 전에는 전송하지 않는다. */
    @Override
    public void sendMessage(String matchId, String content, SendCallback callback) {
        callback.onError();
    }

    /** BT-022 에서 userChatRooms/{uid}/{matchId}/unreadCount = 0 으로 구현한다. */
    @Override
    public void markRead(String matchId) {}

    /** D 의 매칭 종료 서버 함수(BT-038)를 호출한다. 그 전에는 실패로 알린다. */
    @Override
    public void endMatch(String matchId, ActionCallback callback) {
        callback.onError();
    }

    /** BT-043 Callable(ENDED 검사 후 hidden 설정)로 구현한다. 그 전에는 실패로 알린다. */
    @Override
    public void leaveRoom(String matchId, ActionCallback callback) {
        callback.onError();
    }

    // ---- 구독 공통 ----

    private interface SnapshotHandler {
        void onSnapshot(DataSnapshot snapshot);

        void onFailure();

        void onRelease();
    }

    private Registration listen(Query query, SnapshotHandler handler) {
        final boolean[] active = {true};
        ValueEventListener valueListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!active[0]) return;
                try {
                    handler.onSnapshot(snapshot);
                } catch (RuntimeException unexpected) {
                    Log.e(TAG, "snapshot handling failed", unexpected);
                    handler.onFailure();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // 권한 거부(비참여자) 등. RTDB 는 취소된 리스너를 다시 호출하지 않는다.
                if (!active[0]) return;
                Log.w(TAG, "listener cancelled: " + error.getCode());
                handler.onFailure();
            }
        };
        query.addValueEventListener(valueListener);
        return () -> {
            if (!active[0]) return;
            active[0] = false;
            query.removeEventListener(valueListener);
            handler.onRelease();
        };
    }

    private boolean ready() {
        return database != null && !isBlank(uid);
    }

    /** 로그인 전이거나 RTDB 설정이 없을 때. 화면이 오류 상태를 보이도록 바로 알린다. */
    private static <T> Registration failNow(Listener<T> listener) {
        listener.onError();
        return () -> {};
    }

    private static boolean isBlank(@Nullable String text) {
        return text == null || text.trim().isEmpty();
    }
}
