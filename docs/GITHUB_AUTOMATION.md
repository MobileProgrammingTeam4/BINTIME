# BIN TIME GitHub 자동화 운영 안내

이 문서는 Android 소스 구현과 별개의 GitHub 관리용 설정입니다. 기존 61개 이슈의 본문·담당·우선순위를 변경하지 않습니다.

## 적용 파일
- PR 상태 라벨: `.github/workflows/pr-issue-status.yml` / `scripts/pr_issue_status.cjs`
- Project 상태 동기화(선택): `.github/workflows/pr-project-status.yml` / `scripts/pr_project_status.cjs`
- CI: `.github/workflows/android-ci.yml` / `tests/pr-status.test.cjs`
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
PR 및 main push에서 Node.js 헬퍼 테스트를 실행합니다.
Android Gradle Wrapper(`gradlew`와 `settings.gradle[.kts]`)가 루트 또는 `android/`에 존재하면 Java 17 환경으로 `assembleDebug testDebugUnitTest lintDebug`를 실행합니다. **현재 Android 코드가 없으므로 Android 빌드 작업은 건너뛰며 성공으로 간주하지 않습니다.** 이후 프로젝트 설정에 맞춰 JDK·SDK·Gradle 버전을 조정하세요.

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
