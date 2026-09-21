package com.team4.hanbbyeom.trust.repository;

import com.team4.hanbbyeom.matching.domain.TalkLevel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

// trust_profile 테이블의 쓰기 경로를 한 곳에 모은다 (이슈 #94).
// 이전에는 후기 반영·노쇼 신고(ActivityFeedbackService)와 완료 활동 집계(MatchDecisionService)가
// 각자 JdbcTemplate으로 같은 테이블에 upsert를 날리고 있었다. 컬럼 하나를 바꾸면 세 군데를 같이
// 고쳐야 했고, "trust_profile은 누구 소유인가"가 정해지지 않아 매칭 도메인이 임시로 다루고 있었다(V7).
// 이제 이 테이블은 trust 도메인 소유이고, 다른 도메인은 이 클래스의 메서드만 호출한다.
//
// JPA 엔티티를 만들지 않고 JdbcTemplate을 유지하는 이유: 세 쓰기 모두 "행이 없으면 INSERT, 있으면
// 누적 UPDATE"를 한 문장(INSERT ... ON CONFLICT DO UPDATE)으로 원자적으로 처리해야 한다. 같은 사용자에게
// 서로 다른 매칭에서 거의 동시에 후기/신고/종료가 들어와도 PK 충돌이나 lost update가 없다
// (PR #83 리뷰로 발견된 레이스). 엔티티로 바꾸면 SELECT → 계산 → UPDATE가 되어 이 보장을 잃는다.
@Repository
public class TrustProfileRepository {

    private final JdbcTemplate jdbcTemplate;

    public TrustProfileRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 후기 1건을 반영한다 — 평균 별점 누적 갱신, 후기 수 +1, 체감 대화 수준 vote +1.
    // 평균은 "기존 평균 * 기존 개수 + 새 별점"을 "개수 + 1"로 나누는 누적 평균 공식.
    // average_rating은 후기가 없는 행에서 NULL이라(V16) 첫 후기 때 "NULL * 0"이 NULL이 되지 않도록
    // COALESCE로 0 취급한다 (review_count가 0이라 곱은 어차피 0).
    // 대화 수준은 SILENT/LIGHT_CHAT 중 올릴 컬럼이 갈리는데, 컬럼명을 동적으로 만들지 않고 두 SQL로
    // 분기해 각각 명시한다 (SQL 인젝션 걱정 없이 가장 단순한 방법).
    public void applyReview(Long userId, int rating, TalkLevel perceivedTalkLevel) {
        if (perceivedTalkLevel == TalkLevel.SILENT) {
            jdbcTemplate.update("""
                INSERT INTO trust_profile
                    (user_id, average_rating, review_count, review_silent_vote_count)
                VALUES (?, ?, 1, 1)
                ON CONFLICT (user_id) DO UPDATE SET
                    average_rating = ROUND(
                        (COALESCE(trust_profile.average_rating, 0) * trust_profile.review_count + EXCLUDED.average_rating)
                        / (trust_profile.review_count + 1), 1),
                    review_count = trust_profile.review_count + 1,
                    review_silent_vote_count = trust_profile.review_silent_vote_count + 1,
                    updated_at = now()
                """, userId, rating);
        } else {
            jdbcTemplate.update("""
                INSERT INTO trust_profile
                    (user_id, average_rating, review_count, review_light_chat_vote_count)
                VALUES (?, ?, 1, 1)
                ON CONFLICT (user_id) DO UPDATE SET
                    average_rating = ROUND(
                        (COALESCE(trust_profile.average_rating, 0) * trust_profile.review_count + EXCLUDED.average_rating)
                        / (trust_profile.review_count + 1), 1),
                    review_count = trust_profile.review_count + 1,
                    review_light_chat_vote_count = trust_profile.review_light_chat_vote_count + 1,
                    updated_at = now()
                """, userId, rating);
        }
    }

    // 노쇼 신고 1건을 반영한다 — no_show_report_count +1.
    // completed_activity_count는 올리지도 내리지도 않는다: 완료한 활동은 활동 종료 시점에 이미 집계됐고,
    // 신고는 그 뒤에 접수되므로 차감 시점이 불명확하다. 노쇼는 이 컬럼으로 따로 표현된다 (이슈 #96).
    public void incrementNoShowReportCount(Long userId) {
        jdbcTemplate.update("""
            INSERT INTO trust_profile (user_id, no_show_report_count)
            VALUES (?, 1)
            ON CONFLICT (user_id) DO UPDATE SET
                no_show_report_count = trust_profile.no_show_report_count + 1,
                updated_at = now()
            """, userId);
    }

    // 완료한 활동 1건을 반영한다 — completed_activity_count +1.
    // 활동(activity_match)이 ENDED로 전환될 때 두 참가자 모두에 대해 호출된다. 후기·노쇼 신고 여부와
    // 무관하고, CANCELLED·EXPIRED·REJECTED는 집계하지 않는다 (이슈 #96). 후기가 없는 새 행의 별점은 NULL(V16).
    public void incrementCompletedActivityCount(Long userId) {
        jdbcTemplate.update("""
            INSERT INTO trust_profile (user_id, completed_activity_count)
            VALUES (?, 1)
            ON CONFLICT (user_id) DO UPDATE SET
                completed_activity_count = trust_profile.completed_activity_count + 1,
                updated_at = now()
            """, userId);
    }
}
