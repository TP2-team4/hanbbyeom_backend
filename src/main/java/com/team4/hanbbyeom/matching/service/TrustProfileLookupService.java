package com.team4.hanbbyeom.matching.service;

import com.team4.hanbbyeom.matching.dto.TrustProfileResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TrustProfileLookupService {

    private final JdbcTemplate jdbcTemplate;

    public TrustProfileLookupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // 호스트/신청자 프로필 미리보기(호스트 프로필 조회, 신청자 프로필 조회)에서 공용으로 쓰는
    // 조회 전용 메서드 — 상태 전이 없음. userId에 해당하는 trust_profile이 없으면(아직 아무
    // 활동도 안 한 신규 유저 등) 기본값(0, null)으로 응답합니다 — 이슈 #22의 명시적 요구사항입니다.
    public TrustProfileResponse lookup(Long userId) {
        List<TrustProfileResponse> rows = jdbcTemplate.query(
                """
                SELECT average_rating, review_count, completed_activity_count, no_show_report_count
                FROM trust_profile
                WHERE user_id = ?
                """,
                (rs, rowNum) -> new TrustProfileResponse(
                        rs.getObject("average_rating") != null ? rs.getDouble("average_rating") : null,
                        rs.getInt("review_count"),
                        rs.getInt("completed_activity_count"),
                        rs.getInt("no_show_report_count")
                ),
                userId
        );

        return rows.stream().findFirst()
                .orElse(new TrustProfileResponse(null, 0, 0, 0));
    }
}