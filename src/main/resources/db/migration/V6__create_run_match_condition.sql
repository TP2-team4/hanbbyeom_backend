-- 사용자가 러닝 매칭 조건으로 등록한 코스/거리/만나는 곳/페이스 정보를 저장하는 테이블
CREATE TABLE run_match_condition (
    -- PK, 자동 채번 아님. C의 match_request 테이블 ID를 그대로 받아써서 1:1 관계를 이룸
    match_request_id BIGINT PRIMARY KEY REFERENCES match_request(id),
    -- 선택한 코스. 같은 프로젝트(B) 안의 테이블이라 FK 제약을 걸어도 안전함
    course_id BIGINT NOT NULL REFERENCES running_course(id),
    -- 거리 범위(m)
    distance_min_meters INT NOT NULL,
    distance_max_meters INT NOT NULL,
    -- 사용자가 직접 입력한 만나는 곳
    meeting_point VARCHAR(255) NOT NULL,
    -- 페이스 범위 (초/km 단위)
    pace_min_sec INT NOT NULL,
    pace_max_sec INT NOT NULL
);