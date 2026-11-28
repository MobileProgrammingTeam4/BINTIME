package com.team4.bintime.timetable;

/** [BT-048] 원본 보기 확대·넘기기 계산. Android 없이 JVM 단위 테스트한다. */
final class ZoomMath {

    static final float MAX_SCALE = 5f;
    static final float DOUBLE_TAP_SCALE = 2.5f;
    /** 좌우 넘기기로 인정할 최소 가로 이동(px)과 속도(px/s). */
    static final float MIN_SWIPE_DISTANCE = 120f;
    static final float MIN_SWIPE_VELOCITY = 400f;

    private ZoomMath() {
    }

    static float clampScale(float scale) {
        if (Float.isNaN(scale)) return 1f;
        return Math.max(1f, Math.min(MAX_SCALE, scale));
    }

    /**
     * 확대된 이미지가 화면 밖으로 밀려나지 않도록 옮길 거리. 이미지가 화면보다 작으면 가운데로, 크면 빈틈이 생기지 않게.
     */
    static float boundShift(float start, float end, float viewSize) {
        float size = end - start;
        if (size <= viewSize) return (viewSize - size) / 2f - start;
        if (start > 0) return -start;
        if (end < viewSize) return viewSize - end;
        return 0f;
    }

    /** 가로로 충분히 밀었으면 +1(다음, 왼쪽으로 밀기) 또는 -1(이전). 아니면 0. */
    static int swipeDirection(float dx, float dy, float velocityX) {
        if (Math.abs(dx) < MIN_SWIPE_DISTANCE || Math.abs(dx) <= Math.abs(dy)
                || Math.abs(velocityX) < MIN_SWIPE_VELOCITY) {
            return 0;
        }
        return dx < 0 ? 1 : -1;
    }

    /** 다음 페이지 번호. 처음·끝을 넘지 않는다. */
    static int page(int current, int direction, int count) {
        if (count <= 0) return 0;
        return Math.max(0, Math.min(count - 1, current + direction));
    }
}
