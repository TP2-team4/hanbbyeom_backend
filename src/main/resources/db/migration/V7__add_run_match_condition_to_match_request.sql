-- match_request가 실제 코스/거리/페이스 조건(run_match_condition, 담당 B 소관)을 참조하도록 연결한다.
-- ⚠️ 실행 전 필수 확인: run_match_condition의 실제 테이블/컬럼명이 아래와 다르면 REFERENCES를 맞게 고칠 것.

ALTER TABLE match_request
    ADD COLUMN run_match_condition_id BIGINT NOT NULL;

ALTER TABLE match_request
    ADD CONSTRAINT fk_match_request_run_condition
        FOREIGN KEY (run_match_condition_id) REFERENCES run_match_condition(id);

COMMENT ON COLUMN match_request.run_match_condition_id IS
    '이 게시글이 어떤 코스/거리/페이스 조건으로 등록됐는지. run_match_condition(담당 B) 참조';
