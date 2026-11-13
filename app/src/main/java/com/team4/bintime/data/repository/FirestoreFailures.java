package com.team4.bintime.data.repository;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.firestore.FirebaseFirestoreException;

/**
 * [BT-015] Firestore 예외 → {@link TimetableFailure} 변환.
 * 트랜잭션 안에서 직접 끝낸 경우({@link Abort})는 그 실패를 그대로 돌려준다.
 */
public final class FirestoreFailures {

    private FirestoreFailures() {
    }

    /** 트랜잭션 함수 안에서 검증 실패로 끝낼 때 던진다. */
    static final class Abort extends RuntimeException {
        final TimetableFailure failure;

        Abort(TimetableFailure failure) {
            super(failure.toString());
            this.failure = failure;
        }
    }

    public static TimetableFailure from(Exception error) {
        Throwable t = error;
        for (int depth = 0; t != null && depth < 8; depth++, t = t.getCause()) {
            if (t instanceof Abort) return ((Abort) t).failure;
            if (t instanceof FirebaseFirestoreException) {
                FirebaseFirestoreException e = (FirebaseFirestoreException) t;
                return TimetableFailure.of(codeOf(e.getCode()), e.getMessage());
            }
            if (t instanceof FirebaseNetworkException) {
                return TimetableFailure.of(TimetableFailure.Code.NETWORK, t.getMessage());
            }
        }
        return TimetableFailure.of(TimetableFailure.Code.UNKNOWN, error == null ? null : error.toString());
    }

    /** Firestore 오류 코드 → 화면 안내용 코드. */
    public static TimetableFailure.Code codeOf(FirebaseFirestoreException.Code code) {
        if (code == null) return TimetableFailure.Code.UNKNOWN;
        switch (code) {
            case PERMISSION_DENIED:
                return TimetableFailure.Code.PERMISSION_DENIED;
            case UNAUTHENTICATED:
                return TimetableFailure.Code.NOT_SIGNED_IN;
            case UNAVAILABLE:
            case DEADLINE_EXCEEDED:
                return TimetableFailure.Code.NETWORK;
            case ABORTED:
            case FAILED_PRECONDITION:
                return TimetableFailure.Code.CONFLICT;
            case NOT_FOUND:
                return TimetableFailure.Code.NOT_FOUND;
            case INVALID_ARGUMENT:
                return TimetableFailure.Code.INVALID_COURSE;
            default:
                return TimetableFailure.Code.UNKNOWN;
        }
    }
}
