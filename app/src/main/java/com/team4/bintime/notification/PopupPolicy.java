package com.team4.bintime.notification;

/** 날짜는 호출자가 Asia/Seoul 기준으로 제공한다. 억제는 해당 날짜에만 적용한다. */
public final class PopupPolicy {
    private PopupPolicy() { }
    public static boolean canShow(boolean enabled, String suppressedDate, String currentDate) {
        return enabled && !currentDate.equals(suppressedDate);
    }
}
