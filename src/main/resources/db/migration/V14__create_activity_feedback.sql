-- V14__create_activity_feedback.sql

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
     CONSTRAINT chk_activity_review_not_self CHECK (reviewer_user_id <> reviewee_user_id),
     CONSTRAINT chk_activity_review_talk_level CHECK (perceived_talk_level IN ('SILENT', 'LIGHT_CHAT')),
     CONSTRAINT uq_activity_review_once UNIQUE (activity_match_id, reviewer_user_id),
    -- 작성자가 실제로 이 매칭의 참가자여야 함 (match_participant의 복합 유니크를 참조)
     CONSTRAINT fk_activity_review_reviewer_participant
         FOREIGN KEY (activity_match_id, reviewer_user_id)
             REFERENCES match_participant (activity_match_id, user_id),
    -- 대상자도 마찬가지로 실제 참가자여야 함
     CONSTRAINT fk_activity_review_reviewee_participant
         FOREIGN KEY (activity_match_id, reviewee_user_id)
             REFERENCES match_participant (activity_match_id, user_id)
);

CREATE TABLE no_show_report (
    id BIGSERIAL PRIMARY KEY,
    activity_match_id BIGINT NOT NULL REFERENCES activity_match(id),
    reporter_user_id BIGINT NOT NULL REFERENCES users(id),
    reported_user_id BIGINT NOT NULL REFERENCES users(id),
    reason VARCHAR(30) NOT NULL,
    detail VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_no_show_report_not_self CHECK (reporter_user_id <> reported_user_id),
    CONSTRAINT chk_no_show_report_reason CHECK (reason IN ('NOT_SHOWED_UP', 'LEFT_WITHOUT_NOTICE', 'OTHER')),
    CONSTRAINT uq_no_show_report_once UNIQUE (activity_match_id, reporter_user_id),
    CONSTRAINT fk_no_show_report_reporter_participant
        FOREIGN KEY (activity_match_id, reporter_user_id)
            REFERENCES match_participant (activity_match_id, user_id),
    CONSTRAINT fk_no_show_report_reported_participant
        FOREIGN KEY (activity_match_id, reported_user_id)
            REFERENCES match_participant (activity_match_id, user_id)
);

ALTER TABLE trust_profile
    ADD COLUMN review_silent_vote_count INT NOT NULL DEFAULT 0,
    ADD COLUMN review_light_chat_vote_count INT NOT NULL DEFAULT 0;