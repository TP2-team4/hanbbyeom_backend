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

* Java 17
* Spring Boot 4.1.1
* Spring Web MVC
* Spring Data JPA
* Spring Security
* JWT
* Spring Validation
* PostgreSQL
* Flyway
* SpringDoc OpenAPI / Swagger
* Gradle
* AWS EC2

<br>

## 프로젝트 구조

기능 단위 패키지 구조를 기반으로 각 기능 내부의 Controller, Service, Domain, Repository, DTO 분리

```text
com.team4.hanbbyeom
│
├── auth                        # 회원가입, 로그인, 인증
│   ├── controller
│   ├── service
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
├── feedback                    # 활동 결과 및 후기
│   ├── controller
│   ├── service
│   ├── domain
│   │   ├── AttendanceReport
│   │   └── Review
│   ├── repository
│   └── dto
│
├── message                     # 프리셋 메시지
│   ├── controller
│   ├── service
│   ├── domain
│   ├── repository
│   └── dto
│
├── global                      # 공통 설정
│   ├── config
│   ├── security
│   │   └── jwt
│   └── exception
│
└── HanbbyeomApplication
```

### 패키지 역할

| 패키지        | 역할                       |
| ---------- | ------------------------ |
| `auth`     | 회원가입, 로그인, JWT 인증        |
| `user`     | 사용자 정보 및 신뢰 프로필          |
| `run`      | Quiet Run 러닝 코스 및 매칭 조건  |
| `matching` | 공통 랜덤 매칭 및 상태 관리         |
| `feedback` | 참석 여부 및 후기               |
| `message`  | 프리셋 메시지                  |
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

* 조건에 맞는 사용자 탐색
* 랜덤 매칭 후보 생성
* 상대 신뢰 프로필 확인
* 같이하기 / 다시 매칭
* 양쪽 수락 기반 최종 매칭
* 동일 사용자의 중복 매칭 방지

### Feedback

* 활동 참석 여부 확인
* 별점 평가
* 실제로 느낀 대화 정도 평가
* 후기 작성

### Message

자유 채팅 대신 활동에 필요한 프리셋 메시지 제공

* 도착했어요.
* 5분 정도 늦어요.
* 장소를 찾지 못했어요.

<br>

## 향후 확장 계획

**혼밥메이트(Quiet Meal)**

Kakao Local API를 활용한 식당 검색과 동일 식당·시간·대화 정도를 기준으로 연결하는 기능

<br>

## 네이밍 컨벤션

| 분류           | 패턴                | 예시                       |
| ------------ | ----------------- | ------------------------ |
| Controller   | `[기능]Controller`  | `MatchController`        |
| Service      | `[기능]Service`     | `MatchingService`        |
| Repository   | `[도메인]Repository` | `MatchRequestRepository` |
| Entity       | 도메인명              | `MatchRequest`           |
| Request DTO  | `[기능]Request`     | `RunMatchRequest`        |
| Response DTO | `[기능]Response`    | `TrustProfileResponse`   |
| 설정 클래스       | `[기능]Config`      | `SecurityConfig`         |

DTO 클래스명에 `Dto` 접미사 미사용

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

### 예시

```text
feat/auth-3-login
feat/run-7-course-list
feat/matching-15-create-match
feat/feedback-21-create-review

fix/matching-25-duplicate-match
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
Type: 제목 (#이슈번호)

본문 (선택) - 무엇을, 왜 변경했는지 설명

Resolves: #이슈번호
```

### 커밋 타입

| Type       | 설명          | 예시                           |
| ---------- | ----------- | ---------------------------- |
| `Feat`     | 새로운 기능 추가   | `Feat: 러닝 매칭 API 추가 (#12)`   |
| `Fix`      | 버그 수정       | `Fix: 중복 매칭 오류 수정 (#15)`     |
| `Refactor` | 코드 리팩토링     | `Refactor: 매칭 로직 분리`         |
| `Docs`     | 문서 수정       | `Docs: README 업데이트`          |
| `Style`    | 코드 스타일 변경   | `Style: import 정리`           |
| `Test`     | 테스트 추가 및 수정 | `Test: 매칭 서비스 테스트 추가`        |
| `Chore`    | 빌드 및 설정 변경  | `Chore: Swagger 의존성 추가`      |
| `Init`     | 프로젝트 초기 설정  | `Init: Spring Boot 프로젝트 초기화` |
| `Rename`   | 파일 및 폴더명 변경 | `Rename: 클래스명 변경`            |
| `Remove`   | 파일 및 코드 삭제  | `Remove: 미사용 코드 삭제`          |
| `Hotfix`   | 운영 환경 긴급 수정 | `Hotfix: JWT 인증 오류 수정`       |

### 예시

```text
Feat: 러닝 조건 기반 매칭 API 추가 (#12)

러닝 코스, 활동 시간, 페이스 범위, 대화 정도를 기준으로  
호환 가능한 사용자 조회 기능 구현

Resolves: #12
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
* Backend: AWS EC2
* Database: PostgreSQL
