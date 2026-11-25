# AGENTS.md — BIN TIME AI 개발 에이전트 하네스

> 이 파일은 GitHub 저장소의 **루트**에 둡니다. 코딩 에이전트(Cursor/Codex/Claude Code 등) 또는 자동 코드 생성 도구가 BIN TIME을 수정할 때 적용할 **작업 계약**입니다. `README.md`와 함께 읽습니다. **요구사항/계획을 이미 구현된 코드로 착각하지 마세요.**

## 0. 역할과 우선순위

- 당신은 **BIN TIME의 개발 보조 에이전트**입니다. 사용자가 요청한 이슈 범위 안에서 코드·테스트·문서를 작성합니다.
- 핵심 목표는 **5명/5주/61개 GitHub Issues** 계획에 맞춰 **Java/XML Android + Firebase + Gemini** 사용자 흐름을 안전하게 연결하는 것입니다.
- 최신의 **명시적 팀 결정과 현재 요청**이 예전 문서 초안보다 우선합니다. 피그마는 화면/표현 기준, 실제 저장소 코드는 이미 구현된 구조와 버전의 기준, GitHub Issues의 수용 기준은 작업 범위 기준입니다. 충돌은 보고하고 임의로 요구사항을 바꾸지 않습니다.
- **GitHub Actions 기반 PR–Issue 상태 동기화와 Android CI를 도입합니다.** 조직 Projects는 별도 생성·토큰·프로젝트 필드 설정이 끝나면 동기화합니다. 설정과 제한사항은 `docs/GITHUB_AUTOMATION.md`를 따릅니다. 이 작업은 사용자 요청에 의한 협업 자동화이며 기존 제품 요구사항은 변경하지 않습니다.
- 관리자 승인 없이 배포, 외부 서비스 연결, 결제 설정 변경, 데이터 삭제·마이그레이션, 비밀 키 생성·교체·노출을 실행하지 않습니다.

## 1. 매 작업 시작 시 실행할 프리플라이트

1. `pwd`, `git status`, `git branch --show-current`, 폴더 구조를 확인합니다. 기존 사용자 변경 사항을 덮어쓰지 않습니다.
2. 루트의 `README.md`와 `AGENTS.md`, 공통 계약·시간 규칙 문서 [`docs/BT-012.md`](./docs/BT-012.md), 관련 BT Issue와 선행 이슈를 읽습니다. **BT-###은 계획 ID이며 실제 GitHub #번호가 아님**에 유의합니다.
3. Android 프로젝트 위치(루트 또는 `android/`), `build.gradle(.kts)`, `settings.gradle(.kts)`, `gradlew` 및 `functions/`, `firebase.json`, Rules 파일의 실제 존재 여부를 확인합니다.
4. 변경할 화면·경로·함수·데이터 필드, 담당 영역(A~E), 보안 영향, 검증 명령을 먼저 정리합니다.
5. 필요한 **최소 범위로만 수정**합니다. 요청되지 않은 SDK 업그레이드·패키지 이동·의존성 추가·일괄 리팩터링을 하지 않습니다.
6. 테스트 가능 범위는 실제 실행하고, 실행할 수 없는 경우 **실행하지 않았다고 명시**합니다. 시연 데이터와 운영 데이터를 혼동하지 않습니다.

**기본 완료 보고 양식**

```text
[BT-ID / 실제 Issue #] 작업 요약
- 변경 파일:
- UI/데이터/서버 수정 사항:
- 검증: 실제 실행 명령 / 통과 여부 / 미실행 사유
- 보안/권한·스키마 영향:
- 후속 작업 및 남은 위험:
```

## 2. 제품 불변 조건 (Hard constraints)

### 화면·탭

- BIN TIME은 **대학생 공강 메이트 앱**입니다. 데이팅 앱 기능/카피를 만들지 않습니다.
- 매칭 탭은 **`매칭 찾기` / `보낸 요청` / `받은 요청` / `완료된 매칭`** 4개입니다.
- 채팅 탭은 **`전체` / `매칭됨` / `매칭종료`** 3개입니다.
- **`일반 매칭`/`즉석 매칭`을 별도 탭이나 서로 다른 기능으로 생성하지 않습니다.** 현재 겹침 우선/가까운 미래 탐색은 **하나의 매칭 검색 정책**입니다.
- `매칭 스위치 OFF`, `매칭에 사용할 공강 없음`, `검색 결과 없음`의 UI 상태는 각각 구분합니다. **결과 없음 화면**을 생략하지 않습니다.
- Figma 디자인: https://www.figma.com/design/jR1gI7EWb2WomXUuQvf67Z/2026%EB%85%842%ED%95%99%EA%B8%B0_%EB%AA%A8%EB%B0%94%EC%9D%BC2%EB%B6%84%EB%B0%98_%ED%8C%804?node-id=1-3

### 인증

- **Firebase Auth 이메일 링크 방식**을 사용합니다. 6자리 OTP 화면을 추가하지 않습니다.
- 학교 이메일 허용 도메인 검증, **검증된 이메일당 한 계정** 정책, 로그인 복원·로그아웃·탈퇴를 고려합니다.
- UID를 신뢰할 수 있는 토큰/SDK에서 확인합니다. 요청 본문의 UID를 신원으로 사용하지 않습니다.

### 시간표·매칭

- 수업 직접 입력 CRUD는 항상 제공하며, **다중 이미지 선택 → Gemini 인식 → 중복 제거 → 사용자 검수/수정 → 저장** 흐름을 별도 지원합니다.
- 공강 선택 UI의 30분 눈금과 최소 매칭 겹침 필터를 구분합니다. 허용 최소 겹침 필터는 **`60`, `90`, `120`, `150`, `180`분**만; 기본 `60`. **`0`과 `30`은 필터 값이 아닙니다.**
- 검색자와 후보의 **매칭 스위치 모두 ON**, 실제 공강 교집합 **`>=` 사용자가 선택한 최솟값**, 목적/차단/기존 매칭의 충돌 정책을 **AND 조건**으로 검증합니다.
- **지금 겹치는 공강 우선**, 없으면 **가장 가까운 미래 공강**을 사용합니다. 날짜가 다른 슬롯을 잘못 비교하지 않습니다.
- 매칭 적격 여부는 서버 규칙이 판정합니다. **Gemini 추천 점수로 부적격 후보를 복구하거나 우회하지 않습니다.**
- 시간표/공강 변경 후 **기존 보낸 요청·받은 요청·확정 매칭·채팅을 보존**합니다. 신규 검색 데이터만 최신 공강으로 갱신하며 기존 요청/매칭의 **시간 스냅샷은 불변**입니다.

### 요청·채팅·종료

- 요청 상태: `PENDING`, `ACCEPTED`, `REJECTED`, `CANCELED`.
- BT-032 상충/만료 정리는 `docs/BT-032.md`를 따른다. `matchCleanupJobs`는 서버 전용이며 자동 정리는 직접 취소 cooldown을 변경하지 않는다. 5분 주기 예약 코드와 실제 운영 활성화를 구분한다.
- 매칭 상태: `ACTIVE`, `ENDED`.
- BT-038 종료·후기 자격·방 상태 복구 및 BT-043/044 인수인계는 `docs/BT-038.md`를 따른다. `roomStateJobs`는 서버 전용이며 종료는 채팅 쓰기 권한을 변경하지 않는다.
- BT-033부터 개별 matchId와 실제 chatRoomId를 구분합니다. 서버 전용 chatPairs/chatLinkJobs, 현재 참여자 읽기 전용 chatRoomLinks 및 방의 archived/writable 계약은 `docs/BT-034.md`를 따릅니다. 여러 약속의 대표·종료/후기 선택과 비활성 MessageComposer는 `docs/BT-035.md`의 제공 컴포넌트를 재사용합니다.
- 둘 다 나간 방은 목록 hidden·멤버 제거로 양측 접근을 차단하고 서버에만 보관한다. 보관 기간·최종 삭제는 신고 담당·SM 후속 합의 전 자동 실행하지 않는다. 한 명 이탈 방의 남은 사람 읽기 권한은 유지한다.
- 서버가 요청 수락/거절/취소를 검증하고 **동시 수락과 상충 요청**을 트랜잭션/멱등 처리로 보호합니다.
- 매칭 자동 종료는 **확정된 겹침 시간 종료 시점**을 기준으로 하며, 채팅 옵션의 수동 종료도 지원합니다.
- **ACTIVE 매칭은 채팅방 나가기 불가**. **ENDED 이후에만 나가기 허용**. 매칭 종료 후에도 채팅을 계속 사용할 수 있으며, 종료 이벤트로 기록을 삭제하거나 전송을 막지 않습니다.
- 채팅 참여자만 메시지를 읽고 쓸 수 있습니다. AI 추천 답장 **3개를 칩으로 표시**하고, 사용자가 선택한 텍스트만 입력창에 채웁니다. **자동 전송 금지**.
- 교내 장소 추천은 수업 위치·시간과 **검증된 교내 장소 목록**을 근거로 하며, 임의의 건물·매장·운영 시간을 지어내지 않습니다.
- 후기 공개 조건, 차단/신고, 알림/FCM은 **해당 BT 이슈 수용 기준**에 맞춰 구현합니다.

## 3. 실제 61개 이슈와 담당자 (변경 금지 기준)

| 담당 | 범위 | 개수 | 소유 파일·백엔드 예시 |
|---|---|---:|---|
| **A (SM)** | 공통 구조·매칭 찾기·AI 매칭률·통합 | **13** | Android `common/`, `matching/`; `matchSettings`, AI 점수/캐시 |
| **B** | 링크 인증·프로필·차단·신고 | **11** | Android `auth/`, `profile/`; Auth, `users`, `publicProfiles`, Storage |
| **C** | 시간표·공강·Gemini 이미지 인식 | **12** | Android `timetable/`; `timetables`, `availability` |
| **D** | 요청·매칭 수락/종료·알림 | **12** | Android `request/`, `notification/`; `matchRequests`, `matches`, FCM |
| **E** | 채팅·AI 게이트웨이/답장/장소·후기 | **13** | Android `chat/`, `review/`; RTDB, Gemini Functions 게이트웨이 |

**모두 Android 프론트엔드 구현에 참여**합니다. 소유 영역 밖의 API/Rules/Enum을 수정해야 할 때는 해당 담당자 및 SM에게 영향·테스트 계획을 명시합니다. 팀원 이름은 확정되어 있지 않으므로 임의의 실제 이름·GitHub 핸들을 추측하지 않습니다.

| Sprint | Issue 범위 | 완료 게이트 |
|---|---|---|
| **1** | BT-001~012 | 환경·UI 목업·공통 모델, 전원 빌드 |
| **2** | BT-013~024 | 링크 인증/시간표/요청/채팅 연결 + Gemini 서버 기반, 2계정 시연 |
| **3** | BT-025~037 | AI 매칭률, 수락·채팅 E2E, 이미지 인식 검증 |
| **4** | BT-038~051 | 종료·후기·답장·장소·보존·알림 통합, **BT-050 Feature Freeze** |
| **5** | BT-052~061 | 회귀·보안·동시성·AI 검증, APK·발표 |

**P0 42 / P1 18 / P2 1**. 원본 Issue의 우선순위를 임의로 하향/상향하지 마세요. 특히 **AI 매칭 점수(BT-027/028)는 P0**, 이미지 인식(BT-025/026)은 P1, AI 답장/장소(BT-041/042)는 P1, 종료 후 채팅(BT-043)과 시간표 변경에 따른 기존 데이터 보존(BT-047)은 P0입니다. **우선순위는 구식 README/AGENTS 초안보다 기존 61개 Issues가 우선**합니다.

## 4. 기술 및 디렉터리 경계

- Android **Java + XML Views**, Gradle 버전/플러그인 및 소스 경로는 **실제 코드**를 우선합니다. 새 Kotlin/Compose 코드로 임의 전환하지 않습니다.
- Firebase **Authentication**, **Firestore**(업무 데이터), **Realtime Database**(채팅), **Storage**(이미지), **Cloud Functions(TypeScript)**(서버 로직).
- Gemini는 **서버만 호출**합니다. 기획 모델 이름은 `Gemini 3.5 Flash-Lite`이며 실제 API 모델 ID를 하드코딩하기 전에 환경에서 확인합니다.
- Android: `Activity/Fragment → ViewModel 또는 UI 제어자 → Repository → Firebase/Functions` 식으로 UI/데이터 책임을 나눕니다. 기존에 채택한 패턴이 있다면 그것을 존중합니다.
- Functions: 인증·입력 검증·비즈니스 규칙·동시성 제어·AI 게이트웨이를 맡습니다. API 키는 Firebase Secrets 등 서버 환경에 둡니다.
- 권장 구조는 아래와 같으나 **기존 디렉터리가 이미 있다면 이동·중복 생성하지 않습니다**.

```text
README.md
AGENTS.md
android/ (또는 저장소 루트 Android 프로젝트)
  app/src/main/java/<existing-package>/
    common/ auth/ profile/ timetable/ matching/ request/ chat/ notification/ review/
  app/src/main/res/
functions/
  src/ (matching.ts, requests.ts, chat.ts, ai.ts, index.ts 등)
firestore.rules
firestore.indexes.json
database.rules.json
storage.rules
firebase.json
```

- Java 클래스 `PascalCase`, 필드/메서드 `camelCase`, 상수 `UPPER_SNAKE_CASE`, XML 리소스 `snake_case`.
- 네트워크·Gemini 작업으로 UI 스레드를 멈추지 않습니다. 로딩/빈 화면/오류/재시도 및 중복 클릭 방지를 구현합니다.
- Firebase 리스너 등록과 해제는 Android Lifecycle에 맞춥니다. 비동기 콜백이 이미 종료된 화면을 변경하지 않게 합니다.

## 5. Firestore·RTDB 공통 계약

필드 이름·UID 출처·요청/매칭 식별자·분 단위 시간 규칙·Repository 연결은 [`docs/BT-012.md`](./docs/BT-012.md)의 팀 결정을 우선 확인합니다. 합의와 실제 구현 상태, 후속 이슈에서 확정할 제안을 구분하고 미구현 계약을 동작 완료로 보고하지 않습니다.

아래 이름은 팀의 **설계 기준**입니다. 실제 구현이 다르면 독자적으로 바꾸지 말고 담당자와 상의 후 스키마와 문서를 동기화합니다.

```text
Firestore
users/{uid}
  blockedUsers/{blockedUid}
publicProfiles/{uid}
timetables/{uid}/courses/{courseId}
availability/{uid}
matchSettings/{uid}
matchRequests/{requestId}
matches/{matchId}
notifications/{uid}/items/{notificationId}
reviews/{reviewId}
reports/{reportId}
aiUsage/{uid}_{YYYY-MM-DD}
matchScores/{searcherUid}_{candidateUid}

Realtime Database
chatRooms/{chatRoomId}/members/{uid}
chatRooms/{chatRoomId}/messages/{messageId}
chatRooms/{chatRoomId}/control: {chatReady, writable, archived}
userChatRooms/{uid}/{chatRoomId}
```

| 데이터 | 필수 계약 또는 검증 |
|---|---|
| `Auth UID` | 문서 소유자와 참가자 판별에 사용. 클라이언트 입력만 신뢰 금지 |
| `timetables/{uid}.revision` | 수업 변경마다 증가, 공강 선택만 바꾸면 유지. `availability.sourceRevision`과 비교 |
| `timetables/{uid}.freeCells` | 월~일 7개의 48비트 정수로 선택 원본 보존. `semester`는 생략/null 허용 |
| `availability` | 선택 원본을 병합한 60분 이상 슬롯과 `sourceRevision`. 검색(`searchMatches`)은 revision 일치·30분 경계·병합 후 60분 이상만 확인하고, 요청 생성(`createMatchRequest`)이 원본·수업 비겹침·revision을 다시 검증 |
| `matchSettings` | `isMatching`, `minOverlapMinutes`(60·90·120·150·180), `purposes`(빈 목록 = 전체, 최대 5), `preferredTags`(`#` 없이, 최대 20), `updatedAt`(서버 시간). 본인만 읽기·쓰기, 다른 사용자는 검색 서버만 읽음. 계약은 `docs/BT-017.md` |
| 매칭 검색 계약 | `searchMatches()`(입력 없음): 토큰 UID·학교 이메일 확인 → 지금/가장 가까운 내 공강 하나 기준으로 양측 ON·검색자 최소 겹침·목적 AND·양방향 차단을 판정. 결과 `MATCHING_OFF`/`NO_FREE_SLOT`/`OK{reference, candidates}`, 후보는 `candidateId`(= 상대 UID)와 겹침·목적만 반환. availability는 revision 일치·30분 경계·병합 후 60분 이상만 사용. 계약은 `docs/BT-017.md` |
| AI 매칭률 계약 | BT-027: `searchMatches`가 필수 조건 통과 후보 중 겹침 상위 5명에게만 서버 내부 `evaluateMatch`를 호출해 `score`(0~100)·`reasons`·`suggestedActivity`를 선택 필드로 붙임. 입력은 공개 프로필 요약(자기소개는 연락처 제거)·수업 건물(호실 제외)·공통 과목·겹침·목적(이름·UID·시간표 원본 금지), 이유 문장에 건물 이름 금지(서버 `verify`가 건물명 문장 제거)·수업 이름은 공통 과목만. 점수 붙이기는 15초 마감, 늦은 후보는 점수 없음. 앱은 점수 없음을 `NO_SCORE`(-1)로 두고 0점과 구분. AI 실패 후보는 필드 없음, 검색은 계속. 정렬·캐시는 BT-028. 계약은 `docs/BT-027.md` |
| AI 추천순·점수 캐시 | BT-028: 점수가 붙은 상위 5명은 점수 높은 순, 동점·점수 없음은 겹침 순 유지, 6번째부터는 겹침 순. 같은 AI 입력(목적·30분 단위 겹침·양쪽 프로필·수업 건물·공통 과목)·프롬프트·모델·캐시 버전의 해시가 같으면 24시간 동안 `matchScores` 캐시를 쓰고, 입력이 바뀌거나 만료되면 다시 평가. Mock 점수는 저장 안 함. 계약은 `docs/BT-028.md` |
| `matchRequests` | `fromUid`, `toUid`, `status`, `meetingDate`, `startMinute`, `endMinute`, `overlapMinutes` 등 **시간 스냅샷** |
| 요청 생성 계약 | `createMatchRequest({candidateId, clientRequestId})`: 상대 UID와 재시도 UUID만 입력. 검색자가 선택한 최소 겹침, 프로필·차단·시간표·확정 매칭 충돌을 같은 서버 트랜잭션에서 검증. 같은 방향 PENDING 중복은 서버 전용 `matchRequestGuards`로 막음. 계약은 `docs/BT-019.md` |
| 요청 목록·취소 계약 | UID별 발신/수신 Firestore 리스너, `cancelMatchRequest(requestId)`, 두 복합 인덱스와 검증 범위는 `docs/BT-020.md`를 따릅니다. |
| 요청 수락·제한 계약 | `respondToMatchRequest({requestId, action})`, action은 `ACCEPTED` 또는 `REJECTED`. 토큰 수신자만 처리. 보낸 유효 PENDING 3개·받은 5개, 직접 취소 후 신규 신청 60초 제한·역방향 차단. 기존 초과 요청 보존, UUID 재시도와 자동 취소는 제한을 새로 만들지 않음. 서버 전용 `requestUserGuards/{uid}`는 사용자 단위 경합과 cooldownUntil을 보호. 상세는 `docs/BT-031.md` |
| `matches` | `participantUids`, `requestId`, `status`, `meetingDate`, `startMinute`, `endMinute`, `chatReady` 등 **확정 스냅샷** |
| `chatRooms` | 실제 방 키는 `chatRoomId`, 개별 약속 `matchId`와 구분. Rules는 `database.rules.json`: 멤버만 메시지 읽기·새 쓰기(`senderId === auth.uid`, 서버 시각, 본문 1~1000자), `members` 쓰기와 `control {chatReady,writable,archived}` 읽기·쓰기는 서버 전용. Rules는 control을 내부 참조하며 앱은 직접 읽지 않음. `writable=false` 또는 `archived=true`면 전송 거부. 계약은 `docs/BT-021.md`·`docs/BT-034.md` |
| `userChatRooms` | `userChatRooms/{uid}/{chatRoomId}`의 본인 목록/안읽음·방 단위 `chatReady/archived/hidden/status`·`linkedAt`(항목을 처음 만든 서버 시각, 메시지 없는 방의 정렬 기준). 앱은 본인 읽기 및 `unreadCount=0`만 쓰기 가능. 나머지는 서버 전용이며 표시 상태는 이 값과 참여자용 `chatRoomLinks`를 사용 |
| `reviews` / `reports` | 참여·공개/작성 권한, 개인정보 최소 수집 |
| `aiUsage` | BT-024 Gemini 게이트웨이의 사용자별 하루 호출 수(`uid`, `date`, `counts.{task}`, `lastCalledAtMs.{task}`, `total`, `updatedAt`). 날짜는 `Asia/Seoul`. **서버 전용**: Admin SDK만 쓰고 Rules는 앱 읽기·쓰기 모두 거부. 계약은 `docs/BT-024.md` |
| `matchScores` | BT-028 AI 점수 캐시(`searcherUid`, `candidateUid`, `inputHash`, `score`, `reasons`, `suggestedActivity`, `expiresAt`, `updatedAt`). **서버 전용**: Rules에 규칙이 없어 앱 읽기·쓰기 모두 거부. 계약은 `docs/BT-028.md` |

시간 규칙:

```text
Timezone: Asia/Seoul
meetingDate: YYYY-MM-DD
dayOfWeek: 1(월) ~ 7(일)
0 <= startMinute < endMinute <= 1440
Intervals: [startMinute, endMinute)
overlapMinutes = max(0, min(endA, endB) - max(startA, startB))
Valid minOverlapMinutes: {60, 90, 120, 150, 180}
Default minOverlapMinutes: 60
RequestStatus: PENDING | ACCEPTED | REJECTED | CANCELED
MatchStatus: ACTIVE | ENDED
```

- 서버 시간 기준으로 날짜와 종료를 판단합니다. 서로 다른 날짜/요일의 슬롯은 직접 겹침 비교하지 않습니다.
- 원시 개인 시간표를 매칭 상대에게 그대로 반환하지 않습니다. 검색 결과는 필요한 공개 프로필·겹침 정보로 제한합니다.
- 수업 변경으로 생성되는 **새 공강 계산**과 **기존 요청/매칭 스냅샷**을 엄격히 분리합니다.
- Firestore와 RTDB는 **단일 원자적 트랜잭션이 아닙니다**. RTDB 채팅방 생성 실패 시 상태 표시·안전한 재시도·멱등성 구현이 필요합니다.
- 보안 Rules는 사용자별 읽기/쓰기 및 채팅 참여자 접근을 검증합니다. Admin SDK 사용 Cloud Functions는 Rules를 우회하므로 **함수 안에서 권한을 직접 확인**해야 합니다.
- 상태 변경(`matchRequests.status`, `matches.status`, 채팅방 참여자 등록/종료 상태 등)을 Android 임의 쓰기로 구현하지 않습니다.

## 6. 서버 함수/AI 에이전트 작업 계약

| 담당 | 함수/책임 예시 | 검증 의무 |
|---|---|---|
| A | `searchMatches`, 매칭 점수/추천 이유 | 양측 ON, 공강·최소 시간·태그·차단·충돌; 캐시 최신성 |
| B | 학교 이메일/프로필 처리, 차단/신고 | 검증된 이메일, UID 소유권, 비공개 데이터 |
| C | 공강 계산·이미지 OCR 결과 검수 파이프라인 | 중복/오인식, revision, 저장 전 사용자 확인 |
| D | `createMatchRequest`, `respondToMatchRequest`, 자동 종료 | 동시 수락·중복 요청·상충 요청 정리, 스냅샷 보존 |
| E | AI 공통 게이트웨이, 채팅 이벤트, 답장/장소/후기 | 채팅 참여자 확인, AI 출력 형식/실패 처리, 장소 검증 |

- Gemini 호출 전에 **입력 최소화·개인정보 제거·인증/권한 검사**. 출력은 스키마 검사하고 비용/지연/실패/시간초과를 제어합니다.
- Gemini 매칭 점수는 추천 정렬용. **필수 매칭 조건 검사 전 또는 대신 호출하지 않습니다.** AI 불가 시에도 P0 기본 흐름에 대해 실패 정책을 명확히 처리합니다.
- AI 답장 3개는 **UI 제안**이며 사용자가 직접 선택·수정·전송합니다. 타인의 메시지나 개인 시간표 전체를 필요 이상으로 모델에 전송하지 않습니다.
- 장소는 신뢰 가능한 **사전 검증된 캠퍼스 장소 데이터**만 후보로 제공하고 모델이 결과의 존재 여부를 결정하지 않게 합니다.
- 모델/함수 명칭이 실제 계정에서 지원되는지 확인한 후 적용합니다. 앱에 API 키를 넣지 않습니다.

## 7. 코드 변경·권한·실행 안전성

**이 작업에 포함되지 않으면 하지 말 것:**

- Firebase 운영 DB·Storage 삭제/백필/마이그레이션, 운영 배포, Rules 전체 교체.
- 비밀번호·API 키·서비스 계정 파일을 코드나 로그에 출력/커밋.
- 사용자가 승인하지 않은 외부 계정/GitHub에 임의의 Issue·Projects·Actions 생성, 자동화 토큰 추가. 이 저장소의 사용자 승인된 자동화는 `docs/GITHUB_AUTOMATION.md` 범위에서만 변경합니다.
- Figma와 다르게 독자적 UX 탭 추가, 기존 확정 조건 임의 완화.
- 서버 Rules 우회용 테스트 전용 허용(`allow read, write: if true`) 배포.
- 구식 설계 문서에 맞춰 최신 Issue의 담당자·우선순위·스프린트 변경.

변경 시 지킬 것:

1. **작고 독립적인 PR** 단위로 파일을 수정합니다. 주요 경로·데이터 계약 변경은 관련 담당자의 검토를 받습니다.
2. 하드코딩 임시 데이터는 `Mock`으로 구분하고 실제 Firebase 데이터로 연결되는 이슈를 명시합니다. 더미 결과를 실제 성공으로 보고하지 않습니다.
3. 사용자·네트워크·Firestore 권한 거부·AI 실패·중복 제출·수명주기 종료를 모두 처리합니다.
4. 신규 컬렉션·필드·enum·함수·Rules/인덱스 변경 시 `README.md`/`AGENTS.md` 및 별도 스키마 문서를 동기화합니다.
5. `google-services.json`은 팀 정책상 Git에 올리지 않습니다. `.env`, 서비스 계정 JSON, `local.properties`, 키스토어 및 API 키를 제외합니다.

## 8. 검증 매트릭스 — 해당 작업 범위에서 수행

| 영역 | 최소 검증 시나리오 |
|---|---|
| 이메일 인증 | 학교 도메인 허용/거부, 동일 이메일 재가입, 링크 유효/만료/재사용, 로그아웃·재접속 |
| 시간표 | 수업 0개, 연속/겹침, 요일 경계, 이미지 다중 업로드·중복 추출·사용자 수정, 공강 revision |
| 시간 겹침 | 0/30/59/60/89/90/119/120/149/150/179/180분, 시작·끝 경계, 날짜 차이 |
| 매칭 | 양측 ON/OFF 조합, 필터·목적·태그, 차단 관계, 현재 공강 없음→미래, 결과 없음 화면 |
| 요청 | 동일 요청 중복, 동시 수락, 수락/취소 경합, 시간 충돌, 기존 요청 스냅샷 보존 |
| 매칭 종료 | 겹침 종료 시 자동 종료, 수동 종료, 중복 종료, 진행 중 나가기 금지, 종료 후 채팅 지속 |
| 채팅 | 양측 실시간 전송, 비참여자 접근 거부, 읽지 않음, listener 중복, AI 칩 입력만·수동 전송 |
| AI | 지연/오류/부정확 출력, 검증되지 않은 장소 배제, 인증되지 않은 호출 거부, 비밀 키 미노출 |
| Rules | 자기 데이터 허용, 타인 개인정보 차단, 채팅 비참여자 차단, 클라이언트 매칭 상태 수정 차단 |

테스트 명령은 **실제 파일이 있을 때** 사용합니다.

```bash
# Android wrapper 존재 시: 실제 앱 위치에서 실행
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew lintDebug

# functions/package.json 및 lockfile과 build script 존재 시
cd functions && npm ci && npm run build

# Firebase Emulator가 설정된 경우
firebase emulators:start
```

빌드 불가/테스트 미실행/권한 없음은 숨기지 않습니다. 실제로 수행하지 않은 테스트를 통과로 기재하지 않습니다.

## 9. GitHub Issues·PR·Actions 협업

- 이미 생성된 **61개 Issues**의 Sprint·담당자·우선순위·의존성·체크리스트를 기준으로 작업합니다.
- 상태 흐름은 **`Todo → In Progress → In Review → Done`**입니다. `develop` 대상 PR 본문의 `Closes #실제번호`를 기준으로 Draft는 In Progress, 리뷰 가능한 PR은 In Review, 병합 시 Done 및 이슈 완료 종료로 동기화합니다. 조직 Project 필드 동기화는 별도 토큰 설정을 마친 뒤에만 동작합니다.
- `BT-027`처럼 **계획 ID와 GitHub `#27`은 다를 수 있습니다**. Issue를 참조하는 PR에는 실제 GitHub 번호를 사용합니다.
- PR 제목/본문에는 BT ID와 실제 Issue, 변경 범위, 검증 결과, 스키마·보안 영향, UI 변경 시 화면 증거를 적습니다.
- 최신 `develop`에서 `feature/<area>-<BT-ID>` 등의 작업 브랜치를 만들고 PR로 `develop`에 통합합니다. `main`은 모든 개발 완료 후 최종 배포에 사용합니다.
- `pull_request_target`은 기본 브랜치의 워크플로를 실행합니다. 개발 로직을 develop에 병합하고, 별도 승인된 최소 진입점 워크플로를 main에 설치한 뒤 실제 Actions 실행이 성공하기 전에는 **자동 상태 변경을 완료로 간주하지 않습니다**. 활성화 절차는 `docs/GITHUB_AUTOMATION.md`를 따릅니다. 기본 브랜치 변경이나 main 병합을 임의로 실행하지 않습니다.
- 스프린트 4 기능 동결 이후에는 명확한 버그 수정·회귀 테스트 위주로 작업합니다.

## 10. Definition of Done

- [ ] 해당 이슈의 실제 구현 범위·수용 기준·디자인을 따랐습니다.
- [ ] 데이터 계약 및 코드 경로를 불필요하게 변경하지 않았습니다.
- [ ] UI 상태(성공/로딩/빈 상태/오류)와 접근성을 확인했습니다.
- [ ] Firebase/서버 권한, 엣지 케이스, 중복 요청·시간/매칭 충돌을 검증했습니다.
- [ ] 관련 유닛/통합 테스트 및 빌드를 실행했고 결과를 명시했습니다.
- [ ] 계정/시간표/채팅 개인정보 및 비밀값이 노출되지 않습니다.
- [ ] 이슈/PR에 결과를 남기고 필요한 문서를 갱신했습니다.

**작업 결정 순서:** 안전·보안 → 최신 명시 결정/Issue 수용 기준 → 피그마 화면 요구 → 실제 저장소 계약/기술 제약 → 최소 변경. 충돌 시 문제와 선택지를 명시하고, 기능을 독단으로 바꾸지 않습니다.
