-- ============================================================
-- match_request / activity_match의 talk_level CHECK 제약조건을
-- 'SILENT', 'LIGHT_CHAT' 2개로 좁힌다.
-- V3__create_matching_tables.sql은 이미 공유된 마이그레이션이라 직접 수정하지 않고
-- ALTER로 제약조건을 교체한다.
-- ============================================================

ALTER TABLE match_request
    DROP CONSTRAINT chk_match_request_talk,
    ADD CONSTRAINT chk_match_request_talk CHECK (talk_level IN ('SILENT', 'LIGHT_CHAT'));

ALTER TABLE activity_match
    DROP CONSTRAINT chk_activity_match_talk,
    ADD CONSTRAINT chk_activity_match_talk CHECK (talk_level IN ('SILENT', 'LIGHT_CHAT'));
