package com.team4.bintime.data.repository;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

/**
 * [BT-015] FirestoreFailures — 트랜잭션 Abort 꺼내기, 알 수 없는 예외.
 * FirebaseFirestoreException.Code는 클래스 초기화에 Android(SparseArray)가 필요해 JVM에서 검사하지 않는다.
 */
public class FirestoreFailuresTest {

    @Test
    public void abort_isUnwrappedFromCauseChain() {
        TimetableFailure failure = TimetableFailure.overlap("c1");
        Exception wrapped = new RuntimeException("outer",
                new IllegalStateException("mid", new FirestoreFailures.Abort(failure)));
        assertSame(failure, FirestoreFailures.from(wrapped));
    }

    @Test
    public void unknownException_isUnknown() {
        assertEquals(TimetableFailure.Code.UNKNOWN,
                FirestoreFailures.from(new IllegalStateException("x")).getCode());
        assertEquals(TimetableFailure.Code.UNKNOWN, FirestoreFailures.from(null).getCode());
    }
}
