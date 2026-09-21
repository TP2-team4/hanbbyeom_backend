package com.team4.hanbbyeom.matching;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 모집글 등록·수정 요청 바디의 검증을 실제 HTTP 흐름으로 확인한다. 필수 필드를 빠뜨렸거나 잘못된 값을 보낸 것은 클라이언트 실수라
// 400이어야 하는데, 검증이 없던 때에는 누락이 서비스 안에서 NullPointerException으로 터져 500이 나갔다(프론트는 서버 장애로 오인한다).
// 요청 바디는 DTO가 아니라 JSON 문자열로 직접 만든다 — 필드 누락과 null, 잘못된 enum 값은 DTO로는 표현할 수 없는 입력이다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class MatchRequestValidationIntegrationTest {

    private static final String ENUM_FORMAT_MESSAGE = "요청 형식이 올바르지 않습니다.";

    @PersistenceContext private EntityManager entityManager;
    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Long createUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO users (email, password_hash, nickname, email_verified_at) VALUES (?, ?, ?, ?) RETURNING id",
                Long.class, "validation-" + UUID.randomUUID() + "@example.com", "dummy-hash", "검증", OffsetDateTime.now());
    }

    private Long courseId() {
        return jdbcTemplate.queryForObject("SELECT id FROM running_course WHERE name = ? LIMIT 1", Long.class, "뚝섬 한강공원");
    }

    // 등록 요청의 정상 바디. 값은 JSON 조각 그대로라(문자열은 따옴표 포함) 필드를 빼거나 null·엉뚱한 값으로 바꾸기 쉽다
    private Map<String, String> validCreateBody() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("courseId", String.valueOf(courseId()));
        body.put("meetingPoint", "\"뚝섬유원지역 3번 출구\"");
        body.put("distanceMinMeters", "5000");
        body.put("distanceMaxMeters", "8000");
        body.put("paceMinSec", "360");
        body.put("paceMaxSec", "400");
        body.put("scheduledAt", "\"" + OffsetDateTime.now().plusDays(2) + "\"");
        body.put("talkLevel", "\"SILENT\"");
        return body;
    }

    private Map<String, String> validUpdateBody() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("scheduledAt", "\"" + OffsetDateTime.now().plusDays(3) + "\"");
        body.put("talkLevel", "\"LIGHT_CHAT\"");
        return body;
    }

    private String json(Map<String, String> body) {
        return body.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }

    private ResultActions create(Long userId, Map<String, String> body) throws Exception {
        return mockMvc.perform(post("/api/matching/requests")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private ResultActions update(Long userId, Long postId, Map<String, String> body) throws Exception {
        return mockMvc.perform(patch("/api/matching/requests/{id}", postId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtTokenProvider.createAccessToken(userId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private int postsOf(Long userId) {
        entityManager.flush();
        entityManager.clear();
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM match_request WHERE user_id = ?", Integer.class, userId);
    }

    // 수정 테스트가 손댈 게시글을 정상 요청으로 등록해서 id를 돌려준다
    private Long createPost(Long userId) throws Exception {
        create(userId, validCreateBody()).andExpect(status().isCreated());
        entityManager.flush();
        entityManager.clear();
        return jdbcTemplate.queryForObject("SELECT id FROM match_request WHERE user_id = ?", Long.class, userId);
    }

    private String talkLevelOf(Long postId) {
        entityManager.flush();
        entityManager.clear();
        return jdbcTemplate.queryForObject("SELECT talk_level FROM match_request WHERE id = ?", String.class, postId);
    }

    // ---------- 등록 ----------

    @ParameterizedTest(name = "등록 요청에서 {0}이(가) 없으면 500이 아니라 400이고 게시글은 만들어지지 않는다")
    @ValueSource(strings = {"courseId", "meetingPoint", "distanceMinMeters", "distanceMaxMeters",
            "paceMinSec", "paceMaxSec", "scheduledAt", "talkLevel"})
    @DisplayName("등록 요청의 필수 필드가 없으면 400이다")
    void 등록_필수_필드가_없으면_400이다(String missingField) throws Exception {
        Long userId = createUser();
        Map<String, String> body = validCreateBody();
        body.remove(missingField);

        create(userId, body).andExpect(status().isBadRequest());

        assertThat(postsOf(userId)).isZero();
    }

    @Test
    @DisplayName("등록 요청의 talkLevel이 null이면 400이다")
    void 등록_talkLevel이_null이면_400이다() throws Exception {
        Long userId = createUser();
        Map<String, String> body = validCreateBody();
        body.put("talkLevel", "null");

        create(userId, body).andExpect(status().isBadRequest());

        assertThat(postsOf(userId)).isZero();
    }

    @Test
    @DisplayName("등록 요청의 만나는 곳이 공백뿐이면 400이다")
    void 등록_만나는_곳이_공백이면_400이다() throws Exception {
        Long userId = createUser();
        Map<String, String> body = validCreateBody();
        body.put("meetingPoint", "\"   \"");

        create(userId, body).andExpect(status().isBadRequest());

        assertThat(postsOf(userId)).isZero();
    }

    @ParameterizedTest(name = "등록 요청의 talkLevel이 {0}이면 400이고 내부 예외 문구를 노출하지 않는다")
    @ValueSource(strings = {"\"TALKATIVE\"", "\"silent\"", "\"\"", "true"})
    @DisplayName("등록 요청의 talkLevel이 정해진 값이 아니면 400이다")
    void 등록_talkLevel이_잘못된_값이면_400이다(String badValue) throws Exception {
        Long userId = createUser();
        Map<String, String> body = validCreateBody();
        body.put("talkLevel", badValue);

        create(userId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ENUM_FORMAT_MESSAGE));

        assertThat(postsOf(userId)).isZero();
    }

    @ParameterizedTest(name = "등록 요청의 talkLevel {0}은 정상으로 저장된다")
    @ValueSource(strings = {"SILENT", "LIGHT_CHAT"})
    @DisplayName("정해진 talkLevel 값은 문자열 그대로 받아 저장한다(JSON 형식은 그대로)")
    void 등록_정상_talkLevel은_저장된다(String talkLevel) throws Exception {
        Long userId = createUser();
        Map<String, String> body = validCreateBody();
        body.put("talkLevel", "\"" + talkLevel + "\"");

        create(userId, body).andExpect(status().isCreated());

        assertThat(postsOf(userId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT talk_level FROM match_request WHERE user_id = ?", String.class, userId)).isEqualTo(talkLevel);
    }

    // ---------- 수정 ----------

    @ParameterizedTest(name = "수정 요청에서 {0}이(가) 없으면 500이 아니라 400이고 게시글은 그대로다")
    @ValueSource(strings = {"scheduledAt", "talkLevel"})
    @DisplayName("수정 요청의 필수 필드가 없으면 400이다")
    void 수정_필수_필드가_없으면_400이다(String missingField) throws Exception {
        Long userId = createUser();
        Long postId = createPost(userId);
        Map<String, String> body = validUpdateBody();
        body.remove(missingField);

        update(userId, postId, body).andExpect(status().isBadRequest());

        assertThat(talkLevelOf(postId)).isEqualTo("SILENT");
    }

    @ParameterizedTest(name = "수정 요청의 talkLevel이 {0}이면 400이고 내부 예외 문구를 노출하지 않는다")
    @ValueSource(strings = {"\"TALKATIVE\"", "null", "true"})
    @DisplayName("수정 요청의 talkLevel이 정해진 값이 아니거나 null이면 400이다")
    void 수정_talkLevel이_잘못된_값이면_400이다(String badValue) throws Exception {
        Long userId = createUser();
        Long postId = createPost(userId);
        Map<String, String> body = validUpdateBody();
        body.put("talkLevel", badValue);

        // null은 형식 오류가 아니라 필수값 누락이라 문구가 다르다. 둘 다 400이라는 점만 확인한다
        update(userId, postId, body).andExpect(status().isBadRequest());

        assertThat(talkLevelOf(postId)).isEqualTo("SILENT");
    }

    @Test
    @DisplayName("수정 요청의 talkLevel이 잘못된 값이면 형식 오류 문구를 준다")
    void 수정_talkLevel이_잘못된_값이면_형식_오류_문구를_준다() throws Exception {
        Long userId = createUser();
        Long postId = createPost(userId);
        Map<String, String> body = validUpdateBody();
        body.put("talkLevel", "\"TALKATIVE\"");

        update(userId, postId, body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(ENUM_FORMAT_MESSAGE));
    }

    @Test
    @DisplayName("정상 수정 요청은 그대로 204이고 대화 수준이 바뀐다")
    void 수정_정상_요청은_204이다() throws Exception {
        Long userId = createUser();
        Long postId = createPost(userId);

        update(userId, postId, validUpdateBody()).andExpect(status().isNoContent());

        assertThat(talkLevelOf(postId)).isEqualTo("LIGHT_CHAT");
    }
}
