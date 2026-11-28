package com.team4.bintime;

import android.os.Bundle;

import androidx.test.runner.AndroidJUnitRunner;

import com.google.firebase.FirebaseOptions;
import com.team4.bintime.common.FirebaseStartup;

/**
 * 기기 테스트 runner. Application.onCreate 전에 Firebase 모드를 고른다(BinTimeApplication → FirebaseStartup).
 *
 * <ul>
 *   <li>{@link FirebaseEmulatorTest} 클래스만 실행하면 EMULATOR: demo-bintime 설정으로 기본 앱을 만들고,
 *       테스트가 Auth·Firestore·Functions 를 10.0.2.2 로컬 에뮬레이터에 연결한다. 운영 프로젝트를 부르지 않는다.</li>
 *   <li>그 밖에는 MOCK: FirebaseApp 을 만들지 않으므로 FCM 자동 초기화·Installations·토큰 등록이 일어나지 않는다.</li>
 * </ul>
 * 오케스트레이터(app/build.gradle.kts)가 테스트마다 새 프로세스로 이 runner 를 띄우고 {@code class} 인자로 테스트 하나를 넘긴다.
 * 오케스트레이터 없이 여러 클래스를 한 번에 돌리면 MOCK 이 되고, 에뮬레이터 테스트는 {@link #requireEmulatorMode()} 에서 실패한다.
 */
public final class BinTimeTestRunner extends AndroidJUnitRunner {

    public static final String EMULATOR_PROJECT = "demo-bintime";

    @Override
    public void onCreate(Bundle arguments) {
        FirebaseStartup.Mode mode = onlyEmulatorTests(arguments == null ? null : arguments.getString("class"))
                ? FirebaseStartup.Mode.EMULATOR : FirebaseStartup.Mode.MOCK;
        FirebaseStartup.overrideForTests(mode, mode == FirebaseStartup.Mode.EMULATOR ? emulatorOptions() : null);
        super.onCreate(arguments);
    }

    /** 에뮬레이터 테스트의 @Before 에서 부른다. 다른 모드로 시작됐으면 원인을 알려 주고 실패한다. */
    public static void requireEmulatorMode() {
        if (FirebaseStartup.testMode() != FirebaseStartup.Mode.EMULATOR) {
            throw new AssertionError("이 테스트는 @FirebaseEmulatorTest 클래스만 실행하는 프로세스(오케스트레이터 기본 설정)에서 돌아야 합니다.");
        }
    }

    /** {@code class} 인자(쉼표로 구분, 각 항목은 Class 또는 Class#method)의 모든 클래스가 에뮬레이터 테스트인지. */
    static boolean onlyEmulatorTests(String classArgument) {
        if (classArgument == null || classArgument.trim().isEmpty()) return false;
        for (String entry : classArgument.split(",")) {
            String name = entry.trim();
            int hash = name.indexOf('#');
            if (hash >= 0) name = name.substring(0, hash);
            try {
                if (!Class.forName(name).isAnnotationPresent(FirebaseEmulatorTest.class)) return false;
            } catch (ClassNotFoundException e) {
                return false;
            }
        }
        return true;
    }

    private static FirebaseOptions emulatorOptions() {
        // SDK 가 형식만 검사하는 자리 표시 값이다(Google 이 발급한 키가 아님). 에뮬레이터만 받는다.
        String placeholderKey = "AIza" + new String(new char[35]).replace('\0', '0');
        return new FirebaseOptions.Builder().setProjectId(EMULATOR_PROJECT)
                .setApiKey(placeholderKey).setApplicationId("1:123456789:android:abcdef1234567890").build();
    }
}
