-- V15__add_activity_review_reviewee_index.sql

-- TrustProfileLookupService.findRecentReviews()가 activity_review.reviewee_user_id로
-- 필터링하는데, 이 컬럼엔 인덱스가 없어 순차 스캔이 발생한다(PR #91 리뷰로 발견).
-- PostgreSQL은 PK/UNIQUE 제약에만 자동으로 인덱스를 만들고, FK 컬럼(복합 유니크의 일부인
-- reviewer_user_id와 달리 reviewee_user_id는 uq_activity_review_once에도 포함되지 않음)에는
-- 인덱스를 자동 생성하지 않는다.
CREATE INDEX idx_activity_review_reviewee_user_id ON activity_review(reviewee_user_id);
