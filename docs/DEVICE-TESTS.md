# Android 기기 테스트 실행 (androidTest)

## 모드 선택

기기 테스트는 앱이 시작되기 **전에** Firebase 모드를 고른다. 테스트 안에서 `FirebaseApp.delete()` 로 모드를 바꾸지 않는다.
지우면 FCM·Installations 백그라운드 스레드가 지워진 앱을 만나 `IllegalStateException: FirebaseApp was deleted` 로 앱 프로세스가 죽는다.

| 테스트 클래스 | 모드 | 동작 |
|---|---|---|
| 표시 없음(기본) | MOCK | FirebaseApp 을 만들지 않는다. 저장소는 Mock, FCM 자동 초기화·토큰 등록 없음. 운영 프로젝트를 부르지 않는다 |
| `@FirebaseEmulatorTest` | EMULATOR | `demo-bintime` 설정으로 기본 앱을 만든다. 테스트가 Auth·Firestore·Functions 를 `10.0.2.2` 로컬 에뮬레이터에 연결한다 |

- 선택: `app/src/androidTest/.../BinTimeTestRunner` → `common/FirebaseStartup` → `common/BinTimeApplication.onCreate`.
- debug 빌드만 `FirebaseInitProvider` 를 뺐다(`app/src/debug/AndroidManifest.xml`). 일반 debug 실행은 `BinTimeApplication` 이 같은 `google-services.json` 설정으로 앱을 만든다. release 는 provider 를 그대로 쓴다.
- 저장소 선택(`AuthRepositories.isFirebaseEnabled` = 기본 FirebaseApp 존재 여부)은 바뀌지 않았다.

## 테스트별 새 프로세스 (Android Test Orchestrator)

`app/build.gradle.kts` 의 `testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"`, `clearPackageData=true`.

- 테스트마다 앱 프로세스를 새로 띄우고 앱 데이터를 지운다. 한 번의 실행에 MOCK·EMULATOR 테스트가 섞여도 각자 맞는 모드로 시작하고, Mock 저장소(앱 전역 객체)의 상태가 다음 테스트로 넘어가지 않는다.
- 앱 데이터와 함께 **런타임 권한도 초기화**된다. 알림 권한 창이 화면을 가리지 않도록 테스트가 먼저 허용한다(`NotificationInboxDeviceTest.localOnly`).
- `orchestrator`·`test-services` 는 `androidTestUtil` 로만 들어가는 **테스트 전용 의존성**이다. 앱 APK 에는 포함되지 않는다.

## 실행

```bash
# MOCK 테스트만 있으면 에뮬레이터 없이 바로
./gradlew connectedDebugAndroidTest

# EMULATOR 테스트(알림 목록·설정)까지: 로컬 Firebase 에뮬레이터를 먼저 켠다
cd functions && npm ci && npm run build && cd ..
firebase emulators:start --project demo-bintime --only auth,firestore,functions
```

`NotificationSettingsDeviceTest` 는 Auth 에뮬레이터에 **이메일 인증된** 계정 `bt040-qa@school.ac.kr` / `local-emulator-only` 가 있어야 한다(서버가 인증된 학교 이메일만 받는다). 에뮬레이터 UI(Authentication)에서 만들고 "Email verified" 를 켠다. 인증되지 않은 계정이면 설정 저장이 거부되어 `availability` 확인에서 실패한다.

에뮬레이터 테스트 클래스 하나만 Android Studio 에서 따로 실행해도 runner 가 EMULATOR 로 시작한다. 오케스트레이터 없이 MOCK·EMULATOR 클래스를 한 프로세스에서 섞어 돌리면 EMULATOR 테스트는 `BinTimeTestRunner.requireEmulatorMode()` 에서 원인을 알리며 실패한다.
