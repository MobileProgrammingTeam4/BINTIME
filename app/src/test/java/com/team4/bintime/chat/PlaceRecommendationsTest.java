package com.team4.bintime.chat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.team4.bintime.request.MatchAppointment;

import org.junit.Test;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** BT-042 채팅 상단 추천 장소: 요청할 약속 선택, 표시 문구, 서버 응답 해석. */
public class PlaceRecommendationsTest {

    private static long kst(String localDateTime) {
        return OffsetDateTime.parse(localDateTime + "+09:00").toInstant().toEpochMilli();
    }

    private static MatchAppointment active(String id, String date, int start, int end) {
        return new MatchAppointment(id, "room", date, start, end, "카페·디저트", MatchAppointment.Status.ACTIVE, null, false);
    }

    @Test
    public void 상단_카드에_보이는_약속의_ID로_요청한다() {
        List<MatchAppointment> all = Arrays.asList(active("later", "2026-10-08", 840, 930), active("now", "2026-10-07", 840, 930));
        assertEquals("now", PlaceRecommendations.targetMatchId(all, "room", kst("2026-10-07T14:30:00")));
        // 첫 약속이 끝나면 다음 약속으로 바뀐다
        assertEquals("later", PlaceRecommendations.targetMatchId(all, "room", kst("2026-10-07T16:00:00")));
    }

    @Test
    public void 약속_목록이_없는_이전_방은_방_키로_요청하고_보일_약속이_없으면_요청하지_않는다() {
        assertEquals("room", PlaceRecommendations.targetMatchId(Collections.emptyList(), "room", kst("2026-10-07T14:30:00")));
        MatchAppointment ended = new MatchAppointment("old", "room", "2026-10-01", 840, 930, "밥", MatchAppointment.Status.ENDED, true, false);
        assertNull(PlaceRecommendations.targetMatchId(Collections.singletonList(ended), "room", kst("2026-10-07T14:30:00")));
    }

    @Test
    public void 이름은_시안처럼_슬래시로_잇고_비었으면_숨긴다() {
        assertEquals("공학관 카페 / 북악관 카페", PlaceRecommendations.join(Arrays.asList("공학관 카페", "북악관 카페")));
        assertNull(PlaceRecommendations.join(Collections.emptyList()));
        assertNull(PlaceRecommendations.join(null));
    }

    private static Map<String, Object> response(Object places) {
        Map<String, Object> data = new HashMap<>();
        data.put("places", places);
        Map<String, Object> raw = new HashMap<>();
        raw.put("task", "recommendPlace");
        raw.put("data", data);
        return raw;
    }

    private static Map<String, Object> place(Object name) {
        Map<String, Object> item = new HashMap<>();
        item.put("placeId", "id");
        item.put("name", name);
        item.put("reason", "이유");
        return item;
    }

    @Test
    public void 서버_응답에서_이름만_꺼내고_중복_빈값은_뺀다() {
        List<String> names = FunctionsPlaceRecommendationRepository.parseNames(
                response(Arrays.asList(place(" 공학관 카페 "), place("공학관 카페"), place(""), place("학생식당 (종합복지관 1층)"))));
        assertEquals(Arrays.asList("공학관 카페", "학생식당 (종합복지관 1층)"), names);
    }

    @Test
    public void 형식이_틀리면_null() {
        assertNull(FunctionsPlaceRecommendationRepository.parseNames(null));
        assertNull(FunctionsPlaceRecommendationRepository.parseNames(response("not a list")));
        assertNull(FunctionsPlaceRecommendationRepository.parseNames(response(Collections.singletonList(place(3)))));
        assertNull(FunctionsPlaceRecommendationRepository.parseNames(response(Collections.emptyList())));
    }

    @Test
    public void 최대_3곳까지만_보인다() {
        List<String> names = FunctionsPlaceRecommendationRepository.parseNames(
                response(Arrays.asList(place("a"), place("b"), place("c"), place("d"))));
        assertEquals(Arrays.asList("a", "b", "c"), names);
    }
}
