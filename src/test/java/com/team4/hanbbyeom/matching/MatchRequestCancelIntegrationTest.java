package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.ActivityMatchStatus;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.ActivityMatchRepository;
import com.team4.hanbbyeom.matching.repository.MatchParticipantRepository;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchApplyService;
import com.team4.hanbbyeom.matching.service.MatchDecisionService;
import jakarta.persistence.EntityManager;
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

// 모집글 취소(POST /api/matching/requests/{id}/cancel)를 실제 JWT 인증·Controller·Service·DB 흐름으로 검증한다(이슈 #100).
// 모집 중(SEARCHING)인 게시글만 취소할 수 있고, 이미 신청이 걸렸거나 확정된 게시글은 409로 거부하면서
// 게시글·activity_match·참가자 상태가 하나도 바뀌지 않아야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MatchRequestCancelIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private ActivityMatchRepository activityMatchRepository;
    @Autowired private MatchParticipantRepository matchParticipantRepository;
    @Autowired private MatchApplyService matchApplyService;
    @Autowired private MatchDecisionService matchDecisionService;
    @Autowired private EntityManager entityManager;

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

    // 호스트 모집글 + 러닝 조건(신청이 raw SQL로 조회함)
    private MatchRequest createPost(Long hostId) {
        MatchRequest post = matchRequestRepository.save(new MatchRequest(
                hostId, OffsetDateTime.now().plusHours(48), TalkLevel.LIGHT_CHAT, OffsetDateTime.now().plusHours(9)));
        Long courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
        jdbcTemplate.update(
                """
                INSERT INTO run_match_condition
                    (match_request_id, course_id, meeting_point, distance_min_meters, distance_max_meters, pace_min_sec, pace_max_sec)
                VALUES (?, ?, '뚝섬유원지역 3번 출구', 5000, 8000, 360, 400)
                """,
                post.getId(), courseId
        );
        return post;
    }

    private ResultActions cancel(Long requestId, Long userId) throws Exception {
        entityManager.flush();
        return mockMvc.perform(post("/api/matching/requests/{id}/cancel", requestId)
                .header(HttpHeaders.AUTHORIZATION, bearerToken(userId)));
    }

    private MatchRequestStatus postStatus(Long requestId) {
        entityManager.flush();
        entityManager.clear();
        return matchRequestRepository.findById(requestId).orElseThrow().getStatus();
    }

    private long activeParticipants(Long activityMatchId) {
        return matchParticipantRepository.findByActivityMatchId(activityMatchId).stream()
                .filter(p -> p.getReleasedAt() == null).count();
    }

    @Test
    @DisplayName("모집 중(SEARCHING)인 게시글은 취소되어 CANCELLED가 된다")
    void 모집_중인_게시글은_취소된다() throws Exception {
        Long host = createUser("호스트");
        MatchRequest post = createPost(host);

        cancel(post.getId(), host).andExpect(status().isNoContent());

        assertThat(postStatus(post.getId())).isEqualTo(MatchRequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("신청이 진행 중인 게시글은 409로 거부하고 게시글·매칭·참가자가 그대로다")
    void 신청_대기_중인_게시글은_취소할_수_없다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        MatchRequest post = createPost(host);
        Long matchId = matchApplyService.apply(applicant, post.getId());

        cancel(post.getId(), host)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("신청이 진행 중인 모집글이에요. 신청을 먼저 거절한 뒤 취소해주세요."));

        assertThat(postStatus(post.getId())).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
        assertThat(activityMatchRepository.findById(matchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.PROPOSED);
        assertThat(activeParticipants(matchId)).isEqualTo(2);
    }

    @Test
    @DisplayName("안내대로 신청을 먼저 거절하면 게시글이 모집 중으로 돌아와 취소할 수 있다")
    void 신청을_거절한_뒤에는_취소할_수_있다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        MatchRequest post = createPost(host);
        Long matchId = matchApplyService.apply(applicant, post.getId());
        cancel(post.getId(), host).andExpect(status().isConflict());

        matchDecisionService.reject(host, matchId);

        cancel(post.getId(), host).andExpect(status().isNoContent());
        assertThat(postStatus(post.getId())).isEqualTo(MatchRequestStatus.CANCELLED);
    }

    @Test
    @DisplayName("확정된 게시글은 409로 거부하고 게시글·매칭·참가자가 그대로다")
    void 확정된_게시글은_취소할_수_없다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        MatchRequest post = createPost(host);
        Long matchId = matchApplyService.apply(applicant, post.getId());
        matchDecisionService.accept(host, matchId);

        cancel(post.getId(), host)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("이미 확정된 매칭이 있는 모집글은 취소할 수 없어요."));

        assertThat(postStatus(post.getId())).isEqualTo(MatchRequestStatus.MATCHED);
        assertThat(activityMatchRepository.findById(matchId).orElseThrow().getStatus())
                .isEqualTo(ActivityMatchStatus.CONFIRMED);
        assertThat(activeParticipants(matchId)).isEqualTo(2);
    }

    @Test
    @DisplayName("이미 취소·마감된 게시글(CANCELLED/EXPIRED/CLOSED)을 다시 취소하면 409이고 상태가 그대로다")
    void 이미_취소되었거나_마감된_게시글은_다시_취소할_수_없다() throws Exception {
        for (MatchRequestStatus finished : new MatchRequestStatus[]{
                MatchRequestStatus.CANCELLED, MatchRequestStatus.EXPIRED, MatchRequestStatus.CLOSED}) {
            Long host = createUser("호스트" + finished.ordinal()); // 이메일에 대문자가 들어가지 않도록 상태 이름 대신 순번을 쓴다
            MatchRequest post = createPost(host);
            post.changeStatus(finished);

            cancel(post.getId(), host)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("이미 마감되었거나 취소된 모집글이에요."));

            assertThat(postStatus(post.getId())).isEqualTo(finished);
        }
    }

    // 소유권 검증이 상태 검사보다 먼저다 — 남의 신청 대기 게시글을 취소하려 해도 409가 아니라 403이라 상태가 노출되지 않는다.
    @Test
    @DisplayName("본인 게시글이 아니면 상태와 무관하게 403, 없는 게시글은 404, 인증이 없으면 401")
    void 소유권_존재_인증_검증은_그대로다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long stranger = createUser("무관한사람");
        MatchRequest post = createPost(host);
        matchApplyService.apply(applicant, post.getId()); // 이제 PENDING_CONFIRMATION

        cancel(post.getId(), stranger).andExpect(status().isForbidden());
        cancel(999_999_999L, host).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/matching/requests/{id}/cancel", post.getId()))
                .andExpect(status().isUnauthorized());

        assertThat(postStatus(post.getId())).isEqualTo(MatchRequestStatus.PENDING_CONFIRMATION);
    }
}
