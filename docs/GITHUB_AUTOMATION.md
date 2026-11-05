# BIN TIME GitHub 자동화 운영 안내

이 문서는 GitHub 협업 자동화 설정입니다. 제품 코드·데이터·기존 이슈의 담당·우선순위·수용 기준은 변경하지 않습니다.

## 브랜치와 완료 기준

- `feature/*`, `fix/*` 등 작업 브랜치는 최신 `develop`에서 시작해 `develop`으로 PR을 보냅니다.
- `develop`은 팀 작업 통합, CI, 리뷰 및 개발 이슈 완료의 기준입니다.
- `main`은 모든 개발·통합 검증 완료 후 최종 배포용입니다. 기본 브랜치를 변경하지 않습니다.

| develop PR의 현재 상태 | 연결 이슈 라벨 / 선택적 Project Status | 이슈 상태 |
|---|---|---|
| Draft | status:in-progress / In Progress | 유지 |
| 리뷰 가능 | status:in-review / In Review | 유지 |
| 병합 없이 닫힘 | status:in-progress / In Progress | 유지 |
| 병합됨 | status:done / Done | closed, completed |

리뷰 승인이나 CI 성공만으로 이슈를 닫지 않습니다. 병합된 사실을 완료 신호로 사용합니다. `main` 및 다른 대상 브랜치의 PR은 개발 이슈 상태를 변경하지 않습니다.

## 연결 문법과 안전장치

PR **본문**에 완료할 실제 GitHub 이슈 번호를 적습니다. `BT-017`은 계획 ID이므로 실제 #번호와 같다고 가정하지 않습니다.

```text
Closes #7
Fixes: MobileProgrammingTeam4/BINTIME#8
Resolves #9, resolves #10
```

- close/closes/closed, fix/fixes/fixed, resolve/resolves/resolved와 대소문자·콜론을 지원합니다. 여러 이슈는 각각 키워드를 씁니다.
- 일부 작업만 끝낸 PR은 `Related #7`로 참조합니다. 자동 완료되지 않습니다.
- 템플릿 번호, 코드 예제, HTML 주석, 인용문, 다른 저장소 이슈 및 PR 번호는 종료 대상에서 제외합니다.
- 같은 저장소의 develop PR만 처리합니다. Fork PR은 권한이 있는 상태 변경에서 제외합니다.
- GitHub에서 최신 PR 상태·본문을 다시 읽어 늦은 edited/closed 이벤트가 병합 완료를 되돌리지 않도록 합니다. PR별 실행도 직렬화합니다.
- 기존 priority/owner/area/sprint 라벨을 보존하고 알려진 status 라벨만 교체합니다. 이미 닫힌 이슈를 다시 열지 않으며 not_planned 등의 종료 사유를 덮어쓰지 않습니다.
- 이슈 종료와 라벨 변경은 재실행해도 중복 종료되지 않습니다. 없는 이슈는 경고 후 건너뛰고 권한 오류는 실패로 표시합니다.
- 여러 PR이 같은 이슈를 공동 구현한다면 마지막 완료 PR에만 종료 키워드를 씁니다. PR 본문에서 참조를 제거하거나 대상을 다른 브랜치로 바꿨을 때 이전 이슈 라벨은 수동 확인합니다.

GitHub 자체의 closing keyword 기능은 기본 브랜치 병합에서만 동작합니다. 기본 브랜치가 main인 상태에서 develop 이슈 종료는 이 저장소의 Actions가 담당합니다.

## 적용 파일

- CI: `.github/workflows/android-ci.yml`
- 이슈 상태 및 종료: `.github/workflows/pr-issue-status.yml`, `scripts/pr_issue_status.cjs`
- 선택적 Project: `.github/workflows/pr-project-status.yml`, `scripts/pr_project_status.cjs`
- 검증: `tests/*.test.cjs`
- PR 양식: `.github/PULL_REQUEST_TEMPLATE.md`

## 활성화 순서 — 두 PR을 구분

GitHub.com은 2025-12-08부터 `pull_request_target`의 워크플로 소스를 항상 **기본 브랜치**에서 가져옵니다. develop의 워크플로만 바꿔서는 활성화되지 않습니다.

1. 개발 변경 PR을 검토하고 develop에 병합합니다. 개발 스크립트·테스트·CI·문서가 대상입니다.
2. 별도 활성화 PR에서 **main의 두 상태 워크플로 진입점만** 반영합니다. develop 필터, 이벤트·직렬화 설정, `ref: develop` 체크아웃을 설치합니다. 제품 코드나 develop의 기능 커밋은 main에 가져오지 않습니다. 이는 main 배포 전용 정책에 대한 최소 관리 설정 예외이므로 **승인 전에는 병합하지 않습니다**.
3. 진입점은 develop의 신뢰된 스크립트를 사용합니다. PR head나 PR의 merge ref를 체크아웃하거나 실행하지 않습니다. 이후 develop 자동화 코드의 변경도 쓰기 권한·Project 토큰을 사용하므로 신뢰된 팀원의 검토를 받아야 합니다.
4. 활성화 이후 develop 대상 PR의 Draft → Ready → 병합 흐름에서 Actions 로그와 이슈 라벨·closed/completed를 확인합니다. 실제 병합 검증은 별도 승인된 작업으로 진행합니다. 기존 이벤트는 자동 소급되지 않습니다.

main을 전혀 수정하지 않는 정책을 유지한다면 현재 pull_request_target 방식의 자동화는 활성화할 수 없습니다. 기본 브랜치 변경, 외부 App/토큰 추가 또는 배포를 임의로 대안 실행하지 않습니다.

공식 근거: [pull_request_target 변경 안내](https://github.blog/changelog/2025-11-07-actions-pull_request_target-and-environment-branch-protections-changes/), [이슈 종료 키워드](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue).

## Android CI와 리뷰

- develop 및 main 대상 PR, develop/main push, 수동 실행에서 자동화 테스트를 실행합니다. PR 재오픈·추가 커밋·리뷰 준비·본문/대상 편집도 검사합니다.
- Gradle Wrapper가 루트 또는 android/에 있으면 Java 17 환경에서 `assembleDebug testDebugUnitTest lintDebug`를 실행합니다. 현재 저장소에는 루트 Android 프로젝트가 있습니다.
- Wrapper가 없으면 Android 작업은 SKIPPED이며 빌드 통과 증거로 취급하지 않습니다.
- 병합 전 다른 팀원 **최소 1명 승인**, `Automation logic tests`와 `android-build` 성공 및 최신 develop 통합 결과를 확인합니다. 리뷰 요청만으로 승인을 대신하지 않습니다.
- 조사 시 main/develop의 protected 값은 false였습니다. 보호 규칙 조회는 연결 권한으로 거부되고 규칙셋 API는 비공개 저장소의 플랜 제한을 반환했습니다. 따라서 CI·리뷰의 **강제 병합 차단을 설정했다고 주장하지 않습니다**. 지원되는 플랜/권한이 마련되면 develop 보호 규칙에 필수 승인 1명과 위 두 체크, 최신 브랜치 요구를 설정하세요. 그 전에는 팀이 수동으로 병합 기준을 지켜야 합니다.

## 선택적 조직 Project 설정

조사한 실행 로그는 `Project credentials missing; skip Project status sync.`였습니다. Project 필드가 실제로 갱신됐다고 간주하지 않습니다. 이슈 라벨과 종료는 Project 설정 없이도 동작합니다.

| 유형 | 이름 | 값 |
|---|---|---|
| Repository variable | BIN_TIME_PROJECT_OWNER | MobileProgrammingTeam4 |
| Repository variable | BIN_TIME_PROJECT_NUMBER | 기존 Project 번호 |
| Repository secret | BIN_TIME_PROJECT_TOKEN | 조직 Projects 쓰기 권한이 있는 토큰 |

Project Status에 Todo / In Progress / In Review / Done을 구성합니다. 기존 스크립트는 In Review가 없으면 In Progress로 대체하므로 정확한 상태 표시에는 In Review 옵션이 필요합니다. 토큰이나 Project를 이 변경에서 새로 만들지 않습니다.

최초 Project 생성용 `scripts/bootstrap_project.py`는 별도 승인과 로컬 인증이 필요합니다. 이 스크립트는 정확히 61개의 열린 BT 이슈를 전제로 하므로 이미 일부 이슈가 닫힌 현재 저장소에 그대로 실행하지 마세요.

## 기존 BT-007 (#7) 처리

PR #68은 Closes #7을 포함하고 develop에 병합됐습니다(2026-11-05). 당시 CI는 성공했으나 리뷰 승인 기록은 없었습니다. 기존 스크립트에는 실제 이슈 종료 API가 없었고 Done도 기본 브랜치 병합에만 적용되어 #7이 열려 있었습니다. 뒤늦은 편집 이벤트도 완료를 되돌릴 수 있었습니다.

조사 중 #7은 yubin221에 의해 2026-11-05 19:38 KST에 수동으로 status:done 및 closed/completed 처리된 것을 확인했습니다. 병합 커밋이 develop에 포함되어 있고 CI가 성공한 것도 확인했습니다. 이미 완료된 상태를 보존하며 중복 종료하지 않습니다. 기존 본문의 미체크 수용 기준이나 리뷰 증거를 허위로 체크하지 않습니다. 다른 과거 이슈를 일괄 종료하거나 main에 기능을 병합하지 않습니다.

## 검증 및 문제 해결

`node --test tests/*.test.cjs`는 develop 상태 흐름, 실제 종료 호출, 재실행, 지연 이벤트, 미병합 close, 외부/다른 브랜치/없는 이슈 제외, 라벨 보존, 권한 오류 및 선택적 Project 처리를 검증합니다. API 모의 검증은 실제 Actions 종료 검증과 구분합니다.

- 상태 워크플로가 옛 코드를 실행하면 main의 진입점 설치 여부와 로그의 checkout ref를 확인합니다.
- 상태가 바뀌지 않으면 develop 대상·동일 저장소 PR인지, 본문에 유효한 종료 키워드가 있는지 확인합니다.
- 이슈 쓰기 권한 오류는 Actions 정책의 issues:write 허용 여부를 확인합니다.
- Project 실패는 Owner/Number/토큰 권한과 조직 승인, Status 옵션을 확인합니다.
- 완료 이슈가 필요한 검증을 충족하지 못했다면 자동 병합으로 우회하지 말고 담당자가 이슈를 다시 열고 후속 작업을 정합니다.
