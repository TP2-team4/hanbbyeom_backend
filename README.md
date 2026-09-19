# Hanbbyeom Backend

> 내향인을 위한 랜덤 동행 매칭 서비스 `한뼘` 백엔드 레포지토리

<br>

## 서비스 소개

한뼘은 혼자가 편한 내향인이 혼자 하기 애매한 순간에만,  
부담 없이 연결되는 랜덤 동행 매칭 서비스

### 러닝메이트 - Quiet Run

러닝 코스, 시간, 페이스, 대화 정도를 기반으로 한 러닝메이트 매칭

<br>

## 기술 스택

* Language: Java 17
* Framework: Spring Boot 4.1.1, Spring Web MVC, Spring Validation
* Auth: Spring Security, JWT (jjwt)
* Persistence: Spring Data JPA, PostgreSQL, Flyway
* Mail: Spring Mail (SMTP)
* API Docs: SpringDoc OpenAPI / Swagger
* Test: JUnit 5, AssertJ, Mockito
* Build: Gradle
* Infra: Docker / Docker Compose, Caddy, AWS EC2
* CI: GitHub Actions

<br>

## 프로젝트 구조

기능 단위 패키지 구조를 기반으로 각 기능 내부의 Controller, Service, Domain, Repository, DTO 분리

```text
com.team4.hanbbyeom
│
├── auth                        # 회원가입, 로그인, 이메일 인증
│   ├── controller
│   ├── service
│   ├── domain
│   │   └── EmailVerification   # 이메일 인증 요청
│   ├── repository
│   └── dto
│
├── user                        # 사용자 및 신뢰 프로필
│   ├── controller
│   ├── service
│   ├── domain
│   ├── repository
│   └── dto
│
├── run                         # Quiet Run
│   ├── controller
│   ├── service
│   ├── domain
│   │   ├── RunningCourse       # 러닝 코스
│   │   └── RunMatchCondition   # 러닝 매칭 조건
│   ├── repository
│   └── dto
│
├── matching                    # 공통 랜덤 매칭
│   ├── controller
│   ├── service
│   ├── scheduler
│   ├── domain
│   │   ├── MatchRequest        # 매칭 요청
│   │   ├── ActivityMatch       # 매칭 후보 및 확정 약속
│   │   └── MatchParticipant    # 참여자 및 수락 상태
│   ├── repository
│   └── dto
│
├── feedback                    # 활동 후기 및 노쇼 신고
│   ├── controller
│   ├── service
│   ├── domain
│   │   ├── ActivityReview      # 활동 후기
│   │   └── NoShowReport        # 노쇼 신고
│   ├── repository
│   └── dto
│
├── chat                        # 확정 매칭 참가자 간 1:1 채팅
│   ├── controller
│   ├── service
│   ├── domain
│   │   └── ChatMessage         # 채팅 메시지
│   ├── repository
│   └── dto
│
├── global                      # 공통 설정
│   ├── config
│   ├── security
│   │   └── jwt
│   ├── exception
│   ├── validation
│   └── util
│
└── HanbbyeomApplication
```

### 패키지 역할

| 패키지        | 역할                       |
| ---------- | ------------------------ |
| `auth`     | 회원가입, 로그인, 이메일 인증, JWT 발급 |
| `user`     | 사용자 정보 및 신뢰 프로필          |
| `run`      | Quiet Run 러닝 코스 및 매칭 조건  |
| `matching` | 공통 랜덤 매칭 및 상태 관리         |
| `feedback` | 활동 후기 및 노쇼 신고            |
| `chat`     | 확정 매칭 참가자 간 1:1 채팅       |
| `global`   | 공통 설정, 인증, 예외 처리          |

<br>

## 주요 기능

### Quiet Run

* 대표 러닝 코스 선택
* 활동 시간 설정
* 페이스 범위 설정
* 대화 정도 설정
* 조건 기반 랜덤 매칭

### Matching

* 조건 기반 모집글 등록 및 목록 조회
* 코스, 거리, 페이스, 대화 정도 필터
* 상대 신뢰 프로필 확인
* 모집글 신청 및 신청 취소
* 호스트 수락 기반 최종 매칭
* 응답 기한 경과 시 자동 만료
* 동일 사용자의 활성 모집글 중복 생성 방지

### Feedback

* 활동 종료 후 후기 작성 (별점, 체감 대화 정도, 한 줄 후기)
* 노쇼 신고 및 사유 기록
* 후기와 신고 결과의 신뢰 프로필 반영
* 같은 활동에 후기와 신고 중 하나만 제출

### Chat

확정된 매칭 참가자 간 1:1 채팅

* 최근 메시지 순 채팅 목록 조회
* 마지막 수신 메시지 이후의 새 메시지 폴링 조회
* 100자 이하 텍스트 메시지 전송
* 프리셋 문구도 일반 메시지로 전송
* 활동 예정일 다음 날 00시까지 전송 허용

<br>

## 향후 확장 계획

**혼밥메이트(Quiet Meal)**

Kakao Local API를 활용한 식당 검색과 동일 식당·시간·대화 정도를 기준으로 연결하는 기능

<br>

## 네이밍 컨벤션

| 분류           | 패턴                | 예시                       |
| ------------ | ----------------- | ------------------------ |
| Controller   | `[기능]Controller`  | `AuthController`         |
| Service      | `[기능]Service`     | `ChatMessageService`     |
| Repository   | `[도메인]Repository` | `ChatMessageRepository`  |
| Entity       | 도메인명              | `ChatMessage`            |
| Request DTO  | `[기능]Request`     | `SignUpRequest`          |
| Response DTO | `[기능]Response`    | `LoginResponse`          |
| 설정 클래스       | `[기능]Config`      | `SecurityConfig`         |

DTO 클래스명에 `Dto` 접미사 미사용

Entity `MatchRequest`는 이름이 `Request`로 끝나지만 DTO 아님

<br>

## 브랜치 전략

`GitHub Flow` 기반 브랜치 운영

```text
main ← dev ← feat/*
```

| 브랜치                           | 역할         |
| ----------------------------- | ---------- |
| `main`                        | 배포용 안정 브랜치 |
| `dev`                         | 통합 개발 브랜치  |
| `feat/{도메인}-{이슈번호}-{기능명}`     | 기능 개발 브랜치  |
| `fix/{도메인}-{이슈번호}-{기능명}`      | 버그 수정 브랜치  |
| `refactor/{도메인}-{이슈번호}-{기능명}` | 리팩토링 브랜치   |
| `test/{도메인}-{이슈번호}-{기능명}`      | 테스트 작업 브랜치  |
| `chore/{설명}`                   | 빌드 및 설정 변경 브랜치 |
| `docs/{설명}`                    | 문서 수정 브랜치   |

이슈 번호가 없는 작업은 번호 생략

### 예시

```text
feat/auth-3-login
fix/matching-25-duplicate-match

chore/add-dockerfile
docs/readme-update
```

### 작업 흐름

1. 작업 단위로 **Issue** 생성
2. `dev` 브랜치에서 `feat/` 브랜치 분기
3. 작업 완료 후 `dev`로 **PR** 생성
4. **2인 이상 코드 리뷰 및 승인** 후 병합
5. 배포 가능 상태 확인 후 `dev` → `main` PR 생성

<br>

## 커밋 컨벤션

```text
type: 제목 (#이슈번호)

본문 (선택) - 무엇을, 왜 변경했는지 설명
```

### 커밋 타입

| Type       | 설명          | 예시                           |
| ---------- | ----------- | ---------------------------- |
| `feat`     | 새로운 기능 추가   | `feat: 러닝 매칭 API 추가 (#12)`   |
| `fix`      | 버그 수정       | `fix: 중복 매칭 오류 수정 (#15)`     |
| `refactor` | 코드 리팩토링     | `refactor: 매칭 로직 분리`         |
| `docs`     | 문서 수정       | `docs: README 업데이트`          |
| `style`    | 코드 스타일 변경   | `style: import 정리`           |
| `test`     | 테스트 추가 및 수정 | `test: 매칭 서비스 테스트 추가`        |
| `chore`    | 빌드 및 설정 변경  | `chore: Swagger 의존성 추가`      |
| `revert`   | 이전 커밋 되돌리기  | `revert: 매칭 스케줄러 변경 되돌림`     |

### 예시

```text
feat: 기본 대화 수준 변경 기능 추가 (#60)

모집글 작성 시 기본으로 사용할 대화 수준을 사용자가 직접 변경할 수 있도록 설정 API 추가

- 기본 대화 수준 변경 API 구현
- 변경 시 기존 모집글과 매칭의 대화 수준 미변경
```

<br>

## Issue / PR 규칙

### Issue

```text
[Tag] 제목
```

* Labels, Assignees 지정 필수
* 이슈 요약 / 상세 내용 / 체크리스트 / 참고 사항 포함

### PR

```text
[Tag] 제목
```

* Labels, Assignees, Reviewers 지정 필수
* 관련 이슈 / 작업 내용 / 테스트 결과 / 참고 사항 포함
* **2인 이상 승인 후 병합**

<br>

## 배포

* Frontend: Vercel
* Backend: AWS EC2 (Docker Compose)
* Database: PostgreSQL (Docker 컨테이너)
* Domain: DuckDNS
* Reverse Proxy: Caddy (HTTPS 인증서 자동 발급 및 갱신)
* CI/CD: GitHub Actions (PR 및 `dev`, `main` Push 시 빌드·테스트, `main` Push 시 Docker 이미지 발행)
* Deployment: EC2에서 Docker Compose를 이용한 수동 반영

### 서비스 주소

* API: `https://hanbbyeom.duckdns.org`
* API 문서: `https://hanbbyeom.duckdns.org/swagger-ui.html`

API 문서는 `SWAGGER_ENABLED` 환경변수로 비활성화 가능
