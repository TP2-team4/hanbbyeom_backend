# GitHub Actions

현재는 자동 배포를 제외하고, 빌드·테스트를 수행하는 CI와 개발 흐름 자동화만 사용한다.

## CI

다음 상황에서 Java 17과 PostgreSQL 15 환경으로 Gradle 빌드 및 테스트를 실행한다.

- `dev`, `main` 대상 Pull Request 생성 및 변경
- `dev`, `main` 브랜치 Push

GitHub Actions 실행 과정에서 테스트 전용 PostgreSQL 데이터베이스를 생성하며, 실행이 끝나면 함께 제거된다.

실행 명령:

```bash
./gradlew build --no-daemon
```

빌드 또는 테스트가 실패하면 GitHub Actions도 실패로 표시된다.

## dev 병합 시 이슈 종료

`dev` 대상 Pull Request가 실제로 병합되면 PR 본문의 종료 키워드에 연결된 이슈를 자동으로 닫는다.

PR 본문 작성 예시:

```text
Closes #1
```

다음 종료 키워드를 사용할 수 있다.

```text
Closes #1
Fixes #1
Resolves #1
```

여러 이슈를 종료하려면 각 이슈를 별도의 줄에 작성한다.

```text
Closes #1
Closes #2
```

다음 상황에서는 이슈를 닫지 않는다.

- Pull Request를 병합하지 않고 닫은 경우
- 종료 키워드를 PR 제목이나 댓글에만 작성한 경우
- PR 본문에 종료 키워드가 없는 경우
- 이미 종료된 이슈인 경우

이슈 종료 워크플로는 저장소의 기본 브랜치인 `main`에 반영된 이후부터 동작한다.

## Workflow 파일

```text
.github/workflows/
├─ ci.yaml
└─ close-issues-on-dev-merge.yaml
```

- `ci.yaml`: Gradle 빌드 및 테스트
- `close-issues-on-dev-merge.yaml`: `dev` 병합 시 연결 이슈 종료
