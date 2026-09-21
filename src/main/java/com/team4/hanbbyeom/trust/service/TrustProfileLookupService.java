package com.team4.hanbbyeom.trust.service;

import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.trust.dto.RecentReviewResponse;
import com.team4.hanbbyeom.trust.dto.TrustProfileResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class TrustProfileLookupService {

    // 최근 후기는 최대 이 개수만큼만 내려준다 (이슈 #69 — 화면 설계에 맞게 조정 가능)
    private static final int RECENT_REVIEWS_LIMIT = 3;

    private final JdbcTemplate jdbcTemplate;

    public TrustProfileLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 호스트/신청자 프로필 미리보기(호스트 프로필 조회, 신청자 프로필 조회)에서 공용으로 쓰는
    // 조회 전용 메서드 — 상태 전이 없음. userId에 해당하는 trust_profile이 없으면(아직 아무
    // 활동도 안 한 신규 유저 등) 기본값(0, null)으로 응답합니다 — 이슈 #22의 명시적 요구사항입니다.
    public TrustProfileResponse lookup(Long userId) {
        // trust_profile 행이 없어도(=신규 유저) recentReviews는 항상 빈 리스트로 정상 조회되므로,
        // trust_profile 존재 여부와 상관없이 먼저 조회해서 양쪽 분기에서 그대로 재사용한다.
        List<RecentReviewResponse> recentReviews = findRecentReviews(userId);

        List<TrustProfileResponse> rows = jdbcTemplate.query(
                """
                SELECT average_rating, review_count, completed_activity_count, no_show_report_count,
                       review_silent_vote_count, review_light_chat_vote_count
                FROM trust_profile
                WHERE user_id = ?
                """,
                (rs, rowNum) -> {
                    int silentVotes = rs.getInt("review_silent_vote_count");
                    int lightChatVotes = rs.getInt("review_light_chat_vote_count");
                    TalkLevel majority = majorityTalkLevel(silentVotes, lightChatVotes);

                    return new TrustProfileResponse(
                            rs.getObject("average_rating") != null ? rs.getDouble("average_rating") : null,
                            rs.getInt("review_count"),
                            rs.getInt("completed_activity_count"),
                            rs.getInt("no_show_report_count"),
                            majority,
                            majority == null ? 0 : Math.max(silentVotes, lightChatVotes),
                            recentReviews
                    );
                },
                userId
        );

        return rows.stream().findFirst()
                .orElse(new TrustProfileResponse(null, 0, 0, 0, null, 0, recentReviews));
    }

    // SILENT/LIGHT_CHAT vote count를 비교해 다수결을 정한다. 동률이면(둘 다 0인 경우 포함)
    // 어느 한쪽으로 정할 근거가 없으므로 null — "아직 판단할 근거가 부족하다"는 뜻이다.
    private TalkLevel majorityTalkLevel(int silentVotes, int lightChatVotes) {
        if (silentVotes == lightChatVotes) {
            return null;
        }
        return silentVotes > lightChatVotes ? TalkLevel.SILENT : TalkLevel.LIGHT_CHAT;
    }

    // 최근 후기 N건 — activity_review에 activity_match를 조인해서 "어떤 활동에 대한
    // 후기인지"(코스명/거리)까지 같이 내려준다. activity_match는 신청 시점 스냅샷을
    // 그대로 갖고 있어서(course_name/distance_min_meters/distance_max_meters) 다른
    // 테이블을 더 조인할 필요가 없다. 최신순(created_at DESC)으로 최대 N건만 가져온다.
    // created_at만으로 정렬하면 같은 시각에 생성된 후기가 있을 때 LIMIT 경계에서 매번
    // 다른 행이 걸릴 수 있어서, id DESC를 타이브레이커로 추가했다 (PR #91 리뷰로 발견 —
    // 팀이 PR #79/#88에서도 같은 이유로 id를 보조 정렬 기준에 추가해온 것과 동일한 패턴).
    private List<RecentReviewResponse> findRecentReviews(Long userId) {
        return jdbcTemplate.query(
                """
                SELECT ar.rating, ar.perceived_talk_level, ar.comment, ar.created_at,
                       am.course_name, am.distance_min_meters, am.distance_max_meters
                FROM activity_review ar
                JOIN activity_match am ON am.id = ar.activity_match_id
                WHERE ar.reviewee_user_id = ?
                ORDER BY ar.created_at DESC, ar.id DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new RecentReviewResponse(
                        rs.getInt("rating"),
                        TalkLevel.valueOf(rs.getString("perceived_talk_level")),
                        rs.getString("comment"),
                        rs.getObject("created_at", OffsetDateTime.class),
                        rs.getString("course_name"),
                        rs.getInt("distance_min_meters"),
                        rs.getInt("distance_max_meters")
                ),
                userId, RECENT_REVIEWS_LIMIT
        );
    }
}