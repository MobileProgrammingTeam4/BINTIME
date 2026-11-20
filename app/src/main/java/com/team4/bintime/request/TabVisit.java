package com.team4.bintime.request;

import java.util.function.BooleanSupplier;

/**
 * 요청 탭 전환 횟수. 완료 카드에서 시작한 채팅방 준비가 늦게 끝났을 때, 그 사이 탭을 바꿨으면
 * 채팅방으로 이동하지 않게 한다. 같은 탭에서 카드만 다시 그린 경우(render)는 그대로 이동한다.
 */
final class TabVisit {
    private int count;

    /** 탭을 고를 때마다 부른다(같은 탭을 다시 골라도). 카드 다시 그리기에서는 부르지 않는다. */
    void changed() {
        count++;
    }

    /** 지금 탭에서 시작한 작업용. 그 뒤 탭을 고르지 않았고 showing 이 true 일 때만 true. */
    BooleanSupplier current(BooleanSupplier showing) {
        int started = count;
        return () -> count == started && showing.getAsBoolean();
    }
}
