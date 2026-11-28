package com.team4.bintime.timetable;

import android.content.Context;

import java.io.File;

/**
 * BT-030·BT-048: 로그아웃·탈퇴 때 시간표 인식 원본 사본({@link RecognitionImageCache}, 앱 캐시 {@code timetable_originals})을 비운다.
 * 검수 중 프로세스가 끝나 지우지 못한 이전 계정의 사본이 다음 계정 로그인 뒤까지 남지 않게 한다.
 * 업로드 화면 재진입 때의 {@link RecognitionImageCache#clearAll} 정리는 그대로 둔다.
 */
public final class TimetableOriginals {

    private TimetableOriginals() {}

    /** 디스크 작업이라 메인 스레드를 막지 않게 별도 스레드에서 지운다. 실패해도 로그아웃은 계속한다. */
    public static void clearInBackground(Context context) {
        File root = new File(context.getApplicationContext().getCacheDir(), RecognitionImageCache.DIRECTORY);
        new Thread(() -> {
            try {
                new RecognitionImageCache(root).clearAll();
            } catch (RuntimeException ignored) {
                // 남은 사본은 다음 업로드 화면 시작 때 clearAll 이 다시 정리한다.
            }
        }, "timetable-originals-clear").start();
    }
}
