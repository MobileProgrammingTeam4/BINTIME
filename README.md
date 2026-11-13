# BIN TIME ⏰

> **공강이 겹치는 학우를 찾아 함께 시간을 보낼 수 있도록 연결하는 대학생용 Android 앱.**

BIN TIME은 시간표를 바탕으로 공강을 계산하고, 사용자가 지정한 시간·활동 조건에 맞는 학우를 추천합니다. 사용자는 매칭 요청을 보내고, 상대방이 수락하면 정해진 공강 시간에 만나거나 1:1 채팅으로 소통할 수 있습니다. Gemini를 활용한 매칭 추천, 시간표 이미지 인식, 답장 및 장소 추천을 계획하고 있습니다.

> **문서 성격:** 확정 요구사항과 개발 계획을 기록한 저장소 README입니다. 아래 항목이 전부 구현 완료되었다는 뜻은 아닙니다. 실제 완료 여부는 등록된 **GitHub Issues 61개**, PR, 코드, 테스트 결과로 확인합니다.

## 1. 프로젝트 개요

| 항목 | 내용 |
|---|---|
| 프로젝트 | BIN TIME |
| 개발 기간 | 5주, 1주 단위 Sprint 5회 |
| 개발 인원 | 5명(A~E), A가 Scrum Master 겸임 |
| 플랫폼 | Android 앱 (Android Studio, Java, XML Views) |
| 컴파일 SDK (`compileSdk`) | API 37 — 빌드에 사용하는 Android SDK |
| 대상 SDK (`targetSdk`) | API 36 (Android 16) |
| 최소 지원 버전 (`minSdk`) | API 26 (Android 8.0) 이상 |
| 인증·업무 데이터 | Firebase Authentication, Cloud Firestore |
| 채팅 | Firebase Realtime Database |
| 파일·서버 | Firebase Storage, Cloud Functions(TypeScript) |
| AI | Gemini API (계획 모델: Gemini 3.5 Flash-Lite, 실제 호출 모델 ID는 배포 환경에서 확인) |
| 설계 기준 | [Figma 디자인](https://www.figma.com/design/jR1gI7EWb2WomXUuQvf67Z/2026%EB%85%842%ED%95%99%EA%B8%B0_%EB%AA%A8%EB%B0%94%EC%9D%BC2%EB%B6%84%EB%B0%98_%ED%8C%804?node-id=1-3) |
| 협업 | GitHub Issues · Labels · Milestones · Assignees · Pull Requests |

SDK 버전은 현재 `app/build.gradle.kts` 설정 기준입니다. Android Studio에서 빌드하려면 SDK Manager에 Android SDK Platform API 37을 설치하고, 실행 기기 또는 에뮬레이터는 API 26 이상을 사용합니다.

**현재 협업 운영 결정:** 61개의 GitHub Issues가 등록되어 있습니다. **develop 통합을 기준으로 PR–Issue 상태·종료 자동화와 Android CI를 운영하고, main은 최종 배포에 사용합니다.** 조직 GitHub Projects는 별도 권한·인증 설정을 마친 뒤 연결합니다. 자세한 설정과 미완료 조건은 [GitHub 자동화 운영 안내](./docs/GITHUB_AUTOMATION.md)를 참조하세요. 개발 로직과 기본 브랜치의 최소 진입점 설치 및 실제 실행 검증 전에는 상태 변경 성공을 가정하지 않습니다.

## 2. 주요 사용자 흐름

1. **학교 이메일 링크 로그인** → 이메일 인증 → 최초 프로필 설정.
2. 수업 직접 입력 또는 **여러 시간표 이미지 업로드 → Gemini 인식 → 사용자 검수·수정** → 시간표 저장.
3. 수업 사이 공강 계산 및 매칭에 사용할 공강 선택.
4. 매칭 스위치 ON → 최소 공강 겹침 시간·활동 목적 등 필터 선택.
5. 조건을 만족하는 학우 조회 → 프로필·AI 매칭 추천 점수 및 이유 확인 → 매칭 요청.
6. 요청 수락/거절/취소 → 확정된 겹침 시간 스냅샷으로 매칭 생성 → 1:1 채팅.
7. 채팅에서 AI 답장 제안·교내 장소 추천 이용 → 매칭 자동/수동 종료 → **종료 후에도 채팅 기록과 대화 유지**.

### 화면 및 탭 구성

| 영역 | 화면 / 탭 |
|---|---|
| 시작·인증 | 온보딩, 학교 이메일 입력, 인증 링크 안내/처리, 프로필 생성 |
| 매칭 | **`매칭 찾기` · `보낸 요청` · `받은 요청` · `완료된 매칭`** |
| 시간표 | 수업 입력·수정, 주간 시간표, 공강 선택, 이미지 업로드/인식 결과 확인 |
| 채팅 | **`전체` · `매칭됨` · `매칭종료`**, 1:1 대화, 답장 칩, 장소 추천, 채팅 옵션 |
| 마이 | 프로필 조회·수정, 계정 관리, 차단 관련 메뉴 |
| 부가 | 알림, 후기, 신고 등은 연결된 설계·우선순위에 따라 개발 |

**디자인 제약:** `일반 매칭`과 `즉석 매칭`을 **별도 탭/별도 기능으로 분리하지 않습니다**. 매칭 스위치 OFF, 선택 가능한 공강 없음, 검색 결과 없음은 각각 다른 상태로 처리하며, 특히 **결과 없음 전용 화면**을 구현합니다.

## 3. 기능·정책 명세

### 3.1 학교 이메일 인증 및 프로필

- 인증 방식은 **Firebase 이메일 링크 인증**입니다. **6자리 인증번호 입력 방식으로 구현하지 않습니다.**
- 허용된 **학교 이메일 도메인만** 회원가입할 수 있습니다. 허용 도메인은 배포 설정 또는 서버의 관리 설정으로 관리하며 임의의 도메인을 하드코딩하지 않습니다.
- **검증된 이메일 1개당 계정 1개** 정책을 적용합니다. 클라이언트 화면 검증뿐 아니라 인증 흐름·서버에서도 중복 및 도메인 조건을 검증합니다.
- 프로필은 닉네임, 학과/학년, 프로필 사진, 자기소개 및 선호 활동 태그 등의 설계 필드를 따릅니다. AI 자기소개·태그 추천은 보조 기능입니다.

### 3.2 시간표 및 공강

- 사용자는 수업을 직접 **추가·수정·삭제**할 수 있습니다. 직접 입력은 이미지 인식 실패 시에도 사용 가능합니다.
- 여러 이미지를 한 번에 선택하여 Gemini로 수업을 추출할 수 있으며, **중복 수업 정리·오인식 교정·최종 확인을 거쳐야 저장**합니다. 이미지 분석이 수동 입력을 대체하지 않습니다.
- 수업 시작/종료 시각과 공강은 **분 단위**로 계산합니다. 선택 UI가 30분 단위여도 **매칭 최소 겹침 필터의 `30분` 옵션과 혼동하지 않습니다**.
- 시간표 및 공강을 바꾸면 **향후 검색에 사용할 최신 공강을 다시 계산**하되, 이미 보낸/받은 요청·확정 매칭·채팅을 삭제하거나 과거 기준으로 재작성하지 않습니다.

### 3.3 매칭 필수 조건

- 검색자와 후보 학우의 **매칭 스위치가 모두 ON**이어야 합니다.
- 서로의 공강이 실제로 겹쳐야 하며, 검색자가 고른 **최소 겹침 시간 이상**이어야 합니다.
- 최소 시간 선택값: **`60 · 90 · 120 · 150 · 180분`**, 기본값은 **60분**입니다. `30분`, `전체(0)` 선택은 제공하지 않습니다.
- 활동 목적/선호 태그, 차단 관계, 이미 성사된 매칭의 시간 충돌 등도 필터·정책에 맞게 검증합니다.
- 현재 공강이 겹치는 후보를 우선 탐색하고, 없다면 가장 가까운 미래 공강을 탐색합니다. 구현 시 실제 **만남 날짜·시간대**를 명확하게 계산합니다.
- **규칙 기반 조건 충족이 먼저**이고 Gemini 점수는 조건을 만족한 후보의 추천·정렬에 활용합니다. AI가 필수 조건을 우회하도록 해서는 안 됩니다.
- AI 추천 점수는 내부 추천 지표이지 매칭 성공 확률을 보장하는 수치가 아닙니다.

시간 구간은 반열린 구간 `[startMinute, endMinute)`로 계산합니다.

```text
overlapMinutes = max(0, min(endA, endB) - max(startA, startB))
매칭 적격 = 양측 ON ∧ 조건 충족 ∧ overlapMinutes >= minOverlapMinutes
```

### 3.4 매칭 요청·수락·종료

- 요청은 `PENDING → ACCEPTED | REJECTED | CANCELED` 상태를 갖습니다.
- 요청과 확정 매칭에는 **요청 시점의 날짜·요일·겹친 시작/종료 시각**을 스냅샷으로 보관합니다.
- 동일 시간대 중복 수락/충돌을 방지하고, 매칭이 성사되면 상충하는 대기 요청을 정책에 따라 자동 정리합니다. **동시성 제어는 서버 트랜잭션**을 사용합니다.
- 확정 매칭의 상태는 `ACTIVE | ENDED`입니다.
- **겹치는 공강 종료 시 자동 종료**, 채팅 옵션에서 **수동 종료**할 수 있습니다. 시점·권한 판단은 서버를 기준으로 합니다.
- **매칭 진행 중에는 채팅방을 나갈 수 없습니다.** 매칭이 종료된 후에만 나가기 기능을 허용합니다. **매칭 종료만으로 채팅과 기록을 삭제하거나 대화를 차단하지 않습니다.**

### 3.5 채팅·AI·후기·알림

- 확정 매칭 참여자끼리 Realtime Database에서 **1:1 메시지를 실시간 송수신**합니다. 채팅방 상단에는 매칭 시간·남은 시간을 표시합니다.
- Gemini가 **추천 답장 3개**를 생성하면 칩 형태로 표시합니다. 사용자가 고르면 입력창에 들어가며 **AI가 자동 전송하지 않습니다**.
- Gemini는 시간표의 수업 위치/동선과 사전에 확인된 교내 장소 후보를 참고해 만남 장소를 추천합니다. **검증되지 않은 장소를 사실인 것처럼 생성하지 않습니다.**
- 후기는 설계에 정해진 공개 조건을 검증해 노출합니다. 신고·차단은 양측 권한 및 검색 제외 정책에 반영합니다.
- 알림 목록·FCM 푸시는 별도 이슈와 우선순위에 따라 진행하며, 동작 여부를 실제 구현과 구분해 안내합니다.

## 4. 기술 설계와 데이터 책임

**공통 데이터 계약과 시간 규칙은 [`docs/BT-012.md`](./docs/BT-012.md)를 먼저 확인합니다.** 필드 이름·UID·요청/매칭 식별자·분 단위 시간·Repository 연동을 변경할 때 이 문서의 팀 결정과 후속 확정 항목을 따릅니다.

| 구성 | 용도 | 핵심 원칙 |
|---|---|---|
| **Android Java/XML** | 화면·상호작용·상태 표시 | Activity/Fragment에서 서버 상태 변경 로직 분리 |
| **Firebase Auth** | 학교 이메일 링크 로그인 | UID 기준 소유권·인증 상태 검증 |
| **Firestore** | 프로필·시간표·공강·매칭·요청·후기·신고·알림 | 요청 수락 및 매칭 상태는 서버 신뢰 경계에서 변경 |
| **Realtime Database** | 채팅 메시지·채팅 목록 | 매칭 참여자만 읽기/쓰기 |
| **Storage** | 프로필 이미지·시간표 이미지 | 접근 제어 및 사용자별 경로 적용 |
| **Cloud Functions** | 매칭 검색·상태 트랜잭션·채팅 생성·Gemini 중계 | 인증 확인·입력 검증·재시도 시 멱등성 |
| **Gemini** | 시간표 추출·매칭 점수·답장·장소 추천·자기소개 | API 키 앱에 포함 금지, 서버에서 호출 |

### 데이터 경로 초안

아래는 팀 공통 **설계 기준**이며, 구현 저장소에 이미 더 최신의 승인된 스키마가 있다면 담당자와 합의하여 문서를 갱신합니다.

```text
Cloud Firestore
users/{uid}
  blockedUsers/{blockedUid}
publicProfiles/{uid}
timetables/{uid}
  courses/{courseId}
availability/{uid}
matchSettings/{uid}
matchRequests/{requestId}
matches/{matchId}
notifications/{uid}/items/{notificationId}
reviews/{reviewId}
reports/{reportId}
aiUsage/{uid}_{YYYY-MM-DD}

Realtime Database
chatRooms/{matchId}/members/{uid}
chatRooms/{matchId}/messages/{messageId}
userChatRooms/{uid}/{matchId}
```

- `uid`는 Firebase Auth UID입니다. 영업/민감 데이터는 클라이언트 소유권 규칙과 서버 검증으로 보호합니다.
- `dayOfWeek`: 월=1 ~ 일=7, `startMinute/endMinute`: 자정부터 지난 정수 분, **`meetingDate`: `YYYY-MM-DD`**, 시간대는 `Asia/Seoul` 기준으로 통일합니다.
- 공강 계산의 입력 버전(`timetables.revision`)과 파생 결과(`availability.sourceRevision`)를 비교해 오래된 공강으로 매칭하지 않습니다.
- `timetables/{uid}.freeCells`는 월~일 7개의 48비트 정수로 선택 원본을 보존합니다. 수업 변경은 `revision`을 증가시키고 공강 선택만 변경할 때는 유지합니다. `semester`는 없거나 null일 수 있습니다. `availability.slots`는 연속 칸을 병합한 60분 이상 구간이며, 서버는 원본 선택·수업·revision과 일치하는지 다시 검증합니다.
- 매칭 검색은 `searchMatches()` Callable이 저장된 `matchSettings/{uid}`로 판정합니다. 지금 겹치는 공강, 없으면 가장 가까운 미래 공강 **하나**를 기준으로 양측 ON·검색자가 고른 최소 겹침·목적·차단을 확인하고, 상대 UID(`candidateId`)와 겹침 시간·목적만 돌려줍니다. 이름·사진은 앱이 `publicProfiles`에서 읽습니다. 상세 계약은 [`docs/BT-017.md`](./docs/BT-017.md)를 따릅니다.
- 요청 생성 입력은 `{candidateId, clientRequestId}`이며 `candidateId`는 상대 UID, `clientRequestId`는 재시도 동안 유지하는 UUID입니다. 서버는 검색자가 선택한 최소 겹침, 프로필 완료·공개 프로필 존재, 차단·확정 매칭 충돌을 확인한 뒤 시간·목적 스냅샷을 정합니다. 상세 계약은 [`docs/BT-019.md`](./docs/BT-019.md)를 따릅니다.
- `matches/{matchId}`가 **매칭의 기준 데이터**입니다. 채팅 생성은 별도 RTDB 작업이므로 재시도·부분 실패를 고려해 연결 상태를 관리합니다.
- 개인 시간표 원본을 상대방에게 통째로 반환하지 않습니다. 검색 결과에는 필요한 겹침 시간 및 공개 가능한 프로필 정보만 담습니다.
- `aiUsage/{uid}_{YYYY-MM-DD}`(BT-024)는 Gemini 게이트웨이의 사용자별 하루 호출 수입니다. 날짜는 `Asia/Seoul` 기준이며 **서버(Cloud Functions)만 쓰고 앱은 읽기·쓰기 모두 금지**합니다. 자세한 계약은 [`docs/BT-024.md`](./docs/BT-024.md)를 참고합니다.

더 구체적인 역할별 데이터·상태 변경 경계 및 AI 에이전트 작업 규칙은 [`AGENTS.md`](./AGENTS.md)를 참고하세요.

## 5. 5인 담당 영역

**5명 모두 Android 프론트엔드 개발에 참여**하며, 각자 담당 백엔드·데이터 연동까지 구현합니다.

| 팀원 | 핵심 책임 | Android 화면/기능 | Firebase·서버 |
|---|---|---|---|
| **A · Scrum Master** | 매칭 검색·추천·전체 통합 | 매칭 찾기, 조건 필터, 결과 카드, 예외 화면, 공통 내비게이션 | `matchSettings`, 검색/AI 매칭률·캐시, 통합 테스트 |
| **B** | 인증·프로필·신고/차단 | 학교 이메일 링크 인증, 온보딩, 마이, 프로필 | Auth, `users`, `publicProfiles`, Storage, 차단·신고 |
| **C** | 시간표·공강·AI 이미지 인식 | 주간 시간표, 수업 CRUD, 공강 선택, 이미지 검수 | `timetables`, `availability`, 이미지 업로드·Gemini 추출 |
| **D** | 요청·확정/종료·알림 | 보낸/받은 요청, 완료된 매칭, 요청 팝업·알림 | `matchRequests`, `matches`, 상태 트랜잭션, 자동 종료, 알림·FCM |
| **E** | 채팅·Gemini 기능·후기 | 채팅 목록/대화, 답장 칩, 장소 추천, 후기 | RTDB 채팅, Gemini 공통 게이트웨이, 답장·장소·후기 |

**SM의 추가 책임:** 주간 스프린트 계획·진행 공유·리뷰·회고, 모듈 간 데이터 계약 협의, PR 리뷰·통합 빌드, 위험 관리 및 최종 시연. 역할 분담은 기본 책임 구분이며 API·보안 규칙 변경은 관련 담당자와 함께 검토합니다.

## 6. 5주 스프린트 및 GitHub Issues

기존 **61개 GitHub Issues**가 등록되어 있으며 `BT-001`~`BT-061`은 **계획 식별자**입니다. 실제 GitHub Issue의 `#번호`와 같다고 가정하지 마세요.

| Sprint | 범위 | 이슈 수 | 개발 목표 / 완료 게이트 |
|---|---|---:|---|
| **1주차** | BT-001~BT-012 | 12 | 환경 설정·데이터 계약·전체 주요 화면 목업; 5명 모두 빌드·화면 시연 |
| **2주차** | BT-013~BT-024 | 12 | Auth/프로필/시간표/검색/요청/채팅 Firebase 기본 연결, Gemini 서버 게이트웨이; **2계정 통합 시연** |
| **3주차** | BT-025~BT-037 | 13 | AI 매칭률·요청 수락 트랜잭션·채팅 연결·이미지 인식; **인증→AI 추천→수락→채팅 E2E** |
| **4주차** | BT-038~BT-051 | 14 | 매칭 종료·AI 답장·장소·후기·알림·보안/예외 연동, 시간표 변경 보존; **BT-050 Feature Freeze** |
| **5주차** | BT-052~BT-061 | 10 | 회귀·보안·동시성·AI 품질 테스트, 최종 APK, 시연·발표·인수인계 |
| **합계** | **BT-001~BT-061** | **61** | 5인·5주 기준 계획 (P0 42개 / P1 18개 / P2 1개) |

이슈 담당 분배: **A 13개 · B 11개 · C 12개 · D 12개 · E 13개**. 예상 개발 공수는 계획표 기준 약 **500시간**이며 실제 진척·공수와는 구분합니다.

### 중요도

- **P0:** 핵심 사용자 흐름과 필수 통합/검증 게이트. 특히 AI 매칭 점수(BT-027, BT-028), 매칭 종료와 채팅 유지(BT-038, BT-043), 시간표 변경 후 기존 매칭 보존(BT-047)은 P0입니다.
- **P1:** 다중 이미지 인식, AI 답장·교내 장소, 자기소개, 후기, 신고/차단, FCM 등 주요 확장 작업(각 이슈의 중요도를 따름).
- **P2:** 예를 들어 알림 종류별 세부 설정(BT-040)처럼 시간에 따라 제외 가능한 작업.

> 스프린트 마지막 주에 신규 기능을 확대하기보다, **이미 구현한 P0의 안정성·보안·실데이터 검증**을 우선합니다.

## 7. 개발 환경과 실행 준비

**필수 도구:** Android Studio, 프로젝트 Gradle/AGP와 호환되는 JDK 및 SDK, Git, Firebase 프로젝트 접근 권한. Cloud Functions 작업자는 Node.js LTS 및 Firebase CLI가 필요합니다.

```bash
# 저장소가 준비된 이후
 git clone <REPOSITORY_URL>
 cd <REPOSITORY_DIRECTORY>
```

1. **현재 저장소 구조를 먼저 확인**합니다. Android 프로젝트가 루트에 있을 수도, `android/`에 있을 수도 있습니다.
2. Android Studio에서 Android 프로젝트 디렉터리를 열고 Gradle Sync를 실행합니다.
3. 팀의 개발용 Firebase 프로젝트에 Android 앱을 등록하고, 해당 앱 모듈에 `google-services.json`을 넣습니다(저장소에 커밋하지 않음).
4. Auth/Firestore/Realtime Database/Storage/Functions의 **실제 활성화 및 보안 규칙**을 확인합니다.
5. 필요 시 Firebase CLI 로그인 후 Emulator Suite에서 서버 함수·Rules를 테스트합니다.

**예시 명령 — 해당 파일이 있을 때만 실행:**

```bash
# Android 프로젝트가 android/에 있고 wrapper가 있는 경우
cd android
./gradlew assembleDebug
./gradlew testDebugUnitTest
# Windows: gradlew.bat assembleDebug

# Cloud Functions에 npm scripts가 준비된 경우
cd functions
npm ci
npm run build
```

프로젝트 패키지명 `com.bintime.app`, minSdk API 26은 초기 논의의 **기본안**일 뿐입니다. 실제 `build.gradle(.kts)` 및 Manifest가 다르면 저장소 설정을 우선합니다. Gemini의 정확한 모델 식별자 역시 **현재 사용 가능한 API·계정 환경에서 검증한 뒤** 서버 설정으로 지정합니다.

### 환경 변수와 보안

- **절대 Git에 커밋하지 않기:** Gemini API 키, Firebase 서비스 계정 개인 키/JSON, `.env`의 비밀값, 서명 keystore, `local.properties`.
- 팀 정책상 `google-services.json`은 각자 Firebase Console에서 받아 로컬에 두고 Git에서 제외합니다.
- `firebase.json`, Firestore/RTDB/Storage Rules, 인덱스 설정 파일은 팀 검토 후 버전 관리합니다.
- **Gemini는 Cloud Functions에서 호출**하고 키는 Firebase Secrets 등 서버 비밀 저장소로 관리합니다.
- 운영 환경 배포·유료 기능 활성화·비용 설정 변경은 프로젝트 소유자 승인 없이 진행하지 않습니다.

## 8. 협업 규칙 — 커밋 · 브랜치 · PR · Issues

### 8.1 커밋 컨벤션

커밋 제목은 **`타입/변경 내용`** 형식으로 작성합니다. 타입은 아래 영문 소문자를 사용하고, 변경 내용은 한글로 작성해도 됩니다. `/` 뒤에 변경한 대상과 동작을 구체적으로 적습니다.

| 타입 | 사용 기준 | 예시 |
|---|---|---|
| `feat` | 새로운 기능·화면 추가 | `feat/보낸 요청과 받은 요청 탭 구현` |
| `fix` | 오류·잘못된 동작 수정 | `fix/마우스 스크롤이 끝까지 이동하는 문제 수정` |
| `docs` | 문서 작성·수정 | `docs/커밋과 브랜치 및 PR 규칙 정리` |
| `style` | 동작 변경 없는 코드 서식 정리 | `style/Java 들여쓰기 정리` |
| `refactor` | 동작을 유지하는 코드 구조 개선 | `refactor/공통 시간 요약 컴포넌트 분리` |
| `test` | 테스트 추가·수정 | `test/요청 수락과 취소 상태 검증 추가` |
| `chore` | 개발 환경·도구·기타 설정 변경 | `chore/Android Studio 설정 파일 Git 제외` |
| `ci` | CI·협업 자동화 변경 | `ci/Android 빌드 검사 워크플로 수정` |
| `revert` | 이전 커밋 되돌리기 | `revert/정렬 메뉴 변경 되돌리기` |

- 하나의 커밋에는 하나의 목적에 해당하는 변경을 담습니다. 관련 없는 수정은 분리합니다.
- 화면 디자인 변경은 새 기능이면 `feat`, 잘못된 표시 수정이면 `fix`를 사용합니다. `style`은 코드 서식 정리에 사용합니다.
- 설명이 더 필요하면 제목 다음 빈 줄을 두고 본문에 변경 이유·영향·검증 결과를 적습니다. 이슈 연결이 필요하면 본문에 `Refs #실제번호`를 작성합니다.
- `.env`, `google-services.json`, `local.properties`, 서비스 계정·키스토어 등 로컬 설정과 비밀값은 커밋하지 않습니다.

### 8.2 브랜치 규칙

작업 브랜치는 최신 `develop`에서 만들고, 기능·수정 작업을 마친 뒤 PR로 `develop`에 통합합니다. `main`은 모든 개발과 통합 검증을 마친 뒤 최종 배포에 사용합니다. 저장소에 설정된 보호 규칙과 승인된 팀 결정이 있으면 함께 준수합니다.

| 브랜치 | 역할 / 이름 형식 | 예시 |
|---|---|---|
| `main` | 모든 개발 완료 후 최종 배포 | `main` |
| `develop` | 팀 작업 통합 및 통합 검증 | `develop` |
| `feature/<area>-<BT-ID>` | 기능·화면 및 관련 구현 작업 | `feature/request-BT-007` |
| `fix/<area>-<BT-ID>` | 오류 수정 작업 | `fix/matching-BT-006` |

- `<area>`는 `common`, `auth`, `profile`, `timetable`, `matching`, `request`, `chat`, `notification`, `review` 등 실제 담당 영역을 사용합니다.
- `<BT-ID>`는 `BT-007`처럼 세 자리 계획 ID를 사용합니다. 실제 GitHub 이슈 번호와 같다고 가정하지 않습니다.
- 이슈가 없는 문서·설정 작업은 이슈 번호를 만들지 않고 `feature/docs-commit-rules`처럼 작업 설명을 사용합니다.
- `main`·`develop` 반영은 PR을 통해 진행하고, 작업이 병합된 뒤 더 이상 필요 없는 작업 브랜치를 정리합니다.

### 8.3 PR 규칙

- PR은 [`.github/PULL_REQUEST_TEMPLATE.md`](./.github/PULL_REQUEST_TEMPLATE.md)를 기준으로 작성합니다. 본문 항목과 검증 체크리스트는 해당 템플릿을 그대로 사용하며, README에서 별도의 작성 양식을 정의하지 않습니다.
- 템플릿의 `#ISSUE_NUMBER`는 실제 GitHub 이슈 번호로 바꾸고, 각 항목은 실제 작업·검증 결과에 맞게 작성합니다. 해당하지 않거나 실행하지 않은 항목은 사유를 명시합니다.
- 템플릿의 리뷰 요청 및 증거·후속 작업 항목을 확인하고, 작성 중이면 Draft로 관리합니다. 병합 대상 브랜치는 위 브랜치 규칙을 따릅니다.

### 8.4 Issues · Actions · 선택적 Projects

- GitHub **Issues 61개를 기준**으로 일정·담당자·선행 의존성·완료 조건을 관리합니다.
- 사용 가능한 **Milestones (`Sprint 1`~`Sprint 5`), Labels (`P0/P1/P2`, 영역, 상태), Assignees**를 관리합니다. 실제 등록된 라벨 이름을 확인하고 사용합니다.
- 상태는 `Todo → In Progress → In Review → Done`을 사용합니다. `develop` 대상 PR 본문의 `Closes #실제번호`를 기준으로 Draft는 In Progress, 리뷰 가능한 PR은 In Review, 병합 시 Done으로 전환하고 이슈를 완료 종료합니다. 일부 작업만 완료한 PR에는 `Related #번호`를 사용합니다.
- 개발 로직은 `develop`에서 관리합니다. `pull_request_target`은 기본 브랜치에서 실행되므로 `main`에는 승인된 최소 워크플로 진입점 설치가 필요합니다. 활성화 순서와 실제 실행 검증은 [자동화 운영 안내](./docs/GITHUB_AUTOMATION.md)를 따릅니다. 설정 전 또는 연결 이슈가 없는 PR의 상태는 수동으로 관리합니다.
- **조직 Projects 상태 동기화는 Project 생성과 별도 토큰 설정을 완료한 경우에만 활성화됩니다.** 설정과 제한사항은 [GitHub 자동화 운영 안내](./docs/GITHUB_AUTOMATION.md)를 따릅니다.

## 9. 완료 기준 (Definition of Done)

- [ ] 담당 GitHub Issue의 수용 기준·선행 이슈를 확인했다.
- [ ] Figma의 화면·탭·빈 상태를 구현하고 실제 Firebase 데이터와 연동했다(해당 작업에 한함).
- [ ] 서버 권한·중복 처리·오류·로딩·네트워크 실패를 테스트했다.
- [ ] 매칭 시간 경계값과 양측 ON, 요청 경합, 시간표 변경 후 스냅샷 보존을 확인했다.
- [ ] 종료 전 채팅방 나가기 금지, 매칭 종료 후 대화 유지, 참여자별 접근 제한을 확인했다.
- [ ] Gemini 장애/타임아웃 시 앱의 필수 흐름이 무너지지 않으며 키가 노출되지 않는다.
- [ ] Android 빌드 및 관련 테스트를 실행하고 결과를 Issue/PR에 남겼다.
- [ ] 다른 팀원이 리뷰할 수 있도록 변경 스키마·API·Rules 및 미해결 사항을 기록했다.

---

**AI 코드 에이전트/협업 도구 사용 시:** 저장소 루트의 [`AGENTS.md`](./AGENTS.md)를 먼저 읽고, **제품 정책·담당자 경계·검증 절차**를 준수해 주세요.
