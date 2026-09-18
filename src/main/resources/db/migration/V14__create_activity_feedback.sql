-- V14__create_activity_feedback.sql
-- 활동 후기(activity_review)와 노쇼 신고(no_show_report) 테이블을 새로 만들고,
-- trust_profile에는 "체감 대화 수준" 다수결 계산에 쓸 투표 카운트 컬럼을 추가한다.

-- 활동 후기: 한 사람이 같은 활동(activity_match)에 대해 한 번만 남길 수 있어야 하므로
-- (activity_match_id, reviewer_user_id) 조합에 UNIQUE 제약을 건다.
CREATE TABLE activity_review (
     id BIGSERIAL PRIMARY KEY,
     activity_match_id BIGINT NOT NULL REFERENCES activity_match(id),
     reviewer_user_id BIGINT NOT NULL REFERENCES users(id),
     reviewee_user_id BIGINT NOT NULL REFERENCES users(id),
     rating INTEGER NOT NULL,
     perceived_talk_level VARCHAR(20) NOT NULL,
     comment VARCHAR(100),
     created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
     CONSTRAINT chk_activity_review_rating CHECK (rating BETWEEN 1 AND 5),
     CONSTRAINT uq_activity_review_once UNIQUE (activity_match_id, reviewer_user_id)
);

-- 노쇼 신고: 후기와 마찬가지로 한 활동에 한 번만 신고 가능하도록 UNIQUE 제약을 건다.
CREATE TABLE no_show_report (
    id BIGSERIAL PRIMARY KEY,
    activity_match_id BIGINT NOT NULL REFERENCES activity_match(id),
    reporter_user_id BIGINT NOT NULL REFERENCES users(id),
    reported_user_id BIGINT NOT NULL REFERENCES users(id),
    reason VARCHAR(30) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_no_show_report_once UNIQUE (activity_match_id, reporter_user_id)
);

-- 후기가 쌓일 때마다 이 두 카운트 중 하나를 올려서, "타인이 느낀 대화 수준"
-- 다수결(둘 중 더 큰 값)을 매번 집계 쿼리 없이 바로 계산할 수 있게 한다.
ALTER TABLE trust_profile
    ADD COLUMN review_silent_vote_count INT NOT NULL DEFAULT 0,
    ADD COLUMN review_light_chat_vote_count INT NOT NULL DEFAULT 0;