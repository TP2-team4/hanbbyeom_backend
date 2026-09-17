package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.MatchRequestStatus;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.matching.service.MatchDecisionService;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 게시글 등록 → 모집 탭 조회 → 신뢰 프로필 확인 → 신청 → 호스트 수락/거절/자동만료까지
// 전체 흐름을 실제 HTTP 요청(JWT 인증 포함)으로 검증한다.
// JwtAuthenticationIntegrationTest와 동일한 패턴(@AutoConfigureMockMvc + JwtTokenProvider)을
// 재사용해서, Service를 직접 호출하는 게 아니라 Security 필터 체인까지 실제로 거치게 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MatchingFlowIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private MatchRequestRepository matchRequestRepository;
    @Autowired private MatchDecisionService matchDecisionService;
    @PersistenceContext private EntityManager entityManager;

    private Long createUser(String nickname) {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "flow-" + UUID.randomUUID() + "@example.com", "dummy-hash", nickname, OffsetDateTime.now()
        );
    }

    private String bearerTokenOf(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    private Long seededCourseId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
    }

    // ObjectMapper 빈이 이 테스트 컨텍스트엔 없어서(JwtAuthenticationIntegrationTest와 동일한
    // 제약), MatchRequestCreateRequest를 직접 JSON 문자열로 만든다.
    private String createRequestJson(String meetingPoint) {
        return """
                {
                  "courseId": %d,
                  "meetingPoint": "%s",
                  "distanceMinMeters": 5000,
                  "distanceMaxMeters": 8000,
                  "paceMinSec": 360,
                  "paceMaxSec": 400,
                  "scheduledAt": "%s",
                  "talkLevel": "LIGHT_CHAT"
                }
                """.formatted(seededCourseId(), meetingPoint, OffsetDateTime.now().plusHours(48));
    }

    // Location 헤더(".../requests/{id}" 또는 ".../matches/{id}")의 마지막 경로 조각을 id로 추출
    private Long idFromLocationHeader(MvcResult result) {
        String location = result.getResponse().getHeader(HttpHeaders.LOCATION);
        String[] segments = location.split("/");
        return Long.valueOf(segments[segments.length - 1]);
    }

    @Test
    @DisplayName("등록 → 목록조회 → 신뢰프로필 → 신청 → 수락까지 전체 플로우가 JWT 인증 하에 정상 동작한다")
    void 신청부터_수락까지_전체_플로우() throws Exception {
        Long hostUserId = createUser("호스트");
        Long applicantUserId = createUser("신청자");
        String hostToken = bearerTokenOf(hostUserId);
        String applicantToken = bearerTokenOf(applicantUserId);

        // 1) 사용자 A(호스트)가 게시글 등록 — 내부적으로 RunConditionService(담당 B)까지 같이 호출됨
        MvcResult createResult = mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("뚝섬유원지역 3번 출구")))
                .andExpect(status().isCreated())
                .andReturn();
        Long hostRequestId = idFromLocationHeader(createResult);
        // RunMatchCondition은 @MapsId라 save() 시점에 즉시 INSERT되지 않고 flush까지 지연될 수
        // 있는데, apply()가 이걸 raw SQL로 직접 조회하므로 명시적으로 flush해서 보이게 한다.
        entityManager.flush();

        // → 모집 탭에 노출되는지 확인
        mockMvc.perform(get("/api/matching/board")
                        .param("course", "뚝섬 한강공원")
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(hostRequestId)).exists());

        // 2) 사용자 B가 필터로 게시글을 찾은 뒤, 호스트 신뢰 프로필 조회
        mockMvc.perform(get("/api/matching/board/{requestId}/host-profile", hostRequestId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk());

        // 3) 사용자 B가 신청 → activity_match(PROPOSED) 생성
        MvcResult applyResult = mockMvc.perform(post("/api/matching/board/{requestId}/apply", hostRequestId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isCreated())
                .andReturn();
        Long activityMatchId = idFromLocationHeader(applyResult);

        // apply()의 changeStatus()는 dirty-checking으로 반영되는데, 네이티브 쿼리(searchBoard)는
        // 자동 flush를 보장 안 할 수 있어 명시적으로 flush 후 확인한다.
        entityManager.flush();

        // → 호스트 상태가 PENDING_CONFIRMATION으로 바뀌어 게시글이 모집 탭 목록에서 빠지는지 확인
        mockMvc.perform(get("/api/matching/board")
                        .param("course", "뚝섬 한강공원")
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(hostRequestId)).doesNotExist());

        // 4) 사용자 A가 대기 중인 신청을 조회 → 신청자 프로필 확인 → 수락
        mockMvc.perform(get("/api/matching/requests/{id}/pending-application", hostRequestId)
                        .header(HttpHeaders.AUTHORIZATION, hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityMatchId").value(activityMatchId));

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}/applicant-profile", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, hostToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/accept", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activityMatchId").value(activityMatchId))
                .andExpect(jsonPath("$.meetingCode").isString())
                .andExpect(jsonPath("$.confirmedAt").exists());

        // → 호스트 게시글이 최종적으로 MATCHED로 확정됐는지 확인
        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.MATCHED);
    }

    @Test
    @DisplayName("호스트가 거절하면 게시글이 다시 모집 탭에 노출된다")
    void 거절하면_게시글이_다시_노출된다() throws Exception {
        Long hostUserId = createUser("호스트2");
        Long applicantUserId = createUser("신청자2");
        String hostToken = bearerTokenOf(hostUserId);
        String applicantToken = bearerTokenOf(applicantUserId);

        MvcResult createResult = mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("여의도 한강공원 2번 출구")))
                .andExpect(status().isCreated())
                .andReturn();
        Long hostRequestId = idFromLocationHeader(createResult);
        entityManager.flush();

        MvcResult applyResult = mockMvc.perform(post("/api/matching/board/{requestId}/apply", hostRequestId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isCreated())
                .andReturn();
        Long activityMatchId = idFromLocationHeader(applyResult);

        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/reject", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, hostToken))
                .andExpect(status().isNoContent());

        entityManager.flush();

        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.SEARCHING);

        // 다시 모집 탭에 노출되는지 확인
        mockMvc.perform(get("/api/matching/board")
                        .param("course", "뚝섬 한강공원")
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(hostRequestId)).exists());
    }

    @Test
    @DisplayName("응답 기한이 지난 신청은 자동 만료 처리되어 게시글이 다시 모집 탭에 노출된다")
    void 자동_만료되면_게시글이_다시_노출된다() throws Exception {
        Long hostUserId = createUser("호스트3");
        Long applicantUserId = createUser("신청자3");
        String hostToken = bearerTokenOf(hostUserId);
        String applicantToken = bearerTokenOf(applicantUserId);

        MvcResult createResult = mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("반포 한강공원 편의점 앞")))
                .andExpect(status().isCreated())
                .andReturn();
        Long hostRequestId = idFromLocationHeader(createResult);
        entityManager.flush();

        MvcResult applyResult = mockMvc.perform(post("/api/matching/board/{requestId}/apply", hostRequestId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isCreated())
                .andReturn();
        Long activityMatchId = idFromLocationHeader(applyResult);

        // 응답 기한을 created_at 직후로 고정 — chk_activity_match_time 제약(created_at <
        // decision_expires_at)은 지키면서 확정적으로 "이미 지난 기한"을 만든다
        // (MatchDecisionServiceTest.pastDeadlineFor()와 동일한 이유).
        OffsetDateTime createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM activity_match WHERE id = ?", OffsetDateTime.class, activityMatchId);
        jdbcTemplate.update(
                "UPDATE activity_match SET decision_expires_at = ? WHERE id = ?",
                createdAt.plusNanos(1_000_000), activityMatchId
        );
        // raw SQL 업데이트는 JPA 영속성 컨텍스트를 안 거치므로, apply()가 이미 로드해둔
        // ActivityMatch 1차 캐시를 비워야 expireOverdue()가 DB의 최신 값을 다시 읽는다.
        entityManager.clear();

        // 실제 1분 주기 스케줄러를 기다리지 않고, 스케줄러가 호출하는 서비스 메서드를 직접 실행
        matchDecisionService.expireOverdue();

        assertThat(matchRequestRepository.findById(hostRequestId).orElseThrow().getStatus())
                .isEqualTo(MatchRequestStatus.SEARCHING);

        mockMvc.perform(get("/api/matching/board")
                        .param("course", "뚝섬 한강공원")
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == %d)]".formatted(hostRequestId)).exists());
    }
}
