-- 이메일 저장 형식 보장: 앞뒤 공백이 없는 소문자 이메일만 허용 (NULL - 탈퇴 회원)
-- 애플리케이션 정규화 누락 시 대소문자 중복 계정이 저장되는 상황 방지
ALTER TABLE users
    ADD CONSTRAINT chk_users_email_normalized
        CHECK (email IS NULL -- 1. 이메일 값이 NULL이면 허용(탈퇴 회원의 경우)
            -- 2. 저장하려는 이메일이 앞뒤 공백을 제거하고 소문자로 바꾼 결과와 완전히 같을 때 허용(정규화)
            OR email = LOWER(BTRIM(email)));
