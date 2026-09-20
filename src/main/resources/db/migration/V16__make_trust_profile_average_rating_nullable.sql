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
UPDATE trust_profile
SET average_rating = NULL
WHERE review_count = 0;

-- 평균 별점의 범위 제약을 0.0~5.0에서 1.0~5.0으로 좁힌다.
-- 평균은 후기 별점(activity_review.rating, 1~5)의 평균이라 후기가 있으면 항상 1.0 이상이고, 후기가 없으면 위에서
-- NULL이 된다. 즉 0.0은 더 이상 유효한 값이 아니다. 제약이 0.0을 허용한 채로 남으면 컬럼 주석(1.0~5.0)과 어긋나고,
-- 예전처럼 기본값 0.0으로 행을 만드는 실수를 DB가 막아주지 못한다.
-- 반드시 위 UPDATE 뒤에 실행해야 한다: 0.0인 행이 남아 있으면 새 제약을 추가할 때 검증에 실패한다.
-- (NULL은 CHECK 검사를 통과하므로 후기 없는 행은 그대로 허용된다)
ALTER TABLE trust_profile
    DROP CONSTRAINT IF EXISTS chk_trust_profile_rating;

ALTER TABLE trust_profile
    ADD CONSTRAINT chk_trust_profile_rating
        CHECK (average_rating BETWEEN 1.0 AND 5.0);

COMMENT ON COLUMN trust_profile.average_rating IS
    '평균 별점(1.0~5.0). 후기가 하나도 없으면 NULL';
