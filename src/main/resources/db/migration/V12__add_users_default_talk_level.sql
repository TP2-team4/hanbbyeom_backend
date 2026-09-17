-- 사용자가 모집글 작성 시 기본으로 사용할 대화 수준 설정
ALTER TABLE users
    ADD COLUMN default_talk_level VARCHAR(20);

-- 기존 활성 사용자는 서비스 기본값인 SILENT로 설정
-- 탈퇴 사용자는 개인정보 제거 정책에 따라 NULL을 유지
UPDATE users
SET default_talk_level = 'SILENT'
WHERE deleted_at IS NULL;

ALTER TABLE users
    ALTER COLUMN default_talk_level SET DEFAULT 'SILENT';

ALTER TABLE users
    ADD CONSTRAINT chk_users_default_talk_level
        CHECK (
            default_talk_level IS NULL
            OR default_talk_level IN ('SILENT', 'LIGHT_CHAT')
        );

-- 활성 사용자는 기본 대화 수준이 반드시 존재
-- 탈퇴 사용자는 다른 개인정보와 함께 기본 대화 수준도 제거
ALTER TABLE users
    DROP CONSTRAINT chk_users_account_lifecycle;

ALTER TABLE users
    ADD CONSTRAINT chk_users_account_lifecycle
        CHECK (
            (
                deleted_at IS NULL
                AND email IS NOT NULL
                AND password_hash IS NOT NULL
                AND nickname IS NOT NULL
                AND email_verified_at IS NOT NULL
                AND default_talk_level IS NOT NULL
            )
            OR
            (
                deleted_at IS NOT NULL
                AND email IS NULL
                AND password_hash IS NULL
                AND nickname IS NULL
                AND email_verified_at IS NULL
                AND default_talk_level IS NULL
            )
        );

COMMENT ON COLUMN users.default_talk_level IS
    '모집글 작성 화면에 기본으로 적용할 사용자 선호 대화 수준, 탈퇴 시 제거';
