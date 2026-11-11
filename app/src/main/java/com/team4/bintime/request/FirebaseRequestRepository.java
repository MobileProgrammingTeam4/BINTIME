package com.team4.bintime.request;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 서버 Callable만 요청을 생성한다. 실제 목록 구독/취소는 BT-020에서 연결한다. */
final class FirebaseRequestRepository implements RequestRepository {
    private final String uid;
    private final FirebaseFunctions functions = FirebaseFunctions.getInstance("asia-northeast3");
    private final Map<String, Item> items = new HashMap<>();
    private final Set<String> submitting = new HashSet<>();
    private final List<Runnable> listeners = new ArrayList<>();

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

    @Override public void create(RequestDraft draft, Callback callback) {
        if (!signedIn()) { callback.onError("다시 로그인해주세요."); return; }
        if (draft.mock || draft.candidateId == null || draft.candidateId.isBlank()) {
            callback.onError("실제 매칭 검색 연결 후 요청할 수 있어요."); return;
        }
        if (uid.equals(draft.toUid)) { callback.onError("자신에게 요청할 수 없어요."); return; }
        if (!submitting.add(draft.toUid)) return;
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
                changed(); callback.onSuccess(item);
            } catch (RuntimeException invalid) {
                changed(); callback.onError("요청 결과를 확인하지 못했어요. 다시 시도해주세요.");
            }
        });
    }

    private Item decode(Map<?, ?> data) {
        String from = (String) data.get("fromUid"), to = (String) data.get("toUid");
        if (!uid.equals(from) && !uid.equals(to)) throw new IllegalArgumentException();
        return new Item((String) data.get("id"), from, to, uid, (String) data.get("purpose"),
                (String) data.get("meetingDate"), ((Number) data.get("startMinute")).intValue(),
                ((Number) data.get("endMinute")).intValue(), Status.valueOf((String) data.get("status")),
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
            }
        }
        return "요청을 처리하지 못했어요. 연결을 확인하고 다시 시도해주세요.";
    }

    @Override public Registration observe(Runnable listener) {
        listeners.add(listener); listener.run();
        return () -> listeners.remove(listener);
    }
    private void changed() { for (Runnable listener : new ArrayList<>(listeners)) listener.run(); }
}
