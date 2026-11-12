package com.team4.bintime.request;

import android.os.Handler;
import android.os.Looper;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.MetadataChanges;
import com.google.firebase.firestore.Query;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 요청 쓰기는 Callable 전용, 읽기는 토큰 UID로 제한한 발신/수신 리스너를 사용한다. */
final class FirebaseRequestRepository implements RequestRepository {
    private final String uid;
    private final FirebaseFunctions functions = FirebaseFunctions.getInstance("asia-northeast3");
    private final Map<String, Item> items = new HashMap<>();
    private final Set<String> submitting = new HashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private final Map<String, Item> sentItems = new HashMap<>(), receivedItems = new HashMap<>();
    private final Set<String> updating = new HashSet<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ListenerRegistration sentListener, receivedListener;
    private FirebaseAuth.AuthStateListener authListener;
    private boolean sentReady, receivedReady, loading;
    private String loadError;
    private int generation;
    private final Runnable loadTimeout = () -> {
        if (loading && !listeners.isEmpty()) {
            loadError = "서버에 연결하지 못했어요. 연결을 확인하고 다시 시도해주세요.";
            loading = false;
            changed();
        }
    };

    FirebaseRequestRepository(String uid) { this.uid = uid; }

    @Override public List<Item> list(Tab tab) {
        List<Item> result = new ArrayList<>();
        for (Item item : items.values()) {
            if (tab == Tab.COMPLETED ? item.status() == Status.ACCEPTED
                    : item.status() == Status.PENDING && item.sent == (tab == Tab.SENT)) result.add(item);
        }
        result.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return result;
    }
    @Override public Item find(String id) { return items.get(id); }
    @Override public boolean hasPendingRequest(String toUid) {
        for (Item item : list(Tab.SENT)) if (item.toUid.equals(toUid)) return true;
        return false;
    }
    @Override public boolean isSubmitting(String toUid) { return submitting.contains(toUid); }
    @Override public boolean isMock() { return false; }
    @Override public boolean respond(String id, Status next) { return false; }
    @Override public boolean isLoading() { return loading; }
    @Override public String error() { return loadError; }
    @Override public boolean isUpdating(String id) { return updating.contains(id); }
    @Override public void retry() { if (!listeners.isEmpty()) startListening(); }

    @Override public void cancel(String requestId, Callback callback) {
        if (!signedIn()) { callback.onError("다시 로그인해주세요."); return; }
        if (!updating.add(requestId)) return;
        changed();
        Map<String, Object> input = new HashMap<>();
        input.put("requestId", requestId);
        functions.getHttpsCallable("cancelMatchRequest").call(input).addOnCompleteListener(task -> {
            updating.remove(requestId);
            if (!signedIn()) { items.clear(); changed(); callback.onError("다시 로그인해주세요."); return; }
            if (!task.isSuccessful()) { changed(); callback.onError(errorMessage(task.getException())); return; }
            try {
                Item item = decode((Map<?, ?>) task.getResult().getData());
                items.put(item.id, item);
                sentItems.put(item.id, item);
                changed(); callback.onSuccess(item);
            } catch (RuntimeException invalid) {
                changed(); callback.onError("요청 결과를 확인하지 못했어요. 다시 시도해주세요.");
            }
        });
    }

    @Override public void create(RequestDraft draft, Callback callback) {
        if (!signedIn()) { callback.onError("다시 로그인해주세요."); return; }
        if (draft.mock || draft.candidateId == null || draft.candidateId.isBlank()) {
            callback.onError("실제 매칭 검색 연결 후 요청할 수 있어요."); return;
        }
        if (uid.equals(draft.toUid)) { callback.onError("자신에게 요청할 수 없어요."); return; }
        if (!submitting.add(draft.toUid)) { callback.onError("이미 요청을 보내고 있어요."); return; }
        changed();
        Map<String, Object> input = new HashMap<>();
        input.put("candidateId", draft.candidateId);
        input.put("clientRequestId", draft.clientRequestId);
        functions.getHttpsCallable("createMatchRequest").call(input).addOnCompleteListener(task -> {
            submitting.remove(draft.toUid);
            if (!signedIn()) { items.clear(); changed(); callback.onError("다시 로그인해주세요."); return; }
            if (!task.isSuccessful()) {
                changed(); callback.onError(errorMessage(task.getException())); return;
            }
            try {
                Object data = task.getResult().getData();
                if (!(data instanceof Map)) throw new IllegalArgumentException();
                Item item = decode((Map<?, ?>) data);
                items.put(item.id, item);
                sentItems.put(item.id, item);
                changed(); callback.onSuccess(item);
            } catch (RuntimeException invalid) {
                changed(); callback.onError("요청 결과를 확인하지 못했어요. 다시 시도해주세요.");
            }
        });
    }

    private Item decode(Map<?, ?> data) {
        String from = (String) data.get("fromUid"), to = (String) data.get("toUid");
        if (!uid.equals(from) && !uid.equals(to)) throw new IllegalArgumentException();
        int start = ((Number) data.get("startMinute")).intValue();
        int end = ((Number) data.get("endMinute")).intValue();
        java.time.LocalDate.parse((String) data.get("meetingDate"));
        if (start < 0 || end > 1440 || end <= start) throw new IllegalArgumentException();
        return new Item((String) data.get("id"), from, to, uid, (String) data.get("purpose"),
                (String) data.get("meetingDate"), start, end, Status.valueOf((String) data.get("status")),
                null, ((Number) data.get("createdAtMillis")).longValue());
    }

    private boolean signedIn() {
        return uid != null && FirebaseAuth.getInstance().getCurrentUser() != null
                && uid.equals(FirebaseAuth.getInstance().getCurrentUser().getUid());
    }

    static String errorMessage(Exception error) {
        if (error instanceof FirebaseFunctionsException) {
            Object details = ((FirebaseFunctionsException) error).getDetails();
            if (details instanceof Map) {
                Object reason = ((Map<?, ?>) details).get("reason");
                if ("SEARCH_NOT_CONNECTED".equals(reason)) return "실제 매칭 검색 연결 후 요청할 수 있어요.";
                if ("DUPLICATE_PENDING".equals(reason)) return "이미 보낸 요청이 있어요. 보낸 요청에서 확인해주세요.";
                if ("CANDIDATE_EXPIRED".equals(reason)) return "공강 정보가 바뀌었어요. 다시 검색해주세요.";
                if ("NOT_SENDER".equals(reason)) return "내가 보낸 요청만 취소할 수 있어요.";
                if ("REQUEST_NOT_PENDING".equals(reason)) return "이미 처리된 요청이에요. 목록을 확인해주세요.";
                if ("INVALID_CANDIDATE".equals(reason)) return "요청 조건이 바뀌었어요. 다시 검색해주세요.";
                if ("MATCH_CONFLICT".equals(reason)) return "이미 확정된 매칭과 시간이 겹쳐요.";
                if ("PERMISSION_DENIED".equals(reason)) return "학교 이메일로 다시 로그인해주세요.";
            }
        }
        return "요청을 처리하지 못했어요. 연결을 확인하고 다시 시도해주세요.";
    }

    @Override public Registration observe(Runnable listener) {
        listeners.add(listener);
        if (listeners.size() == 1) startListening();
        else listener.run();
        return () -> {
            listeners.remove(listener);
            if (listeners.isEmpty()) stopListening();
        };
    }

    private void startListening() {
        stopListening();
        if (!signedIn()) { items.clear(); loadError = "다시 로그인해주세요."; changed(); return; }
        loading = true; loadError = null; sentReady = receivedReady = false;
        changed();
        final int current = generation;
        authListener = auth -> {
            if (current == generation && !signedIn()) {
                stopListening(); items.clear(); sentItems.clear(); receivedItems.clear();
                loadError = "다시 로그인해주세요."; changed();
            }
        };
        FirebaseAuth.getInstance().addAuthStateListener(authListener);
        Query base = FirebaseFirestore.getInstance().collection("matchRequests");
        sentListener = listen(base.whereEqualTo("fromUid", uid), true, current);
        receivedListener = listen(base.whereEqualTo("toUid", uid), false, current);
        handler.postDelayed(loadTimeout, 10_000);
    }

    private ListenerRegistration listen(Query query, boolean sent, int current) {
        return query.orderBy("createdAt", Query.Direction.DESCENDING).addSnapshotListener(MetadataChanges.INCLUDE,
                (snapshot, error) -> {
            if (current != generation || !signedIn()) return;
            if (error != null) {
                if (sent) sentReady = false; else receivedReady = false;
                loadError = "요청 목록을 불러오지 못했어요. 다시 시도해주세요.";
                loading = false; changed(); return;
            }
            if (snapshot == null) return;
            try {
                Map<String, Item> next = new HashMap<>();
                for (com.google.firebase.firestore.QueryDocumentSnapshot document : snapshot) {
                    Map<String, Object> data = new HashMap<>(document.getData());
                    Timestamp createdAt = document.getTimestamp("createdAt");
                    if (createdAt == null) throw new IllegalArgumentException();
                    data.put("id", document.getId());
                    data.put("createdAtMillis", createdAt.toDate().getTime());
                    Item item = decode(data);
                    next.put(item.id, item);
                }
                Map<String, Item> target = sent ? sentItems : receivedItems;
                target.clear(); target.putAll(next);
                items.clear(); items.putAll(sentItems); items.putAll(receivedItems);
                if (!snapshot.getMetadata().isFromCache()) {
                    if (sent) sentReady = true; else receivedReady = true;
                }
                if (sentReady && receivedReady) {
                    loading = false; loadError = null; handler.removeCallbacks(loadTimeout);
                }
                changed();
            } catch (RuntimeException invalid) {
                if (sent) sentReady = false; else receivedReady = false;
                loading = false; loadError = "요청 정보를 확인하지 못했어요. 다시 시도해주세요."; changed();
            }
        });
    }

    private void stopListening() {
        generation++;
        handler.removeCallbacks(loadTimeout);
        if (sentListener != null) sentListener.remove();
        if (receivedListener != null) receivedListener.remove();
        if (authListener != null) FirebaseAuth.getInstance().removeAuthStateListener(authListener);
        sentListener = receivedListener = null;
        authListener = null;
        loading = false;
    }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
}
