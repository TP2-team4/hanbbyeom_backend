# Git 작업 및 Pull Request 가이드

동일한 작업 순서와 안전한 코드 병합을 위한 협업 가이드

## 기본 원칙

- `main`, `dev` 브랜치 직접 작업 및 직접 Push 금지
- 하나의 Issue에 하나의 작업 브랜치 사용
- 하나의 Pull Request에 관련된 변경만 포함
- 작업 시작 전 `dev` 최신 상태 반영
- 커밋 전 변경 파일과 실제 코드 확인
- Push 전 전체 테스트 실행
- Pull Request 승인 및 CI 성공 후 병합

## 브랜치 역할

| 브랜치 | 역할 |
| --- | --- |
| `main` | 배포 가능한 안정 코드 관리 |
| `dev` | 통합 개발 브랜치 |
| `feat/*` | 새로운 기능 개발 |
| `fix/*` | 잘못된 동작 및 오류 수정 |
| `refactor/*` | 기능 변화 없는 코드 구조 개선 |
| `docs/*` | README와 개발 문서 작성 |
| `chore/*` | 설정과 개발 환경 관리 |

브랜치 이름 형식

```text
종류/도메인-이슈번호-작업명
```

Issue가 없는 공통 문서와 설정 작업 등은 이슈 번호 생략 가능

예시

```text
feat/auth-1-user-foundation
fix/matching-12-duplicate-match
docs/git-workflow-guide
```

## 1. 작업 시작

### Issue 확인

- 작업 목적과 완료 기준 확인
- 본인 담당자 지정 확인
- 브랜치명에 사용할 도메인과 Issue 번호 확인

### 작업 트리 확인

```bash
git status
```

커밋하지 않은 변경이 있다면 다른 브랜치로 이동하기 전에 먼저 정리

### dev 최신화

```bash
git switch dev
git pull --ff-only origin dev
```

- `git switch dev`: 통합 개발 브랜치로 이동
- `git pull --ff-only origin dev`: 원격 `dev`의 최신 변경 반영
- `--ff-only`: 예상하지 않은 병합 커밋 생성 방지

### 작업 브랜치 생성

```bash
git switch -c 브랜치명
```

`-c`는 새 브랜치 생성과 이동을 함께 수행하는 옵션

이미 생성한 브랜치로 이동하는 경우

```bash
git switch 브랜치명
```

## 2. 기능 작업

### 권장 개발 순서

```text
기능 요구사항 확인
→ DB 변경이 필요한 경우 Flyway 마이그레이션 작성
→ Entity 및 Repository 작성
→ Service 작성
→ Controller 및 DTO 작성
→ 테스트 작성
→ 전체 테스트 실행
```

기능에 필요하지 않은 단계는 생략

### 작업 범위 관리

- 현재 Issue의 완료 기준에 필요한 코드만 변경
- 관련 없는 리팩터링과 파일 이동 제외
- 다른 팀원 담당 파일 변경 전 담당자와 협의
- 적용된 Flyway 마이그레이션 파일 수정 금지
- DB 구조 변경 시 새로운 버전의 마이그레이션 추가

Flyway 추가 예시

```text
V1__create_users.sql          기존 파일 유지
V2__create_email_verification.sql  새로운 변경 추가
```

## 3. 변경 내용 확인

현재 변경 파일 확인

```bash
git status
```

아직 `git add`하지 않은 코드 확인

```bash
git diff
```

파일별 확인이 필요한 경우:

```bash
git diff -- 파일경로
```

확인 사항

- 현재 Issue와 관련된 파일만 변경되었는지 확인
- `.env`, 비밀번호, API Key, JWT 비밀키 포함 여부 확인
- 임시 파일과 실행 결과 포함 여부 확인
- 사용하지 않는 코드와 import 포함 여부 확인
- 디버깅용 출력문 포함 여부 확인

## 4. 테스트 실행

Windows PowerShell

```bash
.\gradlew.bat test --no-daemon
```

macOS 또는 Linux

```bash
./gradlew test --no-daemon
```

다음 결과 확인

```text
BUILD SUCCESSFUL
```

테스트 실패 상태의 Push 금지 및 실패 원인 해결 후 재실행

## 5. 커밋

### 관련 파일만 Staging

```bash
git add 파일1 파일2
```

예시:

```bash
git add src/main/java/com/team4/hanbbyeom/user/domain/User.java src/main/java/com/team4/hanbbyeom/user/repository/UserRepository.java
```

`git add .` 사용 전 전체 변경 확인 필수

### Staging 내용 확인

```bash
git status
git diff --staged
```

`git diff --staged`는 이번 커밋에 실제로 포함될 코드를 표시

### 커밋 메시지 작성

형식

```text
타입: 작업 내용 (#이슈번호)
```

주요 타입

| 타입 | 사용 기준 |
| --- | --- |
| `feat` | 새로운 기능 또는 동작 추가 |
| `fix` | 잘못된 동작 또는 오류 수정 |
| `refactor` | 기능 변화 없는 구조 개선 |
| `test` | 테스트 추가 및 수정 |
| `docs` | README와 문서 변경 |
| `chore` | 설정, 주석, 기타 유지보수 |

예시

```bash
git commit -m "feat: 사용자 이메일 조회 구현 (#1)" -m "사용자 조회 기반 구성

- UserRepository 이메일 조회 메서드 추가
- 탈퇴 사용자 조회 제외 조건 적용"
```

커밋 기준

- 제목과 본문의 실제 변경 내용 일치
- 설정, 기능, 수정, 테스트처럼 목적이 다른 변경의 커밋 분리
- 이미 완료한 커밋에서 발견된 오류의 별도 `fix` 커밋 작성
- 공유한 커밋을 임의로 수정하는 `commit --amend` 사용 금지

## 6. Push

현재 브랜치와 커밋 상태 확인

```bash
git status
git log --oneline -5
```

브랜치 최초 Push

```bash
git push -u origin 브랜치명
```

- `origin`: 팀 GitHub 저장소
- `-u`: 로컬 브랜치와 원격 브랜치 연결

같은 브랜치의 이후 Push

```bash
git push
```

`main`, `dev`, 다른 팀원의 기능 브랜치 대상 Push 금지

## 7. Pull Request 작성

### 브랜치 선택

```text
base: dev
compare: 작업 브랜치
```

`base`는 변경을 반영할 대상이고 `compare`는 본인이 작업한 브랜치

### 제목

```text
[도메인] 작업 목적
```

예시

```text
[Auth] 사용자 인증 기반 구축
```

### 본문

```markdown
## 관련 이슈

Closes #1

## 작업 내용

- 사용자 테이블 마이그레이션 추가
- User Entity 및 Repository 작성
- 이메일 조회 테스트 작성

## 테스트

- [x] Flyway 마이그레이션 성공
- [x] JPA 스키마 검증 성공
- [x] Gradle 전체 테스트 성공

## 참고 사항

- 리뷰 시 확인이 필요한 내용 작성
```

`Closes #이슈번호`는 PR 본문에 작성  
PR 제목, 커밋 메시지, 댓글에만 작성하면 이슈 자동 종료 대상에 포함되지 않음

## 8. PR 작성자 확인

- [ ] PR 대상 브랜치 `dev` 확인
- [ ] 관련 Issue 연결 및 `Closes #번호` 작성
- [ ] 작업 목적과 변경 내용 작성
- [ ] 현재 Issue와 관계없는 변경 제외
- [ ] 테스트 결과 작성
- [ ] 민감정보와 개인 환경 파일 제외
- [ ] 리뷰어와 담당자 지정
- [ ] GitHub Actions CI 성공 확인
- [ ] 리뷰 의견 반영 및 대화 해결 처리

리뷰 수정 반영 후

```bash
git add 관련파일
git commit -m "fix: 리뷰 내용 반영 (#이슈번호)"
git push
```

새 Push가 완료되면 기존 PR에 변경 내용이 자동 반영됨

## 9. PR 리뷰어 공통 확인

### 요구사항

- [ ] Issue의 작업 목적과 완료 기준 충족
- [ ] 요청 범위 밖의 기능과 리팩터링 제외
- [ ] 변경 전후 동작의 명확한 설명

### 코드

- [ ] 패키지 위치와 이름의 팀 규칙 일치
- [ ] 코드 역할과 이름의 일치
- [ ] 불필요한 Setter, 사용하지 않는 코드와 import 제외
- [ ] 이해하기 어려운 로직의 적절한 주석 제공
- [ ] 다른 기능에 미치는 영향 확인

### API와 보안

- [ ] 요청값 검증과 예외 상황 처리
- [ ] 인증 및 사용자 권한 확인
- [ ] 비밀번호, 토큰, API Key 등 민감정보 미노출
- [ ] Entity 직접 반환 대신 Response DTO 사용

### DB

- [ ] Entity와 DB 컬럼의 타입 및 NULL 조건 일치
- [ ] 새로운 Flyway 버전 파일 사용
- [ ] 이미 적용된 Flyway 파일 수정 제외
- [ ] 중복과 데이터 무결성 제약조건 확인
- [ ] 트랜잭션이 필요한 작업의 적용 여부 확인

### 테스트와 병합

- [ ] 정상 흐름과 주요 실패 흐름 검증
- [ ] 기존 테스트 포함 전체 테스트 성공
- [ ] GitHub Actions CI 성공
- [ ] 미해결 리뷰 의견 없음
- [ ] 최소 2인 이상 리뷰 및 승인

문제 발견 시 `Request changes`, 문제가 없을 때 `Approve` 선택

## 10. 충돌 처리

원격에 Push하기 전 본인 작업 브랜치에 최신 `dev` 반영

```bash
git fetch origin
git rebase origin/dev
```

충돌 발생 시 Git이 표시한 파일을 수정한 뒤 실행

```bash
git add 충돌을해결한파일
git rebase --continue
```

Rebase 취소

```bash
git rebase --abort
```

이미 Push했거나 PR을 생성한 작업 브랜치에는 Rebase 대신 Merge 사용

```bash
git fetch origin
git merge origin/dev
```

Merge 충돌 파일 수정 후

```bash
git add 충돌을해결한파일
git commit
git push
```

충돌 처리 기준

- Rebase는 원격 Push 전 본인만 사용하는 기능 브랜치에서만 사용
- `main`, `dev`, 다른 팀원과 공유 중인 브랜치에서 사용 금지
- 이미 Push한 브랜치의 Rebase와 강제 Push 금지
- 충돌 해결이 불확실한 경우 임의 진행 중단 및 팀원 확인

## 11. PR 병합

병합 전 최종 확인

- 2인 이상 리뷰 승인
- 모든 리뷰 의견 해결
- GitHub Actions CI 성공
- PR 대상 브랜치 `dev` 확인
- PR 본문의 `Closes #이슈번호` 확인

PR을 단순히 닫지 않고 실제로 병합해야 연결된 이슈가 자동으로 종료됨

## 12. 병합 후 정리

GitHub PR 화면에서 `Delete branch`를 선택하여 원격 작업 브랜치 삭제

로컬 `dev` 최신화

```bash
git switch dev
git pull --ff-only origin dev
```

로컬 작업 브랜치 삭제

```bash
git branch -d 브랜치명
```

삭제된 원격 브랜치 정보 정리

```bash
git fetch --prune origin
```

다음 작업은 최신화된 `dev`에서 새로운 브랜치 생성 후 시작

## 작업 순서 요약

```text
Issue 확인
→ dev 최신화
→ 작업 브랜치 생성
→ 기능 및 테스트 작성
→ 변경 파일 확인
→ 관련 파일만 커밋
→ 전체 테스트
→ Push
→ dev 대상 PR 생성
→ 코드 리뷰 및 CI 확인
→ PR 병합과 Issue 종료
→ 작업 브랜치 삭제
→ dev 최신화
```
