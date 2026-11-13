package com.team4.bintime.matching;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.functions.FirebaseFunctions;
import com.team4.bintime.auth.AuthRepositories;
import com.team4.bintime.model.MatchCandidate;
import com.team4.bintime.model.MatchFilter;
import com.team4.bintime.profile.ProfileDocuments;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** matchSettings/{uid} 저장 → searchMatches(asia-northeast3) → publicProfiles 읽기. */
final class FirebaseMatchSearchRepository implements MatchSearchRepository {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final FirebaseFirestore db = FirebaseFirestore.getInstance();
    private final FirebaseFunctions functions = FirebaseFunctions.getInstance("asia-northeast3");

    @Override
    public void search(boolean matchingOn, MatchFilter filter, Callback callback) {
        String uid = AuthRepositories.currentUid();
        if (uid == null) {
            callback.onError("로그인이 필요해요.");
            return;
        }
        Map<String, Object> settings = MatchSearchMapper.settingsDocument(matchingOn, filter);
        settings.put("updatedAt", FieldValue.serverTimestamp());
        // shortcut: 검색할 때마다 설정을 덮어쓴다, 스위치를 다른 탭에서 바꾸면 매칭 탭에 돌아올 때 반영된다
        db.collection("matchSettings").document(uid).set(settings)
                .onSuccessTask(v -> functions.getHttpsCallable("searchMatches").call())
                .addOnSuccessListener(r -> onResponse(r.getData(), callback))
                .addOnFailureListener(e -> callback.onError("매칭 후보를 불러오지 못했어요."));
    }

    private void onResponse(Object data, Callback callback) {
        Map<?, ?> response = data instanceof Map ? (Map<?, ?>) data : null;
        Status status = MatchSearchMapper.status(response);
        if (status == null) {
            callback.onError("매칭 후보를 불러오지 못했어요.");
            return;
        }
        if (status != Status.OK) {
            callback.onResult(Result.of(status));
            return;
        }
        String today = LocalDate.now(SEOUL).toString();
        String slotLabel = MatchSearchMapper.slotLabel(response, today);
        int dayOfWeek = MatchSearchMapper.num(((Map<?, ?>) response.get("reference")).get("dayOfWeek"));
        List<Map<String, Object>> raws = MatchSearchMapper.candidates(response);
        List<Task<DocumentSnapshot>> reads = new ArrayList<>();
        for (Map<String, Object> raw : raws) {
            reads.add(db.collection(ProfileDocuments.PUBLIC_PROFILES).document((String) raw.get("candidateId")).get());
        }
        Tasks.whenAllComplete(reads).addOnSuccessListener(done -> {
            List<MatchCandidate> cards = new ArrayList<>();
            for (int i = 0; i < raws.size(); i++) {
                Task<DocumentSnapshot> read = reads.get(i);
                // 프로필이 없거나 못 읽은 후보는 빈 카드 대신 뺀다
                if (!read.isSuccessful() || !read.getResult().exists()) continue;
                cards.add(MatchSearchMapper.toCandidate(raws.get(i),
                        ProfileDocuments.fromDocument(read.getResult().getId(), read.getResult().getData()), dayOfWeek, today));
            }
            callback.onResult(new Result(Status.OK, slotLabel, cards));
        });
    }
}
