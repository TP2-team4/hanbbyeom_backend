-- V11__allow_applicant_without_own_match_request.sql

-- 기획 확정: 신청자는 본인 모집글(match_request) 없이도 호스트 게시글에 신청할 수 있어야 함.
-- 지금까지는 match_participant.match_request_id가 NOT NULL이라, 신청자(slot B)도 반드시
-- 본인 소유 match_request 행이 있어야만 복합 FK(fk_participant_request_owner)를 통과할 수 있었음.
-- 이 전제를 없애기 위해 컬럼을 nullable로 변경한다.
-- 참고: Postgres 복합 FK는 참조 컬럼 중 하나라도 NULL이면 제약 검사 대상에서 제외되므로
-- (기본 MATCH SIMPLE 동작), fk_participant_request_owner 자체는 손댈 필요가 없다.
ALTER TABLE match_participant
    ALTER COLUMN match_request_id DROP NOT NULL;

COMMENT ON COLUMN match_participant.match_request_id IS
    '이 참가자가 원래 등록했던 게시글/조건 ID. 호스트(A)는 항상 값이 있고, 신청자(B)는 본인 게시글 없이 신청한
     경우 NULL';
