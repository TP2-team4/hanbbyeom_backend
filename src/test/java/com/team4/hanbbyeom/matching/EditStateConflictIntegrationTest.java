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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 모집글·러닝 조건 수정 경로의 응답 코드 계약을 HTTP 수준에서 고정한다(이슈 #111).
//  - "지금 글의 상태에서는 불가능하다"는 거절(신청이 걸렸거나 확정·취소된 글)은 네 경로 모두 409다:
//    모집글 수정, 러닝 조건 수정, 러닝 조건 삭제, 모집글 취소. 400은 "요청 자체가 잘못됨"이라 상태 충돌에 맞지 않는다.
//  - 입력값 검증 실패(일정이 너무 임박, 거리 범위 밖)는 계속 400이다.
// 서비스 단위 테스트는 예외 종류만 확인하므로, 클라이언트가 실제로 받는 상태 코드는 여기서 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EditStateConflictIntegrationTest {

    private static final String EDIT_CONFLICT_MESSAGE = "모집 중인 게시글만 수정할 수 있어요.";
    private static final String CONDITION_EDIT_CONFLICT_MESSAGE = "모집 중인 게시글만 조건을 수정할 수 있어요.";

    @PersistenceContext private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Long courseId() {
        return jdbcTemplate.queryForObject("SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
    }

    private Long createUser(String nickname) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "edit-conflict-" + UUID.randomUUID() + "@example.com", "dummy-hash", nickname, OffsetDateTime.now());
    }

    // 지정한 상태의 모집글(활동은 3일 뒤)과 러닝 조건
    private Long createPost(Long userId, String status) {
        OffsetDateTime start = OffsetDateTime.now().plusDays(3);
        Long postId = jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (user_id, status, scheduled_at, talk_level, search_expires_at)
                VALUES (?, ?, ?, 'LIGHT_CHAT', ?)
                RETURNING id
                """,
                Long.class, userId, status, start, start.minusHours(1));
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, '뚝섬유원지역 3번 출구', 5000, 8000, 360, 400)
                """,
                postId, courseId());
        return postId;
    }

    private String bearer(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    private ResultActions patchPost(Long userId, Long postId, OffsetDateTime scheduledAt) throws Exception {
        return mockMvc.perform(patch("/api/matching/requests/{id}", postId)
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"scheduledAt\":\"" + scheduledAt + "\",\"talkLevel\":\"SILENT\"}"));
    }

    private ResultActions patchCondition(Long userId, Long postId, int distanceMin, int distanceMax) throws Exception {
        return mockMvc.perform(patch("/api/run/conditions/{id}", postId)
                .header(HttpHeaders.AUTHORIZATION, bearer(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"courseId\":" + courseId() + ",\"meetingPoint\":\"변경된 만나는 곳\",\"distanceMinMeters\":" + distanceMin
                        + ",\"distanceMaxMeters\":" + distanceMax + ",\"paceMinSec\":350,\"paceMaxSec\":420}"));
    }

    // ── 상태 충돌은 409 ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모집글 수정: 신청이 걸린 글은 409이고 메시지는 그대로다")
    void 모집글_수정_상태_충돌은_409다() throws Exception {
        Long owner = createUser("호스트");
        Long post = createPost(owner, "PENDING_CONFIRMATION");

        patchPost(owner, post, OffsetDateTime.now().plusDays(5))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(EDIT_CONFLICT_MESSAGE));
    }

    @Test
    @DisplayName("러닝 조건 수정: 확정된 글은 409이고 메시지는 그대로다")
    void 러닝_조건_수정_상태_충돌은_409다() throws Exception {
        Long owner = createUser("호스트");
        Long post = createPost(owner, "MATCHED");

        patchCondition(owner, post, 5000, 8000)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(CONDITION_EDIT_CONFLICT_MESSAGE));
    }

    @Test
    @DisplayName("러닝 조건 삭제: 신청이 걸린 글은 409다")
    void 러닝_조건_삭제_상태_충돌은_409다() throws Exception {
        Long owner = createUser("호스트");
        Long post = createPost(owner, "PENDING_CONFIRMATION");

        mockMvc.perform(delete("/api/run/conditions/{id}", post).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("모집글 취소: 신청이 걸린 글은 409다")
    void 모집글_취소_상태_충돌은_409다() throws Exception {
        Long owner = createUser("호스트");
        Long post = createPost(owner, "PENDING_CONFIRMATION");

        mockMvc.perform(post("/api/matching/requests/{id}/cancel", post).header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isConflict());
    }

    // ── 입력값 검증 실패는 계속 400 ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("모집글 수정: 모집 중인 글이라도 일정이 너무 임박하면 400이다 (입력값 검증)")
    void 임박한_일정은_400이다() throws Exception {
        Long owner = createUser("호스트");
        Long post = createPost(owner, "SEARCHING");

        patchPost(owner, post, OffsetDateTime.now().plusMinutes(30)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("러닝 조건 수정: 모집 중인 글이라도 거리 범위를 벗어나면 400이다 (입력값 검증)")
    void 범위_밖_거리는_400이다() throws Exception {
        Long owner = createUser("호스트");
        Long post = createPost(owner, "SEARCHING");

        patchCondition(owner, post, 100, 8000).andExpect(status().isBadRequest());
    }

    // ── 대조군: 모집 중인 글은 정상 수정 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("모집 중인 글은 두 수정 API 모두 204로 정상 처리된다")
    void 모집_중인_글은_수정된다() throws Exception {
        Long owner = createUser("호스트");
        Long post = createPost(owner, "SEARCHING");

        patchPost(owner, post, OffsetDateTime.now().plusDays(5)).andExpect(status().isNoContent());
        patchCondition(owner, post, 6000, 9000).andExpect(status().isNoContent());
        entityManager.flush(); // JPA가 바꾼 값을 JDBC로 읽기 전에 반영한다(테스트가 한 트랜잭션이라 자동으로 flush되지 않는다)
        assertThat(jdbcTemplate.queryForObject(
                "SELECT meeting_point FROM run_match_condition WHERE match_request_id = ?", String.class, post))
                .isEqualTo("변경된 만나는 곳");
    }
}
