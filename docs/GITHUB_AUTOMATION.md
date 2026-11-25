# BIN TIME GitHub 자동화 운영 안내

이 문서는 Android 소스 구현과 별개의 GitHub 관리용 설정입니다. 기존 61개 이슈의 본문·담당·우선순위를 변경하지 않습니다.

## 적용 파일
- PR 상태 라벨: `.github/workflows/pr-issue-status.yml` / `scripts/pr_issue_status.cjs`
- Project 상태 동기화(선택): `.github/workflows/pr-project-status.yml` / `scripts/pr_project_status.cjs`
- CI: `.github/workflows/android-ci.yml` / `tests/pr-status.test.cjs`
- CI 실행 범위 판별: `scripts/ci_plan.cjs` / `tests/ci-plan.test.cjs`
- PR 양식: `.github/PULL_REQUEST_TEMPLATE.md`
- 최초 Project 생성·61개 가져오기: `scripts/bootstrap_project.py`

## PR 및 Issue 흐름
1. 브랜치에서 작업하고 같은 저장소의 PR을 만듭니다.
2. PR 본문의 예시 `Closes #ISSUE_NUMBER`를 **실제 이슈 번호**로 바꿉니다. `BT-017`은 계획 식별자입니다.
3. Draft PR: `status:in-progress` / 리뷰 가능한 PR: `status:in-review` / **main 병합**: `status:done`.
4. PR을 병합하지 않고 닫으면 `status:in-progress`로 돌아갑니다. GitHub 기본의 이슈 자동 종료 기능은 기본 브랜치 병합 시 적용됩니다.
5. PR이나 Issue에 기존 `priority:P0`, `owner:A`, `sprint:1` 등 라벨은 보존됩니다.
6. 연결할 이슈가 없거나 다른 저장소의 PR, Fork PR은 자동 변경하지 않습니다. 안전한 `pull_request_target` 사용을 위해 기본 브랜치에서만 스크립트를 읽습니다.

## Android CI
develop/main 대상 PR의 생성·재오픈·추가 커밋·리뷰 준비, develop/main push, 수동 실행을 처리합니다. 제목/본문 편집의 `edited`는 CI에서 제외합니다. PR 대상 브랜치를 바꾼 경우 새 커밋이나 재오픈으로 병합 결과를 재검사합니다. 수동 실행은 선택한 브랜치의 전체 검사이며 PR 병합 결과 검사와 구분합니다.

같은 workflow의 같은 PR/브랜치에서 새 CI가 시작되면 이전 CI를 자동 취소합니다. 이슈/Project 상태 동기화의 `edited`와 직렬화는 유지합니다. 워크플로 전체의 경로 필터 대신 `detect-android`의 출력으로 작업을 분기해, 필요 없는 검사만 SKIPPED로 표시합니다. 판별 작업이 실패하면 두 검사도 명시적으로 실패합니다.

| 변경 범위 | Android | 자동화 테스트 |
|---|---|---|
| README·AGENTS·docs·PR/Issue 템플릿만 | 생략 | 생략 |
| functions·Firebase Rules/인덱스/설정만 | 생략 | 생략 (서버 검증 성공을 뜻하지 않음) |
| app·android·Gradle·buildSrc/build-logic | 실행 | 해당 자동화 변경이 있으면 실행 |
| scripts·tests·상태 동기화 workflow | CI 판별 코드 외에는 생략 | 실행 |
| android-ci.yml·ci_plan.cjs·해당 테스트·공용 Actions | 실행 | 실행 |
| 수동 실행·비교 불가·미분류 경로 | 실행 가능한 Android 프로젝트 검사 | 실행 |

PR은 대상 SHA와 임시 병합 커밋 전체를 비교하고, push는 before~현재 SHA 전체를 비교합니다. 마지막 커밋만 보지 않으며 PR 대상 브랜치에만 추가된 파일은 제외합니다. depth 2로 비교할 수 없는 다중 커밋 push·새 브랜치·이력 부족은 전체 검사로 전환합니다. 삭제/이동은 이전 경로도 검사하고 파일명은 NUL 구분으로 처리해 셸에 보간하지 않습니다.

Android Gradle Wrapper가 루트 또는 `android/`에 존재하면 Java 17에서 기존 `assembleDebug testDebugUnitTest lintDebug`를 실행합니다. Gradle 캐시는 유지하며 APK는 업로드하지 않습니다. 작업 제한은 판별 3분·자동화 5분·Android 20분입니다. Wrapper가 없거나 변경 범위상 필요 없는 Android 검사는 SKIPPED이며 빌드 통과 증거로 취급하지 않습니다.

병합 전 다른 팀원 1명 승인, `detect-android` 성공과 필요한 `Automation logic tests`·`android-build`의 성공을 확인합니다. 보호 규칙·요금 설정은 이 작업에서 변경하지 않습니다.

CI 전용 PR을 develop에 반영하고 main에도 동일 CI 파일·판별 스크립트·테스트·관련 문서만 별도 PR로 반영합니다. 제품 기능 커밋은 포함하지 않으며 생성한 PR은 팀 리뷰 후 병합합니다. 열린 PR은 다음 실행의 임시 병합 결과에 새 설정이 포함되는지 확인합니다. PR에서 CI 파일을 별도로 수정하지 않았다면 같은 변경을 각 작업 브랜치에 복사할 필요는 없습니다. 기존/실행 중 CI는 소급 변경되지 않고 develop 갱신만으로 열린 PR의 CI가 자동 재실행되는 것도 아닙니다. CI 파일 충돌/덮어쓰기가 있으면 해당 PR에서 정리합니다.

## 조직 GitHub Projects 최초 설정
이 채팅의 GitHub 연결에서 저장소 관리자 권한은 확인되었지만 **조직 Project 생성 API는 연결 도구에 없습니다**. 아래 명령을 조직 Project 생성 권한이 있는 사람이 로컬에서 실행해야 합니다.

```bash
gh auth login
gh auth refresh -s project
python scripts/bootstrap_project.py
python scripts/bootstrap_project.py --apply
```

이 스크립트는 기존 61개 **열린 BT 이슈**를 탐색하고, 동일한 이름의 기존 보드가 있으면 재사용하며, 없으면 `BIN TIME - Development Board`를 생성합니다. 저장소 연결, Sprint/Priority/Team 필드 생성, 61개 카드 등록까지 수행합니다. 입력이 61개가 아니면 안전하게 중단합니다. 다시 실행해도 URL이 등록된 항목은 건너뜁니다. **신규 이슈/팀 라벨 변경은 하지 않습니다.** 필드 값은 자동 채우지 않으므로 Project UI에서 설정해야 합니다.

Project 설정 화면에서 기본 `Status`에 **Todo / In Progress / In Review / Done** 옵션을 구성하세요. 뷰는 Sprint별 보드, 담당자별 보드, 전체 상태 칸반으로 만들면 됩니다. Project 기본 자동화에서 신규 이슈 자동 등록과 닫힌 이슈 Done 처리를 켤 수 있습니다.

## PR → Project Status 연동 활성화
보드 생성 후 저장소 Settings → Secrets and variables → Actions에서 다음을 설정하세요.

| 유형 | 이름 | 값 |
|---|---|---|
| Repository variable | `BIN_TIME_PROJECT_OWNER` | `MobileProgrammingTeam4` |
| Repository variable | `BIN_TIME_PROJECT_NUMBER` | 생성된 Project 번호 |
| Repository secret | `BIN_TIME_PROJECT_TOKEN` | 조직 Project에 접근 가능한 GitHub App 토큰 또는 최소 권한 토큰 |

`BIN_TIME_PROJECT_TOKEN`은 적절한 조직 Project 접근 권한(`project` 또는 해당 App의 Projects write)을 가져야 합니다. 일반 `GITHUB_TOKEN`은 조직 Projects 변경 권한이 충분하지 않을 수 있습니다. 인증 정보를 Issues나 파일에 올리지 마세요. 값이 없으면 **Projects 상태 연동만 건너뛰고 이슈 상태 라벨 자동화는 계속됩니다.**

## 권한 및 확인
- Repository Settings → Actions → General에서 Actions 실행 및 `GITHUB_TOKEN`의 Issues 쓰기 정책이 허용되어야 합니다.
- 이 저장소는 GitHub 규칙셋 API에서 플랜 제한 오류가 발생했습니다. 보호 규칙·필수 리뷰 강제는 설정 가능 여부를 조직 정책/플랜에서 별도 확인해야 하며 **현재 활성화했다고 주장하지 않습니다**.
- 이 PR은 **검토 후 main 병합이 필요**합니다. `pull_request_target` 기반 워크플로는 기본 브랜치의 버전으로 실행됩니다.
- 처음에는 테스트 이슈 하나를 연결한 Draft PR → Ready for review → main Merge 순서로 상태 이동 및 Actions 실행 로그를 확인하세요.

## 문제 해결
- Project 연동이 실패하면 Owner/Number/토큰의 Scope와 조직 승인 여부 확인
- PR이 상태 변경되지 않으면 PR 본문에 `Closes #실제번호`가 있는지 확인
- `Closes #ISSUE_NUMBER` 그대로면 어떤 상태도 갱신되지 않음
- Android CI가 스킵되면 `gradlew`와 `settings.gradle`의 실제 경로 확인
- Actions가 `Resource not accessible by integration`이면 저장소·조직의 Workflow 권한을 점검

운영 상태는 자동화 반영 이후에도 최초 실제 PR/CI 실행 결과로 검증해야 합니다.
