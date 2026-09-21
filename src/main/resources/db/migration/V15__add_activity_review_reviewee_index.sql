-- V15__add_activity_review_reviewee_index.sql

-- TrustProfileLookupService.findRecentReviews()가 activity_review.reviewee_user_id로
-- 필터링한 뒤 created_at DESC, id DESC로 정렬해서 LIMIT만큼 가져온다(PR #91 리뷰로 발견).
-- 단일 컬럼 인덱스로는 순차 스캔은 없애도 정렬(Sort)이 남는다 — reviewee_user_id로 행을
-- 찾은 뒤 별도로 재정렬해야 한다. WHERE/ORDER BY 순서 그대로 복합 인덱스를 만들면 인덱스
-- 순서대로 위에서부터 LIMIT만큼만 읽고 끝나서 정렬 자체가 필요 없어진다.
-- reviewee_user_id로 필터링하는 쿼리가 이 하나뿐이라(다른 용도 없음), 복합 인덱스로 만들어도
-- 다른 쿼리 패턴과 충돌하지 않는다 — 가장 왼쪽 컬럼(reviewee_user_id)만 쓰는 조회가 생겨도
-- 이 인덱스가 그대로 커버한다.
CREATE INDEX idx_activity_review_reviewee_recent
    ON activity_review(reviewee_user_id, created_at DESC, id DESC);
