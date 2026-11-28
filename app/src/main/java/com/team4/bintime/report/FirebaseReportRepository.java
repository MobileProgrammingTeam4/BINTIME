package com.team4.bintime.report;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.functions.FirebaseFunctions;
import com.google.firebase.functions.FirebaseFunctionsException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BT-051 submitReport Callable(asia-northeast3). */
public final class FirebaseReportRepository implements ReportRepository {

    static final String MSG_NETWORK = "네트워크 연결을 확인해 주세요.";
    static final String MSG_GENERIC = "신고를 접수하지 못했어요. 잠시 후 다시 시도해 주세요.";

    private static FirebaseReportRepository instance;

    public static synchronized FirebaseReportRepository get() {
        if (instance == null) instance = new FirebaseReportRepository();
        return instance;
    }

    private final FirebaseFunctions functions = FirebaseFunctions.getInstance("asia-northeast3");

    /** 서버 입력 형식. 신고자 UID 는 넣지 않는다(서버가 토큰에서 확인). */
    static Map<String, Object> toData(ReportForm form) {
        List<String> reasons = new ArrayList<>();
        for (ReportReason reason : form.reasons) reasons.add(reason.name());
        Map<String, Object> data = new HashMap<>();
        data.put("targetUid", form.reportedUid);
        data.put("matchId", form.matchId);
        data.put("reasons", reasons);
        data.put("detail", form.detail);
        data.put("alsoBlock", form.alsoBlock);
        return data;
    }

    @Override
    public void submit(ReportForm form, Callback callback) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            callback.onError("로그인이 필요해요.");
            return;
        }
        if (form.validate() != null) {
            callback.onError("신고 내용을 확인해 주세요.");
            return;
        }
        functions.getHttpsCallable("submitReport").call(toData(form)).addOnCompleteListener(task -> {
            if (task.isSuccessful()) {
                Object data = task.getResult() != null ? task.getResult().getData() : null;
                boolean blocked = data instanceof Map && Boolean.TRUE.equals(((Map<?, ?>) data).get("blocked"));
                callback.onSuccess(new Result(false, blocked));
                return;
            }
            Exception e = task.getException();
            Map<?, ?> details = e instanceof FirebaseFunctionsException
                    && ((FirebaseFunctionsException) e).getDetails() instanceof Map
                    ? (Map<?, ?>) ((FirebaseFunctionsException) e).getDetails() : null;
            if (details != null && "ALREADY_REPORTED".equals(details.get("reason"))) {
                callback.onSuccess(new Result(true, Boolean.TRUE.equals(details.get("blocked"))));
                return;
            }
            callback.onError(message(e, details));
        });
    }

    static String message(Exception e, Map<?, ?> details) {
        if (e instanceof FirebaseNetworkException || (e != null && e.getCause() instanceof FirebaseNetworkException)) {
            return MSG_NETWORK;
        }
        Object reason = details != null ? details.get("reason") : null;
        // 자기 자신·함께하지 않은 매칭·없는 사용자는 서버 문구를 그대로 보여 준다.
        if ("SELF_REPORT".equals(reason) || "NOT_PARTICIPANT".equals(reason) || "USER_NOT_FOUND".equals(reason)) {
            String text = e.getMessage();
            if (text != null && !text.trim().isEmpty()) return text;
        }
        return MSG_GENERIC;
    }
}
