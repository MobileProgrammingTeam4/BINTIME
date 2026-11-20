package com.team4.bintime.chat;

import java.util.function.LongSupplier;

/**
 * 화면 표시용 서버 시각 추정(BT-035). RTDB .info/serverTimeOffset 값을 단말 시각에 더한다.
 * 종료 판정·상태 변경에는 쓰지 않는다(종료는 BT-038 서버가 정한다).
 *
 * <ul>
 *   <li>보정 값을 받기 전에는 오프셋 0(단말 시각)이다.</li>
 *   <li>숫자가 아닌 값은 무시하고 마지막 값을 유지한다.</li>
 *   <li>구독이 취소돼도 0 으로 되돌리지 않고 마지막 값을 유지한다. 한 번 받은 보정이 단말 시각보다 정확하기 때문이다.</li>
 * </ul>
 * Firebase SDK 에 의존하지 않아 JVM 단위 테스트로 검증한다. 값 갱신은 메인 스레드 콜백에서 하지만
 * 읽기는 다른 스레드(Firestore 콜백 등)에서도 할 수 있어 volatile 로 둔다.
 */
final class ServerClock {

    private final LongSupplier deviceNow;
    private volatile long offsetMillis;

    ServerClock() {
        this(System::currentTimeMillis);
    }

    ServerClock(LongSupplier deviceNow) {
        this.deviceNow = deviceNow;
    }

    /** .info/serverTimeOffset 의 값. 숫자가 아니면(null 포함) 무시한다. */
    void onOffset(Object value) {
        if (value instanceof Number) offsetMillis = ((Number) value).longValue();
    }

    /** 구독 취소·권한 오류. 마지막 보정 값을 그대로 쓴다. */
    void onCancelled() {
        // 의도적으로 아무것도 하지 않는다. 예전에는 0 으로 되돌려 단말 시계가 틀린 경우 남은 시간이 튀었다.
    }

    long nowMillis() {
        return deviceNow.getAsLong() + offsetMillis;
    }
}
