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

    // created_at과 decision_expires_at을 DB 서버 시각(CURRENT_TIMESTAMP) 기준으로 함께
    // 과거로 밀어서 chk_activity_match_time 제약을 지키면서 "이미 지난 기한"을 확정적으로
    // 만든다. 원래는 decision_expires_at만 created_at + 1ms로 세팅했는데, 테스트가 그 1ms가
    // 지나기 전에 다음 단계까지 도달할 만큼 빠르게 실행되면 간헐적으로 실패했다
    // (MatchDecisionServiceTest와 동일한 문제 — 팀원 리뷰로 발견).
    private void makeDeadlineOverdue(Long activityMatchId) {
        jdbcTemplate.update(
                """
                UPDATE activity_match
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    decision_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                activityMatchId
        );
    }

    // makeDeadlineOverdue()와 같은 이유로, created_at부터 scheduled_end_at까지 네 시각을 전부
    // DB 서버 시각 기준 실제 과거로 고정해서 "이미 지난 예정 종료 시각"을 확정적으로 만든다
    // (MatchDecisionServiceTest.pushScheduledEndAtIntoThePast()와 동일한 패턴).
    private void pushScheduledEndAtIntoThePast(Long activityMatchId) {
        jdbcTemplate.update(
                """
                UPDATE activity_match
                SET created_at = CURRENT_TIMESTAMP - INTERVAL '4 seconds',
                    decision_expires_at = CURRENT_TIMESTAMP - INTERVAL '3 seconds',
                    scheduled_at = CURRENT_TIMESTAMP - INTERVAL '2 seconds',
                    scheduled_end_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
                WHERE id = ?
                """,
                activityMatchId
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

        // → 신청자(B) 본인도 이 매칭 건 상태를 조회해서 CONFIRMED임을 확인할 수 있어야 한다
        // (팀원 피드백: 신청자가 "내 신청이 어떻게 됐는지" 알 방법이 필요하다는 지적 반영)
        mockMvc.perform(get("/api/matching/matches/{activityMatchId}", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.meetingCode").isString());

        // → 이 매칭과 아무 관련 없는 제3자가 조회하면 403이어야 한다 (권한 경계 회귀 테스트 —
        // 리뷰 피드백: 참가자 확인 로직은 있었지만 실제로 막히는지 검증하는 테스트가 없었음)
        Long strangerUserId = createUser("무관한사람");
        mockMvc.perform(get("/api/matching/matches/{activityMatchId}", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenOf(strangerUserId)))
                .andExpect(status().isForbidden());
    }

    // 회귀 테스트: 존재하지 않는 activityMatchId로 조회하면 403이 아니라 404가 나가야 한다.
    // 원래는 이 경우도 NotMatchParticipantException(403)을 던지면서 메시지만 "존재하지
    // 않는 매칭이에요"라 상태 코드와 메시지가 어긋나 있었다(PR #57 리뷰 피드백으로 발견) —
    // ActivityMatchNotFoundException(404)으로 분리해서 위 제3자-403 케이스와 구분한다.
    @Test
    @DisplayName("존재하지 않는 매칭 건을 조회하면 403이 아니라 404가 반환된다")
    void 존재하지_않는_매칭을_조회하면_404를_반환한다() throws Exception {
        Long userId = createUser("사용자");

        mockMvc.perform(get("/api/matching/matches/{activityMatchId}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenOf(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 매칭이에요."));
    }

    // 회귀 테스트: GET /api/matching/requests/me가 GET /api/matching/requests/{id}(Long)
    // 패턴에 가려서 "me"를 id로 파싱하려다 400/500이 나지 않는지 확인한다(Spring이 리터럴
    // 경로를 변수 패턴보다 우선하는 것에 기대는 부분이라 실제 HTTP 요청으로 고정해둔다).
    // 프론트/QA가 방금 만든 게시글의 Location 헤더를 놓쳤을 때 id 없이도 확인할 수 있게
    // 추가한 API.
    @Test
    @DisplayName("등록 직후 내 활성 모집글을 id 없이 /me로 조회할 수 있다")
    void 내_활성_모집글을_me_경로로_조회할_수_있다() throws Exception {
        Long hostUserId = createUser("호스트");
        String hostToken = bearerTokenOf(hostUserId);

        MvcResult createResult = mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("뚝섬유원지역 3번 출구")))
                .andExpect(status().isCreated())
                .andReturn();
        Long hostRequestId = idFromLocationHeader(createResult);
        entityManager.flush();

        mockMvc.perform(get("/api/matching/requests/me")
                        .header(HttpHeaders.AUTHORIZATION, hostToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(hostRequestId))
                .andExpect(jsonPath("$.status").value("SEARCHING"));
    }

    // 활성 모집글이 아예 없는 유저가 조회하면 404여야 한다(존재하지 않는 id로 조회할 때와
    // 동일한 예외 타입을 쓰지만, "id=X" 대신 "활성 모집글이 없다"는 메시지를 내려준다).
    @Test
    @DisplayName("활성 모집글이 없으면 /me 조회 시 404가 반환된다")
    void 활성_모집글이_없으면_me_조회시_404를_반환한다() throws Exception {
        Long userId = createUser("모집글없는사용자");

        mockMvc.perform(get("/api/matching/requests/me")
                        .header(HttpHeaders.AUTHORIZATION, bearerTokenOf(userId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("현재 진행 중인 모집글이 없어요."));
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

        // → 신청자(B) 본인이 이 매칭 건을 조회하면 REJECTED임을 구분해서 알 수 있어야 한다
        // (게시글 상태만 보면 거절인지 자동만료인지 구분 불가 — 팀원 피드백 반영)
        mockMvc.perform(get("/api/matching/matches/{activityMatchId}", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
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

        makeDeadlineOverdue(activityMatchId);
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

        // → 신청자(B) 본인이 조회하면 REJECTED가 아니라 EXPIRED로 구분돼서 보여야 한다
        mockMvc.perform(get("/api/matching/matches/{activityMatchId}", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"));
    }

    // 화면(26 "내가 신청한 모집")이 실제 HTTP 요청+JWT 인증을 거쳐서도 동작하는지, 그리고
    // status 쿼리 파라미터로 걸러지는지 확인한다. EXPIRED는 신청자 입장에서 REJECTED로
    // 재매핑된다는 사실도 이 경로로 같이 확인한다(위 테스트는 원본 activity_match.status만 봄).
    @Test
    @DisplayName("내 신청 내역을 조회하면 자동 만료된 신청도 REJECTED로 나오고, status로 필터링된다")
    void 내_신청_내역을_조회하면_상태별로_필터링된다() throws Exception {
        Long hostUserId = createUser("호스트4");
        Long applicantUserId = createUser("신청자4");
        String hostToken = bearerTokenOf(hostUserId);
        String applicantToken = bearerTokenOf(applicantUserId);

        MvcResult createResult = mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("망원 한강공원 앞")))
                .andExpect(status().isCreated())
                .andReturn();
        Long hostRequestId = idFromLocationHeader(createResult);
        entityManager.flush();

        MvcResult applyResult = mockMvc.perform(post("/api/matching/board/{requestId}/apply", hostRequestId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isCreated())
                .andReturn();
        Long activityMatchId = idFromLocationHeader(applyResult);

        makeDeadlineOverdue(activityMatchId);
        entityManager.clear();
        matchDecisionService.expireOverdue();

        // 전체 조회 — 방금 자동 만료된 신청 1건이 REJECTED로 재매핑돼서 나와야 한다.
        mockMvc.perform(get("/api/matching/board/applications")
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].activityMatchId").value(activityMatchId))
                .andExpect(jsonPath("$[0].status").value("REJECTED"));

        // status=REJECTED로 필터링하면 그대로 나오고, status=PENDING으로 필터링하면 빈 배열이어야 한다.
        mockMvc.perform(get("/api/matching/board/applications")
                        .param("status", "REJECTED")
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));

        mockMvc.perform(get("/api/matching/board/applications")
                        .param("status", "PENDING")
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    // 회귀 테스트: 활동이 자연 종료(ENDED)돼도 호스트 게시글이 MATCHED로 남아 있으면
    // uq_match_request_active_user 부분 유니크 인덱스와 GET /api/matching/requests/me 양쪽에서
    // 계속 "활성" 게시글로 취급돼, 활동이 끝난 지 오래된 게시글이 /requests/me에 계속 노출되고
    // 호스트가 새 게시글을 등록하지도 못했다(팀원 리뷰로 발견). endOverdueActivities()가
    // 게시글을 CLOSED로 전이하도록 고친 뒤, 실제 HTTP 흐름으로 두 가지를 확인한다.
    @Test
    @DisplayName("활동 종료 후 모집글이 CLOSED로 전이되어 /requests/me에서 빠지고 새 모집글을 등록할 수 있다")
    void 활동_종료_후_모집글이_CLOSED로_전이되고_새_모집글_등록이_가능하다() throws Exception {
        Long hostUserId = createUser("호스트5");
        Long applicantUserId = createUser("신청자5");
        String hostToken = bearerTokenOf(hostUserId);
        String applicantToken = bearerTokenOf(applicantUserId);

        MvcResult createResult = mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("망원 한강공원 앞")))
                .andExpect(status().isCreated())
                .andReturn();
        Long hostRequestId = idFromLocationHeader(createResult);
        entityManager.flush();

        MvcResult applyResult = mockMvc.perform(post("/api/matching/board/{requestId}/apply", hostRequestId)
                        .header(HttpHeaders.AUTHORIZATION, applicantToken))
                .andExpect(status().isCreated())
                .andReturn();
        Long activityMatchId = idFromLocationHeader(applyResult);

        mockMvc.perform(post("/api/matching/matches/{activityMatchId}/accept", activityMatchId)
                        .header(HttpHeaders.AUTHORIZATION, hostToken))
                .andExpect(status().isOk());
        // accept()가 남긴 변경(status=CONFIRMED 등)을 DB에 반영해둔다 — 안 그러면 바로 아래
        // raw SQL 업데이트가 실행되는 시점에도 DB엔 아직 PROPOSED로 남아 있어서, endOverdueActivities()의
        // findByStatusAndScheduledEndAtBefore(CONFIRMED, ...) 조회에 이 건이 안 걸린다.
        entityManager.flush();

        pushScheduledEndAtIntoThePast(activityMatchId);
        entityManager.clear();
        matchDecisionService.endOverdueActivities();

        // 활동이 끝난 게시글은 더 이상 /requests/me에 나오면 안 된다(CLOSED로 전이됐으므로).
        mockMvc.perform(get("/api/matching/requests/me")
                        .header(HttpHeaders.AUTHORIZATION, hostToken))
                .andExpect(status().isNotFound());

        // 활성 게시글이 없어졌으니 같은 호스트가 새 게시글을 다시 등록할 수 있어야 한다
        // (uq_match_request_active_user에 더 이상 걸리지 않아야 함).
        mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, hostToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequestJson("망원 한강공원 앞 (재등록)")))
                .andExpect(status().isCreated());
    }
}
