package com.team4.bintime;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 로컬 Firebase 에뮬레이터(demo-bintime)를 실제 SDK 로 쓰는 기기 테스트 클래스에 붙인다.
 * 붙이지 않은 클래스는 Mock 모드(FirebaseApp 없음)로 앱이 시작된다. 모드는 {@link BinTimeTestRunner} 가 앱 시작 전에 고른다.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface FirebaseEmulatorTest {
}
