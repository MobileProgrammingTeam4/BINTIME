package com.team4.bintime.request;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.function.BooleanSupplier;

/** 완료 카드 채팅 준비 응답이 늦게 올 때 이동 여부 (#114 리뷰, MatchChatNavigator). */
public class TabVisitTest {

    @Test
    public void sameTabWithRedrawnCardsStillOpens() {
        TabVisit visit = new TabVisit();
        visit.changed(); // 완료 탭 선택
        BooleanSupplier still = visit.current(() -> true);
        // 실시간 갱신으로 카드만 다시 그림: changed() 를 부르지 않는다.
        assertTrue(still.getAsBoolean());
    }

    @Test
    public void otherTabSelectedBeforeResponseDoesNotOpen() {
        TabVisit visit = new TabVisit();
        BooleanSupplier still = visit.current(() -> true);
        visit.changed(); // 받은 요청 탭으로 변경
        assertFalse(still.getAsBoolean());
    }

    @Test
    public void returningToCompletedTabBeforeResponseDoesNotOpen() {
        TabVisit visit = new TabVisit();
        BooleanSupplier still = visit.current(() -> true);
        visit.changed(); // 다른 탭
        visit.changed(); // 다시 완료 탭
        assertFalse(still.getAsBoolean());
    }

    @Test
    public void hiddenContentDoesNotOpen() {
        TabVisit visit = new TabVisit();
        boolean[] shown = {true};
        BooleanSupplier still = visit.current(() -> shown[0]);
        shown[0] = false; // 매칭 찾기로 요청 영역이 GONE 이거나 하단 탭으로 Fragment 가 숨겨짐
        assertFalse(still.getAsBoolean());
        shown[0] = true;
        assertTrue(still.getAsBoolean());
    }

    @Test
    public void laterTicketUsesNewTab() {
        TabVisit visit = new TabVisit();
        visit.current(() -> true);
        visit.changed();
        assertTrue(visit.current(() -> true).getAsBoolean());
    }
}
