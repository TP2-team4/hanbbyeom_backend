-- 이메일 인증 코드 발송 및 확인 이력 저장 테이블
-- 재전송/재요청 시 기존 행을 수정하지 않고 새 행을 추가하는 방식
-- (가장 최근에 생성된, 미사용·미만료 코드만 유효한 코드로 취급)
CREATE TABLE email_verifications (
    id BIGSERIAL,

    -- 인증 대상 이메일 (Service에서 정규화한 소문자 이메일)
    email VARCHAR(255) NOT NULL,

    -- 인증 목적: SIGNUP(회원가입)/ PASSWORD_RESET(비밀번호 재설정)
    purpose VARCHAR(20) NOT NULL,

    -- 인증 코드 원문이 아닌 해시값
    code_hash VARCHAR(255) NOT NULL,

    -- 인증 코드 만료 시각
    expires_at TIMESTAMPTZ NOT NULL,

    -- 인증 완료(사용 완료) 시각, 미사용 상태면 NULL
    verified_at TIMESTAMPTZ,

    -- 인증 코드 입력 실패 횟수
    attempt_count INT NOT NULL DEFAULT 0,

    -- 생성 시각(=발송 시각), 재전송 대기시간 판단 기준
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_email_verifications PRIMARY KEY (id),

    -- 인증 목적 값 제한
    CONSTRAINT chk_email_verifications_purpose
        CHECK (purpose IN ('SIGNUP', 'PASSWORD_RESET')),

    -- users 테이블(V2)과 동일하게 앞뒤 공백이 없는 소문자 이메일만 허용
    -- 정규화 누락 시 users의 이메일과 대소문자가 어긋나 인증 여부 조회가 실패하는 상황 방지
    CONSTRAINT chk_email_verifications_email_normalized
        CHECK (email = LOWER(BTRIM(email)))
);

COMMENT ON TABLE email_verifications IS '이메일 인증 코드 발송 및 확인 이력';

COMMENT ON COLUMN email_verifications.id IS '인증 요청 식별자';
COMMENT ON COLUMN email_verifications.email IS '인증 대상 이메일';
COMMENT ON COLUMN email_verifications.purpose IS '인증 목적 (SIGNUP, PASSWORD_RESET)';
COMMENT ON COLUMN email_verifications.code_hash IS '인증 코드 해시값 (원문 미저장)';
COMMENT ON COLUMN email_verifications.expires_at IS '인증 코드 만료 시각';
COMMENT ON COLUMN email_verifications.verified_at IS '인증 완료(사용 완료) 시각, 미사용 시 NULL';
COMMENT ON COLUMN email_verifications.attempt_count IS '인증 코드 입력 실패 횟수';
COMMENT ON COLUMN email_verifications.created_at IS '생성 시각(발송 시각), 재전송 대기시간 판단 기준';

-- 이메일+목적별 최신 인증 요청 조회 성능을 위한 인덱스
CREATE INDEX idx_email_verifications_email_purpose_created_at
    ON email_verifications (email, purpose, created_at DESC);

-- 애플리케이션 계층 필수 구현 항목
-- 인증 코드 발송 Service: 6자리 등 충분히 짧은 난수 코드 생성, 코드 원문은 로그에 출력 금지
-- 인증 코드 발송 Service: 코드는 해시(예: SHA-256)로 저장, 원문은 이메일 발송에만 사용 후 폐기
-- 인증 코드 발송 Service: 동일 이메일+목적의 재전송은 최소 대기시간(예: 60초) 이후에만 허용
-- 인증 코드 확인 Service: created_at 기준 가장 최근 행만 유효 후보로 조회
-- 인증 코드 확인 Service: expires_at 경과, verified_at 존재(이미 사용됨) 시 거부
-- 인증 코드 확인 Service: attempt_count가 제한 횟수(예: 5회) 초과 시 해당 코드 거부
-- 인증 코드 확인 Service: 코드 불일치 시 attempt_count 증가, 일치 시 verified_at 기록
-- 회원가입 Service: 가입 완료 시 SIGNUP 목적의 인증 기록이 verified_at 존재하는지 확인
