package com.team4.hanbbyeom.global.exception;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 예외 메시지의 응답 노출 범위 고정

// 400 응답이라도 메시지의 출처가 둘로 갈림
// → 우리가 사용자에게 보여줄 목적으로 작성한 안내는 그대로 노출
// → JDK·라이브러리가 만든 메시지는 내부 클래스명·구현 정보가 담겨 고정 문구로 대체
// 노출을 막으면서 기존 안내까지 덮지 않았는지 양방향으로 확인
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ExceptionMessageScopeIntegrationTest {

    // 응답에 포함되면 안 되는 내부 정보 (패키지 경로 노출 시 클래스 구조 드러남)
    private static final String INTERNAL_PACKAGE = "com.team4.hanbbyeom";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private Long createUser() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "exception-scope-" + UUID.randomUUID() + "@example.com",
                "dummy-hash",
                "예외범위" + UUID.randomUUID().toString().substring(0, 6),
                OffsetDateTime.now()
        );
    }

    private String bearerToken(Long userId) {
        return "Bearer " + jwtTokenProvider.createAccessToken(userId);
    }

    // 우리가 작성한 안내는 그대로 노출

    @Test
    @DisplayName("허용되지 않은 sort 값의 안내 문구 그대로 노출")
    void 허용되지_않은_sort_값의_안내_문구_노출() throws Exception {
        mockMvc.perform(get("/api/matching/board")
                        .param("sort", "NEWEST")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(createUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("sort는 LATEST, SCHEDULED, DISTANCE 중 하나여야 합니다."));
    }

    @Test
    @DisplayName("범위를 벗어난 size의 안내 문구 그대로 노출")
    void 범위를_벗어난_size의_안내_문구_노출() throws Exception {
        mockMvc.perform(get("/api/matching/board")
                        .param("size", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(createUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size는 1 이상 50 이하여야 합니다."));
    }

    // 라이브러리가 만든 메시지는 고정 문구로 대체

    // 허용되지 않은 enum 값은 JSON 변환 단계(HttpMessageNotReadableException)에서 걸러져 고정 문구로 응답한다.
    // 예전에는 서비스의 TalkLevel.valueOf()가 던진 IllegalArgumentException("No enum constant com.team4...")이 그대로 실리던
    // 경로였는데, 요청 DTO가 enum 타입으로 바뀌면서(#127) 이 경로는 사라졌다. 그래서 이 테스트는 IllegalArgumentException 핸들러의
    // 마스킹이 아니라 형식 오류 경로를 확인한다. 그 마스킹은 GlobalExceptionHandlerTest가 핸들러를 직접 호출해 확인한다
    // (사용자 요청으로 도달하는 IllegalArgumentException 경로가 없어 HTTP 테스트로는 만들 수 없다)
    @Test
    @DisplayName("허용되지 않은 talkLevel 응답에 내부 클래스 경로 미포함")
    void 허용되지_않은_talkLevel의_내부_정보_미노출() throws Exception {
        String body = """
                {
                  "courseId": 1,
                  "meetingPoint": "뚝섬유원지역 3번 출구",
                  "distanceMinMeters": 5000,
                  "distanceMaxMeters": 8000,
                  "paceMinSec": 360,
                  "paceMaxSec": 420,
                  "scheduledAt": "%s",
                  "talkLevel": "TALKATIVE"
                }
                """.formatted(OffsetDateTime.now().plusDays(1));

        mockMvc.perform(post("/api/matching/requests")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(createUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."))
                .andExpect(jsonPath("$.message").value(not(containsString(INTERNAL_PACKAGE))));
    }

    // ---- 다른 예외 계열이 함께 덮이지 않았는지 확인 ----

    @Test
    @DisplayName("상태 규칙 위반 안내 문구 유지")
    void 상태_규칙_위반_안내_문구_유지() throws Exception {
        // 인증 기록이 없는 이메일의 비밀번호 재설정 요청 (계정 존재 여부 비노출용 공통 문구)
        String body = """
                {
                  "email": "no-such-%s@example.com",
                  "code": "123456",
                  "newPassword": "newPassword1234"
                }
                """.formatted(UUID.randomUUID());

        mockMvc.perform(post("/api/auth/password-reset")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("비밀번호 재설정 인증 정보가 올바르지 않거나 만료되었습니다."));
    }

    @Test
    @DisplayName("요청 형식 오류 고정 문구 유지")
    void 요청_형식_오류_고정_문구_유지() throws Exception {
        mockMvc.perform(get("/api/matching/board")
                        .param("size", "not-a-number")
                        .header(HttpHeaders.AUTHORIZATION, bearerToken(createUser())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다."));
    }
}
