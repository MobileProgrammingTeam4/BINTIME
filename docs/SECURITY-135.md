# #135 공개 전 보안 보완·운영 인수인계

실제 GitHub Issue [#135](https://github.com/MobileProgrammingTeam4/BINTIME/issues/135)의 문서입니다. 기존 BT 계획에 번호를 새로 부여하지 않습니다. 코드 병합은 운영 보안 설정이나 저장소 공개 전환 완료를 뜻하지 않습니다. 운영 설정·배포·키 변경·공개 전환은 관리자 승인 후 별도로 진행합니다.

## 구현 경계와 병합 순서

1. [#136](https://github.com/MobileProgrammingTeam4/BINTIME/pull/136): `firestore.rules`·`storage.rules`의 공개 프로필/사진 읽기에 검증된 학교 이메일 적용. Auth 토큰만 신뢰하며 기존 쓰기·개인정보·채팅 권한은 유지합니다. 상세는 [BT-014](./BT-014.md).
2. 후속 보안 PR: #136을 선행으로 통합하고 Android App Check·Callable 공통 설정·로컬 시크릿 검사를 추가합니다. #136부터 develop에 병합합니다. #129~131 등 후속 PR과 충돌하면 각 경로의 차단·탈퇴·신고 보호를 모두 보존합니다.
3. 이 문서의 수동 항목 및 실제 운영 증거가 남아 있으므로 PR은 `Related #135`로 연결하고 이슈를 닫지 않습니다.

데이터 스키마·기존 AI 호출량 제한·요청/매칭/채팅 정책은 변경하지 않습니다. 운영 DB 조회/삭제·함수 배포·FCM/Gemini 호출·과금/가시성 변경은 이번 구현 검증에 포함하지 않습니다.

## App Check 코드 계약

| 경로 | 동작 |
|---|---|
| `common/BinTimeApplication.java` | Activity/Repository보다 먼저 기본 Firebase 앱에 provider 설치. 설정 파일이 없는 Mock과 `demo-` 프로젝트는 실제 attestation을 시작하지 않음 |
| `app/src/debug/.../AppCheckProviderInstaller.java` | Debug provider. 개발 PC/AVD별 토큰을 콘솔에 비공개 등록해야 함 |
| `app/src/release/.../AppCheckProviderInstaller.java` | Play Integrity provider. Debug SDK는 release 의존성에 포함하지 않음 |
| `functions/src/security/appCheck.ts` | v2 `setGlobalOptions`로 모든 Callable에 공통 적용. `index.ts`의 첫 import를 유지 |

`APP_CHECK_ENFORCED`는 **서버 배포 환경의 설정**입니다. 요청 본문으로 바꿀 수 없습니다.

| 값 | Callable 동작 |
|---|---|
| 미지정 또는 `false` | 등록/모니터링 단계. 토큰 없는 호출도 기존 Auth·학교 이메일·권한 검증까지 진행. 공식 앱 외 호출 차단이 완료된 상태가 아님 |
| `true` | 승인된 재배포 후 누락/잘못된 App Check 토큰을 SDK가 핸들러 실행 전에 거부 |
| 그 외 값 | 오타로 보호가 조용히 꺼지지 않도록 초기화 실패 |
| `FUNCTIONS_EMULATOR=true` | 로컬 합성 테스트에서는 attestation 강제 안 함. 실제 토큰 검증을 대체하는 증거가 아님 |

현재 `.env.example`은 `false`이며 운영 강제는 실행하지 않았습니다. 이후 담당자는 배포 프로젝트의 환경 설정에 값을 명시하고, 이미 `true`인 운영 값을 실수로 지우거나 `false`로 덮어쓰지 않습니다. `.env.*`에 비밀값을 넣지 않습니다. 함수 내부 모듈을 직접 실행하는 테스트의 `.run()`은 App Check 미들웨어를 건너뛰므로 강제 검증으로 보고하지 않습니다.

적용 범위는 현재 진입점의 AI Callable(`aiPing`, `analyzeTimetable`, `generateProfile`, `recommendPlace`, `suggestChatReplies`), `searchMatches`, `createMatchRequest`, `cancelMatchRequest`, `respondToMatchRequest`, `ensureMatchChat`입니다. 이후 병합되는 v2 Callable도 같은 진입점을 사용하면 상속합니다. 개별 `enforceAppCheck` 옵션으로 덮어쓰지 않도록 회귀 검사합니다. HTTP `onRequest`·예약/DB 트리거·서버 내부 함수의 보호를 이 설정으로 주장하지 않습니다. Firestore/RTDB/Storage 강제는 콘솔의 서비스별 별도 설정입니다.

## 사용자가 직접 해야 할 설정

아래는 **미실행**입니다. 관리자/서비스 담당자가 값 원문 없이 완료 여부·대상 앱/서비스·시각·검증 결과를 #135에 기록합니다.

| 순서 | 담당·수동 작업 | 완료 증거 |
|---|---|---|
| 1 | A/B·프로젝트 관리자: Firebase `bintime-mobile`의 Android 앱 `com.team4.bintime` 확인. 실제 팀원 debug/최종 release 서명의 SHA-1·SHA-256 등록. 로컬 `gradlew.bat signingReport`로 확인하되 keystore·비밀번호 공유 금지 | 올바른 앱과 서명 등록, 이메일 링크 앱 진입 유지. #86의 서명/도메인 작업과 조율 |
| 2 | 관리자: App Check의 Android 앱에 Play Integrity 등록·연결. 실제 APK 배포 경로(Play 배포/직접 APK)에 맞는 고급 설정 확인 | 실제 서명된 release·실기기의 유효 요청 확인 |
| 3 | 각 개발자: debug 앱으로 실제 Firebase 요청 후 자신의 로컬 Logcat에서 SDK Debug token 확인 → Firebase App Check 앱의 Debug tokens에 비공개 등록 | Pixel 10 AVD/각 PC의 유효 요청. 토큰은 채팅·Git·PR·전체 Logcat 첨부 금지 |
| 4 | A~E·관리자: 호환 앱을 배포하고 App Check 요청 지표에서 유효/누락/잘못된 토큰을 확인. 모든 정상 클라이언트 준비 후만 강제 전환 | 학교 링크 로그인·프로필·요청 사진·검색·수락·채팅·AI의 정상 요청 증거. 실제 AI 테스트 횟수는 최소화하고 무료 사용량 내에서 승인 후 수행 |
| 5 | B·관리자: 운영 Firestore/Storage 규칙을 내려받아 최신 develop과 비교하고 #136 학교 인증 규칙을 승인된 범위로 게시. RTDB 운영 규칙도 비교 | 이전 감사의 RTDB HTTP 401 미확인 해소, 기존 참여자/개인정보 권한 유지, 게시 후 파일 일치 |
| 6 | 각 Callable 소유자·관리자: 배포 환경 `APP_CHECK_ENFORCED=true` 명시 후 승인된 Callable 목록만 선택 재배포. Firestore/RTDB/Storage는 합의한 서비스부터 콘솔 강제 | 유효 토큰 허용·누락/잘못된 토큰 거부, Auth/학교/참여자 검증 유지. 콘솔 스위치만 켜고 Callable 배포를 생략하지 않음 |
| 7 | Google Cloud 키 관리자: Android 키에 `com.team4.bintime`과 실제 각 SHA-1 허용 항목 등록. browser 키는 이메일 링크/Hosting의 실제 출처·경로와 용도를 확인해 제한 | 키 원문 없는 변경 전후 설정, AVD/실기기 이메일 링크 로그인 성공 |
| 8 | 저장소 관리자: GitHub Settings → 보안 설정에서 Secret scanning·Push protection 사용 가능 여부 확인 후 허용된 무료 범위로 활성화. 유료 전환은 별도 결정 | 활성 상태 확인. private에서 제공되지 않으면 그 제한을 기록하고 공개 전환 시 재확인 |
| 9 | SM·전원: 아래 자료 검토 및 공개 준비 기준 확인 후에만 저장소 공개 여부 결정 | #135 체크리스트·리뷰·운영 증거, 관리자 최종 판단 |

Debug SDK는 토큰을 개발자 로컬 Logcat에 한 번 안내합니다. 앱 코드에서는 토큰을 출력하지 않으며, SDK 로그 역시 공개 로그/스크린샷에 포함하지 않습니다. 공유되었다면 해당 debug 토큰의 콘솔 폐기는 관리자와 처리합니다. release에 Debug provider나 개발 토큰을 넣어 문제를 우회하지 않습니다.

직접 APK 배포에서는 Play Integrity 기본 설정의 `PLAY_RECOGNIZED` 요구가 맞지 않을 수 있습니다. Google Play 외 배포 지원 설정을 공식 표에 따라 확인하고 실제 기기로 검증합니다. unsigned release 빌드 성공만으로 attestation 성공을 주장하지 않습니다. 토큰 TTL은 SDK/콘솔 기본값을 유지하며 무분별한 강제 갱신·재시도·추가 유료 제공자 도입은 하지 않습니다.

Firebase 앱의 인증서 등록, API 키의 Android/SHA-1 제한, App Check의 SHA-256/provider 등록은 각각 별개입니다. API 제한의 기존 Firebase API 목록은 로그인·설치·Storage 등 실제 사용 SDK에 맞춰 보존하며 **클라이언트 키에 Generative Language API를 추가하지 않습니다.** Gemini 키는 서버 Secret Manager만 사용합니다. 키 원문 조회/회전은 이 작업에 필요하지 않습니다.

단계별 되돌리기: 변경 전 제한/규칙 설정을 비공개로 보관합니다. 정상 앱이 막히면 변경한 서비스의 App Check 강제를 먼저 해제하고 원인을 확인합니다. Callable은 해당 배포 환경을 `false`로 되돌려 승인된 함수만 재배포해야 하며 콘솔 해제만으로 해결되지 않습니다. 키 제한은 직전 검증된 허용 목록으로 되돌리고 임의의 전체 허용으로 바꾸지 않습니다. Auth·학교 인증·기존 소유자/참여자 Rules는 제거하지 않습니다. 변경/롤백은 모두 승인된 운영 작업입니다.

## 로컬 시크릿 검사 (Actions 사용량과 무관)

Node.js 24, Git, **Gitleaks 8.30.1**을 준비합니다. [공식 release](https://github.com/gitleaks/gitleaks/releases/tag/v8.30.1)의 OS별 바이너리와 checksums를 받아 SHA-256을 확인한 후 PATH에 둡니다. Windows x64 ZIP의 SHA-256은 `d29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e`입니다. 바이너리는 커밋하지 않습니다. PATH 대신 PowerShell의 `$env:GITLEAKS_BIN='설치한 gitleaks.exe 절대 경로'`를 사용할 수 있습니다.

저장소 루트에서 실행:

```text
node scripts/secret_scan.cjs --staged
node scripts/secret_scan.cjs --history
node --test scripts/secret_scan.test.cjs
git config --local core.hooksPath .githooks
```

마지막 명령은 **각자 한 번 직접 설정**합니다. 기존 hooksPath가 있다면 `git config --get core.hooksPath`로 확인하고 팀 도구와 통합한 뒤 바꿉니다. 이 PR은 사용자 로컬 Git 설정을 자동 변경하지 않습니다. Windows Git Bash의 hook에서도 `node`와 Gitleaks가 실행되어야 합니다.

- `--staged`: Git index의 diff 검사. 미스테이징 파일 검사가 아니며, 커밋할 내용을 먼저 `git add`합니다.
- `--history`: 현재 로컬에 도달 가능한 **모든 refs**의 Git 이력 검사. 원격에만 있거나 도달 불가능한 객체/삭제 로그까지 검사했다는 뜻이 아닙니다. 공개 검토 전 필요한 원격 refs를 최신화합니다.
- 검사 누락/실행 오류는 종료 코드 2, 의심 키는 1, 정상은 0. hook은 오류 시에도 커밋을 막습니다. 키 의심 시 원문 대신 파일·줄·규칙만 출력하며 redacted 임시 보고서는 검사 후 제거합니다. `gitleaks:allow` 인라인 우회 주석은 허용하지 않습니다.
- 합성 토큰으로 staged 탐지, 삭제된 이력 탐지, 원문 미출력, 바이너리 누락 시 차단을 회귀 검사합니다. `.gitignore`는 서비스 계정 JSON·토큰 파일 이름을 추가로 제외하지만 이미 추적된 파일/이력을 지워 주지 않습니다.
- hooks는 clone만으로 자동 활성화되지 않고 우회할 수도 있습니다. 로컬 검사·GitHub push protection·수동 검토를 함께 적용합니다. 실제 유출 발견 시 원문 없이 위치만 공유하고 관리자에게 폐기/교체 및 이력 처리 판단을 요청합니다.

## 검증과 남은 공개 전 확인

로컬 실행 결과(2026-11-25):

| 검증 | 결과 |
|---|---|
| `gradlew.bat assembleDebug assembleRelease testDebugUnitTest lintDebug --console=plain` | 통과. signed release 실기기 실행은 미실행 |
| APK의 모든 `classes*.dex` provider descriptor 검사 | debug에 Debug만, release에 Play Integrity만 포함 |
| `npm --prefix functions run build` | 통과 |
| `npm --prefix functions test` | 기존 264개 + App Check 정책/SDK HTTP 미들웨어 7개, 총 271개 통과 |
| `firebase emulators:exec --project demo-bintime --only firestore,database,storage "npm --prefix functions run test:emulator"` | 90개 통과. 합성 demo 데이터, 운영 데이터 미사용 |
| `node --test scripts/secret_scan.test.cjs` | 합성 Git 저장소 탐지/차단 테스트 통과 |
| `node scripts/secret_scan.cjs --history` | 도달 가능한 전체 Git 이력에서 탐지 0건 |

App Check HTTP 미들웨어 테스트는 `true`일 때 누락/잘못된 형식 토큰을 거부하고 `false`일 때 기존 핸들러에 도달하는지 확인합니다. **실제 유효 토큰 발급·서명 검증·실기기 로그인/AI E2E는 미실행**이며 수동 항목으로 남깁니다. Rules 테스트에서 email claim이 없는 토큰 평가 경고는 읽기 거부 결과와 구분합니다. 에뮬레이터 Admin SDK의 메타데이터 탐색 경고도 통과 결과를 운영 연결 검증으로 바꾸지 않습니다.

초기 #135 감사의 PR/이슈 텍스트 검사와 로컬 이력 검사에서 실제 키 유출은 발견되지 않았습니다. 다만 **스크린샷·PDF 내부 개인정보/인증 링크·과거 Actions 로그·캐시된/도달 불가능한 커밋을 전수 검토한 상태는 아닙니다.** 저장소 관리자/자료 소유자는 공개될 사진/학교 이메일/대화·신고 내용·이메일 인증 URL을 확인해야 합니다. 패턴 탐지 0건은 이런 자료가 안전하다는 보증이 아닙니다.

CI는 조직 Actions 사용량 제한으로 실행되지 않았으며 재실행·한도 상향을 하지 않습니다. #133/#134 최적화와 별도입니다. 무료 사용량/예산 및 앱의 사용자별 호출 제한은 기존 정책을 유지하며 App Check를 비용의 절대 상한으로 간주하지 않습니다. 운영 강제/키 제한·정상 흐름·공개 자료 확인이 끝나기 전에는 #135 전체 완료 또는 공개 전환 준비 완료로 보고하지 않습니다.

## 공식 자료

- [Android Play Integrity 등록·배포 경로 설정](https://firebase.google.com/docs/app-check/android/play-integrity-provider)
- [Android Debug provider](https://firebase.google.com/docs/app-check/android/debug-provider)
- [Callable App Check 강제](https://firebase.google.com/docs/app-check/cloud-functions)
- [v2 GlobalOptions](https://firebase.google.com/docs/reference/functions/2nd-gen/node/firebase-functions.globaloptions)
- [Firebase API 키 관리·제한](https://firebase.google.com/docs/projects/api-keys)
