# GitHub Actions

빌드·테스트, Docker 이미지 발행, 개발 흐름 자동화를 위한 GitHub Actions 구성

- EC2 자동 배포 제외
- EC2 Docker Compose 수동 반영

## CI

실행 대상

- `dev`, `main` 대상 Pull Request 생성 및 변경
- `dev`, `main` 브랜치 Push

실행 환경

- Java 17
- PostgreSQL 15
- 실행 종료 시 제거되는 테스트 전용 PostgreSQL 데이터베이스

실행 명령

```bash
./gradlew build --no-daemon
```

실패 처리

- Gradle 빌드 또는 테스트 실패 시 GitHub Actions 실패 처리

## Docker 이미지 발행

실행 조건

- `main` 브랜치 Push
- 기존 빌드·테스트 Job 성공
- Pull Request와 `dev` 브랜치 Push 제외

이미지 저장소

```text
shppark/hanbbyeom-backend
```

이미지 태그

- 전체 Git 커밋 해시: 배포 버전 추적 및 롤백용
- `latest`: 기본 배포용

Docker Hub 인증용 Repository Secrets

- `DOCKERHUB_USERNAME`
- `DOCKERHUB_TOKEN`

EC2 `app` 서비스 수동 배포

```bash
docker compose pull app
docker compose up -d app
```

배포 버전 선택

- 특정 버전 배포·롤백: EC2 `.env`의 `APP_IMAGE_TAG`에 전체 커밋 해시 지정
- `latest` 배포: `APP_IMAGE_TAG`를 빈 값으로 유지

## dev 병합 시 이슈 종료

실행 조건

- `dev` 대상 Pull Request 병합
- PR 본문의 종료 키워드와 연결된 이슈 자동 종료

PR 본문 작성 예시

```text
Closes #1
```

사용 가능한 종료 키워드

```text
Closes #1
Fixes #1
Resolves #1
```

여러 이슈 종료 예시

```text
Closes #1
Closes #2
```

자동 종료 제외 조건

- Pull Request를 병합하지 않고 닫은 경우
- 종료 키워드를 PR 제목이나 댓글에만 작성한 경우
- PR 본문에 종료 키워드가 없는 경우
- 이미 종료된 이슈인 경우

적용 조건

- 이슈 종료 워크플로가 저장소 기본 브랜치인 `main`에 반영된 이후 동작

## Workflow 파일

```text
.github/workflows/
├─ ci.yaml
└─ close-issues-on-dev-merge.yaml
```

- `ci.yaml`: Gradle 빌드·테스트 및 `main` Push 시 Docker 이미지 발행
- `close-issues-on-dev-merge.yaml`: `dev` 병합 시 연결 이슈 종료
