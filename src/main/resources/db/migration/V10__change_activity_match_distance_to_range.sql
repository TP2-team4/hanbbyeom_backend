-- V10__change_activity_match_distance_to_range.sql

-- run_match_condition이 거리를 distance_min_meters/distance_max_meters 범위(m)로 받도록
-- 이미 바뀌어 있는데, activity_match.distance_meters는 값 하나만 받던 이전 설계 그대로 남아 있었음.
-- agreed_pace_min_sec/agreed_pace_max_sec와 동일한 패턴으로 거리도 범위 두 컬럼으로 변경.
ALTER TABLE activity_match
    DROP CONSTRAINT chk_activity_match_distance;

ALTER TABLE activity_match
    ADD COLUMN distance_min_meters INT,
    ADD COLUMN distance_max_meters INT;

UPDATE activity_match
    SET distance_min_meters = distance_meters,
        distance_max_meters = distance_meters
    WHERE distance_meters IS NOT NULL;

ALTER TABLE activity_match
    ALTER COLUMN distance_min_meters SET NOT NULL,
    ALTER COLUMN distance_max_meters SET NOT NULL;

ALTER TABLE activity_match
    DROP COLUMN distance_meters;

ALTER TABLE activity_match
    ADD CONSTRAINT chk_activity_match_distance CHECK (
        distance_min_meters > 0 AND distance_max_meters > 0
            AND distance_min_meters <= distance_max_meters
        );

COMMENT ON COLUMN activity_match.distance_min_meters IS '합의할 코스 거리 하한(m)';
COMMENT ON COLUMN activity_match.distance_max_meters IS '합의할 코스 거리 상한(m)';
