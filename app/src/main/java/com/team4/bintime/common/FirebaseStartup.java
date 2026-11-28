package com.team4.bintime.common;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * 앱 시작 시 기본 FirebaseApp 을 만들지 정한다. 저장소 선택(AuthRepositories.isFirebaseEnabled = FirebaseApp 존재 여부)은 그대로다.
 *
 * <ul>
 *   <li>일반 실행: release 는 FirebaseInitProvider 가 이미 만들었으므로 건너뛰고, debug 는 provider 를 뺐으므로
 *       여기서 {@code FirebaseApp.initializeApp(context)} 로 같은 설정(google-services.json)을 만든다. 파일이 없으면 Mock.</li>
 *   <li>기기 테스트: runner(androidTest/BinTimeTestRunner)가 Application.onCreate 전에 {@link #overrideForTests} 로 고른다.
 *       MOCK 은 FirebaseApp 을 만들지 않아 FCM·Installations 도 시작하지 않는다. EMULATOR 는 demo 프로젝트 설정으로 만들고
 *       FCM 자동 초기화를 끈다. 테스트에서 FirebaseApp.delete() 로 모드를 바꾸지 않는다(백그라운드 스레드가 지워진 앱을 만나 죽는다).</li>
 * </ul>
 */
public final class FirebaseStartup {

    public enum Mode { MOCK, EMULATOR }

    @Nullable private static volatile Mode testMode;
    @Nullable private static volatile FirebaseOptions testOptions;

    private FirebaseStartup() {}

    /** 기기 테스트 runner 전용. 일반 실행에서는 아무도 부르지 않는다. EMULATOR 는 options(projectId 가 demo-)가 필요하다. */
    @VisibleForTesting
    public static void overrideForTests(Mode mode, @Nullable FirebaseOptions options) {
        if (mode == Mode.EMULATOR && (options == null || options.getProjectId() == null || !options.getProjectId().startsWith("demo-"))) {
            throw new IllegalArgumentException("EMULATOR mode needs demo- project options");
        }
        testMode = mode;
        testOptions = options;
    }

    @Nullable
    public static Mode testMode() {
        return testMode;
    }

    /** Application.onCreate 맨 앞에서 한 번 부른다. 이미 기본 앱이 있으면(release provider) 아무것도 하지 않는다. */
    static void init(Context context) {
        Mode mode = testMode;
        if (mode == Mode.MOCK) return;
        if (!FirebaseApp.getApps(context).isEmpty()) return;
        if (mode == Mode.EMULATOR) {
            FirebaseApp.initializeApp(context, testOptions);
            // demo 프로젝트는 실제 FCM 토큰을 받을 수 없다. 앱 데이터는 테스트마다 지워진다(clearPackageData).
            FirebaseMessaging.getInstance().setAutoInitEnabled(false);
            return;
        }
        FirebaseApp.initializeApp(context); // google-services.json 이 없으면 null(Mock)
    }
}
