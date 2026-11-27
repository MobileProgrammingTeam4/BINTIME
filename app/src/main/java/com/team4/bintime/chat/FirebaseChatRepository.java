package com.team4.bintime.chat;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ServerValue;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.functions.FirebaseFunctions;
import com.team4.bintime.auth.AuthRepositories;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * RTDB 채팅 저장소 (BT-021: 구독, BT-022: 전송·읽음). 경로는 {@link ChatRtdbPaths}, 권한은 database.rules.json 이 검사한다.
 * <ul>
 *   <li>목록: userChatRooms/{uid} — 서버가 갱신하는 내 채팅 목록</li>
 *   <li>방: userChatRooms/{uid}/{matchId} + 매칭 스냅샷 + 상대 프로필</li>
 *   <li>메시지: chatRooms/{matchId}/messages 최근 {@link ChatRtdbPaths#MESSAGE_PAGE_SIZE}개</li>
 * </ul>
 * 수동 종료는 D 의 BT-038, 나가기는 BT-043 에서 연결한다.
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

    /** 서버 응답을 기다리는 최대 시간. 넘으면 실패로 알리고 같은 키로 다시 보낼 수 있다. */
    static final long SEND_TIMEOUT_MILLIS = 10_000L;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ChatSendPolicy sendPolicy = new ChatSendPolicy();
    private final Set<String> sending = new HashSet<>();
    /** 구독으로 알게 된 내 방별 unreadCount. markRead 의 불필요한 쓰기를 줄인다. */
    private final Map<String, Integer> knownUnread = new HashMap<>();
    /** 구독 중인 목록·방의 결합기. 복귀 시 끊긴 매칭 구독을 다시 열 때 쓴다(메인 스레드 전용). */
    private final Set<ChatRoomsCombiner> liveCombiners = new HashSet<>();
    @Nullable private final DatabaseReference connectedRef;
    @Nullable private final DatabaseReference serverTimeRef;
    private final ServerClock serverClock = new ServerClock();
    private final ValueEventListener serverTimeListener = new ValueEventListener() {
        @Override public void onDataChange(@NonNull DataSnapshot snapshot) { serverClock.onOffset(snapshot.getValue()); }
        @Override public void onCancelled(@NonNull DatabaseError error) { serverClock.onCancelled(); }
    };
    private final ValueEventListener connectedListener = new ValueEventListener() {
        @Override
        public void onDataChange(@NonNull DataSnapshot snapshot) {
            connected = Boolean.TRUE.equals(snapshot.getValue(Boolean.class));
        }

        @Override
        public void onCancelled(@NonNull DatabaseError error) {
            connected = false;
        }
    };
    private boolean connected;

    FirebaseChatRepository(@Nullable String uid, @Nullable FirebaseDatabase database,
                           MatchInfoSource matches, PartnerSource partners, String fallbackName) {
        this.uid = uid;
        this.database = database;
        this.matches = matches;
        this.matches.useClock(this::nowMillis);
        this.partners = partners;
        this.fallbackName = fallbackName;
        // .info/connected: 서버와 연결됐는지. 오프라인 전송을 대기열에 넣지 않으려고 본다.
        connectedRef = ready() ? database.getReference(".info/connected") : null;
        if (connectedRef != null) connectedRef.addValueEventListener(connectedListener);
        serverTimeRef = ready() ? database.getReference(".info/serverTimeOffset") : null;
        if (serverTimeRef != null) serverTimeRef.addValueEventListener(serverTimeListener);
    }

    @Override
    public String currentUid() {
        return uid;
    }
    @Override public long nowMillis() { return serverClock.nowMillis(); }

    @Override
    public void retryFailedSources() {
        // 다시 연 구독의 콜백에서 화면이 구독을 해제하면(liveCombiners 에서 빠짐) 집합이 바뀌므로 복사본을 돈다.
        for (ChatRoomsCombiner combiner : new ArrayList<>(liveCombiners)) combiner.retryFailedMatches();
    }

    @Override
    public Registration observeRooms(Listener<List<ChatRoom>> listener) {
        if (!ready()) return failNow(listener);
        ChatRoomsCombiner combiner = new ChatRoomsCombiner(uid, fallbackName, matches, partners,
                listener::onData);
        liveCombiners.add(combiner);
        DatabaseReference ref = database.getReference(ChatRtdbPaths.userChatRooms(uid));
        return listen(ref, new SnapshotHandler() {
            @Override
            public void onSnapshot(DataSnapshot snapshot) {
                List<UserChatRoomEntry> entries = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    UserChatRoomEntry entry = ChatRtdbMapper.userChatRoom(child.getKey(), child.getValue());
                    if (entry != null) {
                        entries.add(entry);
                        knownUnread.put(entry.matchId, entry.unreadCount);
                    } else {
                        Log.w(TAG, "skip malformed userChatRooms entry");
                    }
                }
                combiner.update(entries);
            }

            @Override
            public void onFailure() {
                listener.onError();
            }

            @Override
            public void onRelease() {
                liveCombiners.remove(combiner);
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
        liveCombiners.add(combiner);
        DatabaseReference ref = database.getReference(ChatRtdbPaths.userChatRooms(uid)).child(matchId);
        return listen(ref, new SnapshotHandler() {
            @Override
            public void onSnapshot(DataSnapshot snapshot) {
                UserChatRoomEntry entry = ChatRtdbMapper.userChatRoom(snapshot.getKey(), snapshot.getValue());
                if (entry != null) knownUnread.put(entry.matchId, entry.unreadCount);
                else knownUnread.remove(matchId);
                combiner.update(entry == null
                        ? Collections.emptyList() : Collections.singletonList(entry));
            }

            @Override
            public void onFailure() {
                listener.onError();
            }

            @Override
            public void onRelease() {
                liveCombiners.remove(combiner);
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

    /**
     * BT-022. push 키 + {senderId, content, createdAt: ServerValue.TIMESTAMP}.
     * <ul>
     *   <li>오프라인이면 쓰지 않고 바로 실패한다. RTDB 는 오프라인 쓰기를 대기열에 넣고 실패를 알리지 않아,
     *       사용자가 실패로 알고 있는 메시지가 나중에 전송되는 일을 막는다.</li>
     *   <li>연결돼 있어도 {@link #SEND_TIMEOUT_MILLIS} 안에 서버 응답이 없으면 실패로 알린다.
     *       그 뒤 늦게 온 성공 응답은 재전송 키를 지우지 않는다({@link ChatSendAttempt}).</li>
     *   <li>같은 내용으로 다시 보내면 같은 키를 쓴다({@link ChatSendPolicy}). 규칙이 덮어쓰기를 거부하면
     *       그 키의 값을 읽어 내 메시지면 성공으로 본다.</li>
     *   <li>방마다 한 번에 하나만 보낸다. 보내는 중 다시 호출하면 실패로 알린다.</li>
     * </ul>
     * lastMessage·상대 unreadCount 는 서버 트리거(functions/src/chat)가 갱신한다.
     */
    @Override
    public void sendMessage(String matchId, String content, SendCallback callback) {
        String text = ChatSendPolicy.normalize(content);
        if (!ready() || isBlank(matchId) || text == null || sending.contains(matchId) || !connected) {
            if (!connected && ready()) Log.w(TAG, "send skipped: offline");
            callback.onError();
            return;
        }
        DatabaseReference messages = database.getReference(ChatRtdbPaths.messages(matchId));
        String key = sendPolicy.keyFor(matchId, text, () -> messages.push().getKey());
        DatabaseReference target = messages.child(key);
        sending.add(matchId);

        ChatSendAttempt attempt = new ChatSendAttempt(sendPolicy, matchId, key,
                () -> sending.remove(matchId), callback);
        Runnable timeout = () -> {
            Log.w(TAG, "send timed out");
            attempt.timeout();
        };
        Consumer<Boolean> finish = success -> {
            main.removeCallbacks(timeout);
            attempt.finish(success);
        };
        main.postDelayed(timeout, SEND_TIMEOUT_MILLIS);

        Map<String, Object> value = new HashMap<>();
        value.put(ChatRtdbPaths.SENDER_ID, uid);
        value.put(ChatRtdbPaths.CONTENT, text);
        value.put(ChatRtdbPaths.CREATED_AT, ServerValue.TIMESTAMP);
        target.setValue(value, (error, ref) -> {
            if (error == null) {
                finish.accept(true);
            } else if (error.getCode() == DatabaseError.PERMISSION_DENIED) {
                // 앞선 시도가 이미 반영됐거나(덮어쓰기 거부), 방 멤버가 아니다. 저장된 값으로 구분한다.
                target.get().addOnCompleteListener(task -> finish.accept(task.isSuccessful()
                        && ChatSendPolicy.isOwnMessage(task.getResult().getValue(), uid, text)));
            } else {
                Log.w(TAG, "send failed: " + error.getCode());
                finish.accept(false);
            }
        });
    }

    /**
     * 내 userChatRooms/{uid}/{matchId}/unreadCount 를 0 으로. 규칙이 허용하는 유일한 앱 쓰기다.
     * 구독으로 이미 0 인 것을 알면 쓰지 않는다. 실패는 화면에 알리지 않는다(다음 호출에서 다시 시도).
     */
    @Override
    public void markRead(String matchId) {
        if (!ready() || isBlank(matchId)) return;
        Integer known = knownUnread.get(matchId);
        if (known != null && known == 0) return;
        knownUnread.put(matchId, 0);
        database.getReference(ChatRtdbPaths.userChatRooms(uid)).child(matchId)
                .child(ChatRtdbPaths.UNREAD_COUNT).setValue(0, (error, ref) -> {
                    if (error == null) return;
                    Log.w(TAG, "markRead failed: " + error.getCode());
                    knownUnread.remove(matchId);
                });
    }

    /** 저장소를 바꿀 때(로그인 UID 변경) 연결 상태 구독을 해제한다. */
    void close() {
        if (connectedRef != null) connectedRef.removeEventListener(connectedListener);
        if (serverTimeRef != null) serverTimeRef.removeEventListener(serverTimeListener);
    }

    /** D 의 매칭 종료 서버 함수(BT-038)를 호출한다. 그 전에는 실패로 알린다. */
    @Override
    public void endMatch(String matchId, ActionCallback callback) {
        if (uid == null || matchId == null || !matchId.matches("[a-f0-9]{64}")
                || !uid.equals(AuthRepositories.currentUid())) { callback.onError(); return; }
        FirebaseFunctions.getInstance("asia-northeast3").getHttpsCallable("endMatch")
                .call(Collections.singletonMap("matchId", matchId))
                .addOnSuccessListener(result -> {
                    if (uid.equals(AuthRepositories.currentUid())) callback.onSuccess();
                    else callback.onError();
                }).addOnFailureListener(error -> callback.onError());
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
