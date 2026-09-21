package com.team4.hanbbyeom.run;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import com.team4.hanbbyeom.matching.domain.MatchRequest;
import com.team4.hanbbyeom.matching.domain.TalkLevel;
import com.team4.hanbbyeom.matching.repository.MatchRequestRepository;
import com.team4.hanbbyeom.run.dto.RunConditionCreateRequest;
import com.team4.hanbbyeom.run.dto.RunConditionUpdateRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// #18: Run API 통합 테스트 및 C파트 연동 확인
// 개별 API 단위가 아니라, 사용자 시나리오처럼 이어지는 흐름과
// Matching 도메인(C파트)이 run_match_condition 데이터를 정상 참조하는지를 검증한다.
// MockMvc + JwtTokenProvider로 실제 HTTP 요청을 흉내 내어 Controller 매핑, JWT 인증,
// JSON (역)직렬화, Bean Validation, HTTP 상태 코드까지 전부 검증한다
// (Service를 직접 호출하면 Security Filter Chain을 거치지 않아 이 부분이 검증되지 않음).
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
public class RunApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    // tools.jackson(Jackson 3) 패키지로 주입받는 이유: Spring Boot 4의 spring-boot-starter-webmvc가
    // 기본으로 쓰는 JSON 라이브러리가 Jackson 3(tools.jackson)이라 Controller가 실제로 역직렬화할 때도
    // 이 라이브러리를 씀. com.fasterxml.jackson(Jackson 2)는 jjwt-jackson이 전이 의존성으로 끌고 온
    // 별개의 라이브러리라, 그걸로 직렬화하면 운영 코드가 쓰는 것과 다른 라이브러리로 테스트하게 되어
    // 미묘한 직렬화 차이를 놓칠 수 있음
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private MatchRequestRepository matchRequestRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @PersistenceContext
    private EntityManager entityManager;

    private Long ownerUserId;
    private Long otherUserId;
    private Long matchRequestId;
    private Long courseId;

    @BeforeEach
    void setUp() {
        ownerUserId = createUser("owner");
        otherUserId = createUser("other");

        courseId = jdbcTemplate.queryForObject(
                "SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");

        MatchRequest matchRequest = new MatchRequest(
                ownerUserId, OffsetDateTime.now().plusHours(5), TalkLevel.SILENT,
                OffsetDateTime.now().plusHours(4)
        );
        matchRequestId = matchRequestRepository.save(matchRequest).getId();
    }

    private Long createUser(String label) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class,
                label + "-" + System.nanoTime() + "@example.com", "dummy-hash", label, OffsetDateTime.now()
        );
    }

    // 매 요청마다 반복되는 "Bearer <토큰>" 헤더 값 생성
    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // 1. 전체 플로우: 코스 조회 -> 조건 등록 -> 조회 -> 수정 -> 삭제 (전부 실제 HTTP 요청)
    @Test
    void 전체_플로우가_이어서_정상_동작한다() throws Exception {
        // 코스 조회 (인증 불필요, permitAll) — 응답 JSON에 시딩된 코스가 들어있는지 확인
        mockMvc.perform(get("/api/run/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.name == '뚝섬 한강공원')]").exists());

        // 조건 등록 — 201 Created + Location 헤더 확인
        RunConditionCreateRequest createRequest = new RunConditionCreateRequest(
                matchRequestId, courseId, "뚝섬유원지 3번 출구", 5000, 8000, 360, 400
        );
        mockMvc.perform(post("/api/run/conditions")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/run/conditions/" + matchRequestId));

        // 조회 — 등록한 값이 그대로 내려오는지 확인
        mockMvc.perform(get("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.courseName").value("뚝섬 한강공원"))
                .andExpect(jsonPath("$.meetingPoint").value("뚝섬유원지 3번 출구"));

        // 수정 — 204 No Content
        RunConditionUpdateRequest updateRequest = new RunConditionUpdateRequest(
                courseId, "여의도 2번 출구", 3000, 6000, 310, 380
        );
        mockMvc.perform(patch("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNoContent());

        // 재조회 — 수정된 값이 실제로 반영됐는지 확인
        mockMvc.perform(get("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetingPoint").value("여의도 2번 출구"))
                .andExpect(jsonPath("$.distanceMinMeters").value(3000))
                .andExpect(jsonPath("$.distanceMaxMeters").value(6000));

        // 삭제 — 204 No Content
        mockMvc.perform(delete("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId)))
                .andExpect(status().isNoContent());

        // 삭제 후 재조회 — GlobalExceptionHandler가 404로 매핑하는지 확인
        mockMvc.perform(get("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId)))
                .andExpect(status().isNotFound());
    }

    // 2. C파트 연동 확인: 모집 탭 목록 조회 API(GET /api/matching/board)에서
    // run_match_condition 데이터가 정상 조회되는지 (Matching 도메인 Controller까지 실제로 거침)
    @Test
    void 모집게시판_목록에_러닝조건_데이터가_정상_연동된다() throws Exception {
        RunConditionCreateRequest createRequest = new RunConditionCreateRequest(
                matchRequestId, courseId, "뚝섬유원지 3번 출구", 5000, 8000, 360, 400
        );
        mockMvc.perform(post("/api/run/conditions")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated());

        // 소유자 본인 글은 목록에서 항상 제외되므로, 다른 유저(otherUserId)의 토큰으로 조회해야
        // 목록에 포함된다
        mockMvc.perform(get("/api/matching/board")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(otherUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == %d)]", matchRequestId).exists())
                .andExpect(jsonPath("$.items[?(@.id == %d)].courseName".formatted(matchRequestId)).value("뚝섬 한강공원"))
                .andExpect(jsonPath("$.items[?(@.id == %d)].distanceMinMeters".formatted(matchRequestId)).value(5000))
                .andExpect(jsonPath("$.items[?(@.id == %d)].distanceMaxMeters".formatted(matchRequestId)).value(8000))
                .andExpect(jsonPath("$.items[?(@.id == %d)].paceMinSec".formatted(matchRequestId)).value(360))
                .andExpect(jsonPath("$.items[?(@.id == %d)].paceMaxSec".formatted(matchRequestId)).value(400));
    }

    // 3. 경계값 테스트: 거리/페이스가 허용 범위의 정확한 경계값이면 정상 등록되어야 함
    @Test
    void 거리와_페이스가_정확히_경계값이면_정상_등록된다() throws Exception {
        RunConditionCreateRequest boundaryRequest = new RunConditionCreateRequest(
                matchRequestId, courseId, "출구", 1000, 20000, 300, 450
        );

        mockMvc.perform(post("/api/run/conditions")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(boundaryRequest)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distanceMinMeters").value(1000))
                .andExpect(jsonPath("$.distanceMaxMeters").value(20000))
                .andExpect(jsonPath("$.paceMinSec").value(300))
                .andExpect(jsonPath("$.paceMaxSec").value(450));
    }

    // 4. Bean Validation 검증: 필수값(meetingPoint)이 비어 있으면 Controller까지 도달하지 못하고
    // 400 Bad Request로 막혀야 한다 (Service 직접 호출 테스트로는 검증 불가능했던 부분)
    @Test
    void 필수값이_비어있으면_400을_반환한다() throws Exception {
        RunConditionCreateRequest invalidRequest = new RunConditionCreateRequest(
                matchRequestId, courseId, "", 5000, 8000, 360, 400 // meetingPoint가 빈 문자열(@NotBlank 위반)
        );

        mockMvc.perform(post("/api/run/conditions")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    // 5. 인증 없이 호출하면 401을 반환한다 (JWT 인증 필터 동작 확인)
    @Test
    void 토큰_없이_조건_등록하면_401을_반환한다() throws Exception {
        RunConditionCreateRequest createRequest = new RunConditionCreateRequest(
                matchRequestId, courseId, "뚝섬유원지 3번 출구", 5000, 8000, 360, 400
        );

        mockMvc.perform(post("/api/run/conditions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isUnauthorized());
    }

    // 6. 애초에 존재한 적 없는 id로 조회하면 404 + 정확한 메시지가 나온다. 위 1번 테스트의
    // "삭제 후 재조회" 케이스는 상태 코드만 확인하는데, 이 케이스는 응답 메시지까지 함께
    // 검증해서 RunMatchConditionNotFoundException이 실제로 GlobalExceptionHandler를 거쳐
    // 404로 매핑되는지 명확히 고정한다(PR 리뷰: 같은 버그를 검증하는 테스트가 run/ 패키지에
    // 두 파일로 나뉘어 있던 것을 여기로 합침 — 원래 별도 파일이었던
    // RunConditionControllerIntegrationTest는 삭제).
    @Test
    void 존재하지_않는_id로_조회하면_404와_정확한_메시지를_반환한다() throws Exception {
        mockMvc.perform(get("/api/run/conditions/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("존재하지 않는 러닝 조건이에요. id=999999999"));
    }

    // 7. 만나는 곳 길이 상한: run_match_condition.meeting_point가 VARCHAR(255)라 256자부터 INSERT/UPDATE가 실패해 500이 된다.
    // 상한은 바이트가 아니라 문자 수다(Postgres VARCHAR(n) 기준). 한글 255자는 UTF-8로 765바이트지만 저장된다.
    // 이 클래스는 테스트 트랜잭션 안에서 돌아 INSERT/UPDATE가 flush까지 미뤄지므로, 정상 경계 테스트는 flush로 실제 SQL을 실행한다
    private int meetingPointLengthInDb() {
        entityManager.flush();
        return jdbcTemplate.queryForObject(
                "SELECT char_length(meeting_point) FROM run_match_condition WHERE match_request_id = ?",
                Integer.class, matchRequestId);
    }

    private void registerCondition(String meetingPoint) throws Exception {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, meetingPoint, 5000, 8000, 360, 400
        );
        mockMvc.perform(post("/api/run/conditions")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void 만나는_곳이_255자를_넘으면_등록이_400이다() throws Exception {
        RunConditionCreateRequest request = new RunConditionCreateRequest(
                matchRequestId, courseId, "가".repeat(256), 5000, 8000, 360, 400
        );

        mockMvc.perform(post("/api/run/conditions")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("만나는 곳은 255자 이하로 입력해주세요."));
    }

    @Test
    void 만나는_곳이_정확히_255자면_등록된다() throws Exception {
        registerCondition("가".repeat(255));

        assertThat(meetingPointLengthInDb()).isEqualTo(255);
    }

    @Test
    void 만나는_곳이_255자를_넘으면_수정이_400이고_조건은_그대로다() throws Exception {
        registerCondition("뚝섬유원지 3번 출구");
        RunConditionUpdateRequest request = new RunConditionUpdateRequest(
                courseId, "가".repeat(256), 3000, 6000, 310, 380
        );

        mockMvc.perform(patch("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("만나는 곳은 255자 이하로 입력해주세요."));

        entityManager.flush();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT meeting_point FROM run_match_condition WHERE match_request_id = ?",
                String.class, matchRequestId)).isEqualTo("뚝섬유원지 3번 출구");
    }

    @Test
    void 만나는_곳이_정확히_255자면_수정된다() throws Exception {
        registerCondition("뚝섬유원지 3번 출구");
        RunConditionUpdateRequest request = new RunConditionUpdateRequest(
                courseId, "가".repeat(255), 3000, 6000, 310, 380
        );

        mockMvc.perform(patch("/api/run/conditions/{id}", matchRequestId)
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(ownerUserId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        assertThat(meetingPointLengthInDb()).isEqualTo(255);
    }
}
