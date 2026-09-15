-- V9__add_review_and_noshow_count_to_trust_profile.sql

-- 이슈 #22의 TrustProfileResponse가 요구하는 두 카운트를 trust_profile에 추가합니다.
-- 후기 작성 API, 노쇼 신고 API가 아직 없어서 당분간은 항상 0이 반환됩니다.
-- 해당 기능을 담당하는 도메인이 정해지면 그쪽에서 이 값을 갱신하는 로직을 추가해야 합니다.
ALTER TABLE trust_profile
    ADD COLUMN review_count INT NOT NULL DEFAULT 0,
    ADD COLUMN no_show_report_count INT NOT NULL DEFAULT 0;

COMMENT ON COLUMN trust_profile.review_count IS '받은 후기 개수. 후기 기능 구현 전까지 항상 0';
COMMENT ON COLUMN trust_profile.no_show_report_count IS '노쇼 신고 누적 횟수. 신고 기능 구현 전까지 항상 0';