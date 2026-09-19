-- 후기가 없는 사용자의 평균 별점을 "평가 없음"(NULL)으로 표현한다.
--
-- 그동안 average_rating이 NOT NULL DEFAULT 0.0이라, 후기 없이 trust_profile 행이 만들어지면
-- (예: 노쇼 신고만 받은 사용자) 별점이 "평가 없음"이 아니라 0.0으로 내려갔다.
-- 완료한 활동 수 집계(#96)가 종료된 활동의 참가자 대부분에게 행을 만들게 되므로, 그 전에
-- 후기 없는 행의 별점이 NULL이 되도록 바꾼다. 별점을 읽는 조회 지점(신뢰 프로필, 모집 카드, 내 신청 내역)은
-- 이미 NULL을 "평가 없음"으로 처리하므로 컬럼만 바꾸면 모두 올바른 값을 받는다.

ALTER TABLE trust_profile
    ALTER COLUMN average_rating DROP NOT NULL;

ALTER TABLE trust_profile
    ALTER COLUMN average_rating DROP DEFAULT;

-- 후기가 하나도 없는 기존 행은 0.0이 아니라 NULL로 보정한다.
-- (chk_trust_profile_rating의 BETWEEN 검사는 NULL이면 통과하므로 제약은 그대로 둔다)
UPDATE trust_profile
SET average_rating = NULL
WHERE review_count = 0;

COMMENT ON COLUMN trust_profile.average_rating IS
    '평균 별점(1.0~5.0). 후기가 하나도 없으면 NULL';
