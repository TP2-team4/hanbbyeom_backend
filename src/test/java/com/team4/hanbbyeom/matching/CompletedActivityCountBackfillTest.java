package com.team4.hanbbyeom.matching;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// V17 백필 마이그레이션의 SQL을 과거 데이터에 다시 실행해 결과를 검증한다(이슈 #96: "보정 후 값과 기존 ENDED 매칭 수 일치").
// 실제 마이그레이션 파일을 그대로 읽어 실행하므로 파일이 바뀌면 이 테스트가 그 결과를 잡는다.
@SpringBootTest
@Transactional
class CompletedActivityCountBackfillTest {

    @Autowired private JdbcTemplate jdbcTemplate;

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    // 지정한 상태의 활동과 두 참가자를 만든다. 아직 진행 중인 CONFIRMED만 참가를 해제하지 않는다
    // (uq_participant_active_user: 사용자당 활성 참가는 하나뿐).
    private void createMatch(Long hostId, Long applicantId, String status, OffsetDateTime start) {
        boolean confirmed = status.equals("CONFIRMED") || status.equals("ENDED") || status.equals("CANCELLED");
        boolean stillActive = status.equals("CONFIRMED");
        Long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match
                    (scheduled_at, scheduled_end_at, talk_level, location, course_name,
                     distance_min_meters, distance_max_meters, route_description,
                     agreed_pace_min_sec, agreed_pace_max_sec, status,
                     decision_expires_at, meeting_code, confirmed_at, closed_at, created_at)
                VALUES (?, ?, 'LIGHT_CHAT', '만나는 곳', '코스', 5000, 8000, '코스 설명', 360, 400, ?, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                start, start.plusHours(2), status, start.minusDays(2),
                confirmed ? "123456" : null, confirmed ? start.minusDays(1) : null,
                stillActive ? null : start.plusHours(3), start.minusDays(3)
        );
        OffsetDateTime releasedAt = stillActive ? null : OffsetDateTime.now();
        for (Object[] p : new Object[][]{{hostId, "A"}, {applicantId, "B"}}) {
            jdbcTemplate.update(
                    "INSERT INTO match_participant (activity_match_id, user_id, slot, accept_status, released_at) "
                            + "VALUES (?, ?, ?, 'ACCEPTED', ?)",
                    matchId, p[0], p[1], releasedAt);
        }
    }

    private Integer completedCount(Long userId) {
        var rows = jdbcTemplate.queryForList(
                "SELECT completed_activity_count FROM trust_profile WHERE user_id = ?", Integer.class, userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void runBackfill() throws Exception {
        String script = new ClassPathResource("db/migration/V17__backfill_trust_profile_completed_activity_count.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(script);
    }

    @Test
    @DisplayName("기존 ENDED 매칭 수만큼 사용자별 완료한 활동 수를 채우고, 다른 상태와 기존 값은 건드리지 않으며, 재실행해도 같다")
    void 백필은_ENDED_매칭_수와_일치한다() throws Exception {
        Long u1 = createUser("사용자1"); // ENDED 2건 + CANCELLED 1건 → 2
        Long u2 = createUser("사용자2"); // ENDED 1건 + CANCELLED 1건 + CONFIRMED(진행 중) 1건 → 1
        Long u3 = createUser("사용자3"); // ENDED 1건 + EXPIRED 1건 → 1
        Long u4 = createUser("사용자4"); // ENDED 없음(CONFIRMED만) → 행 생성 안 함
        OffsetDateTime now = OffsetDateTime.now();
        createMatch(u1, u2, "ENDED", now.minusDays(30));
        createMatch(u1, u3, "ENDED", now.minusDays(20));
        createMatch(u1, u2, "CANCELLED", now.minusDays(10));
        createMatch(u3, u2, "EXPIRED", now.minusDays(5));
        createMatch(u2, u4, "CONFIRMED", now.plusDays(3)); // 아직 종료되지 않은 활동 — 이후 스케줄러가 집계한다
        // u1은 이미 후기 통계가 있는 행을 갖고 있다: 완료 수만 채워지고 나머지는 유지되어야 한다
        jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, review_count, no_show_report_count) "
                        + "VALUES (?, 4.5, 2, 1)", u1);

        runBackfill();

        assertThat(completedCount(u1)).isEqualTo(2);
        assertThat(completedCount(u2)).isEqualTo(1);
        assertThat(completedCount(u3)).isEqualTo(1);
        assertThat(completedCount(u4)).isNull();
        var u1Row = jdbcTemplate.queryForMap(
                "SELECT average_rating, review_count, no_show_report_count FROM trust_profile WHERE user_id = ?", u1);
        assertThat(((BigDecimal) u1Row.get("average_rating"))).isEqualByComparingTo("4.5");
        assertThat(u1Row.get("review_count")).isEqualTo(2);
        assertThat(u1Row.get("no_show_report_count")).isEqualTo(1);
        // 백필로 새로 생긴 행의 별점은 0.0이 아니라 NULL이다(V16에서 기본값 제거)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT average_rating FROM trust_profile WHERE user_id = ?", BigDecimal.class, u2)).isNull();

        // 계산한 값으로 설정하므로 재실행해도 결과가 같다(누적되지 않는다)
        runBackfill();
        assertThat(completedCount(u1)).isEqualTo(2);
        assertThat(completedCount(u2)).isEqualTo(1);
        assertThat(completedCount(u3)).isEqualTo(1);
    }
}
