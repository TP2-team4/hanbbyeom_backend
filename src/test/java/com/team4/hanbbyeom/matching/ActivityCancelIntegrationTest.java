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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 확정된 활동 참여 취소(POST /api/matching/matches/{id}/cancel)를 실제 JWT 인증·Controller·Service·DB·채팅 흐름으로
// 검증한다(이슈 #109). 활동 시각·상태를 정확히 통제하려고 activity_match와 match_participant는 SQL로 직접 만든다.
// 서비스가 엔티티(JPA)로 바꾼 값을 JDBC로 읽으므로, 요청 뒤에는 영속성 컨텍스트를 flush·clear한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ActivityCancelIntegrationTest {

    private static final String CANCEL_MESSAGE = "활동 참여를 취소했어요.";

    @PersistenceContext private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private final OffsetDateTime start = OffsetDateTime.now().plusDays(3);

    // ── 픽스처 ─────────────────────────────────────────────────────────────────────────────────

    private Long createUser(String nickname) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                "activity-cancel-" + UUID.randomUUID() + "@example.com", "dummy-hash", nickname, OffsetDateTime.now()
        );
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // 게시글. 활동은 항상 3일 뒤(start) 시작하는 글이다(chk_match_request_time: created < search_expires < scheduled)
    private Long createPost(Long userId, String status) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO match_request (user_id, status, scheduled_at, talk_level, search_expires_at)
                VALUES (?, ?, ?, 'LIGHT_CHAT', ?)
                RETURNING id
                """,
                Long.class, userId, status, start, start.minusHours(1)
        );
    }

    // 신청(apply)할 수 있는 모집글이 되도록 러닝 조건까지 붙인다
    private void addRunCondition(Long postId) {
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, '뚝섬유원지역 3번 출구', 5000, 8000, 360, 400)
                """,
                postId, courseId
        );
    }

    // 지정한 상태의 매칭과 참가자 2명(호스트 A, 신청자 B). 신청자 게시글은 없을 수 있다(null).
    // chk_activity_match_time(created_at < decision_expires_at < scheduled_at < scheduled_end_at)을 지킨다.
    private Long createMatch(Long hostId, Long hostPostId, Long applicantId, Long applicantPostId,
                             String status, OffsetDateTime scheduledAt) {
        boolean confirmed = !status.equals("PROPOSED");
        Long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match
                    (scheduled_at, scheduled_end_at, talk_level, location, course_name,
                     distance_min_meters, distance_max_meters, route_description,
                     agreed_pace_min_sec, agreed_pace_max_sec, status,
                     decision_expires_at, meeting_code, confirmed_at, created_at)
                VALUES (?, ?, 'LIGHT_CHAT', '만나는 곳', '뚝섬 한강공원', 5000, 8000, '코스 설명', 360, 400, ?, ?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                scheduledAt, scheduledAt.plusHours(2), status, scheduledAt.minusDays(2),
                confirmed ? "123456" : null, confirmed ? scheduledAt.minusDays(1) : null, scheduledAt.minusDays(3)
        );
        insertParticipant(matchId, hostPostId, hostId, "A");
        insertParticipant(matchId, applicantPostId, applicantId, "B");
        return matchId;
    }

    private void insertParticipant(Long matchId, Long postId, Long userId, String slot) {
        jdbcTemplate.update(
                """
                INSERT INTO match_participant (activity_match_id, match_request_id, user_id, slot, accept_status)
                VALUES (?, ?, ?, ?, 'ACCEPTED')
                """,
                matchId, postId, userId, slot
        );
    }

    // ── 요청·조회 헬퍼 ──────────────────────────────────────────────────────────────────────────

    private ResultActions cancel(Long userId, Long matchId) throws Exception {
        return mockMvc.perform(post("/api/matching/matches/{id}/cancel", matchId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)));
    }

    // JPA가 바꾼 값을 JDBC로 읽기 전에 반영한다(테스트가 한 트랜잭션이라 flush가 자동으로 일어나지 않는다)
    private void syncFromDb() {
        entityManager.flush();
        entityManager.clear();
    }

    private String matchStatus(Long matchId) {
        return jdbcTemplate.queryForObject("SELECT status FROM activity_match WHERE id = ?", String.class, matchId);
    }

    private Long closedBy(Long matchId) {
        return jdbcTemplate.queryForObject(
                "SELECT closed_by_user_id FROM activity_match WHERE id = ?", Long.class, matchId);
    }

    private int releasedParticipants(Long matchId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM match_participant WHERE activity_match_id = ? AND released_at IS NOT NULL",
                Integer.class, matchId);
    }

    private String postStatus(Long postId) {
        return jdbcTemplate.queryForObject("SELECT status FROM match_request WHERE id = ?", String.class, postId);
    }

    private List<Map<String, Object>> chatMessages(Long matchId) {
        return jdbcTemplate.queryForList(
                "SELECT sender_id, content FROM chat_message WHERE activity_match_id = ? ORDER BY id", matchId);
    }

    // 거절된 취소 요청은 아무 흔적도 남기면 안 된다(상태·참가자·게시글·채팅 모두 그대로)
    private void assertUntouched(Long matchId, Long hostPostId) {
        syncFromDb();
        assertThat(matchStatus(matchId)).isEqualTo("CONFIRMED");
        assertThat(closedBy(matchId)).isNull();
        assertThat(releasedParticipants(matchId)).isZero();
        assertThat(postStatus(hostPostId)).isEqualTo("MATCHED");
        assertThat(chatMessages(matchId)).isEmpty();
    }

    // ── 취소 결과 ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("호스트가 취소하면 204, 매칭 취소·참가자 해제·호스트 글 취소·신청자 글 재모집·채팅 메시지가 함께 반영된다")
    void 호스트가_취소하면_모든_상태가_함께_반영된다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long applicantPost = createPost(applicant, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, applicantPost, "CONFIRMED", start);

        cancel(host, matchId).andExpect(status().isNoContent());
        syncFromDb();

        assertThat(matchStatus(matchId)).isEqualTo("CANCELLED");
        assertThat(closedBy(matchId)).isEqualTo(host);
        assertThat(releasedParticipants(matchId)).isEqualTo(2);
        assertThat(postStatus(hostPost)).isEqualTo("CANCELLED");
        assertThat(postStatus(applicantPost)).isEqualTo("SEARCHING");
        assertThat(chatMessages(matchId)).hasSize(1);
        assertThat(chatMessages(matchId).get(0).get("sender_id")).isEqualTo(host);
        assertThat(chatMessages(matchId).get(0).get("content")).isEqualTo(CANCEL_MESSAGE);

        // 참가가 해제되고 게시글이 닫혔으므로 취소한 호스트는 새 모집글을 다시 올릴 수 있다(uq_match_request_active_user)
        Long newPost = createPost(host, "SEARCHING");
        assertThat(postStatus(newPost)).isEqualTo("SEARCHING");
    }

    @Test
    @DisplayName("신청자가 취소하면 신청자 글은 취소되고 호스트 글은 다시 모집 중이 된다")
    void 신청자가_취소하면_호스트_글이_다시_모집된다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long applicantPost = createPost(applicant, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, applicantPost, "CONFIRMED", start);

        cancel(applicant, matchId).andExpect(status().isNoContent());
        syncFromDb();

        assertThat(matchStatus(matchId)).isEqualTo("CANCELLED");
        assertThat(closedBy(matchId)).isEqualTo(applicant);
        assertThat(releasedParticipants(matchId)).isEqualTo(2);
        assertThat(postStatus(applicantPost)).isEqualTo("CANCELLED");
        assertThat(postStatus(hostPost)).isEqualTo("SEARCHING");
        assertThat(chatMessages(matchId)).hasSize(1);
        assertThat(chatMessages(matchId).get(0).get("sender_id")).isEqualTo(applicant);
    }

    @Test
    @DisplayName("본인 게시글 없이 신청한 신청자가 취소해도 호스트 글이 다시 모집 중이 된다")
    void 본인_게시글_없이_신청한_신청자가_취소한다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        cancel(applicant, matchId).andExpect(status().isNoContent());
        syncFromDb();

        assertThat(matchStatus(matchId)).isEqualTo("CANCELLED");
        assertThat(closedBy(matchId)).isEqualTo(applicant);
        assertThat(releasedParticipants(matchId)).isEqualTo(2);
        assertThat(postStatus(hostPost)).isEqualTo("SEARCHING");
    }

    // ── 취소 뒤 채팅·이력·내 신청 내역 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("취소하면 상대는 채팅에서 취소 메시지를 보고, 채팅 목록 상태가 CANCELLED이며, 새 메시지는 보낼 수 없다")
    void 상대는_채팅에서_취소를_확인한다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        cancel(host, matchId).andExpect(status().isNoContent());
        syncFromDb();

        // 상대(신청자)는 폴링으로 받는 메시지 목록에서 취소 사실을 본다. 취소 뒤에도 기록 조회는 유지된다
        mockMvc.perform(get("/api/matching/matches/{id}/messages", matchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].senderId").value(host))
                .andExpect(jsonPath("$[0].content").value(CANCEL_MESSAGE));

        // 채팅 목록에는 취소된 방도 상태와 함께 남는다(양쪽 모두)
        mockMvc.perform(get("/api/chats").header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].activityMatchId").value(matchId))
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
        mockMvc.perform(get("/api/chats").header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));

        // 취소된 활동에는 양쪽 모두 새 메시지를 보낼 수 없다(기존 정책)
        mockMvc.perform(post("/api/matching/matches/{id}/messages", matchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(applicant))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\":\"알겠어요\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("활동 이력에서 취소한 사람에게는 ME, 상대에게는 COUNTERPART로 보인다")
    void 활동_이력의_취소_주체가_구분된다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        cancel(applicant, matchId).andExpect(status().isNoContent());
        syncFromDb();

        mockMvc.perform(get("/api/matching/matches").header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].cancelledBy").value("ME"));
        mockMvc.perform(get("/api/matching/matches").header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"))
                .andExpect(jsonPath("$[0].cancelledBy").value("COUNTERPART"));
    }

    @Test
    @DisplayName("내 신청 내역: 내가 취소하면 CANCELLED, 호스트가 확정 후 취소하면 REJECTED로 보인다")
    void 내_신청_내역에_취소_주체별로_보인다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        // 확정 상태에서는 수락됨
        mockMvc.perform(get("/api/matching/board/applications").header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(jsonPath("$[0].status").value("ACCEPTED"));

        cancel(host, matchId).andExpect(status().isNoContent());
        syncFromDb();

        // 호스트가 취소한 건은 "내 신청이 성사되지 않음"이라 REJECTED(기존 매핑 유지)
        mockMvc.perform(get("/api/matching/board/applications").header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REJECTED"));
    }

    @Test
    @DisplayName("내 신청 내역: 신청자가 확정 후 직접 취소하면 취소함(CANCELLED)으로 보인다")
    void 신청자가_직접_취소하면_취소함으로_보인다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        cancel(applicant, matchId).andExpect(status().isNoContent());
        syncFromDb();

        mockMvc.perform(get("/api/matching/board/applications").header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("CANCELLED"));
    }

    // ── 취소 뒤 다시 참여할 수 있는지 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("신청자가 취소하면 호스트 글에 다른 사람이 새로 신청할 수 있고, 취소한 신청자도 다른 모집글에 신청할 수 있다")
    void 취소_뒤_다시_신청할_수_있다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long newcomer = createUser("새신청자");
        Long otherHost = createUser("다른호스트");
        Long hostPost = createPost(host, "MATCHED");
        addRunCondition(hostPost);
        Long otherPost = createPost(otherHost, "SEARCHING");
        addRunCondition(otherPost);
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        cancel(applicant, matchId).andExpect(status().isNoContent());
        syncFromDb();

        // 다시 모집 중이 된 호스트 글에 새 신청자가 신청한다
        mockMvc.perform(post("/api/matching/board/{id}/apply", hostPost)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(newcomer)))
                .andExpect(status().isCreated());
        // 참가가 해제됐으므로(uq_participant_active_user) 취소한 신청자도 다른 모집글에 신청할 수 있다
        mockMvc.perform(post("/api/matching/board/{id}/apply", otherPost)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isCreated());
    }

    // ── 거절 ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("이미 취소된 활동을 다시 취소하면 409이고 채팅 메시지가 또 남지 않는다")
    void 이미_취소된_활동을_다시_취소하면_409다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        cancel(host, matchId).andExpect(status().isNoContent());
        syncFromDb();

        // 상대가 뒤늦게 취소하거나 같은 사람이 다시 눌러도 거절된다
        cancel(applicant, matchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 취소되었거나 종료된 활동이에요."));
        cancel(host, matchId).andExpect(status().isConflict());
        syncFromDb();

        assertThat(chatMessages(matchId)).hasSize(1);
        assertThat(closedBy(matchId)).isEqualTo(host);
    }

    @Test
    @DisplayName("확정 전(PROPOSED) 매칭은 409이고 신청 취소·거절을 안내한다")
    void 확정_전_매칭은_409다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "PENDING_CONFIRMATION");
        Long matchId = createMatch(host, hostPost, applicant, null, "PROPOSED", start);

        cancel(applicant, matchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("아직 확정되지 않은 매칭이에요. 신청 취소나 거절을 이용해주세요."));
        syncFromDb();

        assertThat(matchStatus(matchId)).isEqualTo("PROPOSED");
        assertThat(postStatus(hostPost)).isEqualTo("PENDING_CONFIRMATION");
        assertThat(chatMessages(matchId)).isEmpty();
    }

    @Test
    @DisplayName("이미 종료된 활동은 409다")
    void 종료된_활동은_409다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "CLOSED");
        Long matchId = createMatch(host, hostPost, applicant, null, "ENDED", start);

        cancel(host, matchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 취소되었거나 종료된 활동이에요."));
        syncFromDb();

        assertThat(matchStatus(matchId)).isEqualTo("ENDED");
        assertThat(chatMessages(matchId)).isEmpty();
    }

    @Test
    @DisplayName("이미 시작된 활동은 409이고 아무것도 바뀌지 않는다")
    void 시작된_활동은_409다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long hostPost = createPost(host, "MATCHED");
        // 1시간 전에 시작했고 2시간짜리라 아직 종료 전 — 노쇼 신고를 피하는 취소를 막는 구간
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", OffsetDateTime.now().minusHours(1));

        cancel(applicant, matchId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 시작된 활동은 취소할 수 없어요."));

        assertUntouched(matchId, hostPost);
    }

    @Test
    @DisplayName("참가자가 아닌 사용자는 403이고 아무것도 바뀌지 않는다")
    void 참가자가_아니면_403이다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long outsider = createUser("제삼자");
        Long hostPost = createPost(host, "MATCHED");
        Long matchId = createMatch(host, hostPost, applicant, null, "CONFIRMED", start);

        cancel(outsider, matchId).andExpect(status().isForbidden());

        assertUntouched(matchId, hostPost);
    }

    @Test
    @DisplayName("존재하지 않는 매칭은 404다")
    void 존재하지_않는_매칭은_404다() throws Exception {
        Long user = createUser("아무나");

        cancel(user, 999_999_999L)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 매칭이에요."));
    }
}
