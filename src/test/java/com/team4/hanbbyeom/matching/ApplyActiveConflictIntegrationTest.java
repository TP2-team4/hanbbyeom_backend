package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 이미 진행 중인 신청·활동이 있는 사람이 관여한 신청이 500이 아니라 409로 거절되는지 실제 HTTP 흐름으로 검증한다.
// 사용자당 활성 참가는 하나뿐이라(uq_participant_active_user) 예전에는 match_participant INSERT가 제약 위반으로 500이 났다.
// 서비스가 엔티티(JPA)로 바꾼 값을 JDBC로 읽으므로 요청 뒤에는 영속성 컨텍스트를 flush·clear한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApplyActiveConflictIntegrationTest {

    @PersistenceContext private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Long createUser(String nickname) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "apply-conflict-" + UUID.randomUUID() + "@example.com", "dummy-hash", nickname, OffsetDateTime.now());
    }

    // 신청할 수 있는 모집글(활동은 2일 뒤, 러닝 조건 포함)
    private Long createPost(Long userId) {
        return createPost(userId, OffsetDateTime.now().plusDays(2));
    }

    // 활동 시작 시각을 지정한 모집글. 생성 시각을 시작 3일 전으로 두어, 시작이 임박한 글도 시간 순서 제약을 지켜 만들 수 있다
    private Long createPost(Long userId, OffsetDateTime start) {
        Long postId = jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (user_id, status, scheduled_at, talk_level, search_expires_at, created_at)
                VALUES (?, 'SEARCHING', ?, 'LIGHT_CHAT', ?, ?)
                RETURNING id
                """,
                Long.class, userId, start, start.minusHours(1), start.minusDays(3));
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, '뚝섬유원지역 3번 출구', 5000, 8000, 360, 400)
                """,
                postId, courseId);
        return postId;
    }

    private ResultActions apply(Long userId, Long postId) throws Exception {
        return mockMvc.perform(post("/api/matching/board/{id}/apply", postId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId)));
    }

    private ResultActions cancelApplication(Long userId, Long postId) throws Exception {
        return mockMvc.perform(post("/api/matching/board/{id}/apply/cancel", postId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId)));
    }

    private String postStatus(Long postId) {
        entityManager.flush();
        entityManager.clear();
        return jdbcTemplate.queryForObject("SELECT status FROM match_request WHERE id = ?", String.class, postId);
    }

    // 글에 연결된 참가 수. 신청자는 본인 글 없이 신청하면 match_request_id가 NULL이라 호스트 참가만 센다
    private int participantsOf(Long postId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM match_participant WHERE match_request_id = ?", Integer.class, postId);
    }

    // 사용자가 지금 참가 중인 활동 수(released_at IS NULL). uq_participant_active_user 때문에 0 또는 1이다
    private int activeParticipationsOf(Long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM match_participant WHERE user_id = ? AND released_at IS NULL", Integer.class, userId);
    }

    @Test
    @DisplayName("이미 다른 글에 신청 중인 사람이 또 신청하면 500이 아니라 409이고, 새 글에는 아무것도 만들어지지 않는다")
    void 신청_중인_사람의_새_신청은_409다() throws Exception {
        Long viewer = createUser("신청자");
        Long postA = createPost(createUser("호스트A"));
        Long postB = createPost(createUser("호스트B"));

        apply(viewer, postA).andExpect(status().isCreated());
        apply(viewer, postB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 진행 중인 신청이나 활동이 있어요."));

        assertThat(postStatus(postB)).isEqualTo("SEARCHING");
        assertThat(participantsOf(postB)).isZero();
        // 먼저 넣은 신청은 그대로 유지된다(참가는 하나뿐)
        assertThat(postStatus(postA)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(participantsOf(postA)).isEqualTo(1);
        assertThat(activeParticipationsOf(viewer)).isEqualTo(1);
    }

    @Test
    @DisplayName("작성자가 이미 다른 글에 신청 중이면 그 작성자의 글에는 500이 아니라 409이고, 글은 그대로 모집 중이다")
    void 작성자가_신청_중인_글에는_409다() throws Exception {
        Long author = createUser("작성자");
        Long other = createUser("다른호스트");
        Long applicant = createUser("신청자");
        Long authorPost = createPost(author);
        Long otherPost = createPost(other);

        // 작성자가 다른 글에 신청한다. 신청은 본인 글의 상태를 바꾸지 않아 그 글은 계속 SEARCHING으로 보인다
        apply(author, otherPost).andExpect(status().isCreated());
        assertThat(postStatus(authorPost)).isEqualTo("SEARCHING");

        apply(applicant, authorPost)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 마감되었거나 신청이 진행 중인 모집글이에요."));

        assertThat(postStatus(authorPost)).isEqualTo("SEARCHING");
        assertThat(participantsOf(authorPost)).isZero();
    }

    @Test
    @DisplayName("진행 중인 신청을 취소하면 충돌이 풀려 다른 글에 다시 신청할 수 있다")
    void 신청을_취소하면_다른_글에_신청할_수_있다() throws Exception {
        Long viewer = createUser("신청자");
        Long postA = createPost(createUser("호스트A"));
        Long postB = createPost(createUser("호스트B"));

        apply(viewer, postA).andExpect(status().isCreated());
        apply(viewer, postB).andExpect(status().isConflict());

        cancelApplication(viewer, postA).andExpect(status().isNoContent());
        apply(viewer, postB).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("본인 모집글이 있는 사람이 다른 글에 신청하는 것은 여전히 허용된다")
    void 본인_글이_있어도_신청은_허용된다() throws Exception {
        Long viewer = createUser("신청자");
        createPost(viewer);
        Long otherPost = createPost(createUser("호스트"));

        apply(viewer, otherPost).andExpect(status().isCreated());
    }

    // ── 검증 순서: 400(요청 자체가 안 되는 것)이 409(지금 상태가 안 되는 것)보다 먼저 ────────────────
    // 시작이 임박한 글은 시간이 갈수록 더 신청할 수 없어진다. 409("나중에 다시 시도")로 안내하면 사용자가 재시도만 반복하므로,
    // 두 조건이 함께 걸리면 최종 거절인 400(시작 임박)을 알려야 한다.

    @Test
    @DisplayName("이미 신청 중인 사람이 시작이 임박한 글에 신청하면 409가 아니라 400(시작 임박)이다")
    void 신청_중이어도_시작이_임박한_글은_400이다() throws Exception {
        Long viewer = createUser("신청자");
        Long postA = createPost(createUser("호스트A"));
        Long imminent = createPost(createUser("호스트B"), OffsetDateTime.now().plusMinutes(30));

        apply(viewer, postA).andExpect(status().isCreated());
        apply(viewer, imminent)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("활동 시작 시각이 너무 임박해서 신청할 수 없어요."));
    }

    @Test
    @DisplayName("작성자가 이미 신청 중이어도 시작이 임박한 그 작성자의 글에는 409가 아니라 400(시작 임박)이다")
    void 작성자가_신청_중이어도_시작이_임박한_글은_400이다() throws Exception {
        Long author = createUser("작성자");
        Long applicant = createUser("신청자");
        Long imminent = createPost(author, OffsetDateTime.now().plusMinutes(30));
        Long otherPost = createPost(createUser("다른호스트"));

        apply(author, otherPost).andExpect(status().isCreated());
        apply(applicant, imminent)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("활동 시작 시각이 너무 임박해서 신청할 수 없어요."));
    }
}
