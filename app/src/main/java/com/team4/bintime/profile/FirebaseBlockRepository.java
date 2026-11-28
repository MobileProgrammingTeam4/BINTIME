package com.team4.bintime.profile;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreException;
import com.google.firebase.firestore.Query;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * BT-046 Firebase 차단 저장소. 목록은 users/{uid}/blockedUsers(본인 읽기 전용 Rules)를,
 * 차단·해제는 blockUser/unblockUser Callable(asia-northeast3)을 쓴다. 콜백은 메인 스레드로 온다.
 */
public final class FirebaseBlockRepository implements BlockRepository {

    /** 서버 상한(functions/src/safety/block.ts MAX_BLOCKED_USERS)과 같다. */
    static final int MAX_BLOCKED_USERS = 200;
    static final String MSG_NETWORK = "네트워크 연결을 확인해 주세요.";
    static final String MSG_GENERIC = "잠시 후 다시 시도해 주세요.";

    private static FirebaseBlockRepository instance;

    public static synchronized FirebaseBlockRepository get() {
        if (instance == null) instance = new FirebaseBlockRepository();
        return instance;
    }

    private final FirebaseAuth auth = FirebaseAuth.getInstance();
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final FirebaseFunctions functions = FirebaseFunctions.getInstance("asia-northeast3");
    private final ProfileRepository profiles = FirebaseProfileRepository.get();

    @Override
    public void getBlockedUsers(Callback<List<BlockedUser>> callback) {
        FirebaseUser user = auth.getCurrentUser();
        if (user == null) {
            callback.onError("로그인이 필요해요.");
            return;
        }
        db.collection("users").document(user.getUid()).collection("blockedUsers")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(MAX_BLOCKED_USERS)
                .get()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult() == null) {
                        callback.onError(message(task.getException()));
                        return;
                    }
                    List<String> uids = new ArrayList<>();
                    for (DocumentSnapshot doc : task.getResult().getDocuments()) uids.add(doc.getId());
                    loadProfiles(uids, callback);
                });
    }

    /** 공개 프로필을 모두 읽은 뒤 차단 순서대로 한 번만 돌려준다. 하나라도 읽기에 실패하면 목록 전체를 실패로 본다. */
    private void loadProfiles(List<String> uids, Callback<List<BlockedUser>> callback) {
        if (uids.isEmpty()) {
            callback.onSuccess(Collections.emptyList());
            return;
        }
        UserProfile[] found = new UserProfile[uids.size()];
        int[] remaining = {uids.size()};
        boolean[] failed = {false};
        for (int i = 0; i < uids.size(); i++) {
            final int index = i;
            profiles.getPublicProfile(uids.get(i), new ProfileRepository.Callback<UserProfile>() {
                @Override
                public void onSuccess(UserProfile profile) {
                    if (failed[0]) return;
                    found[index] = profile; // 탈퇴 등으로 없으면 null
                    if (--remaining[0] == 0) {
                        List<BlockedUser> result = new ArrayList<>();
                        for (int j = 0; j < uids.size(); j++) result.add(new BlockedUser(uids.get(j), found[j]));
                        callback.onSuccess(result);
                    }
                }

                @Override
                public void onError(String message) {
                    if (failed[0]) return;
                    failed[0] = true;
                    callback.onError(message);
                }
            });
        }
    }

    @Override
    public void block(String targetUid, Callback<Void> callback) {
        call("blockUser", targetUid, callback);
    }

    @Override
    public void unblock(String targetUid, Callback<Void> callback) {
        call("unblockUser", targetUid, callback);
    }

    private void call(String name, String targetUid, Callback<Void> callback) {
        if (auth.getCurrentUser() == null) {
            callback.onError("로그인이 필요해요.");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("targetUid", targetUid);
        functions.getHttpsCallable(name).call(data).addOnCompleteListener(task -> {
            if (task.isSuccessful()) callback.onSuccess(null);
            else callback.onError(message(task.getException()));
        });
    }

    /** 서버가 준 사용자 문구(자기 자신 차단·상한 등)는 그대로, 네트워크·내부 오류는 공통 문구로. */
    static String message(Exception e) {
        if (e instanceof FirebaseNetworkException) return MSG_NETWORK;
        if (e instanceof FirebaseFunctionsException) {
            FirebaseFunctionsException.Code code = ((FirebaseFunctionsException) e).getCode();
            if (code == FirebaseFunctionsException.Code.UNAVAILABLE
                    || code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED) {
                return e.getCause() instanceof FirebaseNetworkException ? MSG_NETWORK : MSG_GENERIC;
            }
            if (code == FirebaseFunctionsException.Code.INVALID_ARGUMENT
                    || code == FirebaseFunctionsException.Code.NOT_FOUND
                    || code == FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED
                    || code == FirebaseFunctionsException.Code.UNAUTHENTICATED
                    || code == FirebaseFunctionsException.Code.PERMISSION_DENIED) {
                String text = e.getMessage();
                if (text != null && !text.trim().isEmpty()) return text;
            }
            return MSG_GENERIC;
        }
        if (e instanceof FirebaseFirestoreException
                && ((FirebaseFirestoreException) e).getCode() == FirebaseFirestoreException.Code.UNAVAILABLE) {
            return MSG_NETWORK;
        }
        return MSG_GENERIC;
    }
}
