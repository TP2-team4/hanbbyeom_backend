-- 사용자 계정과 영구적인 인증 상태 저장 테이블
CREATE TABLE users (
    id BIGSERIAL,

    -- 로그인과 이메일 인증에 사용하는 소문자 이메일, 탈퇴 시 NULL
    email VARCHAR(255),

    -- BCrypt로 암호화된 비밀번호, 탈퇴 시 NULL
    password_hash VARCHAR(255),

    -- 서비스에서 표시하는 사용자 이름, 탈퇴 시 NULL
    nickname VARCHAR(50),

    -- 회원가입에 사용한 이메일의 인증 완료 시각, 탈퇴 시 NULL
    email_verified_at TIMESTAMPTZ,

    -- 회원 탈퇴 시각, 활성 회원은 NULL
    deleted_at TIMESTAMPTZ,

    -- 계정 생성 및 마지막 수정 시각
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_users PRIMARY KEY (id),

    -- 활성 사용자 이메일 중복 방지
    -- PostgreSQL UNIQUE 제약조건의 NULL 중복 허용에 따른 탈퇴 후 동일 이메일 재가입 지원
    CONSTRAINT uq_users_email UNIQUE (email),

    -- 활성 계정의 필수 정보 존재 및 탈퇴 계정의 개인정보 제거 보장
    CONSTRAINT chk_users_account_lifecycle
        CHECK (
            (
                deleted_at IS NULL
                AND email IS NOT NULL
                AND password_hash IS NOT NULL
                AND nickname IS NOT NULL
                AND email_verified_at IS NOT NULL
            )
            OR
            (
                deleted_at IS NOT NULL
                AND email IS NULL
                AND password_hash IS NULL
                AND nickname IS NULL
                AND email_verified_at IS NULL
            )
        )
);

COMMENT ON TABLE users IS '서비스 사용자 계정과 소프트 딜리트 상태 저장';

COMMENT ON COLUMN users.id IS '사용자 식별자';
COMMENT ON COLUMN users.email IS '로그인 및 이메일 인증용 이메일, 탈퇴 시 제거';
COMMENT ON COLUMN users.password_hash IS 'BCrypt 암호화 비밀번호, 탈퇴 시 제거';
COMMENT ON COLUMN users.nickname IS '서비스 표시용 사용자 이름, 탈퇴 시 제거';
COMMENT ON COLUMN users.email_verified_at IS '이메일 인증 완료 시각, 탈퇴 시 제거';
COMMENT ON COLUMN users.deleted_at IS '회원 탈퇴 시각';
COMMENT ON COLUMN users.created_at IS '계정 생성 시각';
COMMENT ON COLUMN users.updated_at IS '계정 마지막 수정 시각';

-- 애플리케이션 계층 필수 구현 항목
-- 회원가입 요청 DTO: 이메일 필수·공백 검증(@NotBlank), 형식 검증(@Email), 길이 검증(@Size(max = 255))
-- 회원가입 요청 DTO: 비밀번호 필수·공백 검증(@NotBlank), 길이 검증(@Size(min = 8, max = 64))
-- 회원가입 요청 DTO: 닉네임 필수·공백 검증(@NotBlank), 길이 검증(@Size(max = 50))
-- 회원가입 Service: 이메일 앞뒤 공백 제거 및 소문자 변환(trim, toLowerCase(Locale.ROOT))
-- 회원가입 Service: 이메일 사전 중복 검사 및 UNIQUE 위반 예외 변환
-- 회원가입 Service: 이메일 인증 완료·유효기간·사용 여부 확인 및 인증 기록 사용 완료 처리
-- 회원가입 Service: PasswordEncoder를 사용한 BCrypt 비밀번호 암호화
-- User Entity: 생성·수정 시각 자동 기록을 위한 JPA Auditing 적용
-- 로그인·사용자 조회: deletedAt이 NULL인 활성 사용자만 조회
-- 회원 탈퇴 Service: 이메일·비밀번호 해시·닉네임·이메일 인증 시각 NULL 변경 및 현재 탈퇴 시각 기록
-- 회원 탈퇴 Service: Refresh Token과 남아 있는 이메일 인증 기록 제거
-- API 응답 DTO: passwordHash 응답 제외
