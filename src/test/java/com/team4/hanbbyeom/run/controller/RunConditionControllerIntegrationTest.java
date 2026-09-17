package com.team4.hanbbyeom.run.controller;

import com.team4.hanbbyeom.global.security.jwt.JwtTokenProvider;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 회귀 테스트: RunMatchConditionNotFoundException이 실제 HTTP 응답에서 404로 매핑되는지
// 검증한다. Service 레이어 테스트(RunConditionServiceTest)로는 "예외가 던져지는지"만
// 확인할 수 있고, 그 예외가 어떤 HTTP 상태로 변환되는지는 GlobalExceptionHandler를
// 실제로 거쳐야 확인할 수 있다(JwtAuthenticationIntegrationTest와 동일한 이유).
//
// 실제로 이 부분에서 버그가 있었다: RunMatchConditionNotFoundException 전용 핸들러가
// run.exception.RunExceptionHandler라는 별도 @RestControllerAdvice 클래스에 있었는데,
// Spring이 advice 빈을 고를 때 GlobalExceptionHandler를 먼저 골라서(패키지 스캔 순서상)
// 그 안의 Exception.class catch-all이 대신 잡아버려 404가 아니라 500이 나가고 있었다.
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class RunConditionControllerIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;

    private Long createUser() {
        return jdbcTemplate.queryForObject(
                """
                INSERT INTO users (email, password_hash, nickname, email_verified_at)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """,
                Long.class,
                "run-condition-" + UUID.randomUUID() + "@example.com", "dummy-hash", "테스트", OffsetDateTime.now()
        );
    }

    @Test
    void 존재하지_않는_러닝_조건을_조회하면_404를_반환한다() throws Exception {
        Long userId = createUser();
        String token = "Bearer " + jwtTokenProvider.createAccessToken(userId);

        mockMvc.perform(get("/api/run/conditions/{id}", 999_999_999L)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value("존재하지 않는 러닝 조건이에요. id=999999999"));
    }
}
