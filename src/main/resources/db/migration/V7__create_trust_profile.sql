-- trust_profile: 사용자 신뢰도(평점/완료 활동 수) 테이블.
-- 소유 도메인이 아직 정해지지 않아, 이번 #20 이슈 진행을 위해 매칭 도메인에서 우선 생성함.
-- ⚠️ 이후 신뢰/평판 전담 도메인이 생기면 그쪽으로 소유권 이관 필요 — 팀에 공유할 것.
CREATE TABLE trust_profile (
                               user_id BIGINT PRIMARY KEY REFERENCES users(id),
                               average_rating NUMERIC(2,1) NOT NULL DEFAULT 0.0,
                               completed_activity_count INT NOT NULL DEFAULT 0,
                               updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),

                               CONSTRAINT chk_trust_profile_rating CHECK (average_rating BETWEEN 0.0 AND 5.0),
                               CONSTRAINT chk_trust_profile_count CHECK (completed_activity_count >= 0)
);
COMMENT ON TABLE trust_profile IS
    '사용자 신뢰도 프로필(평점/완료 활동 수). 소유 도메인 미정 — 매칭 도메인에서 임시 생성 (#20)';