package com.team4.hanbbyeom.feedback;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 후기가 없는 사용자의 평균 별점이 0.0이 아니라 null("평가 없음")로 내려가는지 검증한다(이슈 #96).
// trust_profile.average_rating이 NOT NULL DEFAULT 0.0이던 시절에는 후기 없이 행이 만들어지면(노쇼 신고만 받은
// 경우 등) 별점이 0.0으로 나왔다. 후기·노쇼 신고는 실제 API로 제출하고, 결과는 마이페이지 프로필
// (GET /api/users/me/trust-profile)로 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class TrustProfileRatingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + UUID.randomUUID() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // 이미 끝난(ENDED) 활동과 두 참가자를 SQL로 만든다. 후기·신고는 종료 시각이 지난 확정 매칭에만 제출할 수 있다.
    // 참가는 해제(released_at)해 한 사용자가 여러 활동에 참가한 이력을 가질 수 있게 한다(uq_participant_active_user).
    private Long createEndedMatch(Long hostId, Long applicantId) {
        OffsetDateTime start = OffsetDateTime.now().minusDays(1);
        Long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match
                    (scheduled_at, scheduled_end_at, talk_level, location, course_name,
                     distance_min_meters, distance_max_meters, route_description,
                     agreed_pace_min_sec, agreed_pace_max_sec, status,
                     decision_expires_at, meeting_code, confirmed_at, closed_at, created_at)
                VALUES (?, ?, 'LIGHT_CHAT', '만나는 곳', '뚝섬 한강공원', 5000, 12000, '코스 설명', 360, 400,
                        'ENDED', ?, '123456', ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                start, start.plusHours(2), start.minusDays(2), start.minusDays(1), start.plusHours(3), start.minusDays(3)
        );
        for (Object[] p : new Object[][]{{hostId, "A"}, {applicantId, "B"}}) {
            jdbcTemplate.update(
                    "INSERT INTO match_participant (activity_match_id, user_id, slot, accept_status, released_at) "
                            + "VALUES (?, ?, ?, 'ACCEPTED', now())",
                    matchId, p[0], p[1]
            );
        }
        return matchId;
    }

    private void submitReview(Long reviewerId, Long matchId, int rating) throws Exception {
        mockMvc.perform(post("/api/matching/matches/{id}/review", matchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(reviewerId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rating\": " + rating + ", \"perceivedTalkLevel\": \"LIGHT_CHAT\", \"comment\": \"좋았어요\"}"))
                .andExpect(status().isNoContent());
    }

    private BigDecimal storedRating(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT average_rating FROM trust_profile WHERE user_id = ?", BigDecimal.class, userId);
    }

    @Test
    @DisplayName("후기 없이 노쇼 신고만 받은 사용자의 별점은 0.0이 아니라 null이다")
    void 노쇼_신고만_받은_사용자의_별점은_null이다() throws Exception {
        Long reporter = createUser("신고자");
        Long reported = createUser("신고당한사람");
        Long matchId = createEndedMatch(reporter, reported);

        mockMvc.perform(post("/api/matching/matches/{id}/no-show-report", matchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(reporter))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"NOT_SHOWED_UP\"}"))
                .andExpect(status().isNoContent());

        assertThat(storedRating(reported)).isNull();
        mockMvc.perform(get("/api/users/me/trust-profile")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(reported)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(nullValue()))
                .andExpect(jsonPath("$.reviewCount").value(0))
                .andExpect(jsonPath("$.noShowReportCount").value(1));
    }

    @Test
    @DisplayName("후기 없이 완료한 활동 수만 있는 행도 별점은 null이다 (별점 기본값이 0.0이 아니다)")
    void 후기_없이_행만_있는_사용자의_별점은_null이다() throws Exception {
        Long user = createUser("완료만있는사용자");
        // 완료한 활동 수 집계가 만드는 행과 같은 형태: 별점 컬럼을 지정하지 않는다
        jdbcTemplate.update("INSERT INTO trust_profile (user_id, completed_activity_count) VALUES (?, 3)", user);

        assertThat(storedRating(user)).isNull();
        mockMvc.perform(get("/api/users/me/trust-profile")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.averageRating").value(nullValue()))
                .andExpect(jsonPath("$.completedCount").value(3));
    }

    // 별점이 NULL인 행에 후기가 쌓일 때 누적 평균 식이 NULL로 무너지지 않는지 확인한다("NULL * 0"이 NULL이 되는 문제).
    @Test
    @DisplayName("별점이 null인 행에 후기가 쌓이면 평균이 정상 계산된다")
    void 별점이_null인_행에_후기가_쌓이면_평균이_계산된다() throws Exception {
        Long reviewed = createUser("후기받는사람");
        Long reviewer1 = createUser("후기작성자1");
        Long reviewer2 = createUser("후기작성자2");
        jdbcTemplate.update("INSERT INTO trust_profile (user_id, completed_activity_count) VALUES (?, 2)", reviewed);
        Long match1 = createEndedMatch(reviewer1, reviewed);
        Long match2 = createEndedMatch(reviewer2, reviewed);

        submitReview(reviewer1, match1, 5);
        assertThat(storedRating(reviewed)).isEqualByComparingTo("5.0");

        submitReview(reviewer2, match2, 4);
        assertThat(storedRating(reviewed)).isEqualByComparingTo("4.5");

        mockMvc.perform(get("/api/users/me/trust-profile")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(reviewed)))
                .andExpect(jsonPath("$.averageRating").value(4.5))
                .andExpect(jsonPath("$.reviewCount").value(2))
                // 후기 반영은 완료한 활동 수를 건드리지 않는다
                .andExpect(jsonPath("$.completedCount").value(2));
    }

    // V16 마이그레이션 SQL을 과거 데이터에 다시 실행해 결과를 확인한다.
    // 후기 없는 기존 행(0.0)은 NULL이 되고 후기가 있는 행은 그대로여야 하며, 범위 제약은 1.0~5.0으로 다시 만들어져야 한다.
    // 테스트 DB에는 V16이 이미 적용돼 0.0을 넣을 수 없으므로, 먼저 제약을 지워 V16 이전 스키마(0.0 허용)를 재현한다.
    // 0.0 행이 남은 채로 새 제약을 추가하면 검증에 실패하므로, 이 테스트는 "보정 UPDATE가 제약 추가보다 먼저"라는
    // 순서도 함께 검증한다.
    @Test
    @DisplayName("V16 보정: 후기 없는 기존 행의 0.0은 null이 되고 후기가 있는 행은 유지되며 제약은 1.0~5.0이 된다")
    void 마이그레이션_보정을_검증한다() throws Exception {
        jdbcTemplate.execute("ALTER TABLE trust_profile DROP CONSTRAINT chk_trust_profile_rating");
        Long legacyNoReview = createUser("과거후기없음");
        Long legacyReviewed = createUser("과거후기있음");
        jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, review_count) VALUES (?, 0.0, 0)", legacyNoReview);
        jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, review_count) VALUES (?, 4.5, 2)", legacyReviewed);

        String script = new ClassPathResource("db/migration/V16__make_trust_profile_average_rating_nullable.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbcTemplate.execute(script);

        assertThat(storedRating(legacyNoReview)).isNull();
        assertThat(storedRating(legacyReviewed)).isEqualByComparingTo("4.5");
        assertThat(ratingConstraintDefinition()).contains("1.0").doesNotContain("0.0");
    }

    // ---- 평균 별점 범위 제약(chk_trust_profile_rating: 1.0~5.0 또는 NULL) ----
    // 제약 위반은 트랜잭션을 중단시키므로 거부 케이스는 테스트 하나에 위반을 하나만 둔다.

    private String ratingConstraintDefinition() {
        return jdbcTemplate.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'chk_trust_profile_rating'",
                String.class);
    }

    @Test
    @DisplayName("평균 별점 0.0은 더 이상 유효하지 않아 DB가 거부한다")
    void 평균_별점_0점은_거부된다() {
        Long user = createUser("영점");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, review_count) VALUES (?, 0.0, 1)", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("평균 별점이 1.0 미만(0.9)이면 DB가 거부한다")
    void 평균_별점_1점_미만은_거부된다() {
        Long user = createUser("일점미만");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, review_count) VALUES (?, 0.9, 1)", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("평균 별점이 5.0을 넘으면(5.1) DB가 거부한다")
    void 평균_별점_5점_초과는_거부된다() {
        Long user = createUser("오점초과");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO trust_profile (user_id, average_rating, review_count) VALUES (?, 5.1, 1)", user))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("평균 별점은 1.0, 5.0, 그리고 후기가 없을 때의 null을 허용한다")
    void 평균_별점_경계값과_null은_허용된다() {
        Long min = createUser("최저");
        Long max = createUser("최고");
        Long none = createUser("없음");

        jdbcTemplate.update("INSERT INTO trust_profile (user_id, average_rating, review_count) VALUES (?, 1.0, 1)", min);
        jdbcTemplate.update("INSERT INTO trust_profile (user_id, average_rating, review_count) VALUES (?, 5.0, 1)", max);
        jdbcTemplate.update("INSERT INTO trust_profile (user_id) VALUES (?)", none);

        assertThat(storedRating(min)).isEqualByComparingTo("1.0");
        assertThat(storedRating(max)).isEqualByComparingTo("5.0");
        assertThat(storedRating(none)).isNull();
    }
}
