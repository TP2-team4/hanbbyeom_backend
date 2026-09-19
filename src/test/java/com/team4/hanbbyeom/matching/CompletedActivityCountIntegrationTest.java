package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 활동이 종료되면 완료한 활동 수가 집계되고, 그 값이 노출되는 모든 응답에 같은 값으로 반영되는지 실제 HTTP로 검증한다(이슈 #96).
// 노출 지점: 마이페이지 · 호스트 프로필 · 신청자 프로필 · 모집 탭 목록 · 내 신청 내역
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CompletedActivityCountIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private MatchDecisionService matchDecisionService;
    @Autowired private MatchApplyService matchApplyService;
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

    private MatchRequest createHostPost(Long hostId) {
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

    // 종료 시각이 지났지만 스케줄러가 아직 종료하지 않은 확정(CONFIRMED) 매칭을 만든다. 호스트 참가자는
    // 정리 시 게시글을 CLOSED로 전이하므로 반드시 본인 게시글(match_request_id)을 가져야 한다.
    private Long createOverdueConfirmedMatch(MatchRequest hostPost, Long applicantId) {
        OffsetDateTime start = OffsetDateTime.now().minusHours(3);
        Long matchId = jdbcTemplate.queryForObject(
                """
                INSERT INTO activity_match
                    (scheduled_at, scheduled_end_at, talk_level, location, course_name,
                     distance_min_meters, distance_max_meters, route_description,
                     agreed_pace_min_sec, agreed_pace_max_sec, status,
                     decision_expires_at, meeting_code, confirmed_at, created_at)
                VALUES (?, ?, 'LIGHT_CHAT', '만나는 곳', '뚝섬 한강공원', 5000, 8000, '코스 설명', 360, 400,
                        'CONFIRMED', ?, '123456', ?, ?)
                RETURNING id
                """,
                Long.class,
                start, start.plusHours(2), start.minusDays(2), start.minusDays(1), start.minusDays(3)
        );
        jdbcTemplate.update(
                "INSERT INTO match_participant (activity_match_id, match_request_id, user_id, slot, accept_status) "
                        + "VALUES (?, ?, ?, 'A', 'ACCEPTED')", matchId, hostPost.getId(), hostPost.getUserId());
        jdbcTemplate.update(
                "INSERT INTO match_participant (activity_match_id, user_id, slot, accept_status) "
                        + "VALUES (?, ?, 'B', 'ACCEPTED')", matchId, applicantId);
        return matchId;
    }

    @Test
    @DisplayName("활동이 종료되면 두 참가자의 완료한 활동 수가 마이페이지·프로필·모집 목록·내 신청 내역에 같은 값으로 반영된다")
    void 종료된_활동의_완료_수가_모든_응답에_반영된다() throws Exception {
        Long host = createUser("호스트");
        Long applicant = createUser("신청자");
        Long viewer = createUser("조회자");
        MatchRequest endedPost = createHostPost(host);
        createOverdueConfirmedMatch(endedPost, applicant);
        entityManager.flush();

        // 스케줄러가 종료 처리하면서 두 참가자의 완료한 활동 수를 집계한다
        matchDecisionService.endOverdueActivities();
        entityManager.flush();

        // 1) 마이페이지 — 호스트와 신청자 각자 본인 기준
        mockMvc.perform(get("/api/users/me/trust-profile").header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(1))
                // 후기가 없으므로 별점은 0.0이 아니라 null이다(V16)
                .andExpect(jsonPath("$.averageRating").doesNotExist());
        mockMvc.perform(get("/api/users/me/trust-profile").header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(jsonPath("$.completedCount").value(1));

        // 종료된 활동의 게시글은 CLOSED가 되어, 호스트는 새 모집글을 올릴 수 있다
        MatchRequest newPost = createHostPost(host);
        entityManager.flush();

        // 2) 호스트 프로필
        mockMvc.perform(get("/api/matching/board/{id}/host-profile", newPost.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(1));

        // 3) 모집 탭 목록의 작성자 카드
        mockMvc.perform(get("/api/matching/board").header(HttpHeaders.AUTHORIZATION, bearerToken(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + newPost.getId() + ")].author.completedCount").value(hasItem(1)));

        // 4) 신청자 프로필 — 종료된 활동을 완료한 신청자가 새 모집글에 신청하면 호스트가 그 프로필을 본다
        Long applicationMatchId = matchApplyService.apply(applicant, newPost.getId());
        entityManager.flush();
        mockMvc.perform(get("/api/matching/matches/{id}/applicant-profile", applicationMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(host)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.completedCount").value(1));

        // 5) 내 신청 내역의 호스트 카드
        mockMvc.perform(get("/api/matching/board/applications").header(HttpHeaders.AUTHORIZATION, bearerToken(applicant)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].host.completedCount").value(1));
    }
}
